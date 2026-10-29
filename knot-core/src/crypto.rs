//! 通信加密：M1 用 PIN 派生密钥的 AES-256-GCM。
//! M1.x 会升级为 QR 配对的 ECDH 前向保密；当前保证局域网内明文不被直接嗅探。

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::Engine;
use rand::RngCore;
use sha2::{Digest, Sha256};

use crate::protocol::Message;

pub struct Cipher(Aes256Gcm);

impl Cipher {
    /// 由组 PIN 派生对称密钥（域分离的 SHA-256）。
    pub fn from_pin(pin: &str) -> Self {
        let key = Sha256::digest(format!("knotyup-v1|{pin}"));
        Cipher(Aes256Gcm::new_from_slice(&key).expect("32-byte key"))
    }

    /// 加密消息 → base64(nonce[12] || ciphertext)
    pub fn encrypt(&self, msg: &Message) -> String {
        let plaintext = serde_json::to_vec(msg).expect("serialize message");
        let mut nonce_bytes = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);
        let ct = self.0.encrypt(nonce, plaintext.as_ref()).expect("encrypt");
        let mut buf = Vec::with_capacity(12 + ct.len());
        buf.extend_from_slice(&nonce_bytes);
        buf.extend_from_slice(&ct);
        base64::engine::general_purpose::STANDARD.encode(buf)
    }

    pub fn decrypt(&self, data: &str) -> Result<Message, String> {
        let buf = base64::engine::general_purpose::STANDARD
            .decode(data)
            .map_err(|e| format!("base64: {e}"))?;
        if buf.len() < 12 {
            return Err("ciphertext too short".into());
        }
        let (nonce_bytes, ct) = buf.split_at(12);
        let plain = self
            .0
            .decrypt(Nonce::from_slice(nonce_bytes), ct)
            .map_err(|_| "decrypt failed (PIN 不匹配?)".to_string())?;
        serde_json::from_slice(&plain).map_err(|e| format!("json: {e}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{ClipboardKind, ClipboardPayload};

    #[test]
    fn roundtrip() {
        let c = Cipher::from_pin("123456");
        let m = Message::Clipboard {
            from: "dev".into(),
            payload: ClipboardPayload { kind: ClipboardKind::Text, text: Some("你好".into()), hash: "ab".into() },
        };
        let sealed = c.encrypt(&m);
        assert!(matches!(c.decrypt(&sealed), Ok(_)));
        let wrong = Cipher::from_pin("654321");
        assert!(wrong.decrypt(&sealed).is_err());
    }
}
