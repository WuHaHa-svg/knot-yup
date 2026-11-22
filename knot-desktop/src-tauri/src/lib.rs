//! KnotYup 桌面端装配层：模块声明 + Tauri 应用装配。
//!
//! 调试能力（日志页 / 45692 监听 / discovery 打点桥接）全部 `#[cfg(debug_assertions)]` 门控：
//! `tauri build`（正式版）完全不编译、不监听、无入口；`tauri dev` 与 `tauri build --debug` 才启用。
//!
//! 模块划分：
//! - state.rs    会话/设备/历史状态与通用工具
//! - clipboard.rs 桌面剪贴板监视线程
//! - bridge.rs   core 事件 → Tauri 事件桥接 + 选主/重连
//! - commands.rs 全部 #[tauri::command]

#[cfg(debug_assertions)]
mod debug_server;

mod bridge;
mod clipboard;
mod commands;
mod files;
mod injector;
mod state;
mod touchpad;

/// 调试日志：仅 debug 构建生效（发布版为空操作，参数被编译器消除）。
#[cfg(debug_assertions)]
pub(crate) fn dlog(tag: &str, text: impl AsRef<str>) {
    debug_server::dlog(tag, text);
}
#[cfg(not(debug_assertions))]
#[allow(unused_variables)]
pub(crate) fn dlog(tag: &str, text: impl AsRef<str>) {}

use knot_core::discovery::{self, GroupAd};
use knot_core::net::CoreEvent;
use knot_core::protocol::{ClipboardPayload, DeviceInfo};
use state::{push_history, Role, SharedState, AppState};
use tokio::sync::{broadcast, mpsc};
use tauri::{Emitter, Manager};

pub fn run() {
    // 日志订阅器装配：debug 构建走 debug_server（含 discovery 桥接），release 走纯 fmt
    #[cfg(debug_assertions)]
    debug_server::init_tracing();
    #[cfg(not(debug_assertions))]
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    tauri::Builder::default()
        .setup(|app| {
            let handle = app.handle().clone();
            let shared: SharedState = std::sync::Arc::new(AppState {
                device: tokio::sync::Mutex::new(None),
                session: tokio::sync::Mutex::new(None),
                clip_tx: tokio::sync::Mutex::new(None),
                core_tx: tokio::sync::Mutex::new(None),
                history: tokio::sync::Mutex::new(Vec::new()),
                discovered: tokio::sync::Mutex::new(Vec::new()),
                last_clip_hash: tokio::sync::Mutex::new(String::new()),
                member_count: std::sync::Arc::new(std::sync::atomic::AtomicU32::new(0)),
                promoted_at: std::sync::atomic::AtomicU64::new(0),
                transferring: std::sync::atomic::AtomicBool::new(false),
                file_map: tokio::sync::Mutex::new(knot_core::protocol::FileMap::new()),
                task_cancels: tokio::sync::Mutex::new(std::collections::HashMap::new()),
                files: std::sync::Arc::new(files::FileManager::load()),
                touchpad: tokio::sync::Mutex::new(None),
            });

            // 本机设备身份
            let device = DeviceInfo {
                id: knot_core::protocol::device_id(),
                name: format!("{} 的 {}", whoami::devicename(), match std::env::consts::OS { "macos" => "Mac", "windows" => "PC", other => other }),
                platform: std::env::consts::OS.to_string(),
                addr: None,
                file_addr: None,
                touchpad_addr: None,
                role: None,
                file_list: vec![],
            };

            // 剪贴板监视线程 + 本机变化通道
            let (local_tx, mut local_rx) = mpsc::channel::<ClipboardPayload>(64);
            let clip_tx = clipboard::spawn_clipboard_watcher(local_tx);

            // core 事件总线
            let (core_tx, core_rx) = broadcast::channel::<CoreEvent>(128);

            // 局域网发现 v2（拉取式）：仅在首页（无会话）时扫描，进群即停、退群回首页立即恢复
            // （scan 状态机自身 3s 周期：REQ → 收集 2s → 静默 1s，每轮整轮直出）
            let (disc_tx, mut disc_rx) = broadcast::channel::<Vec<GroupAd>>(16);
            let (scan_tx, mut scan_rx) = tokio::sync::watch::channel(true);
            let shared_watch = shared.clone();
            tauri::async_runtime::spawn(async move {
                // 轮询会话状态 → 扫描开关（覆盖建群/入群/退群/被踢/转让/承接全部路径）
                loop {
                    let active = shared_watch.session.lock().await.is_none();
                    let _ = scan_tx.send(active);
                    tokio::time::sleep(std::time::Duration::from_millis(500)).await;
                }
            });
            tauri::async_runtime::spawn(async move {
                loop {
                    // 等待激活（首页）
                    while !*scan_rx.borrow() {
                        if scan_rx.changed().await.is_err() {
                            return;
                        }
                    }
                    let listener =
                        tauri::async_runtime::spawn(discovery::scan(disc_tx.clone()));
                    dlog("scan", "扫描启动（进入首页，3s 周期拉取）");
                    // 等待暂停（进群）
                    while *scan_rx.borrow() {
                        if scan_rx.changed().await.is_err() {
                            listener.abort();
                            return;
                        }
                    }
                    listener.abort();
                    dlog("scan", "扫描停止（进入群页面）");
                }
            });
            let shared_disc = shared.clone();
            let handle_disc = handle.clone();
            tauri::async_runtime::spawn(async move {
                let mut last_sig = String::new();
                loop {
                    match disc_rx.recv().await {
                        Ok(list) => {
                            // 只在发现列表变化时记日志（每秒刷新一次，避免刷屏）
                            let sig = list.iter().map(|g| format!("{}@{}", g.name, g.addr)).collect::<Vec<_>>().join(" | ");
                            if sig != last_sig {
                                last_sig = sig.clone();
                                dlog("scan/result", if sig.is_empty() { "(空)" } else { &sig });
                            }
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
            // 供种端任务事件 → 状态层上报（供种服务只创建一次，setup 时注入通道）
            let (task_tx, task_rx) = tokio::sync::mpsc::unbounded_channel::<knot_core::protocol::TaskEvent>();
            {
                use tauri::async_runtime::block_on;
                block_on(shared.files.set_task_sink(task_tx));
            }
            tauri::async_runtime::spawn(bridge::forward_task_events(shared.clone(), task_rx));

            tauri::async_runtime::spawn(bridge::forward_core_events(
                handle.clone(),
                shared.clone(),
                core_rx,
                shared.member_count.clone(),
            ));

            // 本机剪贴板变化 → 广播给组 + 记历史
            let shared_clip = shared.clone();
            let handle_clip = handle.clone();
            tauri::async_runtime::spawn(async move {
                while let Some(payload) = local_rx.recv().await {
                    dlog("evt/local-clip", format!("本机复制 len={} hash={}", payload.text.as_deref().map(str::len).unwrap_or(0), payload.hash));
                    let (from, from_name) = {
                        let d = shared_clip.device.lock().await;
                        (d.as_ref().map(|x| x.id.clone()).unwrap_or_default(),
                         d.as_ref().map(|x| x.name.clone()).unwrap_or_default())
                    };
                    // 记录本机指纹：成员回声同指纹时会被忽略
                    *shared_clip.last_clip_hash.lock().await = payload.hash.clone();
                    {
                        let s = shared_clip.session.lock().await;
                        if let Some(session) = &*s {
                            match session.role {
                                Role::Owner => {
                                    if let Some(server) = &session.server {
                                        server
                                            .broadcast_clipboard(from.clone(), from_name.clone(), payload.clone())
                                            .await;
                                    }
                                }
                                Role::Member => {
                                    if let Some(client) = &session.client {
                                        let _ = client.send_clipboard(from_name.clone(), payload.clone()).await;
                                    }
                                }
                            }
                        }
                    }
                    push_history(&shared_clip, &from, &from_name, &payload).await;
                    let _ = handle_clip.emit(
                        "knot://clipboard",
                        serde_json::json!({ "from": from, "name": from_name, "text": payload.text, "hash": payload.hash }),
                    );
                }
            });

            app.manage(shared);

            // 调试日志服务（仅 debug 构建；浏览器访问 http://127.0.0.1:45692 实时查看）
            #[cfg(debug_assertions)]
            tauri::async_runtime::spawn(debug_server::start());
            dlog("app", format!("启动完成 device={} ip={}", device.name, state::local_ip()));

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_state,
            commands::create_group,
            commands::join_group,
            commands::leave_group,
            commands::kick_device,
            commands::copy_history_item,
            commands::debug_tools_enabled,
            commands::get_files,
            commands::pick_share_file,
            commands::pick_save_path,
            commands::share_file,
            commands::remove_files,
            commands::download_file,
            commands::cancel_task
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
