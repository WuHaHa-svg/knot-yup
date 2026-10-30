//! 局域网组发现：UDP 广播（实现比 mDNS 轻，跨平台无 Bonjour 依赖；后续可换 mDNS）。
//! 组主每 2.5s 广播一次；监听端每 1s 一个扫描周期，killStep 渐进剔除：
//! 连续 5 个扫描周期未收到广播才正式移除（应对 Wi-Fi 广播丢包），再收到即复活并清零。

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

pub const DISCOVERY_PORT: u16 = 45691;
const ANNOUNCE_INTERVAL: Duration = Duration::from_millis(2500);
/// 扫描周期：每 1000ms 对表做一次「命中清零 / 未命中累积」
const SCAN_INTERVAL: Duration = Duration::from_millis(1000);
/// killStep 达到 5（连续 5 个周期未收到）才剔除
const KILL_THRESHOLD: u32 = 5;

#[derive(Debug, Clone, Serialize, Deserialize)]
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
            for target in broadcast_targets() {
                let _ = sock.send_to(payload.as_bytes(), (target, DISCOVERY_PORT)).await;
            }
        }
        // 常规期：每 2.5s 一发
        loop {
            for target in broadcast_targets() {
                let _ = sock.send_to(payload.as_bytes(), (target, DISCOVERY_PORT)).await;
            }
            tokio::select! {
                _ = stop_rx.recv() => break,
                _ = tokio::time::sleep(ANNOUNCE_INTERVAL) => {}
            }
        }
    });
    Ok(stop_tx)
}

/// 监听广播：每个扫描周期（1s）内收到的群命中（killStep 清零/新增），
/// 未命中的 killStep +1，累积到 5 正式剔除。列表变化时发送完整列表。
pub async fn listen(event_tx: tokio::sync::broadcast::Sender<Vec<GroupAd>>) -> std::io::Result<()> {
    let sock = UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)).await?;
    // id -> (群信息, killStep)
    let mut seen: HashMap<String, (GroupAd, u32)> = HashMap::new();
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
                Ok(Ok((n, _))) => {
                    if let Ok(text) = std::str::from_utf8(&buf[..n]) {
                        if let Some(json) = text.strip_prefix("KNOTYUP1|") {
                            if let Ok(ad) = serde_json::from_str::<GroupAd>(json) {
                                got.insert(ad.id.clone(), ad);
                            }
                        }
                    }
                }
                Ok(Err(_)) => break,
                Err(_elapsed) => break, // 周期结束
            }
        }

        // —— 周期末结算 ——
        let mut changed = false;
        // 命中：新增或清零（信息变化时更新）
        for (id, ad) in &got {
            match seen.get_mut(id) {
                Some((old, kill)) => {
                    if old.addr != ad.addr || old.name != ad.name {
                        *old = ad.clone();
                        changed = true;
                    }
                    *kill = 0;
                }
                None => {
                    seen.insert(id.clone(), (ad.clone(), 0));
                    changed = true;
                }
            }
        }
        // 未命中：killStep 累积，达到阈值正式剔除
        let missed: Vec<String> = seen
            .keys()
            .filter(|id| !got.contains_key(*id))
            .cloned()
            .collect();
        for id in missed {
            if let Some((_, kill)) = seen.get_mut(&id) {
                *kill += 1;
                if *kill >= KILL_THRESHOLD {
                    seen.remove(&id);
                    changed = true;
                }
            }
        }

        if changed {
            let list: Vec<GroupAd> = seen.values().map(|(ad, _)| ad.clone()).collect();
            event_tx.send(list).ok();
        }
    }
}
