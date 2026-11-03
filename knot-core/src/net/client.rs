//! 成员（客户端）：连接入组、读循环事件抛出、心跳（Ping/Pong）失联判定。

use futures_util::{SinkExt, StreamExt};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{broadcast, mpsc};
use tokio_tungstenite::tungstenite::Message as WsMessage;

use crate::crypto::Cipher;
use crate::net::{CoreEvent, Tx};
use crate::protocol::{ClipboardPayload, DeviceInfo, Message};

/// 成员（客户端）句柄。drop 时中止读任务 → 断开连接，群主侧才能感知离开。
pub struct GroupClient {
    tx: Tx,
    local_id: String,
    cipher: Arc<Cipher>,
    reader: Option<tokio::task::JoinHandle<()>>,
}

impl Drop for GroupClient {
    fn drop(&mut self) {
        if let Some(h) = &self.reader {
            h.abort();
        }
    }
}

impl GroupClient {
    /// 连接并尝试入组。成功返回句柄；后台任务持续读取组主消息并抛事件。
    pub async fn connect(
        addr: &str,
        pin: &str,
        device: DeviceInfo,
        event_tx: broadcast::Sender<CoreEvent>,
    ) -> Result<Self, String> {
        let url = if addr.starts_with("ws://") { addr.to_string() } else { format!("ws://{addr}") };
        tracing::debug!(target: "ws/client", "连接 {url} …");
        let ws = tokio_tungstenite::connect_async(&url)
            .await
            .map_err(|e| format!("连接失败: {e}"))?;
        let (mut sink, mut source) = ws.0.split();

        tracing::debug!(target: "ws/client", "-> join device={} addr={:?}", device.name, device.addr);
        sink.send(WsMessage::Text(
            serde_json::to_string(&Message::Join { pin: pin.to_string(), device: device.clone() }).unwrap(),
        ))
        .await
        .map_err(|e| format!("发送入组请求失败: {e}"))?;

        // 等待入组结果
        match source.next().await {
            Some(Ok(WsMessage::Text(t))) => match serde_json::from_str::<Message>(&t) {
                Ok(Message::JoinResult { ok: true, members, .. }) => {
                    tracing::debug!(target: "ws/client", "<- join_result{{ok=true}} members={}", members.len());
                    event_tx.send(CoreEvent::Members(members)).ok();
                }
                Ok(Message::JoinResult { ok: false, reason, .. }) => {
                    tracing::debug!(target: "ws/client", "<- join_result{{ok=false}} reason={:?}",
                        reason.as_deref().unwrap_or(""));
                    return Err(reason.unwrap_or_else(|| "入组被拒绝".into()));
                }
                _ => return Err("协议错误：期望入组结果".into()),
            },
            _ => return Err("连接在握手期间中断".into()),
        }

        let (tx, mut rx) = mpsc::channel::<WsMessage>(64);
        let local_id = device.id.clone();
        let cipher = Arc::new(Cipher::from_pin(pin));
        let loop_cipher = cipher.clone();

        // 心跳共享状态：最近一次被回执的 ping 序号（读循环更新，心跳任务检查）
        let last_pong_seq = std::sync::Arc::new(std::sync::Mutex::new(0u64));
        let reader_seq = last_pong_seq.clone();
        let hb_seq = last_pong_seq.clone();
        // 读循环存活标志：读循环退出（被踢/服务端关闭/出错）后心跳任务随之静默退出，
        // 防止陈旧心跳在连接已死后误报「群主失联」
        let reader_alive = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(true));
        let reader_alive_flag = reader_alive.clone();
        let hb_alive = reader_alive.clone();
        // 心跳失联信号：心跳任务 → 读循环（读循环收到后自行结束并抛 Error）
        let (hb_shutdown_tx, mut hb_shutdown_rx) = mpsc::channel::<()>(1);

        let reader = tokio::spawn(async move {
            let writer = tokio::spawn(async move {
                while let Some(msg) = rx.recv().await {
                    if sink.send(msg).await.is_err() {
                        break;
                    }
                }
            });
            loop {
                let msg = tokio::select! {
                    m = source.next() => match m {
                        Some(v) => v,
                        None => break,
                    },
                    _ = hb_shutdown_rx.recv() => {
                        // 心跳判定失联，主动结束读循环
                        event_tx.send(CoreEvent::Error("connection closed: heartbeat timeout".into())).ok();
                        break;
                    }
                };
                match msg {
                    Ok(WsMessage::Text(t)) => {
                        let inner = serde_json::from_str::<Message>(&t)
                            .ok()
                            .and_then(|m| m.open(&loop_cipher));
                        match inner {
                            Some(Message::Members { members }) => {
                                tracing::debug!(target: "ws/client", "<- members n={}", members.len());
                                event_tx.send(CoreEvent::Members(members)).ok();
                            }
                            Some(Message::Pong { members, seq, .. }) => {
                                tracing::debug!(target: "ws/client", "<- pong#{seq} members={}", members.len());
                                *reader_seq.lock().unwrap() = seq;
                                // Pong 携带群状态快照 → 同步成员表（任意成员实时知晓群状态）
                                event_tx.send(CoreEvent::Members(members)).ok();
                            }
                            Some(Message::Clipboard { from, payload }) => {
                                tracing::debug!(target: "ws/client", "<- clipboard from={} len={} hash={}",
                                    from, payload.text.as_deref().map(str::len).unwrap_or(0), payload.hash);
                                event_tx.send(CoreEvent::Clipboard { from, payload }).ok();
                            }
                            Some(Message::Kick { reason }) => {
                                tracing::debug!(target: "ws/client", "<- kick reason={:?}", reason.as_deref().unwrap_or(""));
                                event_tx.send(CoreEvent::Kicked(reason.unwrap_or_default())).ok();
                                break;
                            }
                            Some(Message::OwnerChange { new_owner }) => {
                                tracing::debug!(target: "ws/client", "<- owner_change new_owner={}", new_owner.name);
                                event_tx.send(CoreEvent::OwnerChange(new_owner)).ok();
                                break;
                            }
                            _ => {}
                        }
                    }
                    Ok(WsMessage::Close(_)) => {
                        tracing::debug!(target: "ws/client", "服务端关闭连接");
                        // 视为连接断开：抛 Error 触发上层的选主/重连（否则成员会静默卡死）
                        event_tx.send(CoreEvent::Error("connection closed: server closed connection".into())).ok();
                        break;
                    }
                    Ok(_) => {}
                    Err(e) => {
                        tracing::debug!(target: "ws/client", "连接错误: {e}");
                        event_tx.send(CoreEvent::Error(format!("connection closed: {e}"))).ok();
                        break;
                    }
                }
            }
            reader_alive_flag.store(false, std::sync::atomic::Ordering::Relaxed);
            writer.abort();
        });

        // 心跳任务：每 5s 发一次 Ping；3s 内未收到对应 seq 的 Pong 计一次未达；连续 3 次未达 → 判定群主失联
        {
            let tx = tx.clone();
            let device = device.clone();
            let hb_tx = hb_shutdown_tx;
            tokio::spawn(async move {
                let mut seq: u64 = 0;
                let mut misses: u32 = 0;
                loop {
                    tokio::time::sleep(Duration::from_secs(5)).await;
                    // 读循环已退出（被踢/关闭/出错）：心跳随之静默结束，不误报失联
                    if !hb_alive.load(std::sync::atomic::Ordering::Relaxed) {
                        return;
                    }
                    seq += 1;
                    let ping = Message::Ping {
                        device: device.clone(),
                        seq,
                        ts_ms: crate::protocol::now_ms(),
                    };
                    tracing::debug!(target: "ws/client", "-> ping#{seq}");
                    if tx.send(WsMessage::Text(serde_json::to_string(&ping).unwrap())).await.is_err() {
                        return; // 连接已关闭，读循环会处理后续
                    }
                    // 等待 3s 内的回执
                    let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
                    let mut acked = false;
                    while tokio::time::Instant::now() < deadline {
                        if !hb_alive.load(std::sync::atomic::Ordering::Relaxed) {
                            return;
                        }
                        if *hb_seq.lock().unwrap() >= seq {
                            acked = true;
                            break;
                        }
                        tokio::time::sleep(Duration::from_millis(300)).await;
                    }
                    if acked {
                        misses = 0;
                    } else {
                        misses += 1;
                        tracing::debug!(target: "ws/client", "ping#{seq} 3s 未回执，连续未达 {misses}/3");
                        if misses >= 3 {
                            tracing::debug!(target: "ws/client", "心跳判定群主失联（连续 3 次未回执）");
                            hb_tx.send(()).await.ok();
                            return;
                        }
                    }
                }
            });
        }

        Ok(Self { tx, local_id, cipher, reader: Some(reader) })
    }

    pub fn local_id(&self) -> &str {
        &self.local_id
    }

    /// 发送本机剪贴板变化到组主（加密信封）。
    pub async fn send_clipboard(&self, payload: ClipboardPayload) -> Result<(), String> {
        tracing::debug!(target: "ws/client", "-> clipboard len={} hash={}",
            payload.text.as_deref().map(str::len).unwrap_or(0), payload.hash);
        let text = serde_json::to_string(
            &Message::Clipboard {
                from: self.local_id.clone(),
                payload,
            }
            .seal(&self.cipher),
        )
        .unwrap();
        self.tx
            .send(WsMessage::Text(text))
            .await
            .map_err(|_| "连接已关闭".to_string())
    }
}
