# 传输任务中心设计

> 状态：**已实现**（P1-P3 + P5 取消传输）。本文是「传输任务中心」的设计来源；节流/视角文案/per-task 通知为 TODO。
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
| 上报职责 | **任务诞生**由下载方报（add）；**正常状态**（queuing/started/progress/done）由**供种方**报；**失败由双方各自上报**（任一方掉线，另一方仍能把失败送达） |
| 进度上报 | **节流（TODO）**：设计仅整数百分比变化时上报；当前每块上报（约 160 条/10MB） |
| UI | **桌面新增第三个 tab「传输中心」**（与剪切板/文件并列）；**安卓在文件 tab 的文件夹列表下方**加「传输中心」段（标题+副标题，与群信息 tab 同款一起滚动） |
| 操作 | 已支持取消：**群内任一成员**可取消 `queuing`/`transferring` 的任务，见 [§10 取消传输](#10-取消传输) |

## 2. 核心概念：一次传输 = 一条任务

一条 `TransferTask` 同时表达下载与供种两个视角——它记的是"谁从谁那里传哪个文件"，而不是某台设备私有的"我的上传"或"我的下载"。

```
群主维护的任务数组（全群共享，明文同步）
├─ task#1  a.zip      seeder=设备B  downloader=设备A  32%  transferring
├─ task#2  doc.pdf    seeder=设备A  downloader=设备C   ——  queuing（B 也在下 A 的文件，排队中）
├─ task#3  photo.png  seeder=设备C  downloader=设备B  100% done
└─ task#4  song.mp3   seeder=设备B  downloader=设备A  ——  failed(HASH_MISMATCH)
```

- 设备 A 看 task#1 是「下载中」（我是 downloader）；设备 B 看同一条是「上传中/供种中」（我是 seeder）——**一条记录，两种视角，UI 按 `seeder_id == 我 ? 上行 : 下行` 渲染**。
- 这样避免了"同一次传输记两条（上传一条、下载一条）"的重复。

## 3. 数据模型（双端逐字段一致）

```
TransferTask {
  task_id:       uuid,            // 任务唯一 id，下载方发起时生成；随 P2P 请求帧传给供种方，供种方据它上报状态
  file_id:       uuid,            // 对应的 SharedFile.file_id（快照，非引用）
  name:          "a.zip",         // 文件名快照
  size:          10485760,        // 总字节
  mime:          "application/zip",
  seeder_id:     "<device id>",   // 供种者（文件所在设备）
  downloader_id: "<device id>",   // 下载者
  status:        queuing | transferring | done | failed | cancelled,
  progress:      0..100,          // 整数百分比；queuing 阶段恒为 0
  transferred:   3355443,         // 已传字节（UI 可显示 3.2/10 MB）
  error:         "",              // 失败原因（status=failed 时有值；双方上报时各自填自己看到的原因）
  canceller_id:  "<device id>",   // 取消者 id（status=cancelled 时有值；发起取消的成员，可能是第三方；任务诞生时为空）
  canceller_name: "张三",         // 取消者设备名快照（创建取消时固化，UI 直接用）
  created_at:    ms,              // 任务创建时间
  updated_at:    ms,              // 最近一次进度/状态变化时间
}
```

- **快照语义**：`name/size/mime` 是发起时的文件元数据副本。文件后来被删或改名，历史任务记录不变。
- **状态四态**：
  - `queuing`（排队中）：下载方已发 P2P 请求，但供种方正忙（`Semaphore(1)` 闸门被别的下载占用），等不到 `ok` 帧。**由供种方上报**（第一手信息：它精确知道谁在排队、排第几）。
  - `transferring`（传输中）：供种方发出 `ok` 帧、开始发送块；进度 0→100，**由供种方随发送进度上报**。
  - `done`（完成）：供种方发完最后一块、收到下载方 `done` 控制帧，**由供种方上报**。
  - `failed`（失败）：P2P 断线、HASH_MISMATCH、超时等，**由下载方与供种方双方各自上报**——任一方掉线，另一方仍在线即可把失败送达群主。
  - `cancelled`（已取消，终态）：群内任一成员主动取消，详见 §10。记录 `canceller_id`/`canceller_name`。
- **五态流转**：`queuing → transferring → done|failed`；也可能 `queuing → failed` / `transferring → failed`；`queuing|transferring → cancelled`（任一成员取消）。终态 `done/failed/cancelled` 之间不再流转（群主终态锁定，先到为准）。
- **上报方分工**：任务诞生（add）由下载方发起；正常状态由供种方驱动；失败双方都报、群主按 task_id 幂等覆盖（先到为准，后到同状态无害）。
- **无 status 字段污染 SharedFile**：任务与文件清单彻底解耦，撤销 status 的决定保持不变。

## 4. ws 协议（明文；群主权威）

新增 7 条消息（取消功能落地后为 8 条，见 §10），均**明文**（沿用"只剪贴板加密"的既定策略）。群主是唯一写入者。

### 4.1 消息表

| 消息 | 方向 | 载荷 | 用途 |
|---|---|---|---|
| `file_task_add` | 下载方 → 群主 | `{task: TransferTask}` | 任务诞生：下载方生成 task_id、写全字段，status=queuing |
| `file_task_queued` | 供种方 → 群主 | `{task_id}` | 供种方确认收到请求、发现闸门被占，状态=queuing |
| `file_task_started` | 供种方 → 群主 | `{task_id}` | 供种方拿到闸门、发出 `ok` 帧，状态 queuing→transferring |
| `file_task_progress` | 供种方 → 群主 | `{task_id, progress, transferred}` | 供种方随块发送节流上报进度 |
| `file_task_finish` | 供种方 → 群主 | `{task_id}` | 供种方发完最后一块并收到下载方 `done` 帧，状态=done |
| `file_task_failed` | **下载方或供种方 → 群主** | `{task_id, error, reporter}` | 失败：**双方各自上报**，任一方掉线另一方仍可送达；群主按 task_id 幂等覆盖 |
| `file_task_cancel` | **群内任一成员 → 群主** | `{task_id, canceller, canceller_name}` | 取消任务（§10 实现时新增）：任意成员可取消 queuing/transferring 的任务；群主置 cancelled + 广播 |
| `file_tasks` | 群主 → 全群 | `{tasks: [TransferTask…]}` | 群主广播**全量任务数组**快照 |

- `reporter` = 上报方 device id（`seeder_id` 或 `downloader_id`），仅用于排障展示；群主不因 reporter 不同拒绝覆盖。
- **正常状态只有供种方报**（避免双份进度互相打架）；**失败是唯一双报的状态**，因为失败的原因可能发生在任一端，且掉线方天然无法自报。

### 4.2 为什么广播全量快照而非增量

- 客户端实现最简单：收到 `file_tasks` 直接整体替换本地数组，无合并逻辑、无丢包不一致。
- 新成员入群自然拿到完整任务列表：群主在 join 成功后额外下发一条 `file_tasks`。
- 数据量可控：节流后单任务约 100 条进度，每条广播一个几十项的数组（每项约 200B），局域网可接受。

### 4.3 时序

```
下载方(A)        供种方(B)        群主(O)              其他成员(C)
   |                |                |                     |
   |-- task_add --->|--------------->| 建 task(queuing)     |
   |                |                |--- task_all ----->|
   |-- P2P req(task_id) ------------>|                     |
   |   [B 闸门被占，排队]             |                     |
   |                |-- task_queued->| status=queuing      |
   |                |                |--- task_all ----->|
   |                | [拿到闸门]       |                     |
   |<-- ok 帧 -------|                |                     |
   |                |-- task_started->| status=transferring |
   |                |                |--- task_all ----->|
   |                |-- task_progress->| 节流百分比          |
   |                |                |--- task_all ----->|
   |  ...逐块 + ACK...|                |                     |
   |-- done 帧 ---->|                |                     |
   |                |-- task_finish->| status=done         |
   |                |                |--- task_all ----->|

失败分支（以 A 掉线为例）：
   X (A 掉线，发不出)
   |                | [B 读 ACK 失败]     |
   |                |-- task_failed ---->| status=failed    |
   |                |                    |--- task_all --->|
若反过来 B 掉线：A 侧 P2P 报错 → A 发 task_failed，同样收敛到 failed。
```

- **task_id 如何到供种方**：下载方发起 P2P 请求时，把 `task_id` 加进请求首帧 `FileRequest`（新增字段），供种方收到后所有上报都带这个 id。
- **失败双报的幂等**：两端可能先后都报 `task_failed`，群主按 task_id 置为 failed 即可；后到的同状态覆盖无害。若一端报 failed、另一端（延迟）报 done，群主用 `updated_at` 单调规则仲裁——**先到终态为准**（先到的终态锁定，后到的终态忽略），避免"已失败又被改成完成"。
- 群主自己下载/供种时：群主无 ws 自连接，直接调用本地方法改数组 + 走 `broadcast` 广播（与现有 `broadcast_file_add` 同构）。

### 4.4 新成员入群 / 群主变更

- 新成员 join 成功：群主在 `join_result` 后补发一条 `file_tasks` 全量快照。
- 转让 / 解散：任务数组是会话态，随群重建（不迁移、不持久化）——转让后新群主的任务数组从空开始。

## 5. 引擎层改造（task_id 透传 + 供种端状态回调）

状态正常由供种方上报，因此引擎改造的重点在**供种端**：请求帧要带 task_id，供种过程要暴露状态回调。

### 5.1 请求帧加 `task_id`（双端）

- `FileRequest`（`knot-core/src/file.rs`）加字段 `task_id: String`；Android `P2pFileService.serveOne` 解析时读取。
- 下载方 `fetch_file` / `download()` 签名加 `task_id` 参数，构造请求时填入。
- 供种方 `serve_one` / `serveOne` 拿到 `task_id`，后续所有状态回调都带上它。

### 5.2 knot-core `file.rs`（供种端）

- `FileSeeder::start` 增加可选状态回调 `on_event: Option<impl Fn(TaskEvent) + Send + Sync>`，其中 `TaskEvent` = `Queued{task_id}` | `Started{task_id}` | `Progress{task_id, sent, total}` | `Done{task_id}` | `Failed{task_id, error}`。
- `serve_one` 各节点触发回调：
  - `gate.acquire()` 阻塞前 → `Queued`；拿到闸门后 → `Started`（紧接着发 `ok` 帧）。
  - 每发送一块 → `Progress`（节流由上层做）。
  - 收到下载方 `done` 控制帧 → `Done`。
  - 任一步出错（读 ACK 失败、写失败等）→ `Failed{error}`。
- 上层把回调转成 `send_task_queued/started/progress/finish/failed`。

### 5.3 Android `P2pFileService`（供种端）

- `serveOne` 里 `gate.acquire()` 前后、`ok` 帧发出、每块发送、读 done、出错各处，通过新增的 `onEvent: ((TaskEvent) -> Unit)?` 回调上报（与 Rust 五类事件对齐）。
- 下载端 `download()` 的 `onProgress` 保留（用于本地通知），但**不再作为群主进度的上报源**；下载端只在失败时调 `onFailed` 上报一次。

### 5.4 下载端失败上报

- `fetch_file` / `download()` 内部任何 `Err`（连接失败、等 ok 超时、HASH_MISMATCH、P2P 断线）都通过 `on_failed: Option<impl Fn(&str)>` 把错误串抛给上层。
- 上层据此发 `file_task_failed`（reporter=下载方）——与供种方的失败上报形成双保险。

## 6. 状态层改造

### 6.1 群主端（持有数组）

| 端 | 文件 | 改动 |
|---|---|---|
| Rust | `net/server.rs` | `GroupServer` 加 `tasks: Arc<RwLock<Vec<TransferTask>>>`；读循环处理 6 条 `file_task_*` 消息（add 建任务；queued/started/progress/finish 按 task_id 更新；failed 幂等置终态）；新增 `broadcast_tasks()` 广播全量；join 后补发；转让/解散随群清空。**终态锁定**：已达 done/failed 的任务忽略后到的其它终态 |
| Kotlin | `net/KnotGroupServer.kt` | 同样加 `tasks` 字段 + `onMessage` 分支（6 条）+ `broadcastTasks()` + join 补发 + 终态锁定 |

### 6.2 成员端（接收 + 上报）

| 端 | 文件 | 改动 |
|---|---|---|
| Rust | `net/client.rs` + `CoreEvent` | 读循环解析 `file_tasks` → `CoreEvent::FileTasks(Vec<TransferTask>)`；新增 6 个 send：`task_add`（下载侧）、`task_queued/started/progress/finish`（供种侧）、`task_failed`（两侧都用） |
| Kotlin | `net/GroupClient.kt` + `GroupEvent` | 同样加 `GroupEvent::FileTasks` + 6 个 send 方法 |
| 协议 | `protocol.rs` / `Protocol.kt` | 加 `TransferTask`、`TaskEvent` 结构 + 7 条消息及序列化/解析（逐字段对齐）；`FileRequest` 加 `task_id` |

### 6.3 业务层（触发上报 + 节流）

- **下载方发起**：生成 task_id、写全字段，`send_task_add`（status=queuing），并把 task_id 传给 `fetch_file` / `download()` 写入 P2P 请求帧。
- **供种方（引擎 `on_event` 回调）**：
  - `Queued` → `send_task_queued`；`Started` → `send_task_started`。
  - `Progress` → 计算整数百分比、与上次上报值比较，变化才 `send_task_progress`（节流在这里）。
  - `Done` → `send_task_finish`（status=done）。
  - `Failed` → `send_task_failed`（reporter=供种方）。
- **下载方失败回调**（`on_failed`）：`send_task_failed`（reporter=下载方，error 为下载侧看到的错误）。
- **收到 `file_tasks`**：整体替换本地任务数组 → 刷新传输 UI。

## 7. UI 设计（只读）

### 7.1 桌面：第三个 tab「传输中心」

- 位置：群页 panel 的 tab 栏从 2 个扩到 3 个——`剪切板` / `文件` / `传输中心`（`index.html` 的 `panelTabs` + `panelBody` 各加一个 button/pane；`group.js::setPanel()` 从两态扩到三态）。
- pane 内容：全群任务列表，一个表格/列表容器 `#transferList`。
- 每行：`方向图标(↓下载/↑供种)` + 文件名 + 状态文字 + `已传/总量` + 百分比 + 进度条 + 对方设备名。
  - 状态文案：`排队中` / `传输中` / `已完成` / `失败(原因)`。
  - 视角：`seeder_id == 我` 显示「供种给 XXX」，否则显示「从 XXX 下载」。
- 分组：进行中（queuing + transferring）在上、终态（done + failed）在下；都保留到群解散。
- 只读：无任何操作按钮。
- 数据源：`CoreEvent::FileTasks` → bridge emit `knot://file-tasks` → 前端监听渲染（与 `knot://file-changed` 同构）。

### 7.2 安卓：文件 tab 内、文件夹列表下方

- 位置：`FileTab.kt` 一级页面（未进入某设备文件夹时），在设备文件夹列表**下方**加一段「传输中心」——**参照群信息 Tab（`GroupTabs.kt::DevicesTab`）的布局方式**：标题 + 副标题 + 内容，和上面的文件夹列表在**同一个滚动容器里一起滚动**。
- 形态：
  - 标题：`传输中心`（14.sp SemiBold，与群信息 tab 的「群信息」「设备列表」同级标题样式）。
  - 副标题：`查看本群中文件共享记录`（11.sp，`onSurfaceVariant`，与群信息 tab 的 `click_hint` 副标题同款）。
  - 内容：任务列表行，每行同桌面的信息密度；终态灰化 + 状态文字。
- 布局实现提示：当前一级是 `LazyColumn(Modifier.weight(1f))`，要改成群信息 tab 那样的 `Column(verticalScroll + weight(1f))`，把文件夹列表与传输中心两段放进同一个滚动 Column（或保留 LazyColumn 但在其 item 序列末尾追加标题+列表项）；无任务时该段仍显示标题/副标题 + 空态文案。
- 数据源：`GroupEvent::FileTasks` → `AppState._transferTasks` StateFlow → `FileTab` collect。
- 需补 drawable：现有 24 个 `ic_*` 无下载/上传图标，新增 `ic_download` / `ic_upload`（或用现有 `ic_file` + 箭头）。

### 7.3 与现有通知的关系

- 安卓现有的 `DownloadProgressNotification`（单 ID/单百分比，多任务互相覆盖）保留但**降级为后台兜底**；前台有传输中心后，用户主要看文件 tab 的传输中心段。
- 本期不改造通知体系为 per-task（列为未来扩展）。

## 8. 分期计划

| 阶段 | 范围 | 产出 |
|---|---|---|
| **P1 协议 + 群主数组** | `TransferTask`/`TaskEvent` 模型（四态）、7 条消息双端对齐、`FileRequest` 加 task_id、群主任务数组与广播+终态锁定、新成员补发 | 协议层可收发 `file_tasks`，群主持有真相源 |
| **P2 引擎回调 + 上报** | 两端供种端五类事件回调、下载端失败回调、下载方 add、节流上报、失败双报 | 一次传输能在群主数组里看到 queuing→0→100→done；任一端掉线都能收敛到 failed |
| **P3 双端 UI** | 桌面第三 tab「传输中心」+ `knot://file-tasks`；安卓文件 tab 内传输中心段 + StateFlow + 图标 | 全群可见的只读任务列表 |
| **P4（未来/TODO）** | per-task 通知、重试、任务持久化 | 不在本期；断点续传已废弃 |
| **P5 取消传输**（已完成） | 见 §10：取消消息 + 群主取消终态 + 双端 P2P 中断 + UI 取消入口 | 全群任一成员可取消 queuing/transferring 任务，真正中断 P2P |

## 9. 风险与待定

- **网络量**：全量广播在"多任务 + 高频进度"下会放大。已用整数百分比节流控制；若实测仍偏高，改为群主转发增量 + 客户端按 task_id 合并。
- **乱序 / 终态竞态**：进度与终态若并发，群主按 `updated_at` 单调递增校验；**终态锁定**——任务一旦 done/failed，忽略后到的其它终态消息（防止一端报 failed、另一端延迟报 done 把结果翻回去）。
- **失败双报幂等**：两端先后报 `task_failed` 时按 task_id 覆盖即可（同状态无害）；`error` 取先到那条，reporter 仅排障展示。
- **任一方中途离群 / P2P 断线**：P2P 连接断开会报错，**在线的一方**通过 `on_failed` / `Failed` 回调上报 `task_failed`——不必依赖掉线方自报，也不依赖群主超时兜底。这是"失败双方上报"的核心价值。
- **供种方视角文案**（TODO）：设计按 `seeder_id == 我` 显示"供种给 XXX"或"从 XXX 下载"；当前双端固定显示"X 发送给 Y"，`task_uploading`/`task_downloading` 文案与下载/上传图标未实现。

## 10. 取消传输（已实现）

> 本节功能**已完整实现**：协议 `file_task_cancel`、群主置 `cancelled` 终态+广播、双端数据面中断（Rust `CancellationToken` / Android `cancelFlags`）、双端 UI 取消入口均已接线。以下为设计说明，供维护参考。

### 10.1 需求与权限

- **谁能取消**：群内**任一成员**（包括群主、也包括既不是 seeder 也不是 downloader 的第三方）都能取消任意一条 `queuing` / `transferring` 的任务。取消入口对全群可见、无成员角色/归属限制——这是明确需求。
- **可取消的状态**：仅 `queuing`（排队中）与 `transferring`（传输中）。终态 `done/failed/cancelled` 不可取消，群主收到针对终态的 cancel 消息**幂等忽略**。
- **取消的语义**：一条任务被取消 = 传输**真正中断**（不是只改状态文字）+ 群主把该任务置 `cancelled` 终态 + 全群同步。`cancelled` 与 `done/failed` 同为终态，参与"先到终态锁定"。

### 10.2 核心难点：群主不在 P2P 数据通路上

传输是**下载方 ↔ 供种方的端到端直连**（`knot-core/src/file.rs`、`P2pFileService.kt`），群主只在 ws 控制面维护任务状态、不转发文件块。因此"群主把状态改成 cancelled"本身**不会**让正在跑的 P2P 传输停下来。必须分成两层：

| 层 | 做什么 | 谁负责 |
|---|---|---|
| **控制面（状态权威）** | 收到取消 → 群主置 `cancelled` + 广播全量 `file_tasks` | 群主（唯一写入者） |
| **数据面（真正中断）** | 两端客户端收到快照，发现**自己参与的**任务变成 `cancelled` → 主动 abort 对应 P2P 传输（关闭 socket / 触发取消信号） | 下载方 + 供种方各自本地执行 |

关键：**取消指令借道已有的 `file_tasks` 全量广播下发给两端**，不需要新增一条"群主→某端"的专门中断消息——两端本来就在收全量快照，只需在消费快照时多做一步"比对任务状态、命中 cancelled 则中断本地传输"。

### 10.3 协议改动（实现时新增）

| 消息 | 方向 | 载荷 | 说明 |
|---|---|---|---|
| `file_task_cancel` | **任一成员 → 群主** | `{task_id, canceller, canceller_name}` | 取消请求。`canceller`=发起者 id、`canceller_name`=设备名快照（延续"存 id 必存 name"约定） |

- `TransferTask` 新增两字段（仅 `cancelled` 时有值）：`canceller_id` / `canceller_name`（见 §3，双端结构与序列化已加）。
- 群主处理 `file_task_cancel`：按 `task_id` 找任务 → **仅当状态 ∈ {queuing, transferring}** 时置 `cancelled`，写 `canceller_id/name`、`updated_at` → 广播 `file_tasks`；否则（任务不存在 / 已是终态）忽略。
- 群主自己取消：无 ws 自连接，直接调本地方法改数组 + 广播（与现有 `localTaskEvent` 同构）。

### 10.4 数据面中断机制（双端引擎）

两端都要维护 **task_id → 当前传输 abort 句柄** 的映射，在收到 `file_tasks` 快照时驱动中断：

- **Rust（`knot-core/src/file.rs`）**：
  - `fetch_file`（下载侧）与 `FileSeeder::serve_one`（供种侧）的传输循环接受一个取消信号（如 `tokio_util::sync::CancellationToken`，或 `oneshot` 关闭通道）。
  - 块读写循环（当前裸 `read_exact`/`write_all`）用 `tokio::select!` 同时等待"下一块 I/O"与"取消信号"，命中取消立即返回 `Cancelled` 结果并关闭流。
  - 上层维护 `task_id → CancellationToken` 注册表：传输启动时登记，结束（done/failed/cancelled）时移除。
- **Android（`P2pFileService.kt`）**：
  - `download()` 与 `serveOne()` 的 while 循环每轮检查一个 `cancelFlags: ConcurrentHashMap<String, Boolean>`（按 task_id）；命中取消则跳出循环、`socket.close()`。因为 `readFully/readInt` 是阻塞 IO，关闭 socket 会让阻塞读抛异常，据此退出。
  - 暴露 `fun cancel(taskId: String)`：置标志 + 关闭该 task 的 socket。

- **中断后的上报抑制**：abort 会让引擎走 `Failed`/`on_failed` 分支，但群主已把任务置 `cancelled` 终态，**终态锁定会忽略后到的 failed/done**——所以不需要在引擎里特殊区分"因取消而失败"，终态锁自然收敛。
- **排队中的任务**：下载方此时 socket 已连上供种方、阻塞在等 `ok` 帧；同样按上面的 abort 句柄关闭 socket 即可。供种侧若在 accept 后查到该 task 已 cancelled，可直接拒绝（发 `{"error":"CANCELLED"}` 控制帧）不占闸门。

### 10.5 触发链路（时序）

```
成员 X(任意)   群主(O)        下载方(A)       供种方(B)
   |              |               |               |
   |-- cancel ---->|               |               |
   |  {task_id,    |               |               |
   |   canceller=X}|               |               |
   |              | status=cancelled              |
   |              |--- file_tasks 全量 ----------->|
   |              |-------------------------------->|
   |              |   A/B 各自比对：我参与的 task 变 cancelled
   |              |               |-- abort -------|
   |              |               |   (关闭 P2P socket / 触发 CancellationToken)
   |              |  [引擎 Failed 被终态锁定忽略]     |
   |              |<-- 后续 progress/done 均被忽略 ---|
```

- 关键顺序：**群主先置 cancelled 再广播**；两端以 `file_tasks` 快照为取消指令来源。
- 即便 X 是第三方（既非 A 也非 B），A、B 也会通过全量快照收到取消并执行中断。

### 10.6 状态层改动（实现时）

- 群主两端（`net/server.rs`、`KnotGroupServer.kt`）读循环加 `file_task_cancel` 分支：查 task → 仅 queuing/transferring 可置 cancelled → 写 `canceller_id/name` → 广播。
- **终态判定扩展**：现有"终态锁定"检查是 `!= DONE && != FAILED`，需把 `CANCELLED` 也纳入终态集合（即 `Done/Failed/Cancelled` 三者互斥、先到为准）。
- 成员两端（`net/client.rs`、`GroupClient.kt`）加 `send_task_cancel(task_id)`。
- 协议层（`protocol.rs`/`Protocol.kt`）加消息定义 + 序列化/解析；`TransferTask` 加两字段。

### 10.7 UI 改动（实现时）

- **取消入口**：桌面传输条目、安卓传输条目在 `queuing`/`transferring` 时显示一个"取消"按钮（icon + 文案 `task_cancel`）；终态不显示。任何成员看到任何进行中任务都有此按钮（无归属限制）。
- 点击 → 调 `send_task_cancel(task_id)`；按钮随即禁用防重复点击（群主终态锁定也兜底幂等）。
- 状态文案：`task_cancelled = 已取消`；**配色**：已取消用**灰色**（与排队一致的弱色，区别于失败红/完成绿）。UI 的 `when(status)`/`label` 映射已预留 `cancelled` 分支。
- 分组：`cancelled` 归入"终态组"（与 done/failed 一起置底、灰化）——这组逻辑已在 P3 UI 中预留。

### 10.8 边界与风险

- **取消与完成竞态**：取消消息到群主时传输恰好刚 done → 群主"先到终态为准"，若 done 先到则 cancel 被忽略（任务保持 done）；若 cancel 先到则后续 done 被终态锁定忽略（任务保持 cancelled）。二者只保留其一，不翻转。
- **取消方掉线**：cancel 消息发出前掉线则取消不发生；群主已收到的 cancel 不受影响（已落终态并广播）。
- **被取消端掉线**：若 A/B 之一已掉线，另一端的 abort 仍会执行；群主终态已是 cancelled，掉线端重连后从全量快照看到 cancelled，不会有残留传输。
- **下载残留文件**：下载侧 abort 时要清理已写入的临时/待完成文件（安卓 MediaStore `IS_PENDING` 的条目 delete，桌面目标文件 remove），避免留下半截文件。
- **第三方取消的体验**：由于不限归属，A/B 会看到"我的传输被 X 取消了"——`canceller_name` 用于提示（可选 toast/日志），让被取消方知道是谁操作的。
- **不重试**：本期取消后不提供"重新传输"，取消即终止（重试仍属 P4）。
