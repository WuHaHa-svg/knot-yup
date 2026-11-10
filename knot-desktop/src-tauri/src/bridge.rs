//! 桥接层 v2：core 事件 → Tauri 事件 → 前端；群主失联承接；转让三方握手的桌面侧执行。

use crate::dlog;
use crate::state::{local_ip, push_history, Role, Session, SharedState};
use knot_core::discovery::{self, GroupAd};
use knot_core::net::{CoreEvent, GroupClient, GroupServer};
use knot_core::protocol::{self, DeviceInfo};
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
                // 成员差量 → 加入/离开通知（首次同步与自身变化不提示）
                if had_session && !prev.is_empty() {
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
            Ok(CoreEvent::Clipboard { from, payload }) => {
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
                push_history(&state, &from, &payload).await;
                let _ = app.emit(
                    "knot://clipboard",
                    serde_json::json!({ "from": from, "text": payload.text, "hash": payload.hash }),
                );
            }
            Ok(CoreEvent::Kicked(reason)) => {
                dlog("evt/kicked", &reason);
                *state.session.lock().await = None;
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
                let _ = app.emit("knot://toast", "TRANSFER_FAILED");
            }
            Ok(CoreEvent::TransferCompleted) => {
                dlog("act/transfer-done", "转让完成：清会话回首页");
                *state.session.lock().await = None;
                let _ = app.emit("knot://toast", "TRANSFERRED");
                let _ = app.emit("knot://kicked", "TRANSFERRED");
            }
            Ok(CoreEvent::Error(e)) => {
                dlog("evt/error", &e);
                let is_member = {
                    let s = state.session.lock().await;
                    matches!(&*s, Some(Session { role: Role::Member, .. }))
                };
                if is_member && e.starts_with("connection closed") {
                    // 群主意外离开 → 承接
                    handle_owner_lost(app.clone(), state.clone()).await;
                } else if !e.starts_with("connection closed") {
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

/// 群主失联承接（v2 简化版）：候选 = 成员表中 role==member 的设备按序；
/// 候选[0]是自己 → standby 承接；否则重连候选地址（10 轮）。
pub(crate) async fn handle_owner_lost(app: AppHandle, state: SharedState) {
    let Some(mut s) = state.session.lock().await.take() else {
        return;
    };
    let candidates: Vec<DeviceInfo> = s.members.iter().filter(|d| !d.is_owner()).cloned().collect();
    let my_id = state.device.lock().await.as_ref().map(|d| d.id.clone()).unwrap_or_default();
    let pin = s.pin.clone();
    let core_tx = state.core_tx.lock().await.clone().unwrap_or_else(|| {
        tokio::sync::broadcast::channel::<CoreEvent>(128).0
    });

    if candidates.is_empty() {
        dlog("err/owner-lost", "无候选，群丢失");
        let _ = app.emit("knot://kicked", "GROUP_LOST");
        return;
    }
    dlog("act/owner-lost", format!("候选: {}", candidates.iter().map(|c| c.name.clone()).collect::<Vec<_>>().join(" → ")));

    if candidates[0].id == my_id {
        // 当选：用预留端口升为群主
        let standby_port = s
            .standby
            .as_ref()
            .and_then(|l| l.local_addr().ok())
            .map(|a| a.port())
            .unwrap_or(0);
        s.standby = None; // 释放端口
        drop(s);
        let device = state.device.lock().await.clone().unwrap_or_else(|| candidates[0].clone());
        match GroupServer::start(standby_port, pin.clone(), device.clone(), core_tx).await {
            Ok((server, port)) => {
                let addr = format!("{}:{}", local_ip(), port);
                // v2：群主被动应答发现请求
                let member_count = Arc::new(AtomicU32::new(1));
                let respond_stop = discovery::respond(
                    GroupAd { id: device.id.clone(), name: device.name.clone(), addr: addr.clone(), members: 1 },
                    { let c = member_count.clone(); move || c.load(Ordering::Relaxed) },
                )
                .await
                .ok();
                dlog("act/promoted", format!("承接群主 {addr}"));
                *state.session.lock().await = Some(Session {
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
                let _ = app.emit("knot://members", vec![device]);
                let _ = app.emit("knot://toast", "PROMOTED");
            }
            Err(_) => {
                let _ = app.emit("knot://kicked", "GROUP_LOST");
                dlog("err/promoted", "承接群主失败（端口被占）");
            }
        }
    } else {
        // 重连：依次尝试候选地址（当选者未就绪时退避重试）
        drop(s);
        let _ = app.emit("knot://toast", "RECONNECTING");
        dlog("act/reconnecting", "开始重连候选");
        tauri::async_runtime::spawn(async move {
            for _attempt in 0..10 {
                for c in &candidates {
                    let Some(addr) = &c.addr else { continue };
                    if c.id == my_id {
                        continue;
                    }
                    let device = state.device.lock().await.clone().unwrap();
                    dlog("act/reconnect-try", format!("attempt={_attempt} -> {addr}"));
                    if let Ok(client) = GroupClient::connect(addr, &pin, device, core_tx.clone()).await {
                        let mut guard = state.session.lock().await;
                        if guard.is_some() {
                            return; // 已经被其他路径处理
                        }
                        *guard = Some(Session {
                            role: Role::Member,
                            server: None,
                            client: Some(client),
                            members: vec![],
                            addr: addr.clone(),
                            pin: pin.clone(),
                            announce_stop: None,
                            standby: None,
                            standby_server: None,
                standby_addr: None,
                        });
                        drop(guard);
                        let _ = app.emit("knot://toast", "RECONNECTED");
                        dlog("act/reconnected", format!("已重连 {addr}"));
                        return;
                    }
                }
                tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
            }
            let _ = app.emit("knot://kicked", "GROUP_LOST");
            dlog("err/reconnect", "10 轮重连全部失败，群丢失");
        });
    }
}
