//! 节点间消息协议。M0 阶段使用 JSON 编码，后续大流量通道可换 Protobuf。

use serde::{Deserialize, Serialize};

/// 设备身份信息，入组时交换。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceInfo {
    pub id: String,
    pub name: String,
    pub platform: String, // windows / macos / android
}

/// 剪贴板内容。M0 仅支持文本；图片/文件走 M4 的分块直传通道。
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

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Message {
    /// 入组请求（客户端 → 组主）
    Join {
        pin: String,
        device: DeviceInfo,
    },
    /// 入组结果（组主 → 客户端）
    JoinResult {
        ok: bool,
        reason: Option<String>,
        /// 组主视角的当前成员快照（含自己）
        members: Vec<DeviceInfo>,
    },
    /// 成员变更广播（组主 → 全员）
    Members {
        members: Vec<DeviceInfo>,
    },
    /// 剪贴板同步（任一成员 → 组主 → 广播）
    Clipboard {
        from: String,
        payload: ClipboardPayload,
    },
    /// 剔除成员（组主 → 目标设备）
    Kick {
        reason: Option<String>,
    },
    Ping,
    Pong,
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
