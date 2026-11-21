//! 桥接层 v2：core 事件 → Tauri 事件 → 前端；群主失联承接；转让三方握手的桌面侧执行。

use crate::dlog;
use crate::state::{local_ip, now_ms, push_history, Role, Session, SharedState};
use knot_core::discovery::{self, GroupAd};
use knot_core::net::{CoreEvent, GroupClient, GroupServer};
use knot_core::protocol;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use tauri::{AppHandle, Emitter};

/// core 事件 → Tauri 事件 → 前端
pub(crate) async fn forward_core_events(
    app: AppHandle,
    state: SharedState,
    mut rx: tokio::sync::broadcast::Receiver<CoreEvent>,
    member_count: Arc<AtomicU32>,
) {
    loop {
        match rx.recv().await {
            Ok(CoreEvent::Members(members)) => {
                dlog("evt/members", members.iter().map(|d| d.name.clone()).collect::<Vec<_>>().join(", "));
                member_count.store(members.len() as u32, Ordering::Relaxed); // 发现应答用
                let own_id = state
                    .device
                    .lock()
                    .await
                    .as_ref()
                    .map(|d| d.id.clone())
                    .unwrap_or_default();
                let (prev, had_session) = {
                    let mut guard = state.session.lock().await;
                    match guard.as_mut() {
                        Some(s) => (std::mem::replace(&mut s.members, members.clone()), true),
                        None => (Vec::new(), false),
                    }
                };
                let _ = app.emit("knot://members", &members);
                // fileList 内嵌在成员表：成员表即文件列表唯一真相源，从它重建 file_map
                {
                    let mut fm = state.file_map.lock().await;
                    fm.clear();
                    for d in &members {
                        fm.insert(d.id.clone(), d.file_list.clone());
                    }
                    let _ = app.emit("knot://file-changed", serde_json::json!({}));
                }
                // 升主后 6s 静默期 / 转让进行中：迁移潮造成的成员差量不提示（不是「有人入群/离群」）
                let promoted_recently = {
                    let at = state.promoted_at.load(std::sync::atomic::Ordering::Relaxed);
                    at > 0 && now_ms().saturating_sub(at) < 6_000
                };
                let transferring = state.transferring.load(std::sync::atomic::Ordering::Relaxed);
                // 成员差量 → 加入/离开通知（首次同步与自身变化不提示）
                if had_session && !prev.is_empty() && !promoted_recently && !transferring {
                    use std::collections::HashSet;
                    let prev_ids: HashSet<String> = prev.iter().map(|d| d.id.clone()).collect();
                    let new_ids: HashSet<String> = members.iter().map(|d| d.id.clone()).collect();
                    for d in &members {
                    if !prev_ids.contains(&d.id) && d.id != own_id {
                            let _ = app.emit(
                                "knot://member-change",
                                serde_json::json!({ "type": "joined", "name": d.name }),
                            );
                        }
                    }
                    for d in &prev {
                        if !new_ids.contains(&d.id) && d.id != own_id {
                            let _ = app.emit(
                                "knot://member-change",
                                serde_json::json!({ "type": "left", "name": d.name }),
                            );
                        }
                    }
                }
            }
            Ok(CoreEvent::Clipboard { from, from_name, payload }) => {
                dlog("evt/clipboard", format!("from={} len={} hash={}", from, payload.text.as_deref().map(str::len).unwrap_or(0), payload.hash));
                // 防回环（第二道保险；from-id 已在连接层过滤）
                let mut guard = state.last_clip_hash.lock().await;
                if *guard == payload.hash {
                    continue;
                }
                *guard = payload.hash.clone();
                drop(guard);
                if let Some(tx) = state.clip_tx.lock().await.as_ref() {
                    let _ = tx.send(crate::state::ClipCmd::Set(payload.clone())).await;
                }
                push_history(&state, &from, &from_name, &payload).await;
                let _ = app.emit(
                    "knot://clipboard",
                    serde_json::json!({ "from": from, "name": from_name, "text": payload.text, "hash": payload.hash }),
                );
            }
            Ok(CoreEvent::FileAdd { from, from_name, file }) => {
                dlog("evt/file-add", format!("from={from} id={}", file.file_id));
                let name = file.name.clone();
                let map = &mut *state.file_map.lock().await;
                let list = map.entry(from.clone()).or_default();
                if !list.iter().any(|f| f.file_id == file.file_id) {
                    list.push(file);
                }
                // from=共享者id, from_name=共享者名, name=文件名；前端过滤自己的 from
                let _ = app.emit(
                    "knot://file-changed",
                    serde_json::json!({ "from": from, "from_name": from_name, "name": name }),
                );
            }
            Ok(CoreEvent::FileRemove { from, from_name, file_ids }) => {
                dlog("evt/file-remove", format!("from={from} n={}", file_ids.len()));
                if let Some(list) = state.file_map.lock().await.get_mut(&from) {
                    list.retain(|f| !file_ids.contains(&f.file_id));
                }
                let _ = app.emit("knot://file-changed", serde_json::json!({ "from": from, "from_name": from_name }));
            }
            Ok(CoreEvent::FileTasks(tasks)) => {
                dlog("evt/file-tasks", format!("n={}", tasks.len()));
                let _ = app.emit("knot://file-tasks", serde_json::json!(tasks));
            }
            Ok(CoreEvent::Kicked(reason)) => {
                dlog("evt/kicked", &reason);
                *state.session.lock().await = None;
                state.file_map.lock().await.clear();
                let _ = app.emit("knot://kicked", reason);
            }
            Ok(CoreEvent::Dissolve) => {
                dlog("evt/dissolve", "收到解散通知，0.8s 后退出");
                // 0.8s 缓冲后清会话回首页；不回发任何 ws 消息
                let st = state.clone();
                let app_d = app.clone();
                tauri::async_runtime::spawn(async move {
                    tokio::time::sleep(protocol::dissolve_exit_delay()).await;
                    *st.session.lock().await = None;
                    let _ = app_d.emit("knot://kicked", "DISSOLVED");
                });
            }
            Ok(CoreEvent::TransferOffer { pin }) => {
                dlog("act/transfer-target", "收到转让邀请：起 standby 服务并回 ready");
                handle_transfer_offer(app.clone(), state.clone(), pin).await;
            }
            Ok(CoreEvent::TransferError) => {
                dlog("act/transfer-target", "转让已失败（迟到 ready）：静默关闭 standby 服务");
                let mut s = state.session.lock().await;
                if let Some(session) = s.as_mut() {
                    session.standby_server = None; // drop 句柄 → 服务停止
                    session.standby_addr = None;
                }
            }
            Ok(CoreEvent::NewService { addr, pin, owner }) => {
                let my_id = state.device.lock().await.as_ref().map(|d| d.id.clone()).unwrap_or_default();
                if owner.id == my_id {
                    // 本机即新群主：升格 standby 服务为正式群主会话（绝不能自连接入自己的群）
                    dlog("act/promoted-transfer", "收到 new_service（owner=自己）：standby 服务转正");
                    handle_promote_standby(app.clone(), state.clone()).await;
                } else {
                    dlog("act/migrate", format!("收到 new_service addr={addr} owner={} → 迁移", owner.name));
                    handle_migrate(app.clone(), state.clone(), addr, pin).await;
                }
            }
            Ok(CoreEvent::TransferFailed) => {
                dlog("err/transfer", "转让失败（5s 未收到 ready），原群继续");
                state.transferring.store(false, std::sync::atomic::Ordering::Relaxed);
                let _ = app.emit("knot://toast", "TRANSFER_FAILED");
            }
            Ok(CoreEvent::TransferCompleted) => {
                dlog("act/transfer-done", "转让完成：清会话回首页");
                state.transferring.store(false, std::sync::atomic::Ordering::Relaxed);
                *state.session.lock().await = None;
                let _ = app.emit("knot://toast", "TRANSFERRED");
                let _ = app.emit("knot://kicked", "TRANSFERRED");
            }
            Ok(CoreEvent::Error(e)) => {
                dlog("evt/error", &e);
                if e.starts_with("connection closed") {
                    // 连接断开（成员侧群主失联）：不再自动承接/重连——清会话并通知前端
                    // 弹「连接已断开」对话框，由用户决策重新加入或退出群
                    let (is_member, addr, pin, owner_name) = {
                        let mut s = state.session.lock().await;
                        match s.take() {
                            Some(Session { role: Role::Member, addr, pin, members, .. }) => {
                                // 群主名取自成员表（断连弹窗展示用）
                                let owner = members
                                    .iter()
                                    .find(|d| d.is_owner())
                                    .map(|d| d.name.clone())
                                    .unwrap_or_default();
                                (true, addr, pin, owner)
                            }
                            other => {
                                *s = other; // 群主侧等其他会话：不处理
                                (false, String::new(), String::new(), String::new())
                            }
                        }
                    };
                    if is_member {
                        let _ = app.emit(
                            "knot://conn-lost",
                            serde_json::json!({ "addr": addr, "pin": pin, "owner": owner_name }),
                        );
                    }
                } else {
                    let _ = app.emit("knot://error", e);
                }
            }
            Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
            Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
        }
    }
}

/// 被转让者侧：释放 standby 端口 → 起服务 → 通过原 ws 回 transfer_ready。
/// 服务句柄暂存 Session.standby_server（收到 transfer_error 时静默关闭；new_service 后转为正式群主会话）。
async fn handle_transfer_offer(_app: AppHandle, state: SharedState, pin: String) {
    // 单锁完成：释放 standby 端口 → 起服务 → 通过原 ws 回 ready → 句柄暂存会话
    let mut s = state.session.lock().await;
    let Some(session) = s.as_mut() else { return };
    let port = session
        .standby
        .take() // 释放预留端口给服务端
        .and_then(|l| l.local_addr().ok())
        .map(|a| a.port())
        .unwrap_or(0);
    let core_tx = state.core_tx.lock().await.clone().unwrap();
    let device = state.device.lock().await.clone().unwrap();
    match GroupServer::start(port, pin, device, core_tx).await {
        Ok((server, port)) => {
            let addr = format!("{}:{}", local_ip(), port);
            dlog("act/transfer-target", format!("standby 服务就绪 {addr}"));
            if let Some(client) = &session.client {
                // 起服失败不回 ready：群主 5s 超时即转让失败（此处起服已成功才回）
                let _ = client.send_transfer_ready(addr.clone()).await;
            }
            session.standby_server = Some(server);
            session.standby_addr = Some(addr);
        }
        Err(e) => {
            dlog("err/transfer-target", format!("standby 起服失败: {e}"));
        }
    }
}

/// 被转让者收到 new_service（owner=自己）后转正：standby 服务 → 正式群主会话 + 发现应答。
async fn handle_promote_standby(app: AppHandle, state: SharedState) {
    let mut s = state.session.lock().await;
    let Some(session) = s.as_mut() else { return };
    let Some(server) = session.standby_server.take() else {
        // standby 已被清理（异常路径）：直接清会话
        *s = None;
        drop(s);
        state.promoted_at.store(now_ms(), std::sync::atomic::Ordering::Relaxed);
        let _ = app.emit("knot://toast", "PROMOTED");
        return;
    };
    let addr = session.standby_addr.clone().unwrap_or_default();
    let pin = session.pin.clone();
    let device = state.device.lock().await.clone().unwrap();
    let member_count = state.member_count.clone();
    let respond_stop = discovery::respond(
        GroupAd { id: device.id.clone(), name: device.name.clone(), addr: addr.clone(), members: server.members().await.len() as u32 },
        move || member_count.load(std::sync::atomic::Ordering::Relaxed),
    )
    .await
    .ok();
    *s = Some(Session {
        role: Role::Owner,
        server: Some(server),
        client: None,
        members: vec![device.clone()],
        addr,
        pin,
        announce_stop: respond_stop,
        standby: None,
        standby_server: None,
        standby_addr: None,
    });
    drop(s);
    state.promoted_at.store(now_ms(), std::sync::atomic::Ordering::Relaxed);
    let _ = app.emit("knot://members", vec![device]);
    let _ = app.emit("knot://toast", "PROMOTED");
}

/// 其他成员侧：收到 new_service → 断旧连 → 用同 PIN 连接新服务。
async fn handle_migrate(app: AppHandle, state: SharedState, addr: String, pin: String) {
    let core_tx = state.core_tx.lock().await.clone().unwrap();
    let device = state.device.lock().await.clone().unwrap();
    // 预留新的 standby 端口（在新群中继续具备承接资格）
    let standby = tokio::net::TcpListener::bind(("0.0.0.0", 0)).await.ok();
    let mut dev = device;
    if let Some(l) = &standby {
        if let Ok(p) = l.local_addr() {
            dev.addr = Some(format!("{}:{}", local_ip(), p.port()));
        }
    }
    match GroupClient::connect(&addr, &pin, dev, core_tx).await {
        Ok(client) => {
            *state.session.lock().await = Some(Session {
                role: Role::Member,
                server: None,
                client: Some(client),
                members: vec![],
                addr,
                pin,
                announce_stop: None,
                standby,
                standby_server: None,
                standby_addr: None,
            });
            let _ = app.emit("knot://toast", "MIGRATED");
        }
        Err(e) => {
            dlog("err/migrate", format!("迁移失败: {e}"));
            *state.session.lock().await = None;
            let _ = app.emit("knot://kicked", "GROUP_LOST");
        }
    }
}

// 自动承接/重连机制已移除：成员侧连接断开（connection closed）统一改为清会话 +
// 发 knot://conn-lost 事件，由前端弹「连接已断开」对话框交用户决策（见 forward_core_events）。

/// 供种端任务事件 → ws 上报（群主走本地方法，成员走 client）。
/// 设计见 docs/transfer-center.md：queuing/started/progress/done 由供种方上报。
pub(crate) async fn forward_task_events(
    state: SharedState,
    mut rx: tokio::sync::mpsc::UnboundedReceiver<knot_core::protocol::TaskEvent>,
) {
    use knot_core::protocol::TaskEvent;
    while let Some(ev) = rx.recv().await {
        let session = state.session.lock().await;
        let Some(s) = session.as_ref() else { continue };
        match &ev {
            TaskEvent::Queued { task_id }
            | TaskEvent::Started { task_id }
            | TaskEvent::Done { task_id } => {
                if let Some(server) = &s.server {
                    server.local_task_event(ev.clone()).await;
                } else if let Some(client) = &s.client {
                    let r = match &ev {
                        TaskEvent::Queued { .. } => client.send_task_queued(task_id.clone()).await,
                        TaskEvent::Started { .. } => client.send_task_started(task_id.clone()).await,
                        TaskEvent::Done { .. } => client.send_task_finish(task_id.clone()).await,
                        _ => unreachable!(),
                    };
                    if let Err(e) = r { dlog("evt/task", format!("上报失败: {e}")); }
                }
            }
            TaskEvent::Progress { task_id, transferred, total } => {
                let progress = if *total == 0 { 0 } else { (*transferred * 100 / *total).min(100) as u32 };
                if let Some(server) = &s.server {
                    server.local_task_event(ev.clone()).await;
                } else if let Some(client) = &s.client {
                    if let Err(e) = client.send_task_progress(task_id.clone(), progress, *transferred).await {
                        dlog("evt/task", format!("进度上报失败: {e}"));
                    }
                }
            }
            TaskEvent::Failed { task_id, error } => {
                if let Some(server) = &s.server {
                    server.local_task_event(ev.clone()).await;
                } else if let Some(client) = &s.client {
                    if let Err(e) = client.send_task_failed(task_id.clone(), error.clone()).await {
                        dlog("evt/task", format!("失败上报失败: {e}"));
                    }
                }
            }
        }
    }
}
