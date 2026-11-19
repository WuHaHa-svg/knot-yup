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
        │ 前台同步/后台  │  │ Win:SendInput│
        │ 悬浮通知       │  │ Mac:CGEvent  │
        └──────────────┘   └──────────────┘
```

- 拓扑：**星型**。群主 = WebSocket 服务端，负责成员表、移出、消息广播与密文转发。
- 发现：UDP 拉取式 REQ/RESP（比 mDNS 轻、跨平台零依赖）；广播不含 PIN。
- 加密：入群后所有业务消息套 `encrypted` 信封（AES-256-GCM，PIN 派生密钥）。
- 防回环（Android）：双闸门——clipGuard（内容哈希）+ lastSyncText（文本级回声）；
  前台写入/通知复制后都即时同步，监听器读到相同内容即静默。
- 断连策略：**无自动重连/承接**。成员与群主的 ws 断开（含心跳失联）→ 双端统一弹
  「连接已断开」弹窗（显示群主名称），用户决策「重新加入」（5s 超时判失败、弹窗重现）
  或「退出群」。
- 大文件（M4）：分块 + 背压，成员直传不经群主。

## Android 剪贴板前后台策略（minSdk 29）

**平台限制**：Android 10+ 只有焦点窗口/默认 IME 能读剪贴板，后台读不到。因此不做后台轮询，
也不依赖 Shizuku（shell uid 豁免在 Android 14+ 已被平台封死），改为前后台分流：

```
前台（App 可见）
  本机复制 → OnPrimaryClipChangedListener → 读文本 → 广播到群
  群内剪贴板 → 直接写本机剪贴板（记历史 + 同步双闸门）
后台（App 不可见，前台服务保 ws 连接）
  本机剪贴板 → 不读不传（省电、无感）
  群内剪贴板 → 悬浮通知（IMPORTANCE_HIGH，显示来源 + 内容预览）
              → 点「复制」按钮 → ClipboardCopyReceiver 写剪贴板 + 同步双闸门
```

- **写入不受焦点限制**：`OP_WRITE_CLIPBOARD` 前后台均可写，故通知复制动作在后台也能完成。
- **悬浮通知**：独立渠道 `knotyup_clipboard`（IMPORTANCE_HIGH）支持 heads-up；复用单一
  通知 ID（新消息覆盖旧的），避免后台刷屏；内容点击回 App。
- **通知权限**：`POST_NOTIFICATIONS` 已在清单声明，入群时请求；引导页清单项说明用途。
- **内置 IME**（规划）：默认 IME 全版本恒可读，是 Android 14+ 后台读的正解。

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
- [x] M2.2a Android：废除 Shizuku，前台系统回调同步 + 分享发送；群组前台服务
      （KnotForegroundService）保 ws；后台新剪贴板悬浮通知（一键复制，双闸门防回环）
- [x] v2 群事件：转让三方握手 / 解散 0.8s 缓冲 / 30s 心跳 / 35s 看门狗 / 发现拉取式扫描
- [x] 断连弹窗机制：废除全部自动重连与群主承接，双端统一「重新加入 / 退出群」弹窗
- [x] 体验细节：剪贴板列表挂载动画、深色模式全量适配（图标/卡片/toast/液态背景）、
      液态流动背景（开屏 + 首页）、扫描入群、群二维码、多主题色板
- [ ] M2.2b Android：悬浮窗/磁贴、IME（14+ 后台读的正解）、评估迁移 UniFFI 复用 knot-core
- [ ] M3 触控板
- [ ] M4 文件传输 / ECDH+QR 配对
