# KnotYup 开发与打包命令速查

> 所有命令的执行目录均标注在命令上方；`tauri`/`cargo tauri` 需已安装（`cargo install tauri-cli`）。

## 0. 打包脚本

统一脚本 `scripts/build.sh`，两个参数：

```bash
./scripts/build.sh <a|m> <dev|版本号>
```

- 参数一：打包平台（`a` = Android / `m` = macOS）
- 参数二：`dev` → 测试版（debug，不改版本号）；其他（如 `0.8.6`）→ 正式版（release，**自动把版本号同步写入双端真相源**，见 §8）

```bash
./scripts/build.sh a dev       # Android 测试版
./scripts/build.sh a 0.6.2     # Android 正式版 0.6.2
./scripts/build.sh m dev           # macOS 测试版（含调试日志页）
./scripts/build.sh m 0.6.2         # macOS 正式版 0.6.2（调试能力编译期剔除）
# 产物统一带时间戳放入 dist/
```

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
./gradlew installDebug && adb shell am start -n com.knotyup.android/.MainActivity
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
#   macOS:   bundle/dmg/KnotYup_<version>_aarch64.dmg (+ .app)
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
| 入口 | 软件首页右下角版号 **双击** → 系统浏览器打开（debug 版才显示，版号运行时动态读取） |
| 地址 | `http://127.0.0.1:45692`（只绑本机回环，局域网不可访问） |
| 页面功能 | 实时日志流（SSE）、分类过滤（evt / scan / act / err / app）、暂停、清空、断线自动重连 |
| 缓冲 | 后端环形缓冲 1000 条，新连接先回放历史 |

日志分类：

| 前缀 | 内容 |
|---|---|
| `discovery/scan` | 每个扫描周期（3s）结算：本轮 RESP 数、当前表内容（v2 拉取式 REQ/RESP） |
| `discovery/respond` | 本机作为群主应答发现的 RESP（建群起监听，解散/转让后停） |
| `evt/*` | 协议事件：members / clipboard / kicked / dissolve / transfer / error / 本机复制 |
| `act/*` | 本机动作：create / join / leave / kick / transfer / conn-lost（重新加入）全过程 |
| `err/*` | 失败原因：建群 / 入群 / 转让 / 重新加入超时 |

## 6. 日志埋点规范（双端）

### 桌面端（Rust）

- **`dlog(tag, text)`** 是埋点唯一入口（`lib.rs` 定义，`debug_server.rs` 实现）：
  同步、非阻塞，任何上下文可直接调用；单条截断 400 字符，进环形缓冲（1000 条）供日志页回放。
- **release 构建零成本**：`dlog` 在 `#[cfg(not(debug_assertions))]` 下是空操作，
  参数被编译器消除；`debug_server` 整模块 `#[cfg(debug_assertions)]` 门控——正式版
  不编译日志服务、不监听 45692、首页版号入口由前端 `invoke('debug_tools_enabled')`
  探测后隐藏。新增调试能力一律照此门控。
- 埋点位置约定：动作起止打 `act/*`，收到协议事件打 `evt/*`，失败路径打 `err/*`，
  发现层打 `discovery/*`（见上表）。
- `tracing::` 宏是另一条通道（终端 stdout，dev 运行时直接可见），仅用于错误/启动诊断，
  不进日志页；业务排查一律用 `dlog`。

### Android 端（Kotlin）

- **`android.util.Log`，统一 tag `KnotYup`**，`adb logcat -s KnotYup` 过滤。
  现有埋点集中在：`AppState`（会话/剪贴板/事件收发）、`GroupClient`（心跳/join 失败）、
  `KnotGroupServer`（成员连接/转让）、`ClipboardNotification`（后台通知触发）。
- 级别约定：`Log.d` 常规流程（心跳 ping、本机复制广播），`Log.i` 阶段启动，
  `Log.w` 降级/异常可恢复，`Log.e` 保留给致命错误。
- **release 日志剔除已配置**：`proguard-rules.pro` 已含 `-assumenosideeffects class android.util.Log`
  的 `v/d/i` 规则，正式版会移除这些调用；`Log.w/e` 保留，线上问题可 `adb logcat` 取证。

## 7. 多设备安装

```bash
cd knot-android && ./gradlew installDebug   # 所有已连接设备全部安装
adb devices                                  # 查看设备
adb -s <serial> shell monkey -p com.knotyup.android -c android.intent.category.LAUNCHER 1  # 指定设备启动
```

多台真机联调场景（建群/扫码/复制/踢人/转让/解散/断连弹窗）：桌面建群 + 各真机入群，
配合桌面日志页（§5）观察事件流。

## 8. 版本号管理

正式版打包时由 `scripts/build.sh` 把传入版本号**一次性同步写入以下 6 处**（无论本次打哪一端，所有版本文件始终一致）：

| # | 文件 | 位置 |
|---|---|---|
| 1 | `knot-android/app/build.gradle.kts` | `appVersion = "x.y.z"`（versionName / versionCode 由此派生） |
| 2 | `knot-desktop/src-tauri/Cargo.toml` | `package.version` |
| 3 | `knot-core/Cargo.toml` | `package.version` |
| 4 | `knot-desktop/src-tauri/Cargo.lock` | `[[package]] name = "knot-desktop"` 的 version |
| 5 | `knot-desktop/src-tauri/Cargo.lock` | `[[package]] name = "knot-core"` 的 version |
| 6 | `knot-core/Cargo.lock` | `[[package]] name = "knot-core"` 的 version |

说明：
- `tauri.conf.json` 已设 `version: null` 自动继承 Cargo.toml；UI 版号角标用 `window.__TAURI__.app.getVersion()` 动态读取。
- Android `versionCode` 按 `major*10000 + minor*100 + patch` 自动算（0.8.6 → 806）。
- Cargo.lock 只改本地包段的 version，不动 lock 文件格式版本（如 `version = 4`）。
- 发现协议靠报文前缀 `KNOTYUP2` 区分版本，无独立版本字段，也与应用版本无关。
