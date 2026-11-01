# KnotYup 开发与打包命令速查

> 所有命令的执行目录均标注在命令上方；`tauri`/`cargo tauri` 需已安装（`cargo install tauri-cli`）。

## 1. 启动桌面端 dev

```bash
cd knot-desktop
cargo tauri dev
```

- 首次编译较慢，之后增量编译；`src-tauri/` 下 Rust 代码改动自动重编译重启，`ui/` 静态资源即时生效。
- dev 构建**自带调试能力**（见第 5 节）。
- 日志：终端直接输出 tracing 日志；浏览器日志页见第 5 节。

## 2. 启动 Android dev

```bash
cd knot-android
./gradlew installDebug        # 构建 + 安装到已连接设备
adb shell am start -n com.knotyup.android/.MainActivity   # 启动
```

常用配套：

```bash
adb logcat -s KnotYup        # 查看 Android 端日志（tag: KnotYup）
adb install -r app/build/outputs/apk/debug/app-debug.apk  # 只重装不重建
```

## 3. 打包桌面正式版

```bash
cd knot-desktop
cargo tauri build
# 产物：knot-desktop/src-tauri/target/release/bundle/
#   macOS:   bundle/dmg/KnotYup_0.1.0_aarch64.dmg (+ .app)
#   Windows: bundle/nsis/*.exe / bundle/msi/*.msi（需在 Windows 上打包）
```

- **正式版（release）没有任何调试功能**：调试日志服务、45692 监听、日志入口全部 `#[cfg(debug_assertions)]` 编译期剔除——不监听端口、不执行调试代码、首页无版号入口。

### 打包桌面测试版（带调试能力）

```bash
cd knot-desktop
cargo tauri build --debug
```

- 与正式版同产物路径，但保留调试日志页与监听（用于真机分布式问题排查）。

## 4. 打包 Android 正式版

```bash
cd knot-android
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
# 签名：knotyup.keystore（已 gitignore；发布前建议把 build.gradle.kts 中的明文密码改为环境变量读取）
```

## 5. 调试日志监听（仅 dev / `--debug` 构建）

| 项 | 说明 |
|---|---|
| 入口 | 软件首页右下角版号 `v0.1.0` **双击** → 系统浏览器打开 |
| 地址 | `http://127.0.0.1:45692`（只绑本机回环，局域网不可访问） |
| 页面功能 | 实时日志流（SSE）、分类过滤（evt / scan / act / err / app）、暂停、清空、断线自动重连 |
| 缓冲 | 后端环形缓冲 1000 条，新连接先回放历史 |

日志分类：

| 前缀 | 内容 |
|---|---|
| `discovery/scan` | 每个扫描周期（5s）结算：收到几个广播、当前表内容 |
| `discovery/announce` | 本机作为群主的每次广播（建群突发 5 连发 + 每 2.5s 常规） |
| `evt/*` | 协议事件：members / clipboard / kicked / owner-change / error / 本机复制 |
| `act/*` | 本机动作：create / join（含预留承接端口）/ leave / kick / promoted / reconnect 全过程 |
| `err/*` | 失败原因：建群 / 入群 / 承接群主 / 重连 |

## 6. knot-debug CLI（协议调试工具，独立于正式端）

```bash
cd knot-debug && cargo build

# 虚拟成员入群：打印全部协议事件；stdin 输入一行 = 发送剪贴板
./target/debug/knot-debug join <ip:port> --pin <PIN> [--name 名字]

# 虚拟群主（同时发 UDP 广播，真机发现列表可见）
./target/debug/knot-debug serve --pin <PIN> [--port N] [--name 名字]

# 监听局域网发现广播（验证发现 / 突发）
./target/debug/knot-debug watch
```

典型三人场景（踢人 / 转让 / 选主）：真机 A 建群 → 两个终端各跑一个 `join` 扮演 B/C，配合桌面日志页双视角观察。
