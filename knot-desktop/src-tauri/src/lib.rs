//! KnotYup 桌面端：把 knot-core 桥接到 Tauri 命令与事件。

use knot_core::clipboard::{text_hash, ClipboardWatcher};
use knot_core::discovery::{self, GroupAd};
use knot_core::net::{CoreEvent, GroupClient, GroupServer};
use knot_core::protocol::{ClipboardKind, ClipboardPayload, DeviceInfo};
use std::sync::Arc;
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::{broadcast, mpsc, Mutex};

/// 剪贴板线程收到的指令
enum ClipCmd {
    /// 写入剪贴板（来自远端同步或用户点击历史条目）
    Set(ClipboardPayload),
}

#[derive(Clone, Copy, PartialEq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
enum Role {
    Owner,
    Member,
}

#[derive(Clone, serde::Serialize)]
struct HistoryItem {
    from: String,
    text: String,
    at_ms: u64,
}

struct Session {
    role: Role,
    server: Option<Arc<GroupServer>>,
    client: Option<GroupClient>,
    members: Vec<DeviceInfo>,
    /// Owner: 本机监听地址；Member: 组主地址
    addr: String,
    pin: String,
    /// 组主停止广播的句柄
    announce_stop: Option<mpsc::Sender<()>>,
}

#[derive(Default)]
struct AppState {
    device: Mutex<Option<DeviceInfo>>,
    session: Mutex<Option<Session>>,
    clip_tx: Mutex<Option<mpsc::Sender<ClipCmd>>>,
    core_tx: Mutex<Option<broadcast::Sender<CoreEvent>>>,
    history: Mutex<Vec<HistoryItem>>,
    discovered: Mutex<Vec<GroupAd>>,
    /// 最近一次同步的剪贴板指纹（防回环：忽略同指纹的远端回声）
    last_clip_hash: Mutex<String>,
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// 生成 6 位数字 PIN。
fn gen_pin() -> String {
    use rand::Rng;
    rand::thread_rng().gen_range(100_000..1_000_000).to_string()
}

fn local_ip() -> String {
    local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| "127.0.0.1".into())
}

/// 启动剪贴板监视线程（轮询 + 去重）。
/// 返回指令通道；本机内容变化通过 local_tx 抛给 async 侧。
fn spawn_clipboard_watcher(local_tx: mpsc::Sender<ClipboardPayload>) -> mpsc::Sender<ClipCmd> {
    let (tx, mut rx) = mpsc::channel::<ClipCmd>(32);
    std::thread::Builder::new()
        .name("clipboard-watcher".into())
        .spawn(move || {
            let mut watcher = match ClipboardWatcher::new() {
                Ok(w) => w,
                Err(e) => {
                    tracing::error!("clipboard init failed: {e}");
                    return;
                }
            };
            // tokio mpsc 的 blocking_recv 会阻塞，单独起消费线程桥接到 std channel
            let (cmd_tx, cmd_rx) = std::sync::mpsc::channel::<ClipCmd>();
            std::thread::spawn(move || {
                while let Some(cmd) = rx.blocking_recv() {
                    cmd_tx.send(cmd).ok();
                }
            });
            loop {
                while let Ok(cmd) = cmd_rx.try_recv() {
                    match cmd {
                        ClipCmd::Set(payload) => {
                            if let Err(e) = watcher.apply_remote(&payload) {
                                tracing::error!("apply clipboard: {e}");
                            }
                        }
                    }
                }
                if let Ok(Some(payload)) = watcher.poll_text() {
                    local_tx.blocking_send(payload).ok();
                }
                std::thread::sleep(Duration::from_millis(400));
            }
        })
        .expect("spawn clipboard watcher");
    tx
}

/// core 事件 → Tauri 事件 → 前端
async fn forward_core_events(
    app: AppHandle,
    state: Arc<AppState>,
    mut rx: broadcast::Receiver<CoreEvent>,
) {
    loop {
        match rx.recv().await {
            Ok(CoreEvent::Members(members)) => {
                if let Some(s) = state.session.lock().await.as_mut() {
                    s.members = members.clone();
                }
                let _ = app.emit("knot://members", &members);
            }
            Ok(CoreEvent::Clipboard { from, payload }) => {
                // 防回环：成员把同步内容当作本机复制再发回来时，指纹相同，直接忽略
                let mut guard = state.last_clip_hash.lock().await;
                if *guard == payload.hash {
                    continue;
                }
                *guard = payload.hash.clone();
                drop(guard);
                if let Some(tx) = state.clip_tx.lock().await.as_ref() {
                    let _ = tx.send(ClipCmd::Set(payload.clone())).await;
                }
                push_history(&state, &from, &payload).await;
                let _ = app.emit(
                    "knot://clipboard",
                    serde_json::json!({ "from": from, "text": payload.text, "hash": payload.hash }),
                );
            }
            Ok(CoreEvent::Kicked(reason)) => {
                *state.session.lock().await = None;
                let _ = app.emit("knot://kicked", reason);
            }
            Ok(CoreEvent::Error(e)) => {
                let _ = app.emit("knot://error", e);
            }
            Err(broadcast::error::RecvError::Lagged(_)) => continue,
            Err(broadcast::error::RecvError::Closed) => break,
        }
    }
}

async fn push_history(state: &Arc<AppState>, from: &str, payload: &ClipboardPayload) {
    if let Some(text) = &payload.text {
        let mut h = state.history.lock().await;
        h.insert(
            0,
            HistoryItem {
                from: from.to_string(),
                text: text.clone(),
                at_ms: now_ms(),
            },
        );
        h.truncate(50);
    }
}

type SharedState = Arc<AppState>;

#[tauri::command]
async fn get_state(state: State<'_, SharedState>) -> Result<serde_json::Value, String> {
    let dev = state.device.lock().await.clone();
    let s = state.session.lock().await;
    Ok(serde_json::json!({
        "device": dev,
        "session": s.as_ref().map(|s| serde_json::json!({
            "role": s.role,
            "addr": s.addr,
            "pin": s.pin,
            "members": s.members,
        })),
        "history": *state.history.lock().await,
        "discovered": *state.discovered.lock().await,
    }))
}

#[tauri::command]
async fn create_group(
    app: AppHandle,
    state: State<'_, SharedState>,
    pin: Option<String>,
    port: Option<u16>,
) -> Result<serde_json::Value, String> {
    if state.session.lock().await.is_some() {
        return Err("ALREADY_IN_GROUP".into());
    }
    let pin = pin.unwrap_or_else(gen_pin);
    let core_tx = state.core_tx.lock().await.clone().ok_or("core 未初始化")?;
    let device = state.device.lock().await.clone().unwrap();
    let (server, port) = GroupServer::start(port.unwrap_or(0), pin.clone(), device.clone(), core_tx)
        .await
        .map_err(|e| format!("启动服务失败: {e}"))?;

    let addr = format!("{}:{}", local_ip(), port);
    let _ = app.emit("knot://members", vec![device.clone()]);

    // 组主在局域网内广播本组（供其他设备发现；PIN 不随广播发布）
    let announce_stop = discovery::announce(GroupAd {
        id: device.id.clone(),
        name: device.name.clone(),
        addr: addr.clone(),
    })
    .await
    .ok();

    *state.session.lock().await = Some(Session {
        role: Role::Owner,
        server: Some(server),
        client: None,
        members: vec![device.clone()],
        addr: addr.clone(),
        pin: pin.clone(),
        announce_stop,
    });
    Ok(serde_json::json!({ "addr": addr, "pin": pin, "port": port }))
}

#[tauri::command]
async fn join_group(
    state: State<'_, SharedState>,
    addr: String,
    pin: String,
) -> Result<serde_json::Value, String> {
    if state.session.lock().await.is_some() {
        return Err("ALREADY_IN_GROUP".into());
    }
    let core_tx = state.core_tx.lock().await.clone().ok_or("CORE_INIT")?;
    let device = state.device.lock().await.clone().unwrap();
    let client = GroupClient::connect(&addr, &pin, device, core_tx).await?;

    *state.session.lock().await = Some(Session {
        role: Role::Member,
        server: None,
        client: Some(client),
        members: vec![],
        addr,
        pin,
        announce_stop: None,
    });
    Ok(serde_json::json!({ "ok": true }))
}

#[tauri::command]
async fn leave_group(state: State<'_, SharedState>) -> Result<(), String> {
    if let Some(s) = state.session.lock().await.take() {
        if let Some(server) = s.server {
            server.shutdown().await;
        }
        if let Some(stop) = s.announce_stop {
            stop.send(()).await.ok();
        }
        // client 句柄 drop 后写任务退出，连接随之关闭
    }
    Ok(())
}

#[tauri::command]
async fn kick_device(state: State<'_, SharedState>, device_id: String) -> Result<(), String> {
    let s = state.session.lock().await;
    match &*s {
        Some(Session { server: Some(server), .. }) => {
            server.kick(&device_id).await;
            Ok(())
        }
        _ => Err("OWNER_ONLY".into()),
    }
}

#[tauri::command]
async fn copy_history_item(state: State<'_, SharedState>, index: usize) -> Result<(), String> {
    let item = state.history.lock().await.get(index).cloned();
    if let Some(item) = item {
        let tx = state.clip_tx.lock().await.clone().ok_or("core 未初始化")?;
        tx.send(ClipCmd::Set(ClipboardPayload {
            kind: ClipboardKind::Text,
            text: Some(item.text.clone()),
            hash: text_hash(&item.text),
        }))
        .await
        .map_err(|_| "剪贴板线程已退出")?;
    }
    Ok(())
}

pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    tauri::Builder::default()
        .setup(|app| {
            let handle = app.handle().clone();
            let shared: SharedState = Arc::new(AppState::default());

            // 本机设备身份
            let device = DeviceInfo {
                id: knot_core::protocol::device_id(),
                name: format!("{} 的 {}", whoami::devicename(), match std::env::consts::OS { "macos" => "Mac", "windows" => "PC", other => other }),
                platform: std::env::consts::OS.to_string(),
            };

            // 剪贴板监视线程 + 本机变化通道
            let (local_tx, mut local_rx) = mpsc::channel::<ClipboardPayload>(64);
            let clip_tx = spawn_clipboard_watcher(local_tx);

            // core 事件总线
            let (core_tx, core_rx) = broadcast::channel::<CoreEvent>(128);

            // 局域网发现：常驻监听广播
            let (disc_tx, mut disc_rx) = broadcast::channel::<Vec<GroupAd>>(16);
            let shared_disc = shared.clone();
            let handle_disc = handle.clone();
            tauri::async_runtime::spawn(async move {
                loop {
                    // 端口被占用（例如本机开了两个实例）时稍后重试
                    if discovery::listen(disc_tx.clone()).await.is_err() {
                        tokio::time::sleep(std::time::Duration::from_secs(3)).await;
                    }
                }
            });
            tauri::async_runtime::spawn(async move {
                loop {
                    match disc_rx.recv().await {
                        Ok(list) => {
                            *shared_disc.discovered.lock().await = list.clone();
                            let _ = handle_disc.emit("knot://discovered", &list);
                        }
                        Err(broadcast::error::RecvError::Lagged(_)) => continue,
                        Err(broadcast::error::RecvError::Closed) => break,
                    }
                }
            });

            tauri::async_runtime::block_on(async {
                *shared.device.lock().await = Some(device.clone());
                *shared.clip_tx.lock().await = Some(clip_tx);
                *shared.core_tx.lock().await = Some(core_tx);
            });
            tauri::async_runtime::spawn(forward_core_events(handle.clone(), shared.clone(), core_rx));

            // 本机剪贴板变化 → 广播给组 + 记历史
            let shared_clip = shared.clone();
            let handle_clip = handle.clone();
            tauri::async_runtime::spawn(async move {
                while let Some(payload) = local_rx.recv().await {
                    let from = shared_clip
                        .device
                        .lock()
                        .await
                        .as_ref()
                        .map(|d| d.id.clone())
                        .unwrap_or_default();
                    // 记录本机指纹：成员回声同指纹时会被忽略
                    *shared_clip.last_clip_hash.lock().await = payload.hash.clone();
                    {
                        let s = shared_clip.session.lock().await;
                        if let Some(session) = &*s {
                            match session.role {
                                Role::Owner => {
                                    if let Some(server) = &session.server {
                                        server
                                            .broadcast_clipboard(from.clone(), payload.clone())
                                            .await;
                                    }
                                }
                                Role::Member => {
                                    if let Some(client) = &session.client {
                                        let _ = client.send_clipboard(payload.clone()).await;
                                    }
                                }
                            }
                        }
                    }
                    push_history(&shared_clip, &from, &payload).await;
                    let _ = handle_clip.emit(
                        "knot://clipboard",
                        serde_json::json!({ "from": from, "text": payload.text, "hash": payload.hash }),
                    );
                }
            });

            app.manage(shared);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_state,
            create_group,
            join_group,
            leave_group,
            kick_device,
            copy_history_item
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
