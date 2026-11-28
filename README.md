# KnotYup

> 把设备打成一个结 —— 局域网内的 Win / macOS / Android 剪贴板共享 + 文件共享 + Android 触控板 + 自由组群

在同一 Wi-Fi 下，任意多台设备组成一个「**群**」：任何设备可以创建群或加入群（PIN 码），群主可以移出成员、转让群主。群内实时同步剪贴板；手机可以作为电脑的无线触控板（macOS/Windows 均已支持注入）。文件共享已实现，见 [docs/file-sharing.md](docs/file-sharing.md)。

## 功能

- **剪贴板同步**：Windows、macOS、Android 之间实时共享文本（已完成）
- **文件共享**：每台设备一个持久共享文件夹（群内一级按设备名分组、二级是文件）；入群即上报文件夹清单，文件从所属设备 P2P 直取。下载为纯本地行为（只落盘，不自动共享、不成为供种者）；想让文件对群可见须主动再次共享。传输中心支持进度/取消（见 [docs/file-sharing.md](docs/file-sharing.md)）
- **局域网发现**：扫描端广播请求、群主应答（UDP 拉取式），附近设备可见，输入 PIN 即加入
- **群管理**：创建/加入/退出、群主移出成员、群主三方握手转让、解散
- **通信加密**：入群后业务消息以 AES-256-GCM 加密（PIN 派生密钥）
- **国际化**：简体中文 / 繁體中文 / English
- **Android 触控板**：单指移动/双击/拖拽/双指滚动/数字键盘/调度中心手势；macOS 用 CGEvent、Windows 用 SendInput 注入（见 [docs/touchpad.md](docs/touchpad.md)）

## 快速开始（桌面端）

```bash
cd knot-desktop/src-tauri
cargo run        # 或 cargo install tauri-cli && cargo tauri dev
```

1. 设备 A 点「创建群」→ 得到 `IP:端口` + 6 位 PIN（可一键复制）
2. 设备 B 在「加入群」卡片里等待几秒，点击自动出现的群，输入 PIN 加入
3. 任一设备复制文本 → 全群同步，右侧面板可查看与回溯历史

## 架构

```
┌────────────── 局域网 ──────────────────────────────┐
│  发现：成员广播 REQ → 群主单播 RESP（UDP 45691）      │
│  通信：设备A(群主) ── WebSocket(星型) ──> 成员B / C  │
│         成员表 · 踢出 · 转让 · 加密广播剪贴板         │
│  文件：设备级共享文件夹，入群上报清单 → P2P 直取  │
│        下载为纯本地行为（不自动共享/不成为供种者）  │
└────────────────────────────────────────────────────┘
```

详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（架构）、[docs/ws-design.md](docs/ws-design.md)（协议）、[docs/file-sharing.md](docs/file-sharing.md)（文件共享设计）、[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)（开发指南）。

## 技术栈

| 层 | 选型 |
|---|---|
| 协议核心 | Rust crate（协议、群管理、加密、发现、剪贴板），桌面端直接使用 |
| Windows / macOS | Tauri 2（Rust + 无构建静态前端）；触控板注入 Win=SendInput / macOS=CGEvent |
| Android | Kotlin + Jetpack Compose，Kotlin 镜像实现协议（UniFFI 复用为 TODO） |
| 通信 | WebSocket(TCP) + JSON 消息 + AES-256-GCM；UDP 拉取式发现 |

## 平台约束（Android 剪贴板）

Android 10+ 禁止后台应用读取剪贴板，KnotYup 因此按前后台分层处理：

- **前台**：系统剪贴板变化回调自动同步（写入/读取均可用），另可从任意 App 分享文本到群
- **后台**：不读不写剪贴板；收到群内新剪贴板时发**悬浮通知**（含来源与内容预览），点通知里的「复制」按钮才写入剪贴板

内置 IME（后台读的正解）与悬浮窗/快捷磁贴为 TODO（见路线图 M2.2b）。

## 路线图

- [x] **M0 基础**：Rust core、桌面端、双机文本剪贴板互通
- [x] **M1 群管理**：创建/加入、PIN、移出、局域网发现、AES-GCM 加密、i18n
- [x] **v2 群事件**：拉取式发现、30s 心跳/看门狗、群主三方握手转让、解散缓冲、断连弹窗
- [x] **M2.1 Android**：加入群（发现列表/手动地址）、加密同步、前台剪贴板模式、分享发送、主题色/语言
- [x] **M2.2a Android**：群组前台服务 + 后台新剪贴板悬浮通知（一键复制）
- [x] **M3 触控板**：触摸采集 → 鼠标注入（macOS CGEvent / Windows SendInput）、手势、数字键盘、调度中心；首次使用引导页
- [x] **M4 文件共享**：设备级持久共享文件夹（fileList 内嵌成员表）+ P2P 分块直传 + 下载进度通知 + 传输中心（进度/取消），桌面/安卓群页面文件 tab
- [x] **打包脚本**：`scripts/build.sh <a|m> <dev|版本号>`，正式版自动同步双端 6 处版本号，产物入 dist/
- [ ] **M2.2b Android（TODO）**：悬浮窗/快捷磁贴、内置 IME（14+ 后台读正解）、评估迁移 UniFFI 复用 knot-core
- [ ] **M5 打磨（TODO）**：图片预览、防休眠、Windows 桌面打包
- [ ] **其它 TODO**：ECDH+QR 配对（现为 PIN 派生密钥）、震动调教体系、触控板一对一占用状态、ping/pong 延迟测量、ws 信令背压、剪贴板大文本分片

> 已废弃/不做：数字键盘运算符键（+-*/Enter）、三指手势、底部上滑唤出键盘、断点续传、多选上传、防休眠 WakeLock 按需唤醒。

## 仓库结构

```
knot-yup/
├── knot-core/       Rust 协议核心（桌面端直接使用）
├── knot-desktop/    Tauri 2 桌面客户端（Win/macOS）
│   ├── src-tauri/   Rust 后端
│   └── ui/          前端（无构建静态文件，含 i18n）
├── knot-android/    Android 客户端（Kotlin + Compose）
└── docs/            文档
```

### Android 端

```bash
cd knot-android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

手机与电脑连同一 Wi-Fi：电脑「创建群」→ 手机打开 KnotYup，发现的群点一下、输 PIN 加入。
App 在前台时自动同步剪贴板；也可从任意 App 分享文本到 KnotYup 发送到群。
