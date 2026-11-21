//! Tauri 命令层：前端可调用的全部命令。

use crate::dlog;
use crate::state::{gen_pin, has_lan, local_ip, ClipCmd, Role, Session, SharedState};
use knot_core::clipboard::text_hash;
use knot_core::discovery::{self, GroupAd};
use knot_core::file::fetch_file;
use knot_core::net::{GroupClient, GroupServer};
use knot_core::protocol::{device_id, now_ms, ClipboardKind, ClipboardPayload, SharedFile, TaskStatus, TransferTask};
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
    let mut device = state.device.lock().await.clone().unwrap();
    dlog("act/create", format!("创建群 port={} pin={}", port.unwrap_or(0), pin));
    // 起 P2P 供种服务，填 file_addr 随成员表下发
    if let Some(addr) = state.files.ensure_seeder(&pin, &device.id, &local_ip()).await {
        device.file_addr = Some(addr);
        *state.device.lock().await = Some(device.clone());
    }
    // 群主本地文件列表写入本机 device.file_list（成员表即文件列表真相源）
    {
        let mine = state.files.shared_files().await;
        device.file_list = mine.clone();
        *state.device.lock().await = Some(device.clone());
        state.file_map.lock().await.insert(device.id.clone(), mine);
    }
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
    // 起 P2P 供种服务，填 file_addr 随 join 上报
    if let Some(addr) = state.files.ensure_seeder(&pin, &device.id, &local_ip()).await {
        device.file_addr = Some(addr);
    }
    // 入群携带自己的 fileList（成员表即文件列表真相源）
    {
        let mine = state.files.shared_files().await;
        device.file_list = mine.clone();
        *state.device.lock().await = Some(device.clone());
        state.file_map.lock().await.insert(device.id.clone(), mine);
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
        // 清群文件列表（本机共享文件夹与 seeder 常驻，不随离群停止）
        state.file_map.lock().await.clear();
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

// ── 文件共享命令 ──

/// 弹原生对话框选要共享的文件，返回绝对路径（取消返回 None）。
#[tauri::command]
pub(crate) async fn pick_share_file() -> Result<Option<String>, String> {
    tokio::task::spawn_blocking(|| {
        rfd::FileDialog::new()
            .pick_file()
            .map(|p| p.to_string_lossy().to_string())
    })
    .await
    .map_err(|e| e.to_string())
}

/// 弹原生保存对话框，返回用户选择的保存路径（含文件名）。
#[tauri::command]
pub(crate) async fn pick_save_path(default_name: String) -> Result<Option<String>, String> {
    tokio::task::spawn_blocking(move || {
        rfd::FileDialog::new()
            .set_file_name(&default_name)
            .save_file()
            .map(|p| p.to_string_lossy().to_string())
    })
    .await
    .map_err(|e| e.to_string())
}

/// 群文件列表（按设备分组）+ 本地共享清单。
#[tauri::command]
pub(crate) async fn get_files(state: State<'_, SharedState>) -> Result<serde_json::Value, String> {
    Ok(serde_json::json!({
        "group_files": *state.file_map.lock().await,
        "local_files": state.files.shared_files().await,
    }))
}

/// 共享本机任意路径的文件：登记清单 + 广播 file_add；新文件需重启 seeder 才能供种。
#[tauri::command]
pub(crate) async fn share_file(
    app: AppHandle,
    state: State<'_, SharedState>,
    path: String,
) -> Result<serde_json::Value, String> {
    let file = state.files.add_local(&path).await?;
    let (my_id, my_name) = {
        let d = state.device.lock().await;
        (d.as_ref().map(|x| x.id.clone()).unwrap_or_default(),
         d.as_ref().map(|x| x.name.clone()).unwrap_or_default())
    };
    // 更新自己本地条目 + device.file_list（Members 广播携带真相源）+ 广播 file_add
    state.file_map.lock().await.entry(my_id.clone()).or_default().push(file.clone());
    if let Some(d) = state.device.lock().await.as_mut() {
        if !d.file_list.iter().any(|f| f.file_id == file.file_id) {
            d.file_list.push(file.clone());
        }
    }
    match send_file_add(&state, &my_id, &my_name, file.clone()).await {
        Ok(_) => {}
        Err(e) => return Err(e),
    }
    let _ = app.emit("knot://file-changed", serde_json::json!({}));
    Ok(serde_json::to_value(&file).unwrap())
}

/// 删除本地共享文件条目（不动磁盘文件）+ 广播 file_remove。
#[tauri::command]
pub(crate) async fn remove_files(
    app: AppHandle,
    state: State<'_, SharedState>,
    file_ids: Vec<String>,
) -> Result<(), String> {
    state.files.remove_local(&file_ids).await;
    let (my_id, my_name) = {
        let d = state.device.lock().await;
        (d.as_ref().map(|x| x.id.clone()).unwrap_or_default(),
         d.as_ref().map(|x| x.name.clone()).unwrap_or_default())
    };
    if let Some(list) = state.file_map.lock().await.get_mut(&my_id) {
        list.retain(|f| !file_ids.contains(&f.file_id));
    }
    if let Some(d) = state.device.lock().await.as_mut() {
        d.file_list.retain(|f| !file_ids.contains(&f.file_id));
    }
    match send_file_remove(&state, &my_id, &my_name, file_ids).await {
        Ok(_) => {}
        Err(e) => return Err(e),
    }
    let _ = app.emit("knot://file-changed", serde_json::json!({}));
    Ok(())
}

/// 从某设备下载文件到 dest：P2P 拉取 + sha256 校验。
/// 下载是纯本地行为——不登记到本地共享清单、不广播 file_add、不成为供种者（闭环已取消）。
#[tauri::command]
pub(crate) async fn download_file(
    _app: AppHandle,
    state: State<'_, SharedState>,
    seed_device_id: String,
    src_file_id: String,
    dest: String,
) -> Result<serde_json::Value, String> {
    // 找供种者 file_addr
    let (pin, my_id, my_name, seed_name, seed_addr, src_meta) = {
        let s = state.session.lock().await;
        let session = s.as_ref().ok_or("NOT_IN_GROUP")?;
        let pin = session.pin.clone();
        let my = state.device.lock().await.clone();
        let my_id = my.as_ref().map(|d| d.id.clone()).unwrap_or_default();
        let my_name = my.as_ref().map(|d| d.name.clone()).unwrap_or_default();
        let seed = session.members.iter().find(|m| m.id == seed_device_id);
        let seed_name = seed.map(|m| m.name.clone()).unwrap_or_default();
        let seed_addr = seed.and_then(|m| m.file_addr.clone()).ok_or("SEED_NO_ADDR")?;
        let src_meta = {
            let map = state.file_map.lock().await;
            map.get(&seed_device_id)
                .and_then(|list| list.iter().find(|f| f.file_id == src_file_id).cloned())
                .ok_or("FILE_NOT_FOUND")?
        };
        (pin, my_id, my_name, seed_name, seed_addr, src_meta)
    };
    let dest_path = std::path::Path::new(&dest);
    // 传输任务诞生（下载方上报，status=queuing）；task_id 随 P2P 请求帧传给供种方
    let task_id = device_id();
    let now = now_ms();
    let task = TransferTask {
        task_id: task_id.clone(),
        file_id: src_file_id.clone(),
        name: src_meta.name.clone(),
        size: src_meta.size,
        mime: src_meta.mime.clone(),
        seeder_id: seed_device_id.clone(),
        seeder_name: seed_name.clone(),
        downloader_id: my_id.clone(),
        downloader_name: my_name.clone(),
        status: TaskStatus::Queuing,
        progress: 0,
        transferred: 0,
        error: String::new(),
        created_at: now,
        updated_at: now,
    };
    {
        let s = state.session.lock().await;
        if let Some(session) = s.as_ref() {
            if let Some(server) = &session.server {
                server.local_task_add(task).await;
            } else if let Some(client) = &session.client {
                if let Err(e) = client.send_task_add(task).await {
                    dlog("evt/task", format!("task_add 上报失败: {e}"));
                }
            }
        }
    }
    let result = fetch_file(
        &seed_addr, &pin, &src_file_id, &seed_device_id, &my_id,
        &src_meta.name, &src_meta.mime, dest_path, &task_id,
    )
    .await;
    // 下载方失败上报（与供种方形成双保险：任一方掉线，另一方仍能把失败送达）
    if let Err(e) = &result {
        let s = state.session.lock().await;
        if let Some(session) = s.as_ref() {
            if let Some(server) = &session.server {
                use knot_core::protocol::TaskEvent;
                server.local_task_event(TaskEvent::Failed {
                    task_id: task_id.clone(), error: e.clone(),
                }).await;
            } else if let Some(client) = &session.client {
                if let Err(er) = client.send_task_failed(task_id.clone(), e.clone()).await {
                    dlog("evt/task", format!("task_failed 上报失败: {er}"));
                }
            }
        }
    }
    result.map_err(|e| format!("下载失败: {e}"))?;
    // 下载即终点：不再登记/广播，前端也不消费返回值
    Ok(serde_json::Value::Null)
}

async fn send_file_add(state: &SharedState, my_id: &str, my_name: &str, file: SharedFile) -> Result<(), String> {
    let s = state.session.lock().await;
    let session = s.as_ref().ok_or("NOT_IN_GROUP")?;
    if let Some(server) = &session.server {
        server.broadcast_file_add(my_id.to_string(), my_name.to_string(), file).await;
        Ok(())
    } else if let Some(client) = &session.client {
        client.send_file_add(my_name.to_string(), file).await
    } else {
        Err("NO_SESSION".into())
    }
}

async fn send_file_remove(state: &SharedState, my_id: &str, my_name: &str, ids: Vec<String>) -> Result<(), String> {
    let s = state.session.lock().await;
    let session = s.as_ref().ok_or("NOT_IN_GROUP")?;
    if let Some(server) = &session.server {
        server.broadcast_file_remove(my_id.to_string(), my_name.to_string(), ids).await;
        Ok(())
    } else if let Some(client) = &session.client {
        client.send_file_remove(my_name.to_string(), ids).await
    } else {
        Err("NO_SESSION".into())
    }
}

