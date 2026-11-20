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
        file: SharedFile,
    },
    /// 文件共享②：删除自己文件夹内的一条或多条（带设备 + id 数组；仅移清单）
    FileRemove {
        from: String,
        file_ids: Vec<String>,
    },
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
