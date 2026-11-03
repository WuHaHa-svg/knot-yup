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

use crate::protocol::{ClipboardPayload, DeviceInfo};

/// 核心层向上（UI 层）抛出的事件。
#[derive(Debug, Clone)]
pub enum CoreEvent {
    /// 成员列表更新
    Members(Vec<DeviceInfo>),
    /// 收到远端剪贴板（from 为来源设备）
    Clipboard { from: String, payload: ClipboardPayload },
    /// 本设备被组主剔除
    Kicked(String),
    /// 群主转让（新群主信息）
    OwnerChange(DeviceInfo),
    /// 连接错误 / 断开
    Error(String),
}

pub(crate) type Tx = mpsc::Sender<WsMessage>;
/// 成员表：BTreeMap 按 device.id 保序，保证 members 广播与 Pong 快照顺序确定
/// （选主候选 = members[1..] 顺位承接，双端顺序必须一致：Android 端为 LinkedHashMap 插入序）。
pub(crate) type MemberMap = Arc<RwLock<BTreeMap<String, (DeviceInfo, Tx)>>>;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::device_id;

    fn dev(name: &str, addr: Option<String>) -> DeviceInfo {
        DeviceInfo { id: device_id(), name: name.into(), platform: "test".into(), addr }
    }

    /// 群主转让全链路：A 建群，B/C 入群，A 转让给 B 后停止；
    /// B 用预留端口承接为群主，C 重连 B 成功。
    #[tokio::test]
    async fn owner_transfer_and_reconnect() {
        let (tx, mut rx) = tokio::sync::broadcast::channel::<CoreEvent>(64);
        let (server_a, port_a) = GroupServer::start(0, "123456".into(), dev("A", None), tx.clone())
            .await
            .unwrap();

        // B：预留端口
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

        // 等 B/C 入群广播
        tokio::time::sleep(std::time::Duration::from_millis(300)).await;

        // A 转让给 B 并停止
        server_a.transfer(&dev_b).await;

        // B/C 应收到 OwnerChange
        let mut got_b = false;
        let mut got_c = false;
        for _ in 0..20 {
            while let Ok(ev) = rx.try_recv() {
                if matches!(ev, CoreEvent::OwnerChange(_)) {
                    got_b = true;
                }
            }
            if got_b {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        }
        assert!(got_b, "OwnerChange 未广播");
        let _ = got_c;

        // B：断开旧连接、释放预留端口、承接为群主
        drop(client_b);
        drop(standby_b);
        let (_server_b, port_b2) = GroupServer::start(port_b, "123456".into(), dev_b.clone(), tx.clone())
            .await
            .unwrap();
        assert_eq!(port_b2, port_b, "承接端口应与预留端口一致");

        // C：重连 B
        drop(client_c);
        let c2 = GroupClient::connect(&format!("127.0.0.1:{port_b}"), "123456", dev("C", None), tx.clone())
            .await;
        assert!(c2.is_ok(), "C 重连 B 失败: {:?}", c2.err().map(|e| e.to_string()));

        // C 应收到成员表 [B, C]
        let mut members_ok = false;
        for _ in 0..20 {
            while let Ok(ev) = rx.try_recv() {
                if let CoreEvent::Members(m) = ev {
                    if m.len() == 2 && m.iter().any(|d| d.name == "B") && m.iter().any(|d| d.name == "C") {
                        members_ok = true;
                    }
                }
            }
            if members_ok {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        }
        assert!(members_ok, "C 未收到正确的成员表");
    }
}
