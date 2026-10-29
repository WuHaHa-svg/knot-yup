//! 剪贴板访问与轮询监听。桌面端策略：轮询 + 内容指纹去重（防回环）。

use arboard::Clipboard;
use sha2::{Digest, Sha256};

use crate::protocol::{ClipboardKind, ClipboardPayload};

#[derive(Debug, thiserror::Error)]
pub enum ClipboardError {
    #[error("clipboard unavailable: {0}")]
    Unavailable(String),
}

pub struct ClipboardWatcher {
    clipboard: Clipboard,
    last_hash: String,
}

impl ClipboardWatcher {
    pub fn new() -> Result<Self, ClipboardError> {
        let mut clipboard = Clipboard::new().map_err(|e| ClipboardError::Unavailable(e.to_string()))?;
        // 初始化时记录当前内容的指纹，避免把历史剪贴板广播出去。
        let last_hash = text_hash(&clipboard.get_text().unwrap_or_default());
        Ok(Self { clipboard, last_hash })
    }

    /// 轮询一次；若文本内容变化则返回待广播的 payload。
    pub fn poll_text(&mut self) -> Result<Option<ClipboardPayload>, ClipboardError> {
        let text = self
            .clipboard
            .get_text()
            .map_err(|e| ClipboardError::Unavailable(e.to_string()))?;
        if text.is_empty() {
            return Ok(None);
        }
        let hash = text_hash(&text);
        if hash == self.last_hash {
            return Ok(None);
        }
        self.last_hash = hash.clone();
        Ok(Some(ClipboardPayload {
            kind: ClipboardKind::Text,
            text: Some(text),
            hash,
        }))
    }

    /// 写入远端同步来的内容；同步指纹防止再次广播（回环）。
    pub fn apply_remote(&mut self, payload: &ClipboardPayload) -> Result<(), ClipboardError> {
        if let Some(text) = &payload.text {
            self.clipboard
                .set_text(text.clone())
                .map_err(|e| ClipboardError::Unavailable(e.to_string()))?;
            self.last_hash = payload.hash.clone();
        }
        Ok(())
    }
}

pub fn text_hash(text: &str) -> String {
    let d = Sha256::digest(text.as_bytes());
    hex::encode(&d[..8])
}
