//! 桌面端剪贴板监视线程（轮询 + 去重）。

use crate::state::ClipCmd;
use knot_core::clipboard::ClipboardWatcher;
use knot_core::protocol::ClipboardPayload;
use std::time::Duration;
use tokio::sync::mpsc;

/// 启动剪贴板监视线程（轮询 + 去重）。
/// 返回指令通道；本机内容变化通过 local_tx 抛给 async 侧。
pub(crate) fn spawn_clipboard_watcher(local_tx: mpsc::Sender<ClipboardPayload>) -> mpsc::Sender<ClipCmd> {
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
