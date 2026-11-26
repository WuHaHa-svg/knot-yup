//! 触摸板专用 UDP 数据面（桌面被控端）。
//! 手机向本模块监听的端口发送换行分隔的 JSON 事件帧；本模块解帧后用 CGEvent 注入系统输入。
//! 无连接、无重传；以最近收到数据包的源地址作为当前控制端（新地址自动接管）。
//! 与群组 ws / 文件 P2P 端口完全独立；心跳超时自动释放按键防卡住。

use crate::injector;
use serde_json::Value;
use std::time::{Duration, Instant};
use tokio::net::UdpSocket;

/// 触控板专用 UDP 端口（固定，与 ws 45691 / 调试 45692 / 文件供种随机端口区分）。
pub const TOUCHPAD_PORT: u16 = 45700;

/// 触控板服务句柄（进程级常驻，随 App 生命周期）。
pub struct TouchpadServer {
    shutdown: tokio::sync::watch::Sender<bool>,
}

impl Drop for TouchpadServer {
    fn drop(&mut self) {
        let _ = self.shutdown.send(true);
    }
}

impl TouchpadServer {
    /// 启动 UDP 监听。token 为握手令牌（用群 PIN），防误连。
    pub async fn start(token: String) -> std::io::Result<Self> {
        let socket = UdpSocket::bind(("0.0.0.0", TOUCHPAD_PORT)).await?;
        let (shutdown_tx, mut shutdown_rx) = tokio::sync::watch::channel(false);
        tokio::spawn(async move {
            let mut buf = vec![0u8; 65535];
            let mut active: Option<std::net::SocketAddr> = None; // 当前控制端源地址
            let mut handshaken = false;
            let mut last_seq: u64 = 0;
            #[allow(unused_assignments)]
            let mut last_seen = Instant::now();
            loop {
                tokio::select! {
                    _ = shutdown_rx.changed() => {
                        injector::release_all();
                        break;
                    }
                    recv = socket.recv_from(&mut buf) => {
                        let (n, peer) = match recv {
                            Ok(v) => v,
                            Err(e) => { crate::dlog("touchpad", format!("recv 失败: {e}")); continue; }
                        };
                        let text = match std::str::from_utf8(&buf[..n]) {
                            Ok(t) => t.trim(),
                            Err(_) => continue,
                        };
                        let msg: Value = match serde_json::from_str(text) {
                            Ok(v) => v,
                            Err(_) => continue,
                        };
                        let kind = msg.get("type").and_then(|v| v.as_str()).unwrap_or("");

                        // hello：校验 token，记录/接管控制端
                        if kind == "hello" {
                            let t = msg.get("token").and_then(|v| v.as_str()).unwrap_or("");
                            if t != token {
                                crate::dlog("touchpad", format!("拒绝握手 [{peer}]: BAD_TOKEN"));
                                continue;
                            }
                            if active.is_some() && active != Some(peer) {
                                injector::release_all(); // 换控制端：先释放旧按键
                                crate::dlog("touchpad", "控制端已被新地址接管");
                            }
                            active = Some(peer);
                            handshaken = true;
                            last_seq = 0;
                            let device_id = msg.get("device_id").and_then(|v| v.as_str()).unwrap_or("");
                            crate::dlog("touchpad", format!("握手成功 [{peer}] device={device_id}"));
                            continue;
                        }

                        // 未握手 / 非当前控制端的包：忽略
                        if !handshaken || active != Some(peer) { continue; }
                        last_seen = Instant::now();

                        // seq 过滤：move/scroll 丢弃乱序旧帧；离散事件不过滤
                        let seq = msg.get("seq").and_then(|v| v.as_u64()).unwrap_or(0);
                        let reliable = matches!(kind, "down" | "up" | "click" | "double_click" | "shortcut" | "key" | "ping");
                        if !reliable {
                            if seq < last_seq { continue; }
                            last_seq = seq;
                        }
                        dispatch(&msg, kind);
                    }
                }
                // 心跳超时（5s 无包）：释放按键，回到未握手状态
                if handshaken && last_seen.elapsed() > Duration::from_secs(5) {
                    injector::release_all();
                    handshaken = false;
                    active = None;
                    crate::dlog("touchpad", "心跳超时，已释放按键");
                }
            }
        });
        Ok(Self { shutdown: shutdown_tx })
    }
}

/// 按帧类型分发注入。
fn dispatch(msg: &Value, kind: &str) {
    match kind {
        "move" => injector::move_relative(num(msg, "dx"), num(msg, "dy")),
        "scroll" => injector::scroll(num(msg, "dx"), num(msg, "dy")),
        "down" => injector::button_down(msg.get("button").and_then(|v| v.as_str()).unwrap_or("left")),
        "up" => injector::button_up(msg.get("button").and_then(|v| v.as_str()).unwrap_or("left")),
        "click" => injector::click(msg.get("button").and_then(|v| v.as_str()).unwrap_or("left")),
        "double_click" => injector::double_click(),
        "shortcut" => injector::mission_control(),
        "key" => injector::key_char(msg.get("key").and_then(|v| v.as_str()).unwrap_or("")),
        "ping" => {}, // UDP 无应答
        _ => {}
    }
}

fn num(msg: &Value, key: &str) -> i32 {
    msg.get(key).and_then(|v| v.as_i64()).unwrap_or(0) as i32
}
