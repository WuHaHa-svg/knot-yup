# KnotYup 架构

## 总体

```
                    ┌──────────── knot-core (Rust) ────────────┐
                    │  discovery(UDP 拉取式发现) │ net(群主/成员) │
                    │  protocol(JSON+加密信封) │ crypto(AES-GCM)│
                    │  clipboard(轮询+指纹去重)                │
                    └──────┬──────────────┬─────────────┬─────┘
                  UniFFI(规划)↙        直接使用↙        (未来 Linux/iOS)
        ┌──────────────┐   ┌──────────────┐
        │ knot-android │   │ knot-desktop │
        │ Kotlin/Compose│  │ Tauri 2      │
        │ Shizuku/前台   │  │ Win:SendInput│
        │ 降级链         │  │ Mac:CGEvent  │
        └──────────────┘   └──────────────┘
```

- 拓扑：**星型**。群主 = WebSocket 服务端，负责成员表、移出、消息广播与密文转发。
- 发现：UDP 拉取式 REQ/RESP（比 mDNS 轻、跨平台零依赖）；广播不含 PIN。
- 加密：入群后所有业务消息套 `encrypted` 信封（AES-256-GCM，PIN 派生密钥）。
- 防回环（Android）：三层闸门——clipGuard（内容哈希）+ lastSyncText（文本级回声）+
  clipFpBase（轮询基线，本机写入后立即刷新，轮询只见真正的外部复制）。
- 断连策略：**无自动重连/承接**。成员与群主的 ws 断开（含心跳失联）→ 双端统一弹
  「连接已断开」弹窗（显示群主名称），用户决策「重新加入」（5s 超时判失败、弹窗重现）
  或「退出群」。
- 大文件（M4）：分块 + 背压，成员直传不经群主。

## Android 剪贴板能力分层（minSdk 29）

```
ClipboardCapability 自动选择：
 1. ShizukuProvider   — binder 代理 IClipboard，后台读写（需 Shizuku 运行中 + 已授权）
 2. ForegroundProvider — 前台 App（系统剪贴板变化回调）+ 分享发送（保底）
 3.（规划）ImeProvider — 内置输入法：默认 IME 全版本恒可读，14+ 的正解
```

**平台限制（实测结论，2026-11）**：AOSP 10+ 的 `ClipboardService` 读剪贴板豁免仅限
焦点 uid / 默认输入法 / `READ_CLIPBOARD_IN_BACKGROUND` 签名权限 / 系统服务，
**不含 shell/root uid，也不含已启用的无障碍服务**（空无障碍服务方案对 AOSP 全版本无效，
已实测证伪；MIUI/HyperOS 自家「剪贴板=永远允许」权限待验证）。

- **Shizuku 读实际边界**：Android 10–13 经 `ShizukuBinderWrapper` 中转（transact 以
  shell uid 执行）可用，已在 Android 11 实机验证；**Android 14+ 平台封死，服务端直接
  返回 null**，自动降级前台模式。parcel 编码两端均为 typed-object 风格
  （`readTypedObject`/`writeTypedObject`），勿用 `readParcelable`。
- **写入不受限**：任何版本前后台均可写（`OP_WRITE_CLIPBOARD` 无焦点要求）。
- Shizuku 每次重启需重新激活，模式掉线静默降级前台——已知体验问题（群页面徽章可见）。

## 触控板（M3）

Android 前台 Activity 采集 MotionEvent → 增量(dx/dy/按键/滚轮) → 专用 TCP 连接
→ PC 注入（Windows `SendInput` / macOS `CGEvent`）。目标端到端 < 20ms。
手势：单指移动、双指滚动、点按、双击拖拽。

## 桌面端数据流

```
本机剪贴板变化 ──watcher线程(轮询400ms)──> local_rx ──> 群广播(密文) + 历史 + knot://clipboard 事件
远端密文 ──WebSocket──> knot-core 解密 ──CoreEvent──> 写本机剪贴板 + 历史 + knot://clipboard 事件
发现广播 ──UDP:45691(REQ/RESP)──> knot-core scan ───> knot://discovered 事件（整轮直出）
ws 断开(成员) ──bridge take 会话──> knot://conn-lost {addr,pin,owner} ──> 前端弹窗循环
```

状态集中在 `AppState`（Android 进程级单例）/ `state.rs`（桌面）；
命令与事件是 UI 与核心层的唯一边界。

## 当前实现状态

- [x] M0 基础：协议 + 星型群 + 剪贴板轮询去重 + 桌面端 UI
- [x] M1 群管理：PIN 入群 / 移出 / UDP 发现 / AES-256-GCM 加密 / i18n（简繁英）
- [x] M2.1 Android：Kotlin/Compose 客户端，v2 协议（join/encrypted/发现/心跳），
      前台剪贴板模式 + 分享发送
- [x] M2.2a Android：Shizuku IClipboard binder 代理（10–13 后台读写）+ 1s 轮询 +
      群组前台服务（KnotForegroundService）+ 模式徽章/引导弹窗；失败自动降级前台模式
- [x] v2 群事件：转让三方握手 / 解散 0.8s 缓冲 / 30s 心跳 / 35s 看门狗 / 发现拉取式扫描
- [x] 断连弹窗机制：废除全部自动重连与群主承接，双端统一「重新加入 / 退出群」弹窗
- [x] 体验细节：剪贴板列表挂载动画、深色模式全量适配（图标/卡片/toast/液态背景）、
      液态流动背景（开屏 + 首页）、扫描入群、群二维码、多主题色板
- [ ] M2.2b Android：悬浮窗/磁贴、IME（14+ 后台读的正解）、评估迁移 UniFFI 复用 knot-core
- [ ] M3 触控板
- [ ] M4 文件传输 / ECDH+QR 配对
