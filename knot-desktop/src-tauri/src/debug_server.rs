//! 调试日志服务：内嵌微型 HTTP + SSE，浏览器访问 http://127.0.0.1:45692 实时查看运行日志。
//! 软件正常运行不受影响；日志同时进环形缓冲（新连接先回放历史）。
//! 零新依赖：手写 HTTP 路由（GET / 页面，GET /events 事件流）。
//!
//! 模块边界（整模块 `#[cfg(debug_assertions)]`，正式版不编译）：
//! - `dlog()`          埋点写入（业务代码只调这个）
//! - `init_tracing()`  装配订阅器：fmt 输出 + discovery/* 桥接层
//! - `start()`         HTTP/SSE 服务
//! - CoreLogLayer     把 knot-core 的 tracing 打点（discovery/* 与 ws/*）转发进本模块

use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::broadcast;

pub const DEBUG_PORT: u16 = 45692;

/// 一条日志（JSON 序列化后走 SSE）。
#[derive(Clone, serde::Serialize)]
pub struct LogEntry {
    /// 毫秒时间戳
    pub ts: u64,
    pub tag: String,
    pub text: String,
}

struct DebugLog {
    ring: Mutex<VecDeque<LogEntry>>,
    tx: broadcast::Sender<LogEntry>,
}

static LOG: OnceLock<DebugLog> = OnceLock::new();

fn log() -> &'static DebugLog {
    LOG.get_or_init(|| DebugLog {
        ring: Mutex::new(VecDeque::new()),
        tx: broadcast::channel(256).0,
    })
}

/// 记录一条调试日志（同步、非阻塞；任何上下文可调用）。
pub fn dlog(tag: &str, text: impl AsRef<str>) {
    let entry = LogEntry {
        ts: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0),
        tag: tag.to_string(),
        text: text.as_ref().chars().take(400).collect(),
    };
    {
        let mut ring = log().ring.lock().unwrap();
        ring.push_back(entry.clone());
        while ring.len() > 1000 {
            ring.pop_front();
        }
    }
    let _ = log().tx.send(entry);
}

/// 启动调试 HTTP 服务（失败只打日志，不影响主程序）。
pub async fn start() {
    let listener = match TcpListener::bind(("127.0.0.1", DEBUG_PORT)).await {
        Ok(l) => l,
        Err(e) => {
            tracing::warn!("debug server bind failed: {e}");
            return;
        }
    };
    dlog("debug", format!("日志页已就绪: http://127.0.0.1:{DEBUG_PORT}"));
    loop {
        let (stream, _) = match listener.accept().await {
            Ok(v) => v,
            Err(_) => continue,
        };
        tokio::spawn(handle_conn(stream));
    }
}

async fn handle_conn(mut stream: TcpStream) {
    // 只读请求首行（路由足够）
    let mut buf = vec![0u8; 2048];
    let n = stream.read(&mut buf).await.unwrap_or(0);
    let req = String::from_utf8_lossy(&buf[..n]);
    let path = req.split_whitespace().nth(1).unwrap_or("/").to_string();

    if path == "/events" {
        serve_sse(stream).await;
    } else {
        let body = include_str!("../../ui/debug.html");
        let resp = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body.len()
        );
        let _ = stream.write_all(resp.as_bytes()).await;
        let _ = stream.write_all(body.as_bytes()).await;
    }
}

/// SSE：先回放环形缓冲历史，再实时转发新日志。
async fn serve_sse(mut stream: TcpStream) {
    let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
    if stream.write_all(head.as_bytes()).await.is_err() {
        return;
    }
    let mut rx = log().tx.subscribe();
    // 回放历史
    let backlog: Vec<LogEntry> = log().ring.lock().unwrap().iter().cloned().collect();
    for e in backlog {
        if send_entry(&mut stream, &e).await.is_err() {
            return;
        }
    }
    // 实时流
    loop {
        match rx.recv().await {
            Ok(e) => {
                if send_entry(&mut stream, &e).await.is_err() {
                    return;
                }
            }
            Err(broadcast::error::RecvError::Lagged(_)) => continue,
            Err(broadcast::error::RecvError::Closed) => return,
        }
    }
}

async fn send_entry(stream: &mut TcpStream, e: &LogEntry) -> std::io::Result<()> {
    let json = serde_json::to_string(e).unwrap();
    stream.write_all(format!("data: {json}\n\n").as_bytes()).await
}

/// tracing 桥接层：把 knot-core 的 discovery/* 与 ws/* 打点转发进调试日志页。
struct CoreLogLayer;

impl<S> tracing_subscriber::Layer<S> for CoreLogLayer
where
    S: tracing::Subscriber + 'static,
{
    fn on_event(&self, event: &tracing::Event<'_>, _ctx: tracing_subscriber::layer::Context<'_, S>) {
        let target = event.metadata().target();
        if !(target.starts_with("discovery") || target.starts_with("ws")) {
            return;
        }
        struct MsgVisit(String);
        impl tracing::field::Visit for MsgVisit {
            fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
                if field.name() == "message" {
                    self.0 = format!("{value:?}");
                }
            }
        }
        let mut v = MsgVisit(String::new());
        event.record(&mut v);
        if !v.0.is_empty() {
            dlog(target, v.0);
        }
    }
}

/// 装配 tracing 订阅器（替代业务侧重直接 `fmt().init()`）：
/// 终端 fmt 输出 + discovery 桥接；默认过滤器放开 discovery 打点。
pub fn init_tracing() {
    use tracing_subscriber::prelude::*;
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info,discovery=debug,ws=debug"));
    tracing_subscriber::registry()
        .with(filter)
        .with(tracing_subscriber::fmt::layer())
        .with(CoreLogLayer)
        .init();
}
