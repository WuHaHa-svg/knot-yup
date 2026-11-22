//! 触摸板专用 TCP 数据面（桌面被控端）。
//! 手机连本模块监听的端口，发送换行分隔的 JSON 事件帧；本模块解帧后用 CGEvent 注入系统输入。
//! 与群组 ws / 文件 P2P 端口完全独立；同一时刻只接受一个控制连接（一对一）。

use crate::injector;
use serde_json::Value;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};

/// 触控板专用 TCP 端口（固定，与 ws 45691 / 调试 45692 / 文件供种随机端口区分）。
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
    /// 启动监听。token 为握手令牌（用群 PIN），防误连。
    pub async fn start(token: String) -> std::io::Result<Self> {
        let listener = TcpListener::bind(("0.0.0.0", TOUCHPAD_PORT)).await?;
        let (shutdown_tx, mut shutdown_rx) = tokio::sync::watch::channel(false);
        let token_for_loop = token.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = shutdown_rx.changed() => break,
                    accepted = listener.accept() => {
                        let (stream, peer) = match accepted {
                            Ok(v) => v,
                            Err(e) => { crate::dlog("touchpad", format!("accept 失败: {e}")); continue; }
                        };
                        crate::dlog("touchpad", format!("控制端接入 [{peer}]"));
                        let token = token_for_loop.clone();
                        tokio::spawn(async move {
                            if let Err(e) = serve_connection(stream, &token).await {
                                crate::dlog("touchpad", format!("会话结束 [{peer}]: {e}"));
                                // 松开兜底：连接断开时注入全局抬起，避免按键卡住
                                injector::release_all();
                            }
                        });
                    }
                }
            }
        });
        Ok(Self { shutdown: shutdown_tx })
    }
}

/// 单个控制连接：读帧 → 校验 → 分发注入。
async fn serve_connection(stream: TcpStream, token: &str) -> Result<(), String> {
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    let mut last_seq: u64 = 0;
    let mut handshaken = false;
    // 已按下未抬起的按键（连接断开时兜底）
    let mut held: Vec<String> = Vec::new();

    loop {
        line.clear();
        let n = reader.read_line(&mut line).await.map_err(|e| format!("读帧: {e}"))?;
        if n == 0 { break; } // 对端关闭
        let msg: Value = serde_json::from_str(line.trim()).map_err(|e| format!("JSON 解析: {e}"))?;
        let kind = msg.get("type").and_then(|v| v.as_str()).unwrap_or("");

        if !handshaken {
            if kind != "hello" { continue; } // 握手前忽略其它帧
            let t = msg.get("token").and_then(|v| v.as_str()).unwrap_or("");
            if t != token {
                let _ = reader.write_all(b"{\"type\":\"reject\",\"reason\":\"BAD_TOKEN\"}\n").await;
                return Err("握手令牌错误".into());
            }
            handshaken = true;
            let _ = reader.write_all(b"{\"type\":\"hello_ok\"}\n").await;
            crate::dlog("touchpad", "握手成功，开始接收事件");
            continue;
        }

        // seq 过滤：move/scroll 丢弃乱序旧帧；down/up/click 可靠送达不过滤
        let seq = msg.get("seq").and_then(|v| v.as_u64()).unwrap_or(0);
        let reliable = matches!(kind, "down" | "up" | "click" | "shortcut" | "key" | "ping");
        if !reliable {
            if seq < last_seq { continue; }
            last_seq = seq;
        }

        match kind {
            "move" => {
                let dx = num(&msg, "dx");
                let dy = num(&msg, "dy");
                injector::move_relative(dx, dy);
            }
            "scroll" => {
                let dx = num(&msg, "dx");
                let dy = num(&msg, "dy");
                injector::scroll(dx, dy);
            }
            "down" => {
                let btn = msg.get("button").and_then(|v| v.as_str()).unwrap_or("left");
                injector::button_down(btn);
                held.push(btn.to_string());
            }
            "up" => {
                let btn = msg.get("button").and_then(|v| v.as_str()).unwrap_or("left");
                injector::button_up(btn);
                held.retain(|b| b != btn);
            }
            "click" => {
                let btn = msg.get("button").and_then(|v| v.as_str()).unwrap_or("left");
                injector::click(btn);
            }
            "shortcut" => injector::mission_control(),
            "ping" => {
                let ts = msg.get("ts_ms").and_then(|v| v.as_u64()).unwrap_or(0);
                let pong = serde_json::json!({"type":"pong","ts_ms":ts});
                let _ = reader.write_all(format!("{pong}\n").as_bytes()).await;
            }
            _ => {}
        }
    }
    // 正常结束也释放可能按住的键
    for b in &held { injector::button_up(b); }
    Ok(())
}

fn num(msg: &Value, key: &str) -> i32 {
    msg.get(key).and_then(|v| v.as_i64()).unwrap_or(0) as i32
}
