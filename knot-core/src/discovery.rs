//! 局域网组发现：UDP 广播（实现比 mDNS 轻，跨平台无 Bonjour 依赖；后续可换 mDNS）。
//! 组主周期性广播组信息；其他设备监听并维护「最近可见」表。

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::time::{Duration, Instant};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

pub const DISCOVERY_PORT: u16 = 45691;
const ANNOUNCE_INTERVAL: Duration = Duration::from_millis(1500);
const PRUNE_AFTER: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GroupAd {
    pub id: String,      // 组主设备 id
    pub name: String,    // 组主设备名
    pub addr: String,    // 组 WebSocket 地址 ip:port
}

/// 组主：周期广播。返回停止句柄（drop 即停止）。
pub async fn announce(ad: GroupAd) -> std::io::Result<mpsc::Sender<()>> {
    let sock = UdpSocket::bind(("0.0.0.0", 0)).await?;
    sock.set_broadcast(true)?;
    let payload = format!("KNOTYUP1|{}", serde_json::to_string(&ad).unwrap());
    let (stop_tx, mut stop_rx) = mpsc::channel::<()>(1);
    tokio::spawn(async move {
        let bcast = Ipv4Addr::BROADCAST;
        loop {
            let _ = sock.send_to(payload.as_bytes(), (bcast, DISCOVERY_PORT)).await;
            tokio::select! {
                _ = stop_rx.recv() => break,
                _ = tokio::time::sleep(ANNOUNCE_INTERVAL) => {}
            }
        }
    });
    Ok(stop_tx)
}

/// 监听广播，维护并去重。每次变化（新增/消失/信息更新）发送完整列表。
pub async fn listen(event_tx: tokio::sync::broadcast::Sender<Vec<GroupAd>>) -> std::io::Result<()> {
    let sock = UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)).await?;
    let mut seen: HashMap<String, (GroupAd, Instant)> = HashMap::new();
    let mut buf = [0u8; 1024];
    loop {
        // 周期修剪过期条目
        let recv = tokio::select! {
            r = sock.recv_from(&mut buf) => Some(r?),
            _ = tokio::time::sleep(Duration::from_millis(1000)) => None,
        };
        let mut changed = false;
        if let Some((n, _peer)) = recv {
            if let Ok(text) = std::str::from_utf8(&buf[..n]) {
                if let Some(json) = text.strip_prefix("KNOTYUP1|") {
                    if let Ok(ad) = serde_json::from_str::<GroupAd>(json) {
                        let is_new_or_changed = seen
                            .get(&ad.id)
                            .map(|(old, _)| old.addr != ad.addr || old.name != ad.name)
                            .unwrap_or(true);
                        seen.insert(ad.id.clone(), (ad, Instant::now()));
                        if is_new_or_changed {
                            changed = true;
                        }
                    }
                }
            }
        }
        let before = seen.len();
        seen.retain(|_, (_, t)| t.elapsed() < PRUNE_AFTER);
        if changed || seen.len() != before {
            let list: Vec<GroupAd> = seen.values().map(|(ad, _)| ad.clone()).collect();
            event_tx.send(list).ok();
        }
    }
}
