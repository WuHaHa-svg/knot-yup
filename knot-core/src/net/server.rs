//! 群主（WebSocket 服务端）v2：连接管理、心跳回执（members-only pong）、35s 看门狗、
//! 成员广播、踢人、解散、转让三方握手（offer→ready→new_service，5s 超时兜底）。

use futures_util::{SinkExt, StreamExt};
use std::collections::{BTreeMap, HashMap};
use std::error::Error;
use std::sync::Arc;
use std::time::Duration;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot, RwLock};
use tokio_tungstenite::tungstenite::Message as WsMessage;

use crate::crypto::Cipher;
use crate::net::{CoreEvent, MemberMap, Tx};
use crate::protocol::{self, ClipboardPayload, DeviceInfo, Message, SharedFile, TransferTask, TaskStatus};

/// 转让回执等待表：device_id → 一次性通道（transfer() 注册，读循环收到 TransferReady 唤醒）
type TransferWaiters = Arc<std::sync::Mutex<HashMap<String, oneshot::Sender<String>>>>;

/// 组主（WebSocket 服务端）。
pub struct GroupServer {
    /// 群主自身设备信息（role = owner，file_list 随本机文件增删而变）
    owner: Arc<RwLock<DeviceInfo>>,
    members: MemberMap,
    pin: Arc<String>,
    cipher: Arc<Cipher>,
    event_tx: tokio::sync::broadcast::Sender<CoreEvent>,
    shutdown: Tx,
    waiters: TransferWaiters,
    /// 传输任务数组（群主权威；终态锁定，保留到群解散）
    tasks: Arc<RwLock<Vec<TransferTask>>>,
}

impl GroupServer {
    /// 启动组主。owner 为群主设备信息（自动置 role=owner）。返回 (句柄, 实际监听端口)。
    pub async fn start(
        port: u16,
        pin: String,
        mut owner: DeviceInfo,
        event_tx: tokio::sync::broadcast::Sender<CoreEvent>,
    ) -> std::io::Result<(Arc<Self>, u16)> {
        let listener = TcpListener::bind(("0.0.0.0", port)).await?;
        let port = listener.local_addr()?.port();
        tracing::debug!(target: "ws/server", "群主监听 0.0.0.0:{port} pin={pin}");
        let members: MemberMap = Arc::new(RwLock::new(BTreeMap::new()));
        owner.role = Some("owner".into());
        // shutdown 通道：发送端保留在句柄中，接收端挂到 accept 循环上。
        let (shutdown, mut shutdown_rx) = mpsc::channel::<WsMessage>(1);

        let server = Arc::new(Self {
            owner: Arc::new(RwLock::new(owner)),
            members: members.clone(),
            pin: Arc::new(pin.clone()),
            cipher: Arc::new(Cipher::from_pin(&pin)),
            event_tx,
            shutdown,
            waiters: Arc::new(std::sync::Mutex::new(HashMap::new())),
            tasks: Arc::new(RwLock::new(Vec::new())),
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
        let (pin, mut device) = match source.next().await {
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

        // 2. 注册成员 + 回执（成员身份服务端权威赋值）
        device.role = Some("member".into());
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
        // fileList 已内嵌在成员表里，JoinResult.members 自带全群文件列表，无需单独下发快照。
        // 传输任务数组不内嵌成员表，入群后补发一条全量快照（明文）
        if let Ok(text) = serde_json::to_string(&Message::FileTasks { tasks: self.tasks.read().await.clone() }) {
            let _ = sink.send(WsMessage::Text(text)).await;
        }
        self.broadcast_members().await;

        // 3. 读循环：转发成员消息；写任务：消费出站通道
        let members = self.members.clone();
        let owner_info = self.owner.clone();
        let event_tx = self.event_tx.clone();
        let device_id = device.id.clone();
        let waiters = self.waiters.clone();
        let tasks = self.tasks.clone();
        let tasks_server = self.clone(); // 处理任务消息后广播全量快照

        // 空闲看门狗：成员每 30s 心跳，超过 35s 无任何入站消息即判定假死
        let last_activity = std::sync::Arc::new(std::sync::Mutex::new(tokio::time::Instant::now()));
        let (dead_tx, mut dead_rx) = mpsc::channel::<()>(1);
        {
            let last = last_activity.clone();
            let peer_w = peer.clone();
            tokio::spawn(async move {
                loop {
                    tokio::time::sleep(Duration::from_secs(5)).await;
                    let idle = last.lock().unwrap().elapsed();
                    if idle >= protocol::watchdog_timeout() {
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
                        Some(Message::Clipboard { from, from_name, payload }) => {
                            tracing::debug!(target: "ws/server", "<- clipboard [{peer}] from={} len={} hash={}",
                                from, payload.text.as_deref().map(str::len).unwrap_or(0), payload.hash);
                            // 通知本机 UI，并把原始密文转发给其他成员（同 PIN 同密钥）
                            event_tx.send(CoreEvent::Clipboard {
                                from: from.clone(),
                                from_name,
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
                        Some(Message::FileAdd { from, from_name, file }) => {
                            tracing::debug!(target: "ws/server", "<- file_add [{peer}] from={from} id={}", file.file_id);
                            // 成员表是文件列表唯一真相源：更新发送者 DeviceInfo.file_list
                            {
                                let mut map = members.write().await;
                                if let Some((info, _)) = map.get_mut(&from) {
                                    if !info.file_list.iter().any(|x| x.file_id == file.file_id) {
                                        info.file_list.push(file.clone());
                                    }
                                }
                            }
                            event_tx.send(CoreEvent::FileAdd { from: from.clone(), from_name, file }).ok();
                            let map = members.read().await;
                            for (id, (_, tx)) in map.iter() {
                                if id != &device_id {
                                    tx.try_send(WsMessage::Text(t.clone())).ok();
                                }
                            }
                        }
                        Some(Message::FileRemove { from, from_name, file_ids }) => {
                            tracing::debug!(target: "ws/server", "<- file_remove [{peer}] from={from} n={}", file_ids.len());
                            {
                                let mut map = members.write().await;
                                if let Some((info, _)) = map.get_mut(&from) {
                                    info.file_list.retain(|f| !file_ids.contains(&f.file_id));
                                }
                            }
                            event_tx.send(CoreEvent::FileRemove { from: from.clone(), from_name, file_ids }).ok();
                            let map = members.read().await;
                            for (id, (_, tx)) in map.iter() {
                                if id != &device_id {
                                    tx.try_send(WsMessage::Text(t.clone())).ok();
                                }
                            }
                        }
                        Some(Message::FileTaskAdd { task }) => {
                            tracing::debug!(target: "ws/server", "<- file_task_add [{peer}] id={} seeder={} downloader={}",
                                task.task_id, task.seeder_id, task.downloader_id);
                            let mut list = tasks.write().await;
                            if !list.iter().any(|t| t.task_id == task.task_id) {
                                list.push(task);
                            }
                            drop(list);
                            tasks_server.broadcast_tasks().await;
                        }
                        Some(Message::FileTaskQueued { task_id }) => {
                            tasks_server.update_task(&task_id, |t| {
                                if t.status == TaskStatus::Queuing { t.updated_at = protocol::now_ms(); }
                            }).await;
                            tasks_server.broadcast_tasks().await;
                        }
                        Some(Message::FileTaskStarted { task_id }) => {
                            tasks_server.update_task(&task_id, |t| {
                                if t.status == TaskStatus::Queuing {
                                    t.status = TaskStatus::Transferring;
                                    t.updated_at = protocol::now_ms();
                                }
                            }).await;
                            tasks_server.broadcast_tasks().await;
                        }
                        Some(Message::FileTaskProgress { task_id, progress, transferred }) => {
                            tasks_server.update_task(&task_id, |t| {
                                if t.status == TaskStatus::Transferring && progress >= t.progress {
                                    t.progress = progress.min(100);
                                    t.transferred = transferred;
                                    t.updated_at = protocol::now_ms();
                                }
                            }).await;
                            tasks_server.broadcast_tasks().await;
                        }
                        Some(Message::FileTaskFinish { task_id }) => {
                            tasks_server.update_task(&task_id, |t| {
                                if t.status == TaskStatus::Transferring {
                                    t.status = TaskStatus::Done;
                                    t.progress = 100;
                                    t.updated_at = protocol::now_ms();
                                }
                            }).await;
                            tasks_server.broadcast_tasks().await;
                        }
                        Some(Message::FileTaskFailed { task_id, error, reporter }) => {
                            tracing::debug!(target: "ws/server", "<- file_task_failed [{peer}] id={} reporter={} error={}",
                                task_id, reporter, error);
                            // 终态锁定：已 done/failed 的任务忽略后到终态（防失败被延迟 done 翻回）
                            tasks_server.update_task(&task_id, |t| {
                                if t.status != TaskStatus::Done && t.status != TaskStatus::Failed {
                                    t.status = TaskStatus::Failed;
                                    t.error = error;
                                    t.updated_at = protocol::now_ms();
                                }
                            }).await;
                            tasks_server.broadcast_tasks().await;
                        }
                        Some(Message::Ping { seq, .. }) => {
                            // 回执：成员表快照即状态同步（群主身份看 role 字段）
                            tracing::debug!(target: "ws/server", "<- ping#{seq} [{peer}] -> pong#{seq}");
                            let pong = Message::Pong {
                                members: {
                                    let members_now = members.read().await;
                                    let mut l = vec![owner_info.read().await.clone()];
                                    l.extend(members_now.values().map(|(info, _)| info.clone()));
                                    l
                                },
                                seq,
                                ts_ms: protocol::now_ms(),
                            };
                            let _ = conn_tx.try_send(WsMessage::Text(serde_json::to_string(&pong).unwrap()));
                        }
                        Some(Message::TransferReady { addr }) => {
                            // 转让回执：有等待者则唤醒 transfer 流程；无（已超时）则回 transfer_error
                            let waiter = waiters.lock().unwrap().remove(&device_id);
                            match waiter {
                                Some(w) => {
                                    tracing::debug!(target: "ws/server", "<- transfer_ready [{peer}] addr={addr}");
                                    let _ = w.send(addr);
                                }
                                None => {
                                    tracing::debug!(target: "ws/server", "<- transfer_ready（迟到）[{peer}] -> transfer_error");
                                    let text = serde_json::to_string(
                                        &Message::TransferError.seal(&cipher),
                                    )
                                    .unwrap();
                                    let _ = conn_tx.try_send(WsMessage::Text(text));
                                }
                            }
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
                // fileList 内嵌在成员表里：移除成员即移除其文件夹，无需额外清理
                map.remove(&device_id);
            } else {
                tracing::debug!(target: "ws/server", "连接关闭 [{peer}]（已被新连接取代，保留新成员表条目）");
            }
        }
        self.broadcast_members().await;
        Ok(())
    }

    async fn member_infos(&self) -> Vec<DeviceInfo> {
        let mut list = vec![self.owner.read().await.clone()];
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
    pub async fn broadcast_clipboard(&self, from: String, from_name: String, payload: ClipboardPayload) {
        tracing::debug!(target: "ws/server", "-> clipboard 广播 n={} len={}",
            self.members.read().await.len(), payload.text.as_deref().map(str::len).unwrap_or(0));
        let text = serde_json::to_string(
            &Message::Clipboard { from, from_name, payload }.seal(&self.cipher),
        )
        .unwrap();
        let map = self.members.read().await;
        for (_, (_, tx)) in map.iter() {
            tx.try_send(WsMessage::Text(text.clone())).ok();
        }
    }

    /// 群主本机新增文件：更新 owner.file_list + 广播全员。
    pub async fn broadcast_file_add(&self, from: String, from_name: String, file: SharedFile) {
        tracing::debug!(target: "ws/server", "-> file_add 广播 from={from} id={}", file.file_id);
        {
            let mut owner = self.owner.write().await;
            if owner.id == from && !owner.file_list.iter().any(|x| x.file_id == file.file_id) {
                owner.file_list.push(file.clone());
            }
        }
        let text = serde_json::to_string(
            &Message::FileAdd { from, from_name, file }.seal(&self.cipher),
        )
        .unwrap();
        let map = self.members.read().await;
        for (_, (_, tx)) in map.iter() {
            tx.try_send(WsMessage::Text(text.clone())).ok();
        }
    }

    /// 群主本机删除文件：更新 owner.file_list + 广播全员。
    pub async fn broadcast_file_remove(&self, from: String, from_name: String, file_ids: Vec<String>) {
        tracing::debug!(target: "ws/server", "-> file_remove 广播 from={from} n={}", file_ids.len());
        {
            let mut owner = self.owner.write().await;
            if owner.id == from {
                owner.file_list.retain(|f| !file_ids.contains(&f.file_id));
            }
        }
        let text = serde_json::to_string(
            &Message::FileRemove { from: from.clone(), from_name, file_ids: file_ids.clone() }.seal(&self.cipher),
        )
        .unwrap();
        let map = self.members.read().await;
        for (_, (_, tx)) in map.iter() {
            tx.try_send(WsMessage::Text(text.clone())).ok();
        }
    }

    /// 群主本机任务事件（群主自己下载/供种时无 ws 自连接，走这里）。
    pub async fn local_task_event(&self, ev: protocol::TaskEvent) {
        use protocol::TaskEvent::*;
        match ev {
            Queued { task_id } => {
                self.update_task(&task_id, |t| { t.updated_at = protocol::now_ms(); }).await;
            }
            Started { task_id } => {
                self.update_task(&task_id, |t| {
                    if t.status == TaskStatus::Queuing {
                        t.status = TaskStatus::Transferring;
                        t.updated_at = protocol::now_ms();
                    }
                }).await;
            }
            Progress { task_id, transferred, total } => {
                self.update_task(&task_id, |t| {
                    if t.status == TaskStatus::Transferring {
                        let p = if total == 0 { 0 } else { (transferred * 100 / total).min(100) as u32 };
                        if p >= t.progress {
                            t.progress = p; t.transferred = transferred;
                            t.updated_at = protocol::now_ms();
                        }
                    }
                }).await;
            }
            Done { task_id } => {
                self.update_task(&task_id, |t| {
                    if t.status == TaskStatus::Transferring {
                        t.status = TaskStatus::Done; t.progress = 100;
                        t.updated_at = protocol::now_ms();
                    }
                }).await;
            }
            Failed { task_id, error } => {
                self.update_task(&task_id, |t| {
                    if t.status != TaskStatus::Done && t.status != TaskStatus::Failed {
                        t.status = TaskStatus::Failed; t.error = error;
                        t.updated_at = protocol::now_ms();
                    }
                }).await;
            }
        }
        self.broadcast_tasks().await;
    }

    /// 群主本机登记新任务（下载方为群主自己时）。
    pub async fn local_task_add(&self, task: TransferTask) {
        let mut list = self.tasks.write().await;
        if !list.iter().any(|t| t.task_id == task.task_id) { list.push(task); }
        drop(list);
        self.broadcast_tasks().await;
    }

    /// 按 task_id 原地修改任务（不存在则忽略）。
    pub(crate) async fn update_task<F: FnOnce(&mut TransferTask)>(&self, task_id: &str, f: F) {
        let mut list = self.tasks.write().await;
        if let Some(t) = list.iter_mut().find(|t| t.task_id == task_id) {
            f(t);
        }
    }

    /// 广播全量任务数组给所有成员 + 通知本机 UI。
    pub async fn broadcast_tasks(&self) {
        let list = self.tasks.read().await.clone();
        let text = serde_json::to_string(&Message::FileTasks { tasks: list.clone() }).unwrap();
        let map = self.members.read().await;
        for (_, (_, tx)) in map.iter() {
            tx.try_send(WsMessage::Text(text.clone())).ok();
        }
        drop(map);
        self.event_tx.send(CoreEvent::FileTasks(list)).ok();
    }

    /// 群主本机最近一次任务数组快照（UI 首次拉取用）。
    pub async fn task_snapshot(&self) -> Vec<TransferTask> {
        self.tasks.read().await.clone()
    }

    /// 剔除成员（群主自身不可被移出）。
    pub async fn kick(&self, device_id: &str) {
        if device_id == self.owner.read().await.id {
            return;
        }
        tracing::debug!(target: "ws/server", "-> kick {device_id}");
        {
            let mut map = self.members.write().await;
            if let Some((_, tx)) = map.remove(device_id) {
                let text = serde_json::to_string(
                    &Message::Kick { reason: Some("KICKED".into()) }.seal(&self.cipher),
                )
                .unwrap();
                tx.try_send(WsMessage::Text(text)).ok();
            }
        }
        self.broadcast_members().await;
    }

    /// 解散（v2）：广播 dissolve 后停止。成员收到后 0.8s 自行退出，不回发。
    pub async fn shutdown(&self) {
        tracing::debug!(target: "ws/server", "-> dissolve 广播 n={}", self.members.read().await.len());
        let text = serde_json::to_string(&Message::Dissolve.seal(&self.cipher)).unwrap();
        {
            let map = self.members.read().await;
            for (_, (_, tx)) in map.iter() {
                tx.try_send(WsMessage::Text(text.clone())).ok();
            }
        }
        self.stop().await;
    }

    /// 停止监听并断开全部成员（不发送任何通知）。
    async fn stop(&self) {
        self.members.write().await.clear();
        self.shutdown.try_send(WsMessage::Close(None)).ok();
    }

    /// 实时成员数（发现应答用）。
    pub async fn member_count(&self) -> u32 {
        1 + self.members.read().await.len() as u32
    }

    /// 转让（v2 三方握手）：发 offer → 等 ready（5s 超时）→ 广播 new_service → 停止。
    /// 立即返回（不阻塞调用方）；结果经事件抛出：成功=NewService 广播 + 停服，失败=TransferFailed（原群继续）。
    pub async fn transfer(&self, target_id: &str) {
        let target_id = target_id.to_string();
        let target = {
            let map = self.members.read().await;
            map.get(&target_id).map(|(info, tx)| (info.clone(), tx.clone()))
        };
        let Some((mut target, tx)) = target else {
            tracing::warn!(target: "ws/server", "transfer: 目标 {target_id} 不在成员表，转让失败");
            self.event_tx.send(CoreEvent::TransferFailed).ok();
            return;
        };
        target.role = Some("owner".into()); // 新群主身份随 new_service 下发

        // 1. 发 offer
        let offer = serde_json::to_string(
            &Message::TransferOffer { pin: self.pin.as_ref().clone() }.seal(&self.cipher),
        )
        .unwrap();
        if tx.try_send(WsMessage::Text(offer)).is_err() {
            tracing::warn!(target: "ws/server", "transfer: offer 发送失败（连接写通道已关闭），转让失败");
            self.event_tx.send(CoreEvent::TransferFailed).ok();
            return;
        }
        tracing::debug!(target: "ws/server", "-> transfer_offer -> {}", target.name);

        // 2. 注册等待者 → 5s 超时窗口
        let (ready_tx, ready_rx) = oneshot::channel::<String>();
        self.waiters.lock().unwrap().insert(target_id.to_string(), ready_tx);
        let pin = self.pin.as_ref().clone();
        let cipher = self.cipher.clone();
        let event_tx = self.event_tx.clone();
        let members = self.members.clone();
        let shutdown = self.shutdown.clone();
        let waiters = self.waiters.clone();
        tokio::spawn(async move {
            match tokio::time::timeout(protocol::transfer_timeout(), ready_rx).await {
                // 3a. ready：广播 new_service（全员迁移）后停服
                Ok(Ok(addr)) => {
                    tracing::debug!(target: "ws/server", "转让就绪 -> new_service 广播 addr={addr}");
                    let ns = serde_json::to_string(
                        &Message::NewService { addr, pin, owner: target }.seal(&cipher),
                    )
                    .unwrap();
                    {
                        let map = members.read().await;
                        for (_, (_, tx)) in map.iter() {
                            tx.try_send(WsMessage::Text(ns.clone())).ok();
                        }
                    }
                    // 给消息一点出站时间再停（drop 发送端即关闭连接）
                    tokio::time::sleep(Duration::from_millis(300)).await;
                    members.write().await.clear();
                    shutdown.try_send(WsMessage::Close(None)).ok();
                    // 通知会话层：旧群主清会话回首页
                    event_tx.send(CoreEvent::TransferCompleted).ok();
                }
                // 3b. 超时/通道关闭：转让失败，原群继续
                _ => {
                    waiters.lock().unwrap().remove(&target_id);
                    tracing::warn!(target: "ws/server", "transfer: {} 未在 5s 内 ready，转让失败", target_id);
                    event_tx.send(CoreEvent::TransferFailed).ok();
                }
            }
        });
    }
}
