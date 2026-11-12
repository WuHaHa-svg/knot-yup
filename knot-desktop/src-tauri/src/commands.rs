//! Tauri 命令层：前端可调用的全部命令。

use crate::dlog;
use crate::state::{gen_pin, has_lan, local_ip, ClipCmd, Role, Session, SharedState};
use knot_core::clipboard::text_hash;
use knot_core::discovery::{self, GroupAd};
use knot_core::net::{GroupClient, GroupServer};
use knot_core::protocol::{ClipboardKind, ClipboardPayload};
use tauri::{AppHandle, Emitter, State};

#[tauri::command]
pub(crate) async fn get_state(state: State<'_, SharedState>) -> Result<serde_json::Value, String> {
    let dev = state.device.lock().await.clone();
    let s = state.session.lock().await;
    Ok(serde_json::json!({
        "device": dev,
        "local_ip": local_ip(),
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
pub(crate) async fn create_group(
    app: AppHandle,
    state: State<'_, SharedState>,
    pin: Option<String>,
    port: Option<u16>,
) -> Result<serde_json::Value, String> {
    if state.session.lock().await.is_some() {
        return Err("ALREADY_IN_GROUP".into());
    }
    if !has_lan() {
        return Err("NO_LAN".into());
    }
    let pin = pin.unwrap_or_else(gen_pin);
    let core_tx = state.core_tx.lock().await.clone().ok_or("core 未初始化")?;
    let device = state.device.lock().await.clone().unwrap();
    dlog("act/create", format!("创建群 port={} pin={}", port.unwrap_or(0), pin));
    let (server, port) = GroupServer::start(port.unwrap_or(0), pin.clone(), device.clone(), core_tx)
        .await
        .map_err(|e| {
            dlog("err/create", format!("{e}"));
            format!("启动服务失败: {e}")
        })?;

    let addr = format!("{}:{}", local_ip(), port);
    dlog("act/create", format!("群已建立 ws://{addr} pin={pin}"));
    let _ = app.emit("knot://members", vec![device.clone()]);
    // 建群即 1 名成员（成员数原子量此后由 Members 事件驱动更新）
    state.member_count.store(1, std::sync::atomic::Ordering::Relaxed);

    // v2：群主被动应答发现请求（不再周期广播）；成员数读 AppState 原子计数（bridge 实时更新）
    let count = state.inner().member_count.clone();
    let respond_stop = discovery::respond(
        GroupAd { id: device.id.clone(), name: device.name.clone(), addr: addr.clone(), members: 1 },
        move || count.load(std::sync::atomic::Ordering::Relaxed),
    )
    .await
    .ok();

    *state.session.lock().await = Some(Session {
        role: Role::Owner,
        server: Some(server),
        client: None,
        members: vec![device.clone()],
        addr: addr.clone(),
        pin: pin.clone(),
        announce_stop: respond_stop,
        standby: None,
        standby_server: None,
        standby_addr: None,
    });
    Ok(serde_json::json!({ "addr": addr, "pin": pin, "port": port }))
}

#[tauri::command]
pub(crate) async fn join_group(
    state: State<'_, SharedState>,
    addr: String,
    pin: String,
) -> Result<serde_json::Value, String> {
    if state.session.lock().await.is_some() {
        return Err("ALREADY_IN_GROUP".into());
    }
    let core_tx = state.core_tx.lock().await.clone().ok_or("CORE_INIT")?;
    let mut device = state.device.lock().await.clone().unwrap();
    dlog("act/join", format!("加入群 {addr} pin={pin}"));
    // 预留端口：群主意外离开时本机可能需要承接为群主
    let standby = tokio::net::TcpListener::bind(("0.0.0.0", 0)).await.map_err(|e| format!("{e}"))?;
    if let Ok(p) = standby.local_addr() {
        device.addr = Some(format!("{}:{}", local_ip(), p.port()));
        dlog("act/join", format!("预留承接端口 {}", p.port()));
    }
    let client = GroupClient::connect(&addr, &pin, device.clone(), core_tx).await.inspect_err(|e| {
        dlog("err/join", format!("{e}"));
    })?;

    *state.session.lock().await = Some(Session {
        role: Role::Member,
        server: None,
        client: Some(client),
        members: vec![],
        addr,
        pin,
        announce_stop: None,
        standby: Some(standby),
        standby_server: None,
        standby_addr: None,
    });
    Ok(serde_json::json!({ "ok": true }))
}

#[tauri::command]
pub(crate) async fn leave_group(
    state: State<'_, SharedState>,
    transfer_to: Option<String>,
) -> Result<(), String> {
    // 注意：必须先把 take() 的结果落到局部变量再 if let——若直接写在 if let 的 scrutinee 里，
    // MutexGuard 临时量会存活到块结束，转让分支内的二次加锁将自死锁（命令永久挂起）。
    let taken = state.session.lock().await.take();
    if let Some(s) = taken {
        dlog("act/leave", format!("role={:?} transfer_to={:?}", s.role, transfer_to));
        if let Some(target) = transfer_to {
            if s.members.iter().any(|m| m.id == target) {
                // v2 三方握手：offer→ready→new_service；失败经 TransferFailed 事件提示且原群保留，
                // 因此会话不能先清——先恢复会话，转让失败路径由用户再操作
                let server = s.server.clone();
                // 转让进行中：成员陆续迁移离开本服务器，差量提示静默直到成功/失败终态
                state.transferring.store(true, std::sync::atomic::Ordering::Relaxed);
                *state.session.lock().await = Some(s);
                if let Some(server) = server {
                    server.transfer(&target).await;
                }
                return Ok(());
            }
        }
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
pub(crate) async fn kick_device(state: State<'_, SharedState>, device_id: String) -> Result<(), String> {
    let s = state.session.lock().await;
    match &*s {
        Some(Session { server: Some(server), .. }) => {
            dlog("act/kick", &device_id);
            server.kick(&device_id).await;
            Ok(())
        }
        _ => Err("OWNER_ONLY".into()),
    }
}

#[tauri::command]
pub(crate) async fn copy_history_item(state: State<'_, SharedState>, index: usize) -> Result<(), String> {
    let item = state.history.lock().await.get(index).cloned();
    if let Some(item) = item {
        let tx = state.clip_tx.lock().await.clone().ok_or("core 未初始化")?;
        tx.send(ClipCmd::Set(ClipboardPayload {
            kind: ClipboardKind::Text,
            text: Some(item.text.clone()),
            hash: text_hash(&item.text),
        }))
        .await
        .map_err(|_| "剪贴板线程已退出".to_string())?;
    }
    Ok(())
}

/// 前端查询：调试工具是否可用（发布版隐藏入口）。
#[tauri::command]
pub(crate) fn debug_tools_enabled() -> bool {
    cfg!(debug_assertions)
}
