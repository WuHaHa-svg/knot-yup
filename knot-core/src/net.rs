//! 组网络层：组主 WebSocket 服务端 + 成员客户端。
//! M0 拓扑：星型。组主负责成员管理与广播；剪贴板消息经组主转发到其他成员。

use futures_util::{SinkExt, StreamExt};
use std::collections::HashMap;
use std::error::Error;
use std::sync::Arc;
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

        if pin != *self.pin.as_ref() {
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
        self.members.write().await.insert(device.id.clone(), (device.clone(), tx));
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
        let event_tx = self.event_tx.clone();
        let device_id = device.id.clone();

        let writer = tokio::spawn(async move {
            while let Some(msg) = rx.recv().await {
                if sink.send(msg).await.is_err() {
                    break;
                }
            }
        });

        while let Some(msg) = source.next().await {
            match msg {
                Ok(WsMessage::Text(t)) => {
                    let inner = serde_json::from_str::<Message>(&t)
                        .ok()
                        .and_then(|m| m.open(&cipher));
                    match inner {
                        Some(Message::Clipboard { from, payload }) => {
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
                        Some(Message::Ping) => {
                            let _ = Message::Pong;
                        }
                        _ => {}
                    }
                }
                Ok(WsMessage::Close(_)) => break,
                Ok(_) => {}
                Err(_) => break,
            }
        }

        writer.abort();
        members.write().await.remove(&device_id);
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
        {
            let map = self.members.read().await;
            let text = serde_json::to_string(
                &Message::Kick { reason: Some("GROUP_DISSOLVED".into()) }.seal(&self.cipher),
            )
            .unwrap();
            for (_, (_, tx)) in map.iter() {
                tx.try_send(WsMessage::Text(text.clone())).ok();
            }
        }
        self.members.write().await.clear();
        self.shutdown.try_send(WsMessage::Close(None)).ok();
    }
}

/// 成员（客户端）句柄。
pub struct GroupClient {
    tx: Tx,
    local_id: String,
    cipher: Arc<Cipher>,
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
        let ws = tokio_tungstenite::connect_async(url)
            .await
            .map_err(|e| format!("连接失败: {e}"))?;
        let (mut sink, mut source) = ws.0.split();

        sink.send(WsMessage::Text(
            serde_json::to_string(&Message::Join { pin: pin.to_string(), device: device.clone() }).unwrap(),
        ))
        .await
        .map_err(|e| format!("发送入组请求失败: {e}"))?;

        // 等待入组结果
        match source.next().await {
            Some(Ok(WsMessage::Text(t))) => match serde_json::from_str::<Message>(&t) {
                Ok(Message::JoinResult { ok: true, members, .. }) => {
                    event_tx.send(CoreEvent::Members(members)).ok();
                }
                Ok(Message::JoinResult { ok: false, reason, .. }) => {
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

        tokio::spawn(async move {
            let writer = tokio::spawn(async move {
                while let Some(msg) = rx.recv().await {
                    if sink.send(msg).await.is_err() {
                        break;
                    }
                }
            });
            while let Some(msg) = source.next().await {
                match msg {
                    Ok(WsMessage::Text(t)) => {
                        let inner = serde_json::from_str::<Message>(&t)
                            .ok()
                            .and_then(|m| m.open(&loop_cipher));
                        match inner {
                            Some(Message::Members { members }) => {
                                event_tx.send(CoreEvent::Members(members)).ok();
                            }
                            Some(Message::Clipboard { from, payload }) => {
                                event_tx.send(CoreEvent::Clipboard { from, payload }).ok();
                            }
                            Some(Message::Kick { reason }) => {
                                event_tx.send(CoreEvent::Kicked(reason.unwrap_or_default())).ok();
                                break;
                            }
                            _ => {}
                        }
                    }
                    Ok(WsMessage::Close(_)) => break,
                    Ok(_) => {}
                    Err(e) => {
                        event_tx.send(CoreEvent::Error(format!("connection closed: {e}"))).ok();
                        break;
                    }
                }
            }
            writer.abort();
        });

        Ok(Self { tx, local_id, cipher })
    }

    pub fn local_id(&self) -> &str {
        &self.local_id
    }

    /// 发送本机剪贴板变化到组主（加密信封）。
    pub async fn send_clipboard(&self, payload: ClipboardPayload) -> Result<(), String> {
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
