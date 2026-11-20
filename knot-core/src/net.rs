//! 组网络层门面：公共类型 + 服务端/客户端模块重导出（对外 API：CoreEvent / GroupServer / GroupClient）。
//! M0 拓扑：星型。组主负责成员管理与广播；剪贴板消息经组主转发到其他成员。

mod client;
mod server;

pub use client::GroupClient;
pub use server::GroupServer;

use std::collections::BTreeMap;
use std::sync::Arc;
use tokio::sync::{mpsc, RwLock};
use tokio_tungstenite::tungstenite::Message as WsMessage;

use crate::protocol::{FileMap, SharedFile, ClipboardPayload, DeviceInfo};

/// 核心层向上（UI 层）抛出的事件（v2，见 docs/ws-design.md §3）。
#[derive(Debug, Clone)]
pub enum CoreEvent {
    /// 成员列表更新（群主身份看 DeviceInfo.role）
    Members(Vec<DeviceInfo>),
    /// 收到远端剪贴板（from 为来源设备；自己发的已在连接层过滤）
    Clipboard { from: String, payload: ClipboardPayload },
    /// 群文件列表全量快照（入群 file_list 或全量同步时）
    FileList { files: FileMap },
    /// 某设备文件夹新增文件（含自己广播的回声，UI 按 from 自行判断）
    FileAdd { from: String, file: SharedFile },
    /// 某设备删除文件（file_ids 数组）
    FileRemove { from: String, file_ids: Vec<String> },
    /// 本设备被群主剔除
    Kicked(String),
    /// 群解散：UI 层延迟 0.8s 后退出群（不回发任何消息）
    Dissolve,
    /// 收到转让邀请（本机为被转让者）：用 standby 端口起服务后回 TransferReady
    TransferOffer { pin: String },
    /// 转让已失败（迟到的 ready 后群主回执）：静默关闭多余的 standby 服务
    TransferError,
    /// 全员迁移到新服务（转让完成）：断开当前连接、用同 PIN 连接新 addr
    NewService { addr: String, pin: String, owner: DeviceInfo },
    /// 转让失败（群主侧本地事件，不经网络）：不关 ws、原群继续
    TransferFailed,
    /// 转让成功（群主侧本地事件）：服务已停、已广播 new_service → 会话层清会话回首页
    TransferCompleted,
    /// 连接错误 / 断开
    Error(String),
}

pub(crate) type Tx = mpsc::Sender<WsMessage>;
/// 成员表：BTreeMap 按 device.id 保序，保证 members 广播与 Pong 快照顺序确定
/// （选主候选 = 成员表中 role==member 的设备按序承接，双端顺序必须一致：Android 端为 LinkedHashMap 插入序）。
pub(crate) type MemberMap = Arc<RwLock<BTreeMap<String, (DeviceInfo, Tx)>>>;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{device_id, HEARTBEAT_MS, PONG_TIMEOUT_MS, TRANSFER_TIMEOUT_MS, WATCHDOG_MS};
    use std::time::Duration;

    fn dev(name: &str, addr: Option<String>) -> DeviceInfo {
        DeviceInfo { id: device_id(), name: name.into(), platform: "test".into(), addr, file_addr: None, role: None }
    }

    /// 收集事件直到条件满足（100ms 轮询，上限 3s）
    async fn wait_event(rx: &mut tokio::sync::broadcast::Receiver<CoreEvent>, mut f: impl FnMut(&CoreEvent) -> bool) -> bool {
        for _ in 0..30 {
            while let Ok(ev) = rx.try_recv() {
                if f(&ev) {
                    return true;
                }
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        false
    }

    /// 常量防回归（v2 定稿值）
    #[test]
    fn heartbeat_params() {
        assert_eq!(HEARTBEAT_MS, 30_000);
        assert_eq!(PONG_TIMEOUT_MS, 3_000);
        assert_eq!(WATCHDOG_MS, 35_000);
        assert_eq!(TRANSFER_TIMEOUT_MS, 5_000);
    }

    /// 转让三方握手全链路：A 建群，B/C 入群；A 发 offer → B 起 standby 服务回 ready
    /// → A 广播 new_service 并停服 → C 收到 new_service 后连 B 成功。
    #[tokio::test]
    async fn transfer_handshake_and_migrate() {
        let (tx, mut rx) = tokio::sync::broadcast::channel::<CoreEvent>(128);
        let (server_a, port_a) = GroupServer::start(0, "123456".into(), dev("A", None), tx.clone())
            .await
            .unwrap();

        // B：预留 standby 端口
        let standby_b = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let port_b = standby_b.local_addr().unwrap().port();
        let dev_b = dev("B", Some(format!("127.0.0.1:{port_b}")));
        let client_b =
            GroupClient::connect(&format!("127.0.0.1:{port_a}"), "123456", dev_b.clone(), tx.clone())
                .await
                .unwrap();
        let client_c =
            GroupClient::connect(&format!("127.0.0.1:{port_a}"), "123456", dev("C", Some("127.0.0.1:1".into())), tx.clone())
                .await
                .unwrap();
        tokio::time::sleep(Duration::from_millis(300)).await;

        // A 发起转让（立即返回，等待握手异步完成）
        server_a.transfer(&dev_b.id).await;

        // B 收到 transfer_offer
        assert!(
            wait_event(&mut rx, |ev| matches!(ev, CoreEvent::TransferOffer { .. })).await,
            "B 未收到 transfer_offer"
        );

        // B：起 standby 服务，通过原 ws 回 ready
        drop(standby_b);
        let (_server_b, port_b2) = GroupServer::start(port_b, "123456".into(), dev_b.clone(), tx.clone())
            .await
            .unwrap();
        assert_eq!(port_b2, port_b, "承接端口应与预留端口一致");
        client_b.send_transfer_ready(format!("127.0.0.1:{port_b}")).await.unwrap();

        // C 收到 new_service（owner 为 B 且 role=owner）
        let mut got_ns = false;
        assert!(
            wait_event(&mut rx, |ev| {
                if let CoreEvent::NewService { owner, .. } = ev {
                    if owner.name == "B" && owner.is_owner() {
                        got_ns = true;
                    }
                }
                got_ns
            })
            .await,
            "C 未收到 new_service（或 owner 身份错误）"
        );

        // C：断开旧连接、连接 B 的新服务
        drop(client_c);
        let c2 = GroupClient::connect(&format!("127.0.0.1:{port_b}"), "123456", dev("C", None), tx.clone())
            .await;
        assert!(c2.is_ok(), "C 迁移到新服务失败: {:?}", c2.err().map(|e| e.to_string()));

        let mut members_ok = false;
        assert!(
            wait_event(&mut rx, |ev| {
                if let CoreEvent::Members(m) = ev {
                    if m.len() == 2 && m.iter().any(|d| d.name == "B" && d.is_owner()) && m.iter().any(|d| d.name == "C") {
                        members_ok = true;
                    }
                }
                members_ok
            })
            .await,
            "C 未在新群收到正确成员表"
        );
    }

    /// 转让超时：B 不回 ready → 5s 后 A 抛 TransferFailed，群服务仍存活。
    #[tokio::test]
    async fn transfer_timeout_keeps_group() {
        let (tx, mut rx) = tokio::sync::broadcast::channel::<CoreEvent>(64);
        let (server_a, port_a) = GroupServer::start(0, "123456".into(), dev("A", None), tx.clone())
            .await
            .unwrap();
        let dev_b = dev("B", Some("127.0.0.1:1".into()));
        let _client_b =
            GroupClient::connect(&format!("127.0.0.1:{port_a}"), "123456", dev_b.clone(), tx.clone())
                .await
                .unwrap();
        tokio::time::sleep(Duration::from_millis(300)).await;

        server_a.transfer(&dev_b.id).await;
        // B 收到 offer 但不回 ready
        assert!(
            wait_event(&mut rx, |ev| matches!(ev, CoreEvent::TransferOffer { .. })).await,
            "B 未收到 offer"
        );

        // 5s 超时 → TransferFailed（等待窗口需覆盖 5s 超时 + 余量）
        let mut failed = false;
        for _ in 0..80 {
            while let Ok(ev) = rx.try_recv() {
                if matches!(ev, CoreEvent::TransferFailed) {
                    failed = true;
                }
            }
            if failed {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert!(failed, "超时后未抛 TransferFailed");

        // 原群仍可用：新成员仍可入群
        let c = GroupClient::connect(&format!("127.0.0.1:{port_a}"), "123456", dev("C", None), tx.clone()).await;
        assert!(c.is_ok(), "转让失败后原群应继续可用");
    }

    /// 解散：成员收到 Dissolve 事件（0.8s 退出属 UI 层，core 只验消息）。
    #[tokio::test]
    async fn dissolve_flow() {
        let (tx, mut rx) = tokio::sync::broadcast::channel::<CoreEvent>(64);
        let (server_a, port_a) = GroupServer::start(0, "123456".into(), dev("A", None), tx.clone())
            .await
            .unwrap();
        let _client_b =
            GroupClient::connect(&format!("127.0.0.1:{port_a}"), "123456", dev("B", None), tx.clone())
                .await
                .unwrap();
        tokio::time::sleep(Duration::from_millis(300)).await;

        server_a.shutdown().await;
        assert!(
            wait_event(&mut rx, |ev| matches!(ev, CoreEvent::Dissolve)).await,
            "成员未收到 Dissolve"
        );
    }

    /// 剪贴板回声去重：成员不会收到自己发出的剪贴板事件。
    #[tokio::test]
    async fn clipboard_self_echo_ignored() {
        let (tx, mut rx) = tokio::sync::broadcast::channel::<CoreEvent>(64);
        let (server_a, port_a) = GroupServer::start(0, "123456".into(), dev("A", None), tx.clone())
            .await
            .unwrap();
        let client_b =
            GroupClient::connect(&format!("127.0.0.1:{port_a}"), "123456", dev("B", None), tx.clone())
                .await
                .unwrap();
        let client_c =
            GroupClient::connect(&format!("127.0.0.1:{port_a}"), "123456", dev("C", None), tx.clone())
                .await
                .unwrap();
        tokio::time::sleep(Duration::from_millis(300)).await;
        while let Ok(_) = rx.try_recv() {} // 清空缓冲

        client_b
            .send_clipboard(ClipboardPayload {
                kind: crate::protocol::ClipboardKind::Text,
                text: Some("hello".into()),
                hash: "a1b2c3d4e5f60718".into(),
            })
            .await
            .unwrap();

        // 事件来源分析（共享总线）：预期恰好 2 条 from=B 的 Clipboard 事件
        // —— A（服务端本机通知）+ C（客户端收广播）；若 B 的客户端把回声也抛出，会出现第 3 条。
        let b_id = client_b.local_id().to_string();
        let mut count = 0;
        let mut from_b = false;
        for _ in 0..20 {
            while let Ok(ev) = rx.try_recv() {
                if let CoreEvent::Clipboard { from, .. } = &ev {
                    if from == &b_id {
                        count += 1;
                        from_b = true;
                    }
                }
            }
            if count >= 2 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert!(from_b, "无人收到 B 的剪贴板消息");
        // 等一拍确认没有第 3 条（B 的回声）
        tokio::time::sleep(Duration::from_millis(300)).await;
        while let Ok(ev) = rx.try_recv() {
            if let CoreEvent::Clipboard { from, .. } = &ev {
                if from == &b_id {
                    count += 1;
                }
            }
        }
        assert_eq!(count, 2, "Clipboard 事件数异常（期望 A+C 共 2 条，B 的回声未被过滤: {count} 条）");
    }
}
