# 文件共享设计（M4）

> 状态：**设计定稿，待实现**。本文是文件共享功能的唯一设计来源，实现前先读本文与 [ws-design.md](ws-design.md)。
> 依赖：v2 群组协议（发现 / 入群 / 心跳 / 加密信封）。

## 1. 核心概念：设备文件夹 = 供种者

整个设计建立在一个等式上：**文件在谁的文件夹里，谁就是它的供种者**。群里看到的文件列表，就是各设备文件夹的并集，按设备名分组——不需要单独的 `owner` / `seeders` 字段。

```
群文件列表（群内同步的唯一信息）
├─ 设备 A 的文件夹
│   ├─ a.zip       ← 原始上传
│   └─ doc.pdf
├─ 设备 B 的文件夹
│   ├─ a.zip       ← 从 A 下载所得，B 现在也能供种
│   └─ photo.png
└─ 设备 C 的文件夹
    └─ song.mp3
```

同一 `a.zip` 在 A、B 各显示一条，且**各自持有独立的 file_id**（下载所得就是另一份文件，即使 sha256 相同也不沿用来源 id）——**重复展示是明确接受的 UI 语义**，正好提供冗余：A 不可达就点 B 的那条。群内同步的**始终是文件列表**（按设备分组），不是文件本体、不是聚合视图。

### 两层结构

| | **本地共享清单**（设备级、持久） | **群文件列表**（群级、会话态） |
|---|---|---|
| 存什么 | 本设备文件夹内的文件（元数据 + 路径） | 各设备上报的清单，按 `device_id` 分组 |
| 在哪 | 设备磁盘 + 一份持久化清单 | 每个成员各缓存一份，群主汇总广播 |
| 生命周期 | **永久**，与群无关 | 随成员进出群增减 |
| 离群后 | 文件与清单一动不动 | 该设备整个文件夹条目移除 |
| 再加群 | 内容还在 | **入群全量上报**，自动恢复 |

**用户确认的语义（不可推翻）**：
1. 文件夹跟设备走、不跟群走——**跨群可见**：加入任何群都会上报自己文件夹的全部内容。
2. UI 一级目录是**设备名文件夹**，点进去才是文件；重复文件按各自所在文件夹分别显示。
3. 下载完成 = 往**自己文件夹**加一条记录，随即成为该文件的供种者（无需单独 seed 信令）；供种服务设备级常驻，不随离群停止。
4. 只记路径、不复制；**删除只移清单条目，绝不删磁盘真实文件**；只能删自己文件夹内的。
5. 上传者离群 → 其整个文件夹条目从群列表消失，本地文件保留，下次加群自动恢复。

## 2. 目标与非目标

**目标**：群内按设备浏览文件；点某设备文件夹下的文件即从该设备 P2P 拉取；下载后自己文件夹自动多一条、成为新供种者；跨群共享同一套本地文件夹。

**非目标（本期）**：广域网 / NAT 穿透（仅局域网）；端到端前向保密（ECDH+QR 属 M5）；文件在线预览 / 秒传去重 / sha256 跨设备合并。

## 3. 总体架构

```
控制面（小信令，走群主 ws 加密广播）     数据面（文件本体，成员 P2P）
  file_announce  入群全量上报文件夹       供种者起 TCP 文件服务
  file_list      群主下发全量快照(新成员)  下载者连该文件夹所属设备的 file_addr
  file_add       文件夹新增文件           分块 + AES-GCM + sha256 校验
  file_remove    文件夹删除文件(批量 id)
```

- **群主**：只做信令路由 + 汇总广播，**零文件字节中转**、不持久化列表。
- **每个设备**：文件夹里有文件就可供种，同时也能下载。
- **群主转让**：无需搬文件——文件都在各自设备；各成员靠 `file_announce` 重建列表。

## 4. 数据模型

### 4.1 设备侧持久清单（`SharedFile`）

```
SharedFile {
  file_id:  uuid,          // 全局唯一；上传和下载完成都各自生成新 id，不沿用来源文件
  name:     "a.zip",
  size:     1048576,
  sha256:   "…",           // 仅用于下载后完整性校验（不用于跨设备合并、不决定 id）
  mime:     "application/zip",
  path:     "/Users/me/Shared/a.zip",  // 本地路径（仅自己用，不上报）
  added_at: 1760000000000,
}
```

- **共享路径无限制**：可共享本机任意位置文件，清单只记路径，不复制、不搬移。
- **下载落盘**：桌面端每次下载**询问保存路径**；Android 固定公共 `Download/knotShare/`（见 §9）。下载完成后把该文件（元数据 + 落盘路径）写入**自己清单**。
- 清单持久化：`~/KnotYup/shared/index.json`（桌面）/ App 私有目录（Android）。
- **上传** = 选任意本地文件 → 算 sha256 → 写入自己清单 → 广播 `file_add`。
- **下载完成** = 落盘 → **生成新 file_id**（不沿用来源文件）→ 写入自己清单 → 广播 `file_add`。两份文件（A 的原件与 C 的下载件）是两个独立身份。
- **删除** = 只从自己清单移除条目，**磁盘文件一律不动**；不能删别人文件夹的。
- 路径失效（原文件被移走）：UI 标注"文件不可达"，该条不供种；不自动删条目。

### 4.2 群侧文件列表（`Map<device_id, SharedFile[]>`）

```
{
  "<A.id>": [SharedFile(a.zip), SharedFile(doc.pdf)],
  "<B.id>": [SharedFile(a.zip), SharedFile(photo.png)],
  "<C.id>": [SharedFile(song.mp3)],
}
```

- **没有 `owner`、没有 `seeders`**：条目的 key 就是拥有者/供种者；`a.zip` 出现在 A、B 两个 key 下，两台都能供种。
- 每个设备文件夹内按 `added_at` 排序。
- 同一 sha256 在不同设备文件夹下是**两条独立记录、各自独立 file_id**，不做合并。

## 5. 控制面信令（走 ws，加密信封）

在 [ws-design.md](ws-design.md) §3 消息表登记，共 4 条：

| 消息 | 方向 | 载荷 | 用途 |
|---|---|---|---|
| `file_announce` | 成员→群主→全员 | `{from, files:[SharedFile…]}` | **入群/重连时全量上报**自己文件夹（全量替换该设备的条目） |
| `file_add` | 任意→群主→全员 | `{from, file:SharedFile}` | 自己文件夹新增（上传**或**下载完成，共用此条） |
| `file_remove` | 任意→群主→全员 | `{from, file_ids:[uuid…]}` | 删除自己文件夹内的一条或多条（仅移清单，不动磁盘） |

**新成员如何拿到已有文件列表**（场景 1 的空白，已定）：群主维护完整 `Map<device_id, SharedFile[]>`。B 入群成功后，群主**立即下发一条加密的全量列表快照**给 B（B 并入自己的数据即得全群视图），后续增量靠广播。新成员不需要等老成员逐个重报。

> 为什么不放进 `join_result`：`join_result` 是**明文握手消息**（此时尚未建立加密上下文），而文件列表是业务数据。塞进明文会让文件名暴露给局域网嗅探者，违反 ws-design.md §1「业务消息全部装 `encrypted` 信封」的约定。落地为"紧随 `join_result` 的加密全量快照"——对 B 而言同样是入群即拿到完整 Map，用户视角无差别。实现可用一条 `file_announce` 的响应变体或专用 `file_list`，加入 `encrypted` 信封即可。

- 群主收到任一 file_* 消息 → 更新 Map 对应 key → 广播给全员；各端按 `from` 更新/替换对应设备的文件夹条目。
- 群主不持久化到磁盘（会话内缓存）；新群主靠各成员 `file_announce` 重建。
- **离群无需任何文件信令**：各端收到 `members` 广播后发现某设备已不在成员表，**自行推演**删除 `Map` 中该设备的整个文件夹条目；本地磁盘与清单不动。
- **删除走独立 `file_remove`**：全量 `file_announce` 只用于汇报"我现在有什么"，删除明确发 `{from, file_ids[]}`（带设备 + 待删 id 数组），避免用"全量汇报里少了某条"来隐式推断删除。
- **已删（设计简化）**：`file_seed`（下载完成复用 `file_add`）、`file_req`（点谁的文件直接连谁，无需请求）、`owner`/`seeders` 字段（文件夹归属已表达）。

## 6. 数据面协议（P2P 直连）

### 6.1 地址与端口

- 新增 `DeviceInfo.file_addr: Option<String>`（`ip:port`）——文件服务地址，与 ws 端口、standby 转让端口各自独立。
- 入群时随 `join`/`ping` 上报；从 `members`/`pong` 快照取目标设备地址。
- 地址不可达 → UI 提示"该设备不在线"；用户可改点另一设备文件夹下的同一文件（冗余）。

### 6.2 连接与传输

```
下载者：在某设备文件夹下点文件 → 取该设备的 file_addr → connect
供种者：accept → 校验首帧 token → 按 src_file_id 读本地文件 → 分块发送
下载者：逐块解密 + 写临时文件 → sha256 校验 → 原子改名 → 生成**自己的新 file_id** → 写入自己清单 → 广播 file_add
```

- **两个 id 要分清**：数据面首帧的 `src_file_id` 是**供种者文件夹里那条的 id**（用它定位要读的本地文件）；下载落盘后 C 生成**全新的 `file_id`** 写入自己清单并广播——A 的原件与 C 的下载件是两个独立文件身份。
- **认证首帧**：`{src_file_id, from, to, token}`，`token = HMAC(PIN 派生 key, src_file_id|from|to)`，防端口扫描蹭传。
- **加密**：每块独立 AES-256-GCM，nonce 12 字节随块下发；复用 `Cipher` 密钥体系（独立 nonce 空间）。
- **块格式**（`CHUNK_SIZE = 64 KiB`，双端常量）：
  ```
  [u32 BE idx][u32 BE len][12B nonce][AES-GCM 密文(len)]
  ```
- **校验**：整文件 sha256；不符 → 删临时文件 + 报错，不入库。
- **落盘**：先写 `file_id.partial`，校验通过 rename 为正式名（避免半文件）。

### 6.3 并发、背压与续传

- **并发限制**：供种者同一时刻只服务 **1 个下载者**，后续 FIFO 排队；**不提供限速开关**。
- 停等式 ACK（发一块等一块）首版足够可靠；TCP 原生背压兜底。
- 断点续传：临时文件 + 已收块位图；重连发 `resume_from`，从缺失块续传（二期完善跨进程持久化）。
- 看门狗：传输期间控制面照常 30s 心跳，数据流量不替代心跳。

## 7. 双端 UI

### 7.1 桌面端：群页面右侧面板 tab 化

- 现状：`index.html` 的 `.clip-panel` 单面板，`group.js` 的 `renderClipboard()`。
- 改造：右侧面板加 tab 头（**剪贴板 / 文件**），下方内容区切换。
  - 剪贴板 tab：沿用 `clipList` 现有渲染。
  - 文件 tab：**一级目录 = 各设备名文件夹**（按 `members` 顺序），点开某设备文件夹 → **二级 = 该设备的文件列表**（名称 / 大小 / 时间）。点文件 → 选保存路径 → 从该设备下载；提供"共享文件"按钮（选本地文件登记）。
- 改动文件：`ui/index.html`（结构 + symbol 图标）、`ui/group.js`（tab 切换 + 两级文件列表渲染）、`ui/style.css`、`ui/i18n.js`（三语）、`src-tauri` 命令与事件桥。

### 7.2 安卓端：悬浮 tab 栏从 2 项到 3 项

- 现状：`GroupScreen.kt` 的 `HorizontalPager { 2 }` + `GroupTabBar` 2 段（群信息 / 剪贴板）。
- 改造为 3 段：**群信息 / 剪贴板 / 文件**。
  - `rememberPagerState { 3 }`；色块 `visualPos` clamp 上界改 `2 - curPage`；松手吸附 `roundToInt().coerceIn(0, 2)`。
  - tab 栏容器加宽容纳 3 项；`onDragStart` 落点换算 segW 用新宽度重算（按下即跟手逻辑保留）。
  - 新增 `FileTab.kt`：**一级设备文件夹列表 → 二级文件列表**；共享 / 下载进度。
- 下载到 `MediaStore.Downloads` 公共目录；传输期复用 `KnotForegroundService` 保活 + 进度通知。

## 8. 双端接入点

### knot-core（Rust）
| 文件 | 改动 |
|---|---|
| `protocol.rs` | `Message` 加 `FileAnnounce/FileAdd/FileRemove`；`SharedFile` 结构；`DeviceInfo` 加 `file_addr` |
| `file.rs`（新增） | 数据面：`FileSeeder::serve()` / `FileDownloader::fetch()`、分块编解码、sha256、partial 落盘 |
| `crypto.rs` | 加按块加解密辅助（独立 nonce，不序列化 JSON） |
| `net.rs` | `CoreEvent` 加文件事件 |
| `net/server.rs` | 转发 file_* 信令（同 clipboard 转发路径） |

### 桌面（Tauri）
| 文件 | 改动 |
|---|---|
| `commands.rs` | `share_file(path)` / `remove_files(file_ids)` / `download_file(src_device_id, src_file_id, dest)` / `local_files()` |
| `bridge.rs` | CoreEvent → `knot://file-*`；入群时调 `file_announce` |
| `state.rs` | 群文件列表 `Map<device_id, SharedFile[]>` + 本地清单 + 传输任务 |
| `ui/*` | 见 §7.1 |

### Android（Kotlin）
| 文件 | 改动 |
|---|---|
| `net/Protocol.kt` | 镜像 Rust 消息与 SharedFile（**逐字段一致**） |
| `file/`（新增） | `FileIndex`（本地清单持久化）/ `FileService`（seeder / downloader） |
| `data/AppState.kt` | 文件事件处理、入群 announce、传输状态流 |
| `ui/screens/FileTab.kt` | 两级文件 tab UI；SAF 选文件 / 存公共 `Download/knotShare/` |
| `ui/screens/GroupScreen.kt` | pager 2→3、tab 栏 3 段（见 §7.2） |

## 9. 平台差异

| 项 | 桌面 | Android |
|---|---|---|
| 选文件 | Tauri dialog，任意路径 | `ACTION_OPEN_DOCUMENT`（SAF），任意文件 |
| 存文件 | **每次下载询问保存路径** | **固定公共 `Download/knotShare/`**（见下方说明） |
| 后台供种 | 进程常驻即可 | 复用现有 `KnotForegroundService`（入群期间常驻、`START_STICKY`、类型 `connectedDevice`），入群即供种；WakeLock 按需 |
| 文件服务 | tokio `TcpListener` | `ServerSocket` + 协程 |
| 地址 | `Discovery.localIpAddress()` | 同左 |

**Android 下载落盘（关键）**：必须写入**用户文件管理器可见的公共 Download 目录**，而非
`Android/data/<pkg>/...` 这种 app-private 路径。用 `MediaStore.Downloads` + `ContentValues`：
`RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/knotShare"`、`DISPLAY_NAME = 文件名`、
`MIME_TYPE`、`IS_PENDING = 1`；写入流后置 `IS_PENDING = 0` 提交。Android 10+ 无需存储权限，
无需 `requestLegacyExternalStorage`。

## 10. 安全

- 控制面信令套 `encrypted` 信封。
- 数据面首帧 `token = HMAC(PIN 派生 key, …)` 认证，防蹭传。
- 每块 AES-GCM 独立 nonce；整文件 sha256 校验。
- 文件服务**设备级常驻**（首次入群启动、离群不停、入新群只更新 PIN、端口不变），仅局域网可达。
- **隐私边界**：跨群可见是明确语义——加入任何群都会上报自己文件夹清单。用户需知晓其共享文件夹对所有加入的群开放。

## 11. 分期计划

| 阶段 | 范围 |
|---|---|
| **M4.1 单文件直传** | 共享任意本地文件 → 群列表（两级目录）→ P2P 下载 → 下载完成写入自己文件夹并 `file_add`；桌面↔桌面 + 桌面↔Android；停等式 ACK + sha256 |
| **M4.2 持久化与续传** | 本地清单持久化 + 入群全量 announce；断点续传；文件不可达标注 |
| **M4.3 体验** | 下载进度通知、文件历史、多文件 |

## 12. 测试要点

- 集成测试（knot-core）：announce→add 全链路；设备离群其文件夹条目移除；重入群 announce 恢复；删除权限（只能删自己文件夹的）；HASH_MISMATCH；token 认证失败断开；同一文件在两台设备文件夹各一条。
- 双端一致性：消息字段与块格式双端逐字一致。
- 真机场景：两台真机 + 桌面建群，跨 Android 13/15 与 macOS 互传；A→B 下载后 B 文件夹可见该文件，C 能从 B 下载；上传者离群/重入群；跨群加入后文件可见；UI 两级目录（设备文件夹 → 文件）。

## 13. 已确认决策（用户拍板）

1. **删除不删真实文件**：`file_remove` 只移清单条目，磁盘文件一律不动。
2. **共享路径无限制**：可共享本机任意文件；桌面下载每次询问路径，Android 固定公共 `Download/knotShare/`。
3. **无大小上限、无磁盘预检**。
4. **并发上限 1**：同一时刻只服务 1 个下载者，后续排队；不提供限速开关。
5. **Android 后台供种**：复用现有前台服务（`KnotForegroundService`，入群期间常驻），不新增常驻服务。
6. **去掉 `owner` / `seeders` / `file_seed` / `file_req`**：文件夹归属即供种者；下载完成写入自己文件夹即成为供种者，复用 `file_add`。
7. **群内同步的始终是文件列表**（按设备分组）；UI 一级为设备名文件夹、二级为文件；重复文件按各自文件夹分别显示（接受重复）。
8. **新成员拿列表**：群主维护完整 Map，B 入群成功后立即收到一条**加密的全量列表快照**并并入自己的数据（不放明文 `join_result`，避免文件名泄露；见 §5）。
9. **下载所得用全新 file_id**：不沿用来源文件；`src_file_id`（供种者侧，定位读文件）与 C 新生成的 `file_id` 是两个身份。
10. **离群靠各端推演**：收到 `members` 后删除该设备整个文件夹条目，无专用文件信令。
11. **`file_announce` 全量 + `file_remove` 独立删除**：announce 只汇报"我现在有什么"（全量替换）；删除明确发 `{from, file_ids[]}`，不靠全量差异隐式推断。
