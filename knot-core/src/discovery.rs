//! 局域网组发现 v2（拉取式，见 docs/ws-design.md §2）：
//! 扫描端周期广播 REQ（→ 收集回应 2s → 静默 1s，周期 3s）；
//! 群主建群后在发现端口被动监听 REQ、收到即单播回源 RESP。群主不再周期广播。

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

pub const DISCOVERY_PORT: u16 = 45691;
/// v2 协议前缀（与旧版不兼容）
const PREFIX_REQ: &str = "KNOTYUP2|REQ|";
const PREFIX_RESP: &str = "KNOTYUP2|RESP|";
const PROTOCOL_VER: u32 = 2;
/// 收集回应窗口
const COLLECT_WINDOW: Duration = Duration::from_secs(2);
/// 窗口结束后的静默段（与收集窗口合计 3s 周期）
const QUIET_GAP: Duration = Duration::from_secs(1);

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GroupAd {
    pub id: String,      // 群主设备 id
    pub name: String,    // 群主设备名
    pub addr: String,    // 群 WebSocket 地址 ip:port
    #[serde(default)]
    pub members: u32,    // 群内设备数（含群主）
}

/// 扫描端 REQ 载荷
#[derive(Serialize)]
struct ReqPayload {
    scan_id: String,
    ts_ms: u64,
}

/// 群主端 RESP 载荷
#[derive(Serialize)]
struct RespPayload<'a> {
    id: &'a str,
    name: &'a str,
    addr: &'a str,
    members: u32,
    ver: u32,
    ts_ms: u64,
}

#[derive(Deserialize)]
struct RespParse {
    id: String,
    name: String,
    addr: String,
    #[serde(default)]
    members: u32,
    #[serde(default)]
    ver: u32,
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

/// 扫描端状态机（见 ws-design §2.2）：发 REQ → 收集 2s → 静默 1s → 循环。
/// 每轮整轮直出（本轮 RESP 集合即结果，无历史状态），列表变化时发送完整列表。
pub async fn scan(event_tx: tokio::sync::broadcast::Sender<Vec<GroupAd>>) -> std::io::Result<()> {
    let sock = UdpSocket::bind(("0.0.0.0", 0)).await?;
    sock.set_broadcast(true)?;
    let mut last: Vec<GroupAd> = Vec::new();
    let mut buf = [0u8; 1024];
    loop {
        // —— 发 REQ（1 个广播包 × 各目标）——
        let req = format!(
            "{PREFIX_REQ}{}",
            serde_json::to_string(&ReqPayload {
                scan_id: crate::protocol::device_id(),
                ts_ms: crate::protocol::now_ms(),
            })
            .unwrap()
        );
        let targets = broadcast_targets();
        tracing::debug!(target: "discovery/scan", "-> REQ ({} 目标)", targets.len());
        for target in targets {
            let _ = sock.send_to(req.as_bytes(), (target, DISCOVERY_PORT)).await;
        }

        // —— 收集窗口 2s：窗口内所有 RESP = 本轮结果 ——
        let deadline = tokio::time::Instant::now() + COLLECT_WINDOW;
        let mut got: HashMap<String, GroupAd> = HashMap::new();
        loop {
            let remain = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remain.is_zero() {
                break;
            }
            match tokio::time::timeout(remain, sock.recv_from(&mut buf)).await {
                Ok(Ok((n, peer))) => {
                    if let Some(ad) = parse_resp(&buf[..n]) {
                        tracing::debug!(target: "discovery/scan", "<- RESP [{peer}] {}@{} members={}", ad.name, ad.addr, ad.members);
                        got.insert(ad.id.clone(), ad);
                    }
                }
                Ok(Err(_)) => break,
                Err(_elapsed) => break, // 窗口结束
            }
        }

        // —— 本轮结算：整轮直出 ——
        let received = got.len();
        let mut list: Vec<GroupAd> = got.into_values().collect();
        list.sort_by(|a, b| a.name.cmp(&b.name)); // 稳定顺序，避免列表随机抖动
        if list != last {
            event_tx.send(list.clone()).ok();
            last = list;
        }
        let table = last.iter().map(|ad| format!("{}@{}", ad.name, ad.addr)).collect::<Vec<_>>().join(", ");
        tracing::debug!(target: "discovery/scan", "本轮结算: 收到 {received} 个回应, 当前表 [{table}]");

        // —— 静默 1s ——
        tokio::time::sleep(QUIET_GAP).await;
    }
}

fn parse_resp(bytes: &[u8]) -> Option<GroupAd> {
    let text = std::str::from_utf8(bytes).ok()?;
    let json = text.strip_prefix(PREFIX_RESP)?;
    let p: RespParse = serde_json::from_str(json).ok()?;
    if p.ver != PROTOCOL_VER {
        return None; // 版本不匹配忽略（向前演进保护）
    }
    Some(GroupAd { id: p.id, name: p.name, addr: p.addr, members: p.members })
}

/// 群主端：建群后调用——在发现端口被动监听 REQ，收到即单播回源 RESP。
/// 返回停止句柄（drop 即停止）。members 为实时成员数获取函数（异步闭包由调用方传入当前值）。
pub async fn respond(
    ad: GroupAd,
    members_now: impl Fn() -> u32 + Send + Sync + 'static,
) -> std::io::Result<mpsc::Sender<()>> {
    let sock = UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)).await?;
    let (stop_tx, mut stop_rx) = mpsc::channel::<()>(1);
    let ad = std::sync::Arc::new((ad, members_now));
    tokio::spawn(async move {
        let mut buf = [0u8; 512];
        loop {
            tokio::select! {
                _ = stop_rx.recv() => break,
                res = sock.recv_from(&mut buf) => match res {
                    Ok((n, peer)) => {
                        let text = match std::str::from_utf8(&buf[..n]) {
                            Ok(t) => t,
                            Err(_) => continue,
                        };
                        if !text.starts_with(PREFIX_REQ) {
                            continue; // 非 v2 扫描请求（可能是其他实例的 RESP），忽略
                        }
                        let (ad, members_now) = &*ad;
                        let resp = format!(
                            "{PREFIX_RESP}{}",
                            serde_json::to_string(&RespPayload {
                                id: &ad.id,
                                name: &ad.name,
                                addr: &ad.addr,
                                members: members_now(),
                                ver: PROTOCOL_VER,
                                ts_ms: crate::protocol::now_ms(),
                            })
                            .unwrap()
                        );
                        tracing::debug!(target: "discovery/respond", "<- REQ [{peer}] -> RESP members={}", members_now());
                        let _ = sock.send_to(resp.as_bytes(), peer).await;
                    }
                    Err(e) => {
                        tracing::warn!(target: "discovery/respond", "recv error: {e}");
                        break;
                    }
                }
            }
        }
    });
    Ok(stop_tx)
}
