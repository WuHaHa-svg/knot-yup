//! 节点间消息协议（v2）。JSON 编码，后续大流量通道可换 Protobuf。

use serde::{Deserialize, Serialize};
use std::time::Duration;

// ── v2 协议常量（双端必须一致，Android 侧见 Discovery.kt / GroupClient.kt）──

/// 成员心跳间隔
pub const HEARTBEAT_MS: u64 = 30_000;/// 单次心跳回执超时（超时即判群主失联）
pub const PONG_TIMEOUT_MS: u64 = 3_000;
/// 群主看门狗：连接无入站消息超过此时长即断开（心跳 + 超时 + 余量）
pub const WATCHDOG_MS: u64 = 35_000;
/// 转让：offer 后等待 ready 的超时
pub const TRANSFER_TIMEOUT_MS: u64 = 5_000;
/// 解散：成员收到 dissolve 后延迟退出（给 UI 缓冲）
pub const DISSOLVE_EXIT_MS: u64 = 800;
/// 文件传输：分块大小（64 KiB），双端常量必须一致
pub const FILE_CHUNK_SIZE: usize = 65_536;

pub fn heartbeat_interval() -> Duration {
    Duration::from_millis(HEARTBEAT_MS)
}
pub fn pong_timeout() -> Duration {
    Duration::from_millis(PONG_TIMEOUT_MS)
}
pub fn watchdog_timeout() -> Duration {
    Duration::from_millis(WATCHDOG_MS)
}
pub fn transfer_timeout() -> Duration {
    Duration::from_millis(TRANSFER_TIMEOUT_MS)
}
pub fn dissolve_exit_delay() -> Duration {
    Duration::from_millis(DISSOLVE_EXIT_MS)
}

/// 设备身份信息，所有含成员表的消息共用（见 docs/ws-design.md §3.1）。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceInfo {
    pub id: String,
    pub name: String,
    pub platform: String, // windows / macos / android
    /// 本机 standby 服务地址（ip:port）。成员入组时携带预留端口；群主/未预留为 None。
    #[serde(default)]
    pub addr: Option<String>,
    /// 文件共享的 P2P 供种服务地址（ip:port）。与 ws 端口、standby 转让端口各自独立。
    #[serde(default)]
    pub file_addr: Option<String>,
    /// 成员身份：owner | member。服务端权威赋值；join 上报省略（默认 member）。
    #[serde(default)]
    pub role: Option<String>,
    /// 该成员共享的文件列表（文件夹随成员走）。join 时携带，随成员表同步。
    #[serde(default)]
    pub file_list: Vec<SharedFile>,
}

impl DeviceInfo {
    pub fn is_owner(&self) -> bool {
        self.role.as_deref() == Some("owner")
    }
}

/// 剪贴板内容。当前仅文本；图片/文件走 M4 的分块直传通道。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipboardPayload {
    pub kind: ClipboardKind,
    pub text: Option<String>,
    /// 内容指纹（sha256 前 16 字节 hex），接收端用于去重/防回环。
    pub hash: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ClipboardKind {
    Text,
    #[allow(dead_code)]
    Image,
    #[allow(dead_code)]
    File,
}

/// 文件共享条目（网络载荷，不含本地路径——path 只存本地清单，绝不上报）。
/// file_id 每次上传/下载完成各自生成，两份副本互不沿用。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SharedFile {
    pub file_id: String,
    pub name: String,
    pub size: u64,
    /// sha256 hex，仅用于下载后完整性校验，不决定 file_id、不做去重
    pub sha256: String,
    pub mime: String,
    pub added_at: u64,
}

/// 群文件列表条目：device_id → 该设备文件夹内的文件列表
pub type FileMap = std::collections::BTreeMap<String, Vec<SharedFile>>;

/// 传输任务状态（群主维护的全局任务数组）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    /// 排队中：供种方闸门被占，等待开始
    Queuing,
    /// 传输中：进度 0→100
    Transferring,
    /// 完成（终态）
    Done,
    /// 失败（终态）
    Failed,
}

/// 一条传输任务：一次 P2P 传输 = 一条任务，含下载方与供种方两个视角。
/// name/size/mime 是发起时的快照，不与 SharedFile 联动。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferTask {
    pub task_id: String,
    pub file_id: String,
    pub name: String,
    pub size: u64,
    pub mime: String,
    /// 供种者 device id
    pub seeder_id: String,
    /// 供种者设备名快照（创建时固化，UI 直接用）
    pub seeder_name: String,
    /// 下载者 device id
    pub downloader_id: String,
    /// 下载者设备名快照（创建时固化，UI 直接用）
    pub downloader_name: String,
    pub status: TaskStatus,
    /// 整数百分比，queuing 阶段恒为 0
    pub progress: u32,
    /// 已传字节
    pub transferred: u64,
    /// 失败原因（status=failed 时有值）
    pub error: String,
    pub created_at: u64,
    pub updated_at: u64,
}

/// 供种端引擎向上抛的任务事件（P2P 层与 ws 层解耦）。
#[derive(Debug, Clone)]
pub enum TaskEvent {
    Queued { task_id: String },
    Started { task_id: String },
    Progress { task_id: String, transferred: u64, total: u64 },
    Done { task_id: String },
    Failed { task_id: String, error: String },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Message {
    /// 入组请求（成员 → 群主）：明文握手
    Join {
        pin: String,
        device: DeviceInfo,
    },
    /// 入组结果（群主 → 成员）：明文握手
    JoinResult {
        ok: bool,
        reason: Option<String>,
        members: Vec<DeviceInfo>,
    },
    /// 心跳：成员 → 群主，每 30s 一次
    Ping {
        device: DeviceInfo,
        seq: u64,
        ts_ms: u64,
    },
    /// 心跳回执：群主 → 成员。成员表快照即状态同步（群主身份看 role 字段）
    Pong {
        members: Vec<DeviceInfo>,
        seq: u64,
        ts_ms: u64,
    },
    /// 剪贴板同步（成员 → 群主 → 广播）：from = 发送者 device.id，
    /// 接收端 from == 自己 id 时忽略（回声去重）
    Clipboard {
        from: String,
        /// 发送方设备名快照（创建时固化，接收端 UI 直接用，无需查成员表）
        from_name: String,
        payload: ClipboardPayload,
    },
    /// 成员变更即时广播（群主 → 全员）
    Members {
        members: Vec<DeviceInfo>,
    },
    /// 剔除成员（群主 → 目标设备）
    Kick {
        reason: Option<String>,
    },
    /// 解散通知（群主 → 全员）：成员收到后 0.8s 自行退出，不回发
    Dissolve,
    /// 转让第一步：群主 → 被转让者，邀请其用 standby 端口起服务
    TransferOffer {
        pin: String,
    },
    /// 转让第二步：被转让者 → 群主（通过原 ws），新服务已就绪
    TransferReady {
        addr: String,
    },
    /// 转让失败（群主 5s 超时后收到迟到 ready）：目标静默关闭多余的 standby 服务
    TransferError,
    /// 转让第三步：群主 → 全员，指挥迁移到新服务
    NewService {
        addr: String,
        pin: String,
        owner: DeviceInfo,
    },
    /// 文件共享①：文件夹新增（群主汇总 + 广播全员）
    FileAdd {
        from: String,
        /// 共享者设备名快照（创建时固化）
        from_name: String,
        file: SharedFile,
    },
    /// 文件共享②：删除自己文件夹内的一条或多条（带设备 + id 数组；仅移清单）
    FileRemove {
        from: String,
        /// 删除者设备名快照（创建时固化）
        from_name: String,
        file_ids: Vec<String>,
    },
    /// 传输中心①：任务诞生（下载方 → 群主，status=queuing）
    FileTaskAdd { task: TransferTask },
    /// 传输中心②：供种方确认排队（供种方 → 群主）
    FileTaskQueued { task_id: String },
    /// 传输中心③：供种方拿到闸门开始发送（供种方 → 群主）
    FileTaskStarted { task_id: String },
    /// 传输中心④：供种方节流进度（供种方 → 群主）
    FileTaskProgress { task_id: String, progress: u32, transferred: u64 },
    /// 传输中心⑤：供种方完成（供种方 → 群主）
    FileTaskFinish { task_id: String },
    /// 传输中心⑥：失败（下载方或供种方 → 群主；双方都报，幂等）
    FileTaskFailed { task_id: String, error: String, reporter: String },
    /// 传输中心⑦：群主广播全量任务数组（群主 → 全群）
    FileTasks { tasks: Vec<TransferTask> },
    /// 加密信封：data = base64(nonce || AES-256-GCM(json(内部消息)))
    Encrypted { data: String },
}

impl Message {
    /// 打包为加密信封。
    pub fn seal(self, cipher: &crate::crypto::Cipher) -> Self {
        Message::Encrypted { data: cipher.encrypt(&self) }
    }

    /// 若是加密信封则解开封；普通消息原样返回。
    pub fn open(self, cipher: &crate::crypto::Cipher) -> Option<Self> {
        match self {
            Message::Encrypted { data } => cipher.decrypt(&data).ok(),
            other => Some(other),
        }
    }
}

pub fn device_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// 当前毫秒时间戳。
pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_task() -> TransferTask {
        TransferTask {
            task_id: "t-1".into(), file_id: "f-1".into(), name: "a.zip".into(),
            size: 1048576, mime: "application/zip".into(),
            seeder_id: "dev-B".into(), seeder_name: "Bob's Mac".into(),
            downloader_id: "dev-A".into(), downloader_name: "Alice's PC".into(),
            status: TaskStatus::Transferring, progress: 42, transferred: 440401,
            error: "".into(), created_at: 1000, updated_at: 2000,
        }
    }

    /// 传输任务消息往返：字段与 Android Protocol.kt TransferTask/toJson 逐字段对齐。
    #[test]
    fn transfer_task_roundtrip() {
        let task = sample_task();
        let msg = Message::FileTaskAdd { task: task.clone() };
        let json = serde_json::to_string(&msg).unwrap();
        assert!(json.contains("\"file_task_add\""));
        assert!(json.contains("\"task_id\":\"t-1\""));
        assert!(json.contains("\"status\":\"transferring\""));
        let back: Message = serde_json::from_str(&json).unwrap();
        match back {
            Message::FileTaskAdd { task: t } => {
                assert_eq!(t.task_id, task.task_id);
                assert_eq!(t.progress, 42);
                assert_eq!(t.status, TaskStatus::Transferring);
                assert_eq!(t.seeder_id, "dev-B");
                assert_eq!(t.seeder_name, "Bob's Mac");
                assert_eq!(t.downloader_id, "dev-A");
                assert_eq!(t.downloader_name, "Alice's PC");
            }
            _ => panic!("wrong variant"),
        }
    }

    /// 7 条传输消息的 wire tag 冻结（与 Android 端 type 字符串一致）。
    #[test]
    fn transfer_message_wire_tags() {
        let task = sample_task();
        let cases = vec![
            (Message::FileTaskAdd { task: task.clone() }, "file_task_add"),
            (Message::FileTaskQueued { task_id: "t".into() }, "file_task_queued"),
            (Message::FileTaskStarted { task_id: "t".into() }, "file_task_started"),
            (Message::FileTaskProgress { task_id: "t".into(), progress: 1, transferred: 1 }, "file_task_progress"),
            (Message::FileTaskFinish { task_id: "t".into() }, "file_task_finish"),
            (Message::FileTaskFailed { task_id: "t".into(), error: "e".into(), reporter: "r".into() }, "file_task_failed"),
            (Message::FileTasks { tasks: vec![task] }, "file_tasks"),
        ];
        for (msg, tag) in cases {
            let json = serde_json::to_string(&msg).unwrap();
            assert!(json.contains(&format!("\"type\":\"{tag}\"")), "tag mismatch: {json}");
        }
    }
}
