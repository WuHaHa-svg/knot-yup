//! 组网络层：组主 WebSocket 服务端 + 成员客户端。
//! M0 拓扑：星型。组主负责成员管理与广播；剪贴板消息经组主转发到其他成员。

use futures_util::{SinkExt, StreamExt};
use std::collections::HashMap;
use std::error::Error;
use std::sync::Arc;
use std::time::Duration;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, mpsc, RwLock};
use tokio_tungstenite::tungstenite::Message as WsMessage;

use crate::crypto::Cipher;
use crate::protocol::{ClipboardPayload, DeviceInfo, Message};

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

type Tx = mpsc::Sender<WsMessage>;
type MemberMap = Arc<RwLock<HashMap<String, (DeviceInfo, Tx)>>>;

/// 组主（WebSocket 服务端）。
pub struct GroupServer {
    /// 群主自身设备信息（始终位于成员表首位）
    owner: DeviceInfo,
    members: MemberMap,
    pin: Arc<String>,
    cipher: Arc<Cipher>,
    event_tx: broadcast::Sender<CoreEvent>,
    shutdown: Tx,
}

impl GroupServer {
    /// 启动组主。owner 为群主设备信息，计入成员表首位。返回 (句柄, 实际监听端口)。
    pub async fn start(
        port: u16,
        pin: String,
        owner: DeviceInfo,
        event_tx: broadcast::Sender<CoreEvent>,
    ) -> std::io::Result<(Arc<Self>, u16)> {
        let listener = TcpListener::bind(("0.0.0.0", port)).await?;
        let port = listener.local_addr()?.port();
        tracing::debug!(target: "ws/server", "群主监听 0.0.0.0:{port} pin={pin}");
        let members: MemberMap = Arc::new(RwLock::new(HashMap::new()));
        // shutdown 通道：发送端保留在句柄中，接收端挂到 accept 循环上。
        let (shutdown, mut shutdown_rx) = mpsc::channel::<WsMessage>(1);

        let server = Arc::new(Self {
            owner,
            members: members.clone(),
            pin: Arc::new(pin.clone()),
            cipher: Arc::new(Cipher::from_pin(&pin)),
            event_tx,
            shutdown,
        });

        let accept_server = server.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = shutdown_rx.recv() => break,
                    accepted = listener.accept() => {
                        let (stream, _) = match accepted {
                            Ok(v) => v,
                            Err(e) => {
                                tracing::warn!("accept error: {e}");
                                continue;
                            }
                        };
                        let server = accept_server.clone();
                        tokio::spawn(async move {
                            if let Err(e) = server.handle_conn(stream).await {
                                tracing::debug!("connection closed: {e}");
                            }
                        });
                    }
                }
            }
        });
        Ok((server, port))
    }

    async fn handle_conn(self: Arc<Self>, stream: TcpStream) -> Result<(), Box<dyn Error + Send + Sync>> {
        let cipher = self.cipher.clone();
        let peer = stream
            .peer_addr()
            .map(|a| a.to_string())
            .unwrap_or_else(|_| "?".into());
        let ws = tokio_tungstenite::accept_async(stream).await?;
        let (mut sink, mut source) = ws.split();

        // 1. 等待 Join（入组握手）
        let (pin, device) = match source.next().await {
            Some(Ok(WsMessage::Text(t))) => match serde_json::from_str::<Message>(&t)? {
                Message::Join { pin, device } => (pin, device),
                _ => return Err("first message must be join".into()),
            },
            _ => return Err("expected join message".into()),
        };
        tracing::debug!(target: "ws/server", "<- join [{peer}] device={} platform={} addr={:?}",
            device.name, device.platform, device.addr);

        if pin != *self.pin.as_ref() {
            tracing::warn!(target: "ws/server", "-> join_result{{ok=false}} [{peer}] reason=WRONG_PIN");
            let _ = sink
                .send(WsMessage::Text(serde_json::to_string(&Message::JoinResult {
                    ok: false,
                    reason: Some("WRONG_PIN".into()),
                    members: vec![],
                })?))
                .await;
            return Ok(());
        }

        // 2. 注册成员 + 回执
        let (tx, mut rx) = mpsc::channel::<WsMessage>(64);
        let conn_tx = tx.clone(); // 心跳回执 + 退出校验用（本连接的通道标识）
        // 同 id 重复入群：旧条目被覆盖（旧发送端 drop → 旧连接自然关闭），旧连接的清理不会误删新条目（见下方退出校验）
        self.members.write().await.insert(device.id.clone(), (device.clone(), tx));
        tracing::debug!(target: "ws/server", "-> join_result{{ok=true}} [{peer}] members={}", self.member_infos().await.len());
        let _ = sink
            .send(WsMessage::Text(serde_json::to_string(&Message::JoinResult {
                ok: true,
                reason: None,
                members: self.member_infos().await,
            })?))
            .await;
        self.broadcast_members().await;

        // 3. 读循环：转发成员消息；写任务：消费出站通道
        let members = self.members.clone();
        let owner_info = self.owner.clone();
        let event_tx = self.event_tx.clone();
        let device_id = device.id.clone();

        // 空闲看门狗：成员每 5s 心跳，超过 20s（≈3 次未达 + 余量）无任何入站消息即判定假死
        let last_activity = std::sync::Arc::new(std::sync::Mutex::new(tokio::time::Instant::now()));
        let (dead_tx, mut dead_rx) = mpsc::channel::<()>(1);
        {
            let last = last_activity.clone();
            let peer_w = peer.clone();
            tokio::spawn(async move {
                loop {
                    tokio::time::sleep(Duration::from_secs(5)).await;
                    let idle = last.lock().unwrap().elapsed();
                    if idle >= Duration::from_secs(20) {
                        tracing::debug!(target: "ws/server", "心跳看门狗：{peer_w} 空闲 {idle:?}，关闭连接");
                        dead_tx.send(()).await.ok();
                        return;
                    }
                }
            });
        }

        let writer = tokio::spawn(async move {
            while let Some(msg) = rx.recv().await {
                if sink.send(msg).await.is_err() {
                    break;
                }
            }
        });

        loop {
            let msg = tokio::select! {
                _ = dead_rx.recv() => break, // 看门狗超时
                m = source.next() => match m {
                    Some(v) => v,
                    None => break,
                },
            };
            *last_activity.lock().unwrap() = tokio::time::Instant::now();
            match msg {
                Ok(WsMessage::Text(t)) => {
                    let inner = serde_json::from_str::<Message>(&t)
                        .ok()
                        .and_then(|m| m.open(&cipher));
                    match inner {
                        Some(Message::Clipboard { from, payload }) => {
                            tracing::debug!(target: "ws/server", "<- clipboard [{peer}] from={} len={} hash={}",
                                from, payload.text.as_deref().map(str::len).unwrap_or(0), payload.hash);
                            // 通知本机 UI，并把原始密文转发给其他成员（同 PIN 同密钥）
                            event_tx.send(CoreEvent::Clipboard {
                                from: from.clone(),
                                payload: payload.clone(),
                            })
                            .ok();
                            let map = members.read().await;
                            for (id, (_, tx)) in map.iter() {
                                if id != &device_id {
                                    tx.try_send(WsMessage::Text(t.clone())).ok();
                                }
                            }
                        }
                        Some(Message::Ping { seq, .. }) => {
                            // 回执：群主视角的群状态快照（成员表，[0] 为群主）
                            tracing::debug!(target: "ws/server", "<- ping#{seq} [{peer}] -> pong#{seq}");
                            let members_now = members.read().await;
                            let list = {
                                let mut l = vec![owner_info.clone()];
                                l.extend(members_now.values().map(|(info, _)| info.clone()));
                                l
                            };
                            let pong = Message::Pong {
                                owner: owner_info.clone(),
                                members: list,
                                seq,
                                ts_ms: crate::protocol::now_ms(),
                            };
                            let _ = conn_tx.try_send(WsMessage::Text(serde_json::to_string(&pong).unwrap()));
                        }
                        _ => {}
                    }
                }
                Ok(WsMessage::Close(_)) => break,
                Ok(_) => {}
                Err(e) => {
                    tracing::debug!(target: "ws/server", "连接错误 [{peer}]: {e}");
                    break;
                }
            }
        }

        writer.abort();
        // 退出校验：仅当成员表里注册的仍是本连接的通道才移除
        // （同 id 重复入群后旧连接退出时，成员表里已是新连接，不能误删）
        {
            let mut map = members.write().await;
            let is_mine = map
                .get(&device_id)
                .map(|(_, t)| t.same_channel(&conn_tx))
                .unwrap_or(false);
            if is_mine {
                tracing::debug!(target: "ws/server", "连接关闭 [{peer}] 移除成员 {device_id}");
                map.remove(&device_id);
            } else {
                tracing::debug!(target: "ws/server", "连接关闭 [{peer}]（已被新连接取代，保留新成员表条目）");
            }
        }
        self.broadcast_members().await;
        Ok(())
    }

    async fn member_infos(&self) -> Vec<DeviceInfo> {
        let mut list = vec![self.owner.clone()];
        list.extend(self.members.read().await.values().map(|(info, _)| info.clone()));
        list
    }

    pub async fn members(&self) -> Vec<DeviceInfo> {
        self.member_infos().await
    }

    async fn broadcast_members(&self) {
        let members = self.member_infos().await;
        tracing::debug!(target: "ws/server", "-> members 广播 n={} ({})",
            members.len(), members.iter().map(|d| d.name.clone()).collect::<Vec<_>>().join(","));
        let text = serde_json::to_string(
            &Message::Members { members: members.clone() }.seal(&self.cipher),
        )
        .unwrap();
        {
            let map = self.members.read().await;
            for (_, (_, tx)) in map.iter() {
                tx.try_send(WsMessage::Text(text.clone())).ok();
            }
        }
        self.event_tx.send(CoreEvent::Members(members)).ok();
    }

    /// 广播本机剪贴板变化到所有成员。
    pub async fn broadcast_clipboard(&self, from: String, payload: ClipboardPayload) {
        tracing::debug!(target: "ws/server", "-> clipboard 广播 n={} len={}",
            self.members.read().await.len(), payload.text.as_deref().map(str::len).unwrap_or(0));
        let text = serde_json::to_string(
            &Message::Clipboard { from, payload }.seal(&self.cipher),
        )
        .unwrap();
        let map = self.members.read().await;
        for (_, (_, tx)) in map.iter() {
            tx.try_send(WsMessage::Text(text.clone())).ok();
        }
    }

    /// 剔除成员（群主自身不可被移出）。
    pub async fn kick(&self, device_id: &str) {
        if device_id == self.owner.id {
            return;
        }
        tracing::debug!(target: "ws/server", "-> kick {device_id}");
        {
            let mut map = self.members.write().await;
            if let Some((_, tx)) = map.remove(device_id) {
                let text = serde_json::to_string(
                    &Message::Kick { reason: Some("removed by owner".into()) }.seal(&self.cipher),
                )
                .unwrap();
                tx.try_send(WsMessage::Text(text)).ok();
            }
        }
        self.broadcast_members().await;
    }

    /// 关闭服务端（群解散）：通知全部成员后断开。
    pub async fn shutdown(&self) {
        self.dissolve_notify().await;
        self.stop().await;
    }

    async fn dissolve_notify(&self) {
        tracing::debug!(target: "ws/server", "-> 解散通知 kick reason=GROUP_DISSOLVED n={}", self.members.read().await.len());
        let map = self.members.read().await;
        let text = serde_json::to_string(
            &Message::Kick { reason: Some("GROUP_DISSOLVED".into()) }.seal(&self.cipher),
        )
        .unwrap();
        for (_, (_, tx)) in map.iter() {
            tx.try_send(WsMessage::Text(text.clone())).ok();
        }
    }

    /// 停止监听并断开全部成员（不发送任何通知）。
    async fn stop(&self) {
        self.members.write().await.clear();
        self.shutdown.try_send(WsMessage::Close(None)).ok();
    }

    /// 转让群主并退出：通知成员新群主后直接停止（不发解散通知）。
    pub async fn transfer(&self, new_owner: &DeviceInfo) {
        tracing::debug!(target: "ws/server", "-> owner_change new_owner={} 停止服务", new_owner.name);
        {
            let map = self.members.read().await;
            let text = serde_json::to_string(
                &Message::OwnerChange { new_owner: new_owner.clone() }.seal(&self.cipher),
            )
            .unwrap();
            for (_, (_, tx)) in map.iter() {
                tx.try_send(WsMessage::Text(text.clone())).ok();
            }
        }
        self.stop().await;
    }
}

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
        let (tx, mut rx) = broadcast::channel::<CoreEvent>(64);
        let (server_a, port_a) = GroupServer::start(0, "123456".into(), dev("A", None), tx.clone())
            .await
            .unwrap();

        // B：预留端口
        let standby_b = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
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
