# 传输任务中心设计

> 状态：**设计稿，待评审后实现**。本文是「传输任务中心」的唯一设计来源。
> 依赖：v2 群组协议（文件共享见 [file-sharing.md](file-sharing.md)、ws 消息见 [ws-design.md](ws-design.md)）。

## 1. 定位与已确认决策

传输任务中心 = **群主维护的一个全局传输任务数组**，记录本群内正在/曾经发生的文件传输，全群设备可见。它是此前撤销的 `SharedFile.status` 的替代品——状态不再绑在文件上（避免"谁能改别人文件状态"的语义困境），而是独立成群主权威维护的任务流。

已拍板（不可随意推翻）：

| 维度 | 决策 |
|---|---|
| 归属 | **群主维护、全群可见**；任务数组独立于文件清单 |
| 范围 | 下载 + 供种两类（同一次传输是一条任务，含下载方与供种方） |
| 持久化 | **不持久化**，随群重建；群主在线期间一直维护 |
| 生命周期 | 任务**保留到群解散**（完成/失败也留着，不自动移除） |
| 进度上报 | **节流**：仅整数百分比变化时上报（一个文件约 100 条） |
| UI | **文件页内分区**（不新增 tab/页），只读 |
| 操作 | **只读监控**，无取消、无重试 |

## 2. 核心概念：一次传输 = 一条任务

一条 `TransferTask` 同时表达下载与供种两个视角——它记的是"谁从谁那里传哪个文件"，而不是某台设备私有的"我的上传"或"我的下载"。

```
群主维护的任务数组（全群共享，明文同步）
├─ task#1  a.zip      seeder=设备B  downloader=设备A  32%  transferring
├─ task#2  doc.pdf    seeder=设备A  downloader=设备C  100% done
└─ task#3  photo.png  seeder=设备C  downloader=设备B  ——  failed(HASH_MISMATCH)
```

- 设备 A 看 task#1 是「下载中」（我是 downloader）；设备 B 看同一条是「上传中/供种中」（我是 seeder）——**一条记录，两种视角，UI 按 `seeder_id == 我 ? 上行 : 下行` 渲染**。
- 这样避免了"同一次传输记两条（上传一条、下载一条）"的重复。

## 3. 数据模型（双端逐字段一致）

```
TransferTask {
  task_id:       uuid,            // 任务唯一 id，下载方发起时生成
  file_id:       uuid,            // 对应的 SharedFile.file_id（快照，非引用）
  name:          "a.zip",         // 文件名快照
  size:          10485760,        // 总字节
  mime:          "application/zip",
  seeder_id:     "<device id>",   // 供种者（文件所在设备）
  downloader_id: "<device id>",   // 下载者
  status:        transferring | done | failed,
  progress:      0..100,          // 整数百分比
  transferred:   3355443,         // 已传字节（UI 可显示 3.2/10 MB）
  error:         "",              // 失败原因（status=failed 时有值）
  created_at:    ms,              // 任务创建时间
  updated_at:    ms,              // 最近一次进度/状态变化时间
}
```

- **快照语义**：`name/size/mime` 是发起时的文件元数据副本。文件后来被删或改名，历史任务记录不变。
- **状态枚举只有三态**：`transferring`（传中）、`done`（完成）、`failed`（失败）。
  - 排队（`queuing`）**不在本期**：供种端 `Semaphore(1)` 的排队发生在内核，可见化需要额外协议，列为未来扩展。
- **无 status 字段污染 SharedFile**：任务与文件清单彻底解耦，撤销 status 的决定保持不变。

## 4. ws 协议（明文；群主权威）

新增 4 条消息，均**明文**（沿用"只剪贴板加密"的既定策略）。群主是唯一写入者。

### 4.1 消息表

| 消息 | 方向 | 载荷 | 用途 |
|---|---|---|---|
| `file_task_add` | 下载方 → 群主 | `{task: TransferTask}` | 传输开始，下载方上报完整任务（progress=0, status=transferring） |
| `file_task_progress` | 下载方 → 群主 | `{task_id, progress, transferred}` | 节流进度（整数百分比变化才发） |
| `file_task_finish` | 下载方 → 群主 | `{task_id, status, error}` | 结束（done / failed） |
| `file_tasks` | 群主 → 全群 | `{tasks: [TransferTask…]}` | 群主广播**全量任务数组**快照 |

### 4.2 为什么广播全量快照而非增量

- 客户端实现最简单：收到 `file_tasks` 直接整体替换本地数组，无合并逻辑、无丢包不一致。
- 新成员入群自然拿到完整任务列表：群主在 join 成功后额外下发一条 `file_tasks`。
- 数据量可控：节流后单任务约 100 条进度，每条广播一个几十项的数组（每项约 200B），局域网可接受。

### 4.3 时序

```
下载方(A)                    群主(O)                      其他成员(B/C)
   |-- file_task_add ---------->|                              |
   |                            | 加入数组, updated_at=now      |
   |                            |-- file_tasks(全量) -------->|
   |-- file_task_progress(10%) ->|                              |
   |                            | 更新该 task.progress          |
   |                            |-- file_tasks(全量) -------->|
   |  ...（每整数百分比一条）...   |                              |
   |-- file_task_finish(done) -->|                              |
   |                            | status=done, 保留在数组        |
   |                            |-- file_tasks(全量) -------->|
```

- **只有下载方上报**：供种方不上报，避免同一次传输产生两条任务。供种方从全量快照里按 `seeder_id == 我` 识别自己的上行任务。
- 群主自己下载/供种时：群主无 ws 自连接，直接调用本地方法改数组 + 走 `broadcast` 广播（与现有 `broadcast_file_add` 同构）。

### 4.4 新成员入群 / 群主变更

- 新成员 join 成功：群主在 `join_result` 后补发一条 `file_tasks` 全量快照。
- 转让 / 解散：任务数组是会话态，随群重建（不迁移、不持久化）——转让后新群主的任务数组从空开始。

## 5. 引擎层改造（进度回调）

当前 `fetch_file`（Rust）**无进度回调**，Android `download()` 有 `onProgress` 但只推给单条通知。要让下载方节流上报，引擎需暴露进度。

### 5.1 knot-core `file.rs`

- `fetch_file` 增加可选进度回调参数：`on_progress: Option<impl Fn(u64, u64) + Send + Sync>`（received, total）。
- 在逐块接收循环里，`received` 更新后调用回调。
- **调用方负责节流**：引擎每块都回调，节流逻辑放在上层状态层（避免引擎与 ws 耦合）。
- 现有 `DownloadResult` 不变（仍返回元信息）。

### 5.2 Android `P2pFileService.download`

- 已有 `onProgress: ((Long, Long) -> Unit)?`，无需改签名。
- 上层把 `onProgress` 从"只更新单条通知"改为"更新通知 + 节流上报群主"。

## 6. 状态层改造

### 6.1 群主端（持有数组）

| 端 | 文件 | 改动 |
|---|---|---|
| Rust | `net/server.rs` | `GroupServer` 加 `tasks: Arc<RwLock<Vec<TransferTask>>>`；读循环处理 3 条 `file_task_*` 消息（按 task_id upsert / 更新 / 置终态）；新增 `broadcast_tasks()` 广播全量；join 后补发；转让/解散随群清空 |
| Kotlin | `net/KnotGroupServer.kt` | 同样加 `tasks` 字段 + `onMessage` 分支 + `broadcastTasks()` + join 补发 |

### 6.2 成员端（接收 + 上报）

| 端 | 文件 | 改动 |
|---|---|---|
| Rust | `net/client.rs` + `CoreEvent` | 读循环解析 `file_tasks` → `CoreEvent::FileTasks(Vec<TransferTask>)`；新增 `send_task_add/progress/finish` |
| Kotlin | `net/GroupClient.kt` + `GroupEvent` | 同样加 `GroupEvent::FileTasks` + 三个 send 方法 |
| 协议 | `protocol.rs` / `Protocol.kt` | 加 `TransferTask` 结构 + 4 条消息及序列化/解析（逐字段对齐） |

### 6.3 业务层（触发上报 + 节流）

- **下载开始时**：`commands.rs::download_file` / `AppState.downloadSharedFile` 里生成 task_id、`send_task_add`。
- **进度回调里**：计算整数百分比，与上一次上报值比较，变化才 `send_task_progress`；同时更新本地 UI 缓存。
- **结束时**：`send_task_finish(done)`；异常则 `send_task_finish(failed, error)`（`fetch_file` 的错误串作为 error）。
- **收到 `file_tasks`**：整体替换本地任务数组 → 刷新传输分区 UI。

## 7. UI 设计（文件页内分区，只读）

### 7.1 桌面

- 位置：群页 `files` tab 顶部（`group.js::renderFiles()` 之前插入一个传输分区容器）。
- 形态：可折叠区，有任务时显示；标题「传输任务」+ 进行中数量。
- 每行：`方向图标(↓下载/↑供种)` + 文件名 + `已传/总量` + 百分比 + 对方设备名 + 进度条；终态显示「已完成」/「失败(原因)」。
- 分组：进行中在上、终态在下（都保留到群解散）。
- 只读：无任何按钮。
- 数据源：`CoreEvent::FileTasks` → bridge emit `knot://file-tasks` → 前端监听并渲染（与 `knot://file-changed` 同构）。

### 7.2 安卓

- 位置：`FileTab.kt` 文件列表顶部加一个传输任务区（在设备文件夹列表之前）。
- 形态：Card + 标题「传输任务」+ LazyColumn 行；无任务时隐藏。
- 每行同桌面的信息密度；终态用灰化 + 状态文字。
- 数据源：`GroupEvent::FileTasks` → `AppState._transferTasks` StateFlow → `FileTab` collect。
- 需补 drawable：现有 24 个 `ic_*` 无下载/上传图标，新增 `ic_download` / `ic_upload`（或用现有 `ic_file` + 箭头）。

### 7.3 与现有通知的关系

- 安卓现有的 `DownloadProgressNotification`（单 ID/单百分比，多任务互相覆盖）保留但**降级为后台兜底**；前台有任务中心后，用户主要看文件页分区。
- 本期不改造通知体系为 per-task（列为未来扩展）。

## 8. 分期计划

| 阶段 | 范围 | 产出 |
|---|---|---|
| **P1 协议 + 群主数组** | `TransferTask` 模型、4 条消息双端对齐、群主任务数组与广播、新成员补发 | 协议层可收发 `file_tasks`，群主持有真相源 |
| **P2 引擎回调 + 上报** | `fetch_file` 加进度回调、下载链路节流上报、终态上报 | 一次下载能在群主数组里看到 0→100→done |
| **P3 双端 UI** | 桌面 `knot://file-tasks` + 文件页分区；安卓 StateFlow + FileTab 分区 + 图标 | 全群可见的只读任务列表 |
| **P4（未来）** | 排队可见化、per-task 通知、取消/重试、持久化与断点续传 | 不在本期 |

## 9. 风险与待定

- **网络量**：全量广播在"多任务 + 高频进度"下会放大。已用整数百分比节流控制；若实测仍偏高，改为群主转发增量 + 客户端按 task_id 合并。
- **乱序**：ws 有序，但进度与终态若并发需保证终态覆盖——群主 upsert 时校验 `updated_at` 单调递增即可。
- **下载方中途离群**：任务停在 transferring 且无人收尾。本期接受（随群重建/不持久化）；未来可加"群主超时置 failed"。
- **供种方视角文案**：一条任务的 `seeder_id == 我` 显示"上传中"，否则"下载中"；需 i18n 加 `task_uploading` / `task_downloading` / `task_done` / `task_failed`。
