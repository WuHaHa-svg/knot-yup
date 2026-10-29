# KnotYup

> 把设备打成一个结 —— 局域网内的 Win / macOS / Android 剪贴板共享 + Android 触控板 + 自由组群

在同一 Wi-Fi 下，任意多台设备组成一个「**群**」：任何设备可以创建群或加入群（PIN 码），群主可以移出成员。群内实时同步剪贴板；手机可以作为电脑的无线触控板（开发中）。

## 功能

- **剪贴板同步**：Windows、macOS、Android 之间实时共享文本（图片/文件规划中）
- **局域网自动发现**：创建群后附近设备可直接看到，输入 PIN 即加入
- **群管理**：创建/加入/退出，群主可移出成员
- **通信加密**：入群后消息以 AES-256-GCM 加密（PIN 派生密钥）
- **国际化**：简体中文 / 繁體中文 / English
- **Android 触控板**：规划中（M3）

## 快速开始（桌面端 M1）

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
│  设备A(群主) ── UDP 广播发现 ──> 设备B / Android    │
│      │                                              │
│      └─ WebSocket(星型) ──> 成员B ── 成员C ─ …      │
│         成员表 · 踢出 · 加密广播剪贴板               │
└────────────────────────────────────────────────────┘
```

详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（架构）、[docs/PROTOCOL.md](docs/PROTOCOL.md)（协议）、[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)（开发指南）。

## 技术栈

| 层 | 选型 |
|---|---|
| 协议核心 | Rust crate（协议、群管理、加密、发现、剪贴板），三端复用 |
| Windows / macOS | Tauri 2（Rust + 无构建静态前端） |
| Android | Kotlin + Jetpack Compose，UniFFI 调 Rust 核心（M2） |
| 通信 | WebSocket(TCP) + JSON 消息 + AES-256-GCM；UDP 广播发现 |

## 平台约束（Android 剪贴板）

Android 10+ 禁止后台应用读取剪贴板。KnotYup 采用能力分层：

1. **Shizuku**（首选）：shell uid 代理 `IClipboard`，后台读写
2. **内置 IME**（次选）：用户启用后合规读写
3. **前台模式**（保底）：前台 App / 悬浮窗 / 分享 + 快捷磁贴

## 路线图

- [x] **M0 基础**：Rust core、桌面端、双机文本剪贴板互通
- [x] **M1 群管理**：创建/加入、PIN、移出、局域网发现、AES-GCM 加密、i18n
- [ ] **M2 Android**：加入群、剪贴板收发（Shizuku / IME / 悬浮窗降级链）
- [ ] **M3 触控板**：触摸采集 → 鼠标注入、手势、延迟调优
- [ ] **M4 打磨**：图片/文件传输、断线重连、群主迁移、防休眠、打包分发

## 仓库结构

```
knot-yup/
├── knot-core/       Rust 协议核心（可被三端复用）
├── knot-desktop/    Tauri 2 桌面客户端（Win/macOS）
│   ├── src-tauri/   Rust 后端
│   └── ui/          前端（无构建静态文件，含 i18n）
├── knot-android/    Android 客户端（M2，规划中）
└── docs/            文档
```
