//! 文件共享数据面（P2P 直传）。
//!
//! 供种端：TCP 监听 + 首帧 HMAC token 认证 + 流式分块加密发送；同一时刻只服务 1 个下载者（Semaphore 排队）。
//! 下载端：连接 → 发请求头 → 逐块解密写 partial → sha256 校验 → 原子改名。
//! 块格式：[u32 BE idx][u32 BE len][nonce(12) || AES-GCM 密文]，块大小 FILE_CHUNK_SIZE。

use hex::ToHex;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, Semaphore};
use tokio::task::JoinHandle;

use crate::crypto::Cipher;
use crate::protocol::FILE_CHUNK_SIZE;

/// HMAC-SHA256（用 sha2 手写，避免引入 hmac 依赖）。key = PIN 字节。
fn hmac_sha256(key: &[u8], msg: &[u8]) -> [u8; 32] {
    const BLOCK: usize = 64;
    let mut k = [0u8; BLOCK];
    if key.len() > BLOCK {
        let h = Sha256::digest(key);
        k[..32].copy_from_slice(&h);
    } else {
        k[..key.len()].copy_from_slice(key);
    }
    let mut ipad = [0x36u8; BLOCK];
    let mut opad = [0x5cu8; BLOCK];
    for i in 0..BLOCK {
        ipad[i] ^= k[i];
        opad[i] ^= k[i];
    }
    let mut inner = Sha256::new();
    inner.update(ipad);
    inner.update(msg);
    let ih = inner.finalize();
    let mut outer = Sha256::new();
    outer.update(opad);
    outer.update(ih);
    let oh = outer.finalize();
    let mut out = [0u8; 32];
    out.copy_from_slice(&oh);
    out
}

/// 认证 token：HMAC(PIN, src_file_id|from|to) 的 hex。防端口扫描蹭传。
fn auth_token(pin: &str, src_file_id: &str, from: &str, to: &str) -> String {
    let msg = format!("{src_file_id}|{from}|{to}");
    hmac_sha256(pin.as_bytes(), msg.as_bytes()).encode_hex()
}

/// 下载请求头（首帧，JSON + 长度前缀）
#[derive(Debug, Clone, Serialize, Deserialize)]
struct FileRequest {
    src_file_id: String,
    from: String,
    to: String,
    token: String,
    /// 断点续传：从该块序号开始；首版 0
    resume_from: u32,
}

/// 供种端响应控制帧（长度前缀 JSON）。
/// 用扁平字段（ok/size/sha256/error/done）而非 serde enum——必须与 Android 端
/// P2pFileService.kt 手写的 JSONObject 逐字段兼容，否则双向下载都失败。
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct SeedControl {
    #[serde(default)]
    ok: bool,
    #[serde(default)]
    size: u64,
    #[serde(default)]
    sha256: String,
    #[serde(default)]
    error: String,
    #[serde(default)]
    done: bool,
}

impl SeedControl {
    fn ok(size: u64, sha256: String) -> Self {
        Self { ok: true, size, sha256, ..Default::default() }
    }
    fn err(msg: impl Into<String>) -> Self {
        Self { ok: false, error: msg.into(), ..Default::default() }
    }
    fn done() -> Self {
        Self { done: true, ..Default::default() }
    }
}

async fn write_frame<T: Serialize>(stream: &mut TcpStream, frame: &T) -> io::Result<()> {
    let json = serde_json::to_vec(frame).map_err(io::Error::other)?;
    stream.write_all(&(json.len() as u32).to_be_bytes()).await?;
    stream.write_all(&json).await?;
    Ok(())
}

async fn read_frame<T: for<'de> Deserialize<'de>>(stream: &mut TcpStream) -> io::Result<T> {
    let mut len_buf = [0u8; 4];
    stream.read_exact(&mut len_buf).await?;
    let len = u32::from_be_bytes(len_buf) as usize;
    if len > 1024 * 1024 {
        return Err(io::Error::other("frame too large"));
    }
    let mut buf = vec![0u8; len];
    stream.read_exact(&mut buf).await?;
    serde_json::from_slice(&buf).map_err(io::Error::other)
}

/// 本地文件解析器：src_file_id（供种者自己清单里的 id）→ 本地磁盘路径。
pub type FileResolver = Arc<dyn Fn(&str) -> Option<PathBuf> + Send + Sync>;

/// 供种服务句柄。设备级常驻：与群生命周期无关，仅 App 退出才停止。
/// PIN 可动态更新（入新群时换 PIN，端口保持不变）。
pub struct FileSeeder {
    port: u16,
    pin: Arc<RwLock<String>>,
    shutdown: mpsc::Sender<()>,
    handle: Option<JoinHandle<()>>,
}

impl Drop for FileSeeder {
    fn drop(&mut self) {
        if let Some(h) = &self.handle {
            h.abort();
        }
    }
}

impl FileSeeder {
    /// 启动供种服务。port=0 由 OS 分配随机端口。返回句柄，port() 取实际端口上报。
    pub async fn start(
        port: u16,
        pin: String,
        local_id: String,
        resolver: FileResolver,
    ) -> io::Result<Self> {
        let listener = TcpListener::bind(("0.0.0.0", port)).await?;
        let actual_port = listener.local_addr()?.port();
        let (shutdown_tx, mut shutdown_rx) = mpsc::channel::<()>(1);
        let pin = Arc::new(RwLock::new(pin));
        let pin_for_handle = pin.clone();
        // 并发闸门：容量 1 → 同一时刻只服务 1 个下载者，后续连接排队
        let gate = Arc::new(Semaphore::new(1));
        let handle = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = shutdown_rx.recv() => break,
                    accepted = listener.accept() => {
                        let (stream, peer) = match accepted {
                            Ok(v) => v,
                            Err(e) => { tracing::warn!("file accept: {e}"); continue; }
                        };
                        let pin = pin.read().map(|g| g.clone()).unwrap_or_default();
                        let local_id = local_id.clone();
                        let resolver = resolver.clone();
                        let gate = gate.clone();
                        tokio::spawn(async move {
                            // 取闸门许可：排队直到前一个下载结束
                            let _permit = match gate.acquire_owned().await {
                                Ok(p) => p,
                                Err(_) => return,
                            };
                            if let Err(e) = serve_one(stream, &pin, &local_id, &resolver).await {
                                tracing::debug!(target: "file", "会话结束 [{peer}]: {e}");
                            }
                        });
                    }
                }
            }
        });
        Ok(Self { port: actual_port, pin: pin_for_handle, shutdown: shutdown_tx, handle: Some(handle) })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    /// 入新群时更新 PIN（端口不变，已上报的 file_addr 继续有效）。
    pub fn update_pin(&self, pin: &str) {
        if let Ok(mut p) = self.pin.write() {
            *p = pin.to_string();
        }
    }

    pub async fn stop(mut self) {
        self.shutdown.send(()).await.ok();
        if let Some(h) = self.handle.take() {
            h.abort();
        }
    }
}

/// 处理单个下载会话：认证 → 计算 sha256/size → 发 ok → 分块发送 → 等 done。
async fn serve_one(
    mut stream: TcpStream,
    pin: &str,
    local_id: &str,
    resolver: &FileResolver,
) -> Result<(), String> {
    let req: FileRequest = read_frame(&mut stream).await.map_err(|e| format!("读请求头: {e}"))?;
    // 校验 token
    let expect = auth_token(pin, &req.src_file_id, &req.from, &req.to);
    if req.to != local_id || req.token != expect {
        write_frame(&mut stream, &SeedControl::err("AUTH_FAILED"))
            .await
            .map_err(|e| format!("回执: {e}"))?;
        return Err("token 认证失败".into());
    }
    // 解析本地文件
    let path = resolver(&req.src_file_id).ok_or_else(|| "文件不在本地清单".to_string())?;
    let meta = tokio::fs::metadata(&path).await.map_err(|e| format!("读取文件元数据: {e}"))?;
    let size = meta.len();
    // 计算整文件 sha256（流式）
    let sha256 = hash_file(&path).await.map_err(|e| format!("计算 sha256: {e}"))?;
    write_frame(&mut stream, &SeedControl::ok(size, sha256)).await.map_err(|e| format!("发 ok: {e}"))?;

    let cipher = Cipher::from_pin(pin);
    let mut file = tokio::fs::File::open(&path).await.map_err(|e| format!("打开文件: {e}"))?;
    let start = req.resume_from as u64 * FILE_CHUNK_SIZE as u64;
    file.seek(io::SeekFrom::Start(start)).await.map_err(|e| format!("seek: {e}"))?;
    let mut idx = req.resume_from;
    let mut buf = vec![0u8; FILE_CHUNK_SIZE];
    loop {
        let n = file.read(&mut buf).await.map_err(|e| format!("读文件: {e}"))?;
        if n == 0 {
            break;
        }
        let enc = cipher.encrypt_chunk(&buf[..n]);
        stream.write_all(&idx.to_be_bytes()).await.map_err(|e| format!("写 idx: {e}"))?;
        stream.write_all(&(enc.len() as u32).to_be_bytes()).await.map_err(|e| format!("写 len: {e}"))?;
        stream.write_all(&enc).await.map_err(|e| format!("写块: {e}"))?;
        // 停等式：等下载端 ACK 本块序号（0xFFFFFFFF 表示中止）
        let mut ack = [0u8; 4];
        stream.read_exact(&mut ack).await.map_err(|e| format!("等 ACK: {e}"))?;
        let ack = u32::from_be_bytes(ack);
        if ack == u32::MAX {
            return Err("下载端中止".into());
        }
        if ack != idx {
            return Err(format!("ACK 序号不符: 期望 {idx} 收到 {ack}"));
        }
        idx += 1;
    }
    // 等下载端 done/error 控制帧
    match read_frame::<SeedControl>(&mut stream).await {
        Ok(ctl) if ctl.done => {
            tracing::debug!(target: "file", "传输完成 idx={idx} size={size}");
            Ok(())
        }
        Ok(ctl) => Err(format!("下载端报错: {}", ctl.error)),
        Err(e) => Err(format!("未收到 done: {e}")),
    }
}

/// 下载结果元信息（上层据此生成新 file_id 写清单）
#[derive(Debug, Clone)]
pub struct DownloadResult {
    pub name: String,
    pub size: u64,
    pub sha256: String,
    pub mime: String,
}

/// 从供种者下载文件到 dest 路径，校验 sha256，返回元信息（不含 file_id，由上层生成）。
/// seed_id = 供种者设备 id，my_id = 下载者设备 id。
pub async fn fetch_file(
    file_addr: &str,
    pin: &str,
    src_file_id: &str,
    seed_id: &str,
    my_id: &str,
    name: &str,
    mime: &str,
    dest: &Path,
) -> Result<DownloadResult, String> {
    let mut stream = TcpStream::connect(file_addr)
        .await
        .map_err(|e| format!("连接供种者失败: {e}"))?;
    // token 与供种端校验一致：auth_token(pin, src_file_id, 下载者, 供种者)
    let token = auth_token(pin, src_file_id, my_id, seed_id);
    let req = FileRequest {
        src_file_id: src_file_id.into(),
        from: my_id.into(),
        to: seed_id.into(),
        token,
        resume_from: 0,
    };
    write_frame(&mut stream, &req).await.map_err(|e| format!("发请求头: {e}"))?;
    let (size, expect_sha) = match read_frame::<SeedControl>(&mut stream).await {
        Ok(ctl) if ctl.ok => (ctl.size, ctl.sha256),
        Ok(ctl) => return Err(format!("供种者拒绝: {}", ctl.error)),
        Err(e) => return Err(format!("供种者响应异常: {e}")),
    };

    let cipher = Cipher::from_pin(pin);
    let partial = dest.with_extension(format!(
        "{}.partial",
        dest.extension().and_then(|s| s.to_str()).unwrap_or("")
    ));
    let mut file = tokio::fs::File::create(&partial)
        .await
        .map_err(|e| format!("创建临时文件: {e}"))?;
    let mut hasher = Sha256::new();
    let mut received: u64 = 0;
    loop {
        // 块头：[u32 idx][u32 len]
        let mut head = [0u8; 8];
        stream.read_exact(&mut head).await.map_err(|e| format!("读块头: {e}"))?;
        let block_idx = u32::from_be_bytes(head[..4].try_into().unwrap());
        let enc_len = u32::from_be_bytes(head[4..].try_into().unwrap()) as usize;
        if enc_len > FILE_CHUNK_SIZE + 1024 {
            return Err("块长度异常".into());
        }
        let mut enc = vec![0u8; enc_len];
        stream.read_exact(&mut enc).await.map_err(|e| format!("读块: {e}"))?;
        let plain = cipher.decrypt_chunk(&enc).map_err(|e| format!("解密块: {e}"))?;
        hasher.update(&plain);
        file.write_all(&plain).await.map_err(|e| format!("写块: {e}"))?;
        received += plain.len() as u64;
        // 停等式 ACK：本块序号
        stream
            .write_all(&block_idx.to_be_bytes())
            .await
            .map_err(|e| format!("发 ACK: {e}"))?;
        if received >= size {
            break;
        }
    }
    file.flush().await.map_err(|e| format!("刷盘: {e}"))?;
    drop(file);

    // 校验整文件 sha256
    let got_sha = hasher.finalize().encode_hex();
    if got_sha != expect_sha {
        let _ = tokio::fs::remove_file(&partial).await;
        write_frame(&mut stream, &SeedControl::err("HASH_MISMATCH")).await.ok();
        return Err(format!("sha256 不符: 期望 {expect_sha} 实得 {got_sha}"));
    }
    // 原子改名 partial → dest
    tokio::fs::rename(&partial, dest)
        .await
        .map_err(|e| format!("改名落盘: {e}"))?;
    write_frame(&mut stream, &SeedControl::done()).await.ok();

    Ok(DownloadResult {
        name: name.into(),
        size,
        sha256: got_sha,
        mime: mime.into(),
    })
}

/// sha256 流式计算整文件
async fn hash_file(path: &Path) -> Result<String, io::Error> {
    let mut file = tokio::fs::File::open(path).await?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hasher.finalize().encode_hex())
}

/// 计算文件 sha256 hex（同步，供上层登记清单用）。
pub fn sha256_file(path: &Path) -> io::Result<String> {
    use sha2::Digest;
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = std::io::Read::read(&mut file, &mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hasher.finalize().encode_hex())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncWriteExt;

    /// 全链路：seeder 起服务 → fetch 分块下载 → sha256 校验 → 落盘一致；错误 token 被拒。
    #[tokio::test]
    async fn seed_fetch_roundtrip() {
        let dir = std::env::temp_dir().join(format!("knot-file-test-{}", protocol_now()));
        tokio::fs::create_dir_all(&dir).await.unwrap();
        let src = dir.join("src.bin");
        // 造一个跨多块的文件（3 块 + 零头）
        let payload: Vec<u8> = (0..(FILE_CHUNK_SIZE * 3 + 123))
            .map(|i| (i % 251) as u8)
            .collect();
        let mut f = tokio::fs::File::create(&src).await.unwrap();
        f.write_all(&payload).await.unwrap();
        f.flush().await.unwrap();
        drop(f);

        let file_id = "file-abc".to_string();
        let src_for_resolver = src.clone();
        let resolver: FileResolver = Arc::new(move |id| {
            if id == "file-abc" {
                Some(src_for_resolver.clone())
            } else {
                None
            }
        });
        let seeder = FileSeeder::start(0, "123456".into(), "seed-device".into(), resolver)
            .await
            .unwrap();
        let addr = format!("127.0.0.1:{}", seeder.port());

        let dest = dir.join("dst.bin");
        let result = fetch_file(
            &addr,
            "123456",
            &file_id,
            "seed-device",
            "download-device",
            "src.bin",
            "application/octet-stream",
            &dest,
        )
        .await
        .expect("下载应成功");
        assert_eq!(result.size, payload.len() as u64);
        let got = tokio::fs::read(&dest).await.unwrap();
        assert_eq!(got, payload, "落盘内容须与源一致");
        assert!(result.sha256.len() == 64);

        // 错误 token（PIN 不对）应被拒
        let dest2 = dir.join("dst2.bin");
        let bad = fetch_file(
            &addr,
            "wrong-pin",
            &file_id,
            "seed-device",
            "download-device",
            "src.bin",
            "application/octet-stream",
            &dest2,
        )
        .await;
        assert!(bad.is_err(), "错误 PIN 的下载应失败");

        seeder.stop().await;
        let _ = tokio::fs::remove_dir_all(&dir).await;
    }

    /// 跨端格式契约：控制帧必须是扁平字段，不能退回 serde enum 的
    /// {"Ok":{...}} / "Done" —— 否则 Android 端（手写 JSONObject）解析不了，双向下载失败。
    #[test]
    fn control_frame_flat_json_compatible_with_android() {
        let ok = serde_json::to_value(SeedControl::ok(1024, "abc".into())).unwrap();
        assert_eq!(ok["ok"], true, "成功帧须有扁平 ok 字段");
        assert_eq!(ok["size"], 1024u64);
        assert_eq!(ok["sha256"], "abc");
        assert!(ok.get("Ok").is_none(), "不得出现 serde enum 变体键");

        let err = serde_json::to_value(SeedControl::err("AUTH_FAILED")).unwrap();
        assert_eq!(err["ok"], false);
        assert_eq!(err["error"], "AUTH_FAILED");

        let done = serde_json::to_value(SeedControl::done()).unwrap();
        assert_eq!(done["done"], true);

        // Android 成功帧格式能被 Rust 解析
        let android_ok: SeedControl =
            serde_json::from_str(r#"{"ok":true,"size":2048,"sha256":"def"}"#).unwrap();
        assert!(android_ok.ok && android_ok.size == 2048 && android_ok.sha256 == "def");
        // Android 错误帧 / done 帧
        let android_err: SeedControl = serde_json::from_str(r#"{"error":"NOT_FOUND"}"#).unwrap();
        assert!(!android_err.ok && android_err.error == "NOT_FOUND");
        let android_done: SeedControl = serde_json::from_str(r#"{"done":true}"#).unwrap();
        assert!(android_done.done);
    }

    fn protocol_now() -> u64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos() as u64
    }
}
