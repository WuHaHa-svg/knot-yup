//! KnotYup 协议核心：消息协议、组成员管理、剪贴板同步。

pub mod clipboard;
pub mod crypto;
pub mod discovery;
pub mod net;
pub mod protocol;

pub use protocol::{ClipboardPayload, DeviceInfo, Message};
