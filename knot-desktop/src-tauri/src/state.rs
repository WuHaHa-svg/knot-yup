//! 桌面端会话状态与通用工具：设备/会话/历史 + IP/PIN 等辅助。

use knot_core::discovery::GroupAd;
use knot_core::net::{CoreEvent, GroupClient, GroupServer};
use knot_core::protocol::{DeviceInfo, FileMap};
use std::sync::Arc;
use tokio::sync::{broadcast, mpsc, Mutex};

/// 剪贴板线程收到的指令
pub(crate) enum ClipCmd {
    /// 写入剪贴板（来自远端同步或用户点击历史条目）
    Set(knot_core::protocol::ClipboardPayload),
}

#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Role {
    Owner,
    Member,
}

#[derive(Clone, serde::Serialize)]
pub(crate) struct HistoryItem {
    pub from: String,
    /// 发送方设备名快照（创建时固化）
    pub name: String,
    pub text: String,
    pub at_ms: u64,
}

pub(crate) struct Session {
    pub role: Role,
    pub server: Option<Arc<GroupServer>>,
    pub client: Option<GroupClient>,
    pub members: Vec<DeviceInfo>,
    /// Owner: 本机监听地址；Member: 组主地址
    pub addr: String,
    pub pin: String,
    /// 组主停止发现应答的句柄（v2 被动应答）
    pub announce_stop: Option<mpsc::Sender<()>>,
    /// 成员期预留的 TcpListener（承接群主用），端口即 DeviceInfo.addr 中的端口
    pub standby: Option<tokio::net::TcpListener>,
    /// 被转让者暂存的 standby 服务句柄（transfer_error 时静默关闭；new_service 后转正）
    pub standby_server: Option<Arc<GroupServer>>,
    /// standby 服务的对外地址（ip:port，转正时作为群地址）
    pub standby_addr: Option<String>,
}

pub(crate) struct AppState {
    pub device: Mutex<Option<DeviceInfo>>,
    pub session: Mutex<Option<Session>>,
    pub clip_tx: Mutex<Option<mpsc::Sender<ClipCmd>>>,
    pub core_tx: Mutex<Option<broadcast::Sender<CoreEvent>>>,
    pub history: Mutex<Vec<HistoryItem>>,
    pub discovered: Mutex<Vec<GroupAd>>,
    /// 最近一次同步的剪贴板指纹（防回环：忽略同指纹的远端回声）
    pub last_clip_hash: Mutex<String>,
    /// 实时成员数（发现应答 RESP 用；bridge 收到 Members 事件时更新）
    pub member_count: Arc<std::sync::atomic::AtomicU32>,
    /// 最近一次升主时间（unix 毫秒）：升主后老成员迁移涌入会连环触发 member_joined，
    /// 静默期内（+6s）不弹成员差量提示，成员表 UI 照常更新
    pub promoted_at: std::sync::atomic::AtomicU64,
    /// 群文件列表（按 device_id 分组；bridge 收到 file_* 事件时更新）
    pub file_map: Mutex<FileMap>,
    /// 本地持久共享文件夹（清单 + 供种服务）
    pub files: Arc<crate::files::FileManager>,
    /// 转让进行中：转出方会看到成员陆续离开（迁移潮），期间成员差量不提示；
    /// TransferFailed/TransferCompleted 时清除
    pub transferring: std::sync::atomic::AtomicBool,
}

pub(crate) type SharedState = Arc<AppState>;

pub(crate) fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// 生成 6 位数字 PIN。
pub(crate) fn gen_pin() -> String {
    use rand::Rng;
    rand::thread_rng().gen_range(100_000..1_000_000).to_string()
}

pub(crate) fn local_ip() -> String {
    local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| "127.0.0.1".into())
}

/// 是否有可用的局域网 IPv4（功能全量依赖局域网，建群前检查）。
pub(crate) fn has_lan() -> bool {
    matches!(
        local_ip_address::local_ip(),
        Ok(ip) if !ip.is_loopback()
    )
}

/// 追加历史记录（新条目置顶，保留 50 条）。
pub(crate) async fn push_history(state: &SharedState, from: &str, name: &str, payload: &knot_core::protocol::ClipboardPayload) {
    if let Some(text) = &payload.text {
        let mut h = state.history.lock().await;
        h.insert(
            0,
            HistoryItem {
                from: from.to_string(),
                name: name.to_string(),
                text: text.clone(),
                at_ms: now_ms(),
            },
        );
        h.truncate(50);
    }
}
