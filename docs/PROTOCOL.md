# KnotYup 协议规范（v1）

## 传输层

- **群内通信**：WebSocket（TCP）。群主监听 `0.0.0.0:<port>`（默认自动分配），成员主动连接。
- **发现**：UDP 广播，端口 `45691`。群主每 1.5s 广播一次 `KNOTYUP1|<json>`；
  监听方 5s 未收到即视为下线。广播不含 PIN。
- 消息体为 UTF-8 JSON（单帧一条）。大流量通道（M4 文件）将改 Protobuf + 分块。

## 消息类型（`knot-core/src/protocol.rs`）

所有消息带 `type` 标签（snake_case）。

| 消息 | 方向 | 字段 | 说明 |
|---|---|---|---|
| `join` | 成员 → 群主 | `pin`, `device` | 入群握手（明文） |
| `join_result` | 群主 → 成员 | `ok`, `reason?`, `members` | `ok:false` 时 `reason` 为错误码（如 `WRONG_PIN`） |
| `members` | 群主 → 全员 | `members: DeviceInfo[]` | 成员变更快照（加密） |
| `clipboard` | 成员 → 群主 → 全员 | `from`, `payload` | 剪贴板同步（加密） |
| `kick` | 群主 → 目标 | `reason?` | 移出（加密），随后断开连接 |
| `ping`/`pong` | 双向 | — | 保活（预留） |
| `encrypted` | 双向 | `data` | 加密信封，内含以上任意一条 |

```jsonc
// DeviceInfo
{ "id": "uuid-v4", "name": "Jackey 的 Mac", "platform": "macos" }
// ClipboardPayload
{ "kind": "text", "text": "…", "hash": "sha256 前 8 字节 hex" }
```

## 加密

- 入群成功后，双方由 `SHA-256("knotyup-v1|" + PIN)` 派生 AES-256-GCM 密钥。
- `encrypted.data = base64(nonce[12] || ciphertext)`，nonce 每条随机。
- 群主转发成员消息时**原样转发密文**（同群同 PIN 同密钥）。
- PIN 不正确 ⇒ 派生密钥不同 ⇒ 解密失败，消息被丢弃。
- 说明：这是对称口令加密（防嗅探/蹭网），不含前向保密；ECDH + QR 配对在 M4 演进。

## 剪贴板同步与防回环

- 桌面端轮询本地剪贴板（~400ms）：
  - macOS `NSPasteboard.changeCount` / Windows 序列号 / arboard 统一抽象
- 内容指纹 = SHA-256 前 8 字节。指纹未变 ⇒ 不广播；写入远端内容时同步指纹 ⇒ 不会再次广播（防回环）。
- 初始化时记录当前指纹，**不**把历史剪贴板内容广播出去。

## 错误码（跨端稳定，UI 层负责本地化）

| 码 | 含义 |
|---|---|
| `WRONG_PIN` | PIN 码不正确 |
| `ALREADY_IN_GROUP` | 已在群中，需先退出 |
| `OWNER_ONLY` | 仅群主可执行（移出成员等） |
| `CORE_INIT` | 核心层未初始化（内部错误） |

## 未来（M3/M4）

- `pointer` 增量消息：`dx, dy, button, scroll`（触控板，独立 TCP 通道）
- `file_chunk`：分块直传 + 背压，不经群主
- ECDH 会话密钥、设备配对表（换 PIN 不失效）
