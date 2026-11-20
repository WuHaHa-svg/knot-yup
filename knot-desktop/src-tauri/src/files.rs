//! 桌面端文件共享：本地持久清单 + P2P 供种服务生命周期。
//!
//! 本地清单存 ~/KnotYup/shared/index.json，条目 = 网络元数据(SharedFile) + 本地路径。
//! 入群期间起 FileSeeder，resolver 动态查 path_map（增删文件即时生效、端口稳定）；离群停服。

use knot_core::file::{sha256_file, FileSeeder};
use knot_core::protocol::{now_ms, SharedFile};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};
use tokio::sync::Mutex;

/// 本地清单条目：网络元数据 + 本地路径（path 绝不上报）。
#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct LocalFile {
    #[serde(flatten)]
    pub file: SharedFile,
    pub path: String,
}

/// 本地文件管理器：清单 + 动态路径映射（供种 resolver 用）+ 供种服务句柄。
pub(crate) struct FileManager {
    dir: PathBuf,
    pub files: Mutex<Vec<LocalFile>>,
    /// file_id → 本地路径；供种 resolver 动态读，增删即时生效，seeder 端口稳定不重启
    path_map: Arc<RwLock<HashMap<String, PathBuf>>>,
    pub seeder: Mutex<Option<FileSeeder>>,
}

impl FileManager {
    /// 加载清单（不存在则创建空目录 + 空清单）。
    pub fn load() -> Self {
        let dir = dirs_home().join("KnotYup").join("shared");
        let _ = std::fs::create_dir_all(&dir);
        let idx = dir.join("index.json");
        let files: Vec<LocalFile> = std::fs::read_to_string(&idx)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        let path_map = files
            .iter()
            .map(|f| (f.file.file_id.clone(), PathBuf::from(&f.path)))
            .collect();
        Self {
            dir,
            files: Mutex::new(files),
            path_map: Arc::new(RwLock::new(path_map)),
            seeder: Mutex::new(None),
        }
    }

    fn index_path(&self) -> PathBuf {
        self.dir.join("index.json")
    }

    async fn persist(&self) {
        let list = self.files.lock().await.clone();
        let json = serde_json::to_string_pretty(&list).unwrap_or_else(|_| "[]".into());
        let _ = tokio::fs::write(self.index_path(), json).await;
    }

    /// 入群时启动/更新供种服务。设备级常驻：首次入群启动，之后入新群只更新 PIN（端口不变），
    /// 离群不停服——本机共享文件夹与群无关，仍可持续供种。
    pub async fn ensure_seeder(&self, pin: &str, local_id: &str, ip: &str) -> Option<String> {
        let mut guard = self.seeder.lock().await;
        if let Some(s) = guard.as_ref() {
            // 入新群：更新 PIN 以校验新群的下载请求，端口保持不变
            s.update_pin(pin);
            return Some(format!("{}:{}", ip, s.port()));
        }
        let map = self.path_map.clone();
        let resolver: knot_core::file::FileResolver =
            Arc::new(move |id| map.read().ok().and_then(|m| m.get(id).cloned()));
        match FileSeeder::start(0, pin.to_string(), local_id.to_string(), resolver).await {
            Ok(s) => {
                let addr = format!("{}:{}", ip, s.port());
                *guard = Some(s);
                Some(addr)
            }
            Err(e) => {
                crate::dlog("file", format!("供种服务启动失败: {e}"));
                None
            }
        }
    }

    /// 登记本地文件（上传）：选任意路径 → 算 sha256 → 写清单 + path_map → 返回 SharedFile。
    pub async fn add_local(&self, path: &str) -> Result<SharedFile, String> {
        let p = Path::new(path);
        let name = p
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("file")
            .to_string();
        let size = tokio::fs::metadata(p)
            .await
            .map_err(|e| format!("读取文件失败: {e}"))?
            .len();
        let sha256 = sha256_file(p).map_err(|e| format!("计算 sha256 失败: {e}"))?;
        let mime = mime_from_name(&name);
        let file = SharedFile {
            file_id: knot_core::protocol::device_id(), // uuid 复用作 file_id
            name,
            size,
            sha256,
            mime,
            added_at: now_ms(),
        };
        self.files
            .lock()
            .await
            .push(LocalFile { file: file.clone(), path: path.to_string() });
        self.path_map
            .write()
            .ok()
            .and_then(|mut m| m.insert(file.file_id.clone(), PathBuf::from(path)));
        self.persist().await;
        Ok(file)
    }

    /// 删除本地清单条目（不动磁盘文件）。
    pub async fn remove_local(&self, file_ids: &[String]) {
        self.files
            .lock()
            .await
            .retain(|f| !file_ids.contains(&f.file.file_id));
        if let Ok(mut m) = self.path_map.write() {
            for id in file_ids {
                m.remove(id);
            }
        }
        self.persist().await;
    }

    /// 全量上报用的清单（网络元数据，不含 path）。
    pub async fn shared_files(&self) -> Vec<SharedFile> {
        self.files.lock().await.iter().map(|f| f.file.clone()).collect()
    }
}

fn dirs_home() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

fn mime_from_name(name: &str) -> String {
    let ext = name.rsplit('.').next().unwrap_or("").to_lowercase();
    let m = match ext.as_str() {
        "txt" | "md" => "text/plain",
        "pdf" => "application/pdf",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "zip" => "application/zip",
        "json" => "application/json",
        _ => "application/octet-stream",
    };
    m.to_string()
}
