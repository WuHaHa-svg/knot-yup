# KnotYup 架构

## 总体

```
                    ┌──────────── knot-core (Rust) ────────────┐
                    │  discovery(UDP 广播) │ group(状态机)      │
                    │  protocol(JSON+加密信封) │ crypto(AES-GCM)│
                    │  clipboard(轮询+指纹去重)                │
                    └──────┬──────────────┬─────────────┬─────┘
                  UniFFI↙(M2)        直接使用↙        (未来 Linux/iOS)
        ┌──────────────┐   ┌──────────────┐
        │ knot-android │   │ knot-desktop │
        │ Kotlin/Compose│  │ Tauri 2      │
        │ Shizuku/IME/  │  │ Win:SendInput│
        │ 悬浮窗降级链   │  │ Mac:CGEvent  │
        └──────────────┘   └──────────────┘
```

- 拓扑：**星型**。群主 = WebSocket 服务端，负责成员表、移出、消息广播与密文转发。
- 发现：UDP 广播（比 mDNS 轻、跨平台零依赖）；广播不含 PIN。
- 加密：入群后所有消息套 `encrypted` 信封（AES-256-GCM，PIN 派生密钥）。
- 防回环：内容 SHA-256 指纹；接收端写入后同步指纹，轮询不再触发广播。
- 大文件（M4）：分块 + 背压，成员直传不经群主。

## Android 剪贴板能力分层（minSdk 29）

```
CapabilityDetector 自动选择：
 1. ShizukuProvider  — shell uid 代理 IClipboard，后台读写（需 Shizuku 运行中）
 2. ImeProvider      — 内置 IME，合规读写（需用户启用）
 3. ForegroundProvider — 前台 App / 悬浮窗 / 分享 + 快捷磁贴（保底）
```

Android 10+ 只允许焦点窗口或默认 IME 读剪贴板，但豁免
`SYSTEM/ROOT/SHELL` uid —— Shizuku 以 shell uid 提供 binder 代理，故可后台读写。
Shizuku 本身每次重启需重新激活（root 自动 / Android 11+ 无线调试自助 / Android 10 需 adb），
因此必须保留降级链。

## 触控板（M3）

Android 前台 Activity 采集 MotionEvent → 增量(dx/dy/按键/滚轮) → 专用 TCP 连接
→ PC 注入（Windows `SendInput` / macOS `CGEvent`）。目标端到端 < 20ms。
手势：单指移动、双指滚动、点按、双击拖拽。

## 桌面端数据流

```
本机剪贴板变化 ──watcher线程(轮询400ms)──> local_rx ──> 群广播(密文) + 历史 + knot://clipboard 事件
远端密文 ──WebSocket──> knot-core 解密 ──CoreEvent──> 写本机剪贴板 + 历史 + knot://clipboard 事件
发现广播 ──UDP:45691──> knot-core listen ──────────> knot://discovered 事件（5s 未见即移除）
```

状态集中在 `AppState`（device / session / history / discovered），
命令（`get_state` 等）与事件（`knot://*`）是 UI 与核心层的唯一边界。

## 当前实现状态

- [x] M0 基础：协议 + 星型群 + 剪贴板轮询去重 + 桌面端 UI
- [x] M1 群管理：PIN 入群 / 移出 / UDP 发现 / AES-256-GCM 加密 / i18n（简繁英）
- [ ] M2 Android：Kotlin 客户端 + UniFFI + 剪贴板降级链
- [ ] M3 触控板
- [ ] M4 文件传输 / ECDH+QR 配对 / 群主迁移
