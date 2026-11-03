//! 桥接层：core 事件 → Tauri 事件 → 前端；群主失联后的选主与重连。

use crate::dlog;
use crate::state::{local_ip, push_history, Role, Session, SharedState};
use knot_core::discovery::{self, GroupAd};
use knot_core::net::{CoreEvent, GroupClient, GroupServer};
use knot_core::protocol::DeviceInfo;
use tauri::{AppHandle, Emitter};

/// core 事件 → Tauri 事件 → 前端
pub(crate) async fn forward_core_events(
    app: AppHandle,
    state: SharedState,
    mut rx: tokio::sync::broadcast::Receiver<CoreEvent>,
) {
    loop {
        match rx.recv().await {
            Ok(CoreEvent::Members(members)) => {
                dlog("evt/members", members.iter().map(|d| d.name.clone()).collect::<Vec<_>>().join(", "));
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
                // 防回环：成员把同步内容当作本机复制再发回来时，指纹相同，直接忽略
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
            Ok(CoreEvent::OwnerChange(new_owner)) => {
                dlog("evt/owner-change", format!("new_owner={} @ {}", new_owner.name, new_owner.addr.clone().unwrap_or_default()));
                // 群主主动转让：new_owner 升为主，其余重连
                handle_owner_lost(app.clone(), state.clone(), Some(new_owner)).await;
            }
            Ok(CoreEvent::Error(e)) => {
                dlog("evt/error", &e);
                let is_member = {
                    let s = state.session.lock().await;
                    matches!(&*s, Some(Session { role: Role::Member, .. }))
                };
                if is_member && e.starts_with("connection closed") {
                    // 群主意外离开 → 选主
                    handle_owner_lost(app.clone(), state.clone(), None).await;
                } else if !e.starts_with("connection closed") {
                    let _ = app.emit("knot://error", e);
                }
            }
            Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
            Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
        }
    }
}

/// 群主消失（意外断开或主动转让）后的选主与重连。
/// explicit = 转让时指定的新群主；None = 按 members[1] 顺位确定选主。
pub(crate) async fn handle_owner_lost(app: AppHandle, state: SharedState, explicit: Option<DeviceInfo>) {
    let Some(mut s) = state.session.lock().await.take() else {
        return;
    };
    let candidates: Vec<DeviceInfo> = match &explicit {
        Some(d) => vec![d.clone()],
        None => s.members.iter().skip(1).cloned().collect(), // [0] 是旧群主
    };
    let my_id = state.device.lock().await.as_ref().map(|d| d.id.clone()).unwrap_or_default();
    let pin = s.pin.clone();
    let core_tx = state.core_tx.lock().await.clone().unwrap_or_else(|| {
        // 理论上 setup 已初始化；兜底新建（事件转发只订阅原通道，此分支仅保编译）
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
                let announce_stop = discovery::announce(GroupAd {
                    id: device.id.clone(),
                    name: device.name.clone(),
                    addr: addr.clone(),
                })
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
                    announce_stop,
                    standby: None,
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
