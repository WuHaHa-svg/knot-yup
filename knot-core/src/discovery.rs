//! 局域网组发现：UDP 广播（实现比 mDNS 轻，跨平台无 Bonjour 依赖；后续可换 mDNS）。
//! 组主每 1s 常规广播一次（建群另有突发）；监听端每 5s 一个扫描周期，窗口直出模型：
//! 一个周期（期望 ~5 个广播包）内收到即在线、未收到即剔除——广播密度下单窗全空
//! 几乎只意味着群主真的离开；万一误剔，下个窗口收到即自动复活（自愈）。

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

pub const DISCOVERY_PORT: u16 = 45691;
const ANNOUNCE_INTERVAL: Duration = Duration::from_secs(1);
/// 扫描周期：窗口内收到的群即当前在线群
const SCAN_INTERVAL: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GroupAd {
    pub id: String,      // 组主设备 id
    pub name: String,    // 组主设备名
    pub addr: String,    // 组 WebSocket 地址 ip:port
}

/// 收集广播目标：全局广播 + 本机各 IPv4 所在 /24 子网广播（应对 VPN/多网卡下全局广播走错接口）。
fn broadcast_targets() -> Vec<Ipv4Addr> {
    let mut targets = vec![Ipv4Addr::BROADCAST];
    if let Ok(local) = local_ip_address::local_ip() {
        if let std::net::IpAddr::V4(v4) = local {
            let o = v4.octets();
            targets.push(Ipv4Addr::new(o[0], o[1], o[2], 255));
        }
    }
    targets
}

/// 组主：周期广播（建群初期密集突发，快速建立可见性）。
/// 返回停止句柄（drop 即停止）。
pub async fn announce(ad: GroupAd) -> std::io::Result<mpsc::Sender<()>> {
    let sock = UdpSocket::bind(("0.0.0.0", 0)).await?;
    sock.set_broadcast(true)?;
    let payload = format!("KNOTYUP1|{}", serde_json::to_string(&ad).unwrap());
    let (stop_tx, mut stop_rx) = mpsc::channel::<()>(1);
    tokio::spawn(async move {
        async fn send_all(sock: &UdpSocket, payload: &str, reason: &str) {
            let targets = broadcast_targets();
            tracing::debug!(target: "discovery/announce", "{reason} -> {} 目标", targets.len());
            for target in targets {
                let _ = sock.send_to(payload.as_bytes(), (target, DISCOVERY_PORT)).await;
            }
        }
        // 突发期：前 2s 内每 400ms 一发（连丢 4~5 个包也能秒级被发现）
        let burst_plan: Vec<Duration> = vec![
            Duration::from_millis(0),
            Duration::from_millis(400),
            Duration::from_millis(800),
            Duration::from_millis(1200),
            Duration::from_millis(1600),
        ];
        for delay in burst_plan {
            tokio::select! {
                _ = stop_rx.recv() => return,
                _ = tokio::time::sleep(delay) => {}
            }
            send_all(&sock, &payload, &format!("突发广播 +{delay:?}")).await;
        }
        // 常规期：每 1s 一发
        send_all(&sock, &payload, "常规广播").await;
        loop {
            tokio::select! {
                _ = stop_rx.recv() => break,
                _ = tokio::time::sleep(ANNOUNCE_INTERVAL) => {
                    send_all(&sock, &payload, "常规广播").await;
                }
            }
        }
    });
    Ok(stop_tx)
}

/// 监听广播：每个扫描周期（5s）窗口直出——窗口内收到即在线、未收到即剔除，
/// 列表变化时发送完整列表。
pub async fn listen(event_tx: tokio::sync::broadcast::Sender<Vec<GroupAd>>) -> std::io::Result<()> {
    let sock = UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)).await?;
    // 上次广播的列表（仅在变化时发送，避免每 5s 刷一次 UI）
    let mut last: Vec<GroupAd> = Vec::new();
    let mut buf = [0u8; 1024];
    loop {
        // —— 一个扫描周期：收集周期内收到的广播 ——
        let deadline = tokio::time::Instant::now() + SCAN_INTERVAL;
        let mut got: HashMap<String, GroupAd> = HashMap::new();
        loop {
            let remain = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remain.is_zero() {
                break;
            }
            match tokio::time::timeout(remain, sock.recv_from(&mut buf)).await {
                Ok(Ok((n, peer))) => {
                    if let Ok(text) = std::str::from_utf8(&buf[..n]) {
                        if let Some(json) = text.strip_prefix("KNOTYUP1|") {
                            if let Ok(ad) = serde_json::from_str::<GroupAd>(json) {
                                tracing::debug!(target: "discovery/recv", "<- 广播 [{peer}] {}@{}", ad.name, ad.addr);
                                got.insert(ad.id.clone(), ad);
                            }
                        }
                    }
                }
                Ok(Err(_)) => break,
                Err(_elapsed) => break, // 周期结束
            }
        }

        // —— 周期末结算：窗口直出（本窗口收到即在线，未收到即剔除；误剔下窗自愈） ——
        let received = got.len();
        let mut list: Vec<GroupAd> = got.into_values().collect();
        list.sort_by(|a, b| a.name.cmp(&b.name)); // 稳定顺序，避免列表随机抖动
        if list != last {
            event_tx.send(list.clone()).ok();
            last = list;
        }
        // 每个扫描周期都打点
        let table = last.iter().map(|ad| format!("{}@{}", ad.name, ad.addr)).collect::<Vec<_>>().join(", ");
        tracing::debug!(target: "discovery/scan", "周期结算: 本周期收到 {received} 个广播, 当前表 [{table}]");
    }
}
