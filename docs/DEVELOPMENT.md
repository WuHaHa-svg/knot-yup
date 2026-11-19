# 开发指南

## 环境要求

| 工具 | 版本 | 用途 |
|---|---|---|
| Rust | stable（≥1.75） | knot-core、桌面端 |
| Node.js | 可选（当前前端无构建步骤） | — |
| Xcode CLT | — | macOS 构建 |
| Android Studio | M2 起 | Android 端 |

Rust 工具链安装（国内推荐 rsproxy 镜像）：

```bash
export RUSTUP_DIST_SERVER=https://rsproxy.cn RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup
curl --proto '=https' --tlsv1.2 -sSf https://rsproxy.cn/rustup-init.sh | sh -s -- -y
# ~/.cargo/config.toml 已在文档仓库中说明：使用 sparse+https://rsproxy.cn/index/
```

## 构建 & 运行

```bash
# 协议核心（含单元测试）
cd knot-core && cargo test

# 桌面端（开发）
cd knot-desktop/src-tauri && cargo run

# 桌面端（发布安装包：dmg / msi）
cargo install tauri-cli --version '^2'
cargo tauri build
```

### Android 端

```bash
cd knot-android
./gradlew assembleDebug                      # 调试包
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                    # 发布包（需配签名）
```

依赖已配阿里云 Maven 镜像；gradle wrapper 用腾讯镜像分发。
`local.properties`（sdk.dir）不入库。

前端在 `knot-desktop/ui/`，纯静态文件（HTML/CSS/JS），改完刷新即生效（`cargo run` 下需重启应用，dev 模式热加载待接入）。

## 单机多实例调试

同机跑两个实例即可自测群功能（监听端口自动分配，不冲突）。
注意：UDP 发现端口（45691）单机只能绑一次，第二个实例的监听会静默重试，属预期行为。

## 代码结构导览

```
knot-core/src/
├── protocol.rs    消息定义（加密封装 seal/open）—— 与 knot-android net/Protocol.kt 镜像
├── net/           群主服务端(server.rs) + 成员客户端(client.rs)
├── crypto.rs      AES-256-GCM（PIN 派生密钥）
├── discovery.rs   UDP 拉取式发现（REQ/RESP，扫描状态机 + 群主应答）
└── clipboard.rs   剪贴板轮询 + 指纹去重

knot-desktop/src-tauri/src/
├── lib.rs         Tauri 入口 + 命令注册
├── commands.rs    Tauri 命令（create_group/join_group/leave_group/…）
├── bridge.rs      CoreEvent → knot://* 事件桥（含断连 conn-lost、剪贴板监视线程）
└── state.rs       会话/历史状态

knot-desktop/ui/                  （纯静态，无构建步骤）
├── i18n.js        三语词典 + data-i18n 静态翻译
├── app.js         装配：事件订阅（knot://*）+ 启动引导
├── home.js / group.js / common.js   首页/群页面渲染与共享组件
├── liquid.js      液态流动背景（桌面侧实现）
└── style.css      设计令牌（--accent 等 CSS 变量）与动效

knot-android/app/src/main/java/com/knotyup/android/
├── data/AppState.kt     进程级单例：会话/历史/事件处理/剪贴板监听/UI 状态流
├── net/                 Protocol.kt / GroupClient.kt / KnotGroupServer.kt / Discovery.kt
├── clipboard/           ClipboardCapability.kt（能力分层）/ ShizukuProvider.kt（binder 代理）
├── service/             KnotForegroundService.kt（入群期前台服务）
└── ui/                  screens（首页/群页/扫码/引导）+ components + Strings.kt（三语）
```

## 约定

- **术语**：产品文案统一用「群 / 群主」（group / owner），不使用「组」。
- **错误码**：跨端错误一律返回稳定错误码（见 ws-design.md §3/§7），UI 层负责本地化展示。
- **图标**：统一 SVG —— 在 `ui/index.html` 顶部的 `<symbol>` sprite 中定义（stroke 1.6–1.8 / round 风格），
  引用方式 `<svg class="icon [sm|lg]"><use href="#i-xxx"/></svg>`。不要用 emoji 或位图图标。
- **主题色**：不写死颜色值。`--accent` 由 JS（`THEMES` + `applyTheme`）设置，柔和底/悬停/阴影等
  一律用 `--accent-soft` / `--accent-hover` / `--accent-shadow` / `--accent-2`（color-mix 派生）。
  新增配色在 `ui/` 的 `THEMES` 数组里加一项即可。
- **阴影**：Android 端 `shadowElevation = 0` 是定论，勿加阴影。
- **弹窗**：任何带操作按钮的弹窗不允许点击空白处关闭（Compose 用 `DialogProperties` 关闭
  dismiss；桌面 JS 不绑 modal onclick）。
- **i18n**：所有面向用户的文案走 `t(key)`（Android `ui/Strings.kt` / 桌面 `ui/i18n.js`），
  三语词典都要补；后端只返回错误码。
- **改协议**：先改 `protocol.rs`（+ 镜像 `Protocol.kt`）并更新 `docs/ws-design.md`，保持双端一致。
