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
├── protocol.rs    消息定义（加密封装 seal/open）—— 协议唯一事实来源
├── net.rs         群主服务端 + 成员客户端（WebSocket 星型）
├── crypto.rs      AES-256-GCM（PIN 派生密钥）
├── discovery.rs   UDP 广播发现（群主 announce / 监听 listen）
└── clipboard.rs   剪贴板轮询 + 指纹去重

knot-desktop/src-tauri/src/lib.rs
    Tauri 命令（create_group/join_group/…）+ 事件桥（knot://*）+ 剪贴板监视线程

knot-desktop/ui/
├── i18n.js        三语词典 + data-i18n 静态翻译
├── app.js         状态渲染 + Tauri 事件订阅
└── style.css      设计令牌（--accent 等 CSS 变量）与动效
```

## 约定

- **术语**：产品文案统一用「群 / 群主」（group / owner），不使用「组」。
- **错误码**：跨端错误一律返回稳定错误码（见 PROTOCOL.md），UI 层负责本地化展示。
- **图标**：统一 SVG —— 在 `ui/index.html` 顶部的 `<symbol>` sprite 中定义（stroke 1.6–1.8 / round 风格），
  引用方式 `<svg class="icon [sm|lg]"><use href="#i-xxx"/></svg>`。不要用 emoji 或位图图标。
- **主题色**：不写死颜色值。`--accent` 由 JS（`THEMES` + `applyTheme`）设置，柔和底/悬停/阴影等
  一律用 `--accent-soft` / `--accent-hover` / `--accent-shadow` / `--accent-2`（color-mix 派生）。
  新增配色在 `app.js` 的 `THEMES` 数组里加一项即可。
- **i18n**：所有面向用户的文案走 `t(key)`（`ui/i18n.js`），三语词典都要补；后端只返回错误码。
- **UI**：简洁、动效优雅克制（统一 `cubic-bezier(.22,1,.36,1)`）；新组件先看 `style.css` 已有的设计令牌。
- **改协议**：先改 `protocol.rs` 并更新 `docs/PROTOCOL.md`，保持三端一致。
