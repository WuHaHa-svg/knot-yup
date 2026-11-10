# KnotYup v2 实现规划（发现层 + ws 群事件）

> 依据：[ws-design.md](ws-design.md)（v2 定稿）。本文给出可实施性验证结论、模块级改动清单、
> 事件与常量表、测试计划与实施顺序。双端镜像实现，任何协议改动必须双端同步落地。

## 1. 可实施性验证结论（逐机制）

| 机制 | Rust（knot-core / src-tauri） | Kotlin（knot-android） | 结论 |
|---|---|---|---|
| 拉取式扫描（3s 状态机） | `tokio::time::sleep` 循环 + `recv_from` 2s 窗口（`so_timeout` 由 select 超时实现） | 协程 `delay` + `DatagramSocket.soTimeout` | ✅ 直接可实现 |
| RESP 单播回源 | `recv_from` 返回对端地址，`send_to` 回包 | 同 | ✅ |
| 群主被动应答 | 发现端口常驻 `recv_from` 循环，建群起、解散/转让停 | 同（复用 MulticastLock 保广播可达） | ✅ |
| 30s 心跳 / 3s 单次失联 | client 心跳任务改常量即可（现有结构保留） | GroupClient 心跳协程同理 | ✅ |
| 35s 看门狗 | server 常量 20→35 | KnotGroupServer watchdog 常量 | ✅ |
| pong 只带 members | 删 owner 字段；成员表 role 自描述 | 同 | ✅ |
| clipboard from-id 去重 | client 读循环 `from == local_id` 直接跳过（不抛事件） | GroupClient 同 | ✅ 在连接层去重，早于 UI |
| dissolve + 0.8s 自退 | client 抛 `Dissolve` 事件；**0.8s 定时在 UI/会话层**（bridge.rs / AppState） | 同 | ✅ |
| 转让三方握手 + 5s 超时 + 迟到 ready | server 需要「按连接等待特定回执」能力：per-connection oneshot 通道注册表（`transfer_waiters: Mutex<HashMap<device_id, oneshot::Sender<addr>>>`）；读循环收到 `TransferReady` 时按 device_id 唤醒 | Android 群主为 Java-WebSocket：成员表加 waiter map，`onMessage` 分发 | ✅ 需新增小型基础设施（两端对称） |
| 被转让者起 standby 服务 | bridge/AppState 层调 `GroupServer::start(standby_port…)`（已有能力） | `KnotGroupServer` + standby ServerSocket（已有） | ✅ |
| new_service 迁移 | client 抛 `NewService{addr,pin}` 事件 → 会话层断旧连、起新 client（复用现有重连骨架） | 同（AppState） | ✅ |
| v2 断旧 | 协议前缀 KNOTYUP2；`ver:2` 校验 | 同 | ✅ |

**无阻断项**。唯一新增基础设施：群主侧「定向等待回执」通道（transfer 场景专用，两端各 ~40 行）。

## 2. 模块级改动清单

### 2.1 knot-core

| 文件 | 改动 |
|---|---|
| `protocol.rs` | `DeviceInfo` 增 `role: Option<String>`（serde default，join 上报省略）；删 `Pong.owner`；新增 `Dissolve`、`TransferOffer{pin}`、`TransferReady{addr}`、`TransferError`、`NewService{addr, pin, owner}` 变体；常量 `HEARTBEAT_MS=30_000 / PONG_TIMEOUT_MS=3_000 / WATCHDOG_MS=35_000 / TRANSFER_TIMEOUT_MS=5_000 / DISSOLVE_EXIT_MS=800` |
| `discovery.rs` | 重写为 v2：`scan()` 状态机（REQ→2s 窗口→1s 静默）与 `respond()`（常驻应答）；载荷 `KNOTYUP2\|REQ\|{scan_id,ts_ms}` / `KNOTYUP2\|RESP\|{id,name,addr,members,ver,ts_ms}`；删 announce 周期广播 |
| `net/server.rs` | ping→pong 改 members-only；watchdog 35s；新增 `transfer(target_id)`：发 offer → 注册 waiter → 5s select 等 ready → 成功则广播 new_service + stop，超时则发 `CoreEvent::TransferFailed`（不关服务）；迟到 ready → 回 transfer_error；读循环分发 TransferReady/TransferError；`dissolve()` 改发 dissolve 消息后 stop |
| `net/client.rs` | 心跳 30s/3s 单次失联；读循环：clipboard from-id 去重；新增事件分发 Dissolve / TransferOffer / TransferError / NewService；新增 `send_transfer_ready(addr)`；`CoreEvent` 增对应变体（TransferFailed 仅群主侧 UI 用，不经网络） |
| `net.rs`（门面） | `CoreEvent` 变体扩充；测试更新（见 §4） |

### 2.2 knot-desktop/src-tauri

| 文件 | 改动 |
|---|---|
| `state.rs` | 无结构变化；Session 语义微调（role 来源=成员表） |
| `commands.rs` | `leave_group(transfer_to=Some)` 调用新 `server.transfer()`；新增错误码 `TRANSFER_FAILED` 透传 |
| `bridge.rs` | `forward_core_events` 新分支：`Dissolve`→0.8s 后清会话回首页；`TransferOffer`→起 standby GroupServer→`client.send_transfer_ready`；`TransferError`→静默关 standby；`NewService{addr,pin}`→断旧连+连接新 addr；`TransferFailed`→emit toast 码；成员表 role=="owner" 判定替代 [0] 约定 |
| `lib.rs` | 扫描启停改调新 `discovery::scan()` 状态机（进群停/回首页立即补一轮逻辑不变） |

### 2.3 knot-android

| 文件 | 改动 |
|---|---|
| `net/Protocol.kt` | Msg 增 Dissolve / TransferOffer / TransferReady / TransferError / NewService；Pong 去 owner；DeviceInfo 增 role；JSON 同 §3.2 示例 |
| `net/Discovery.kt` | 重写为 scan() 状态机 + respond()（挂 KnotGroupServer 生命周期）；常量同步 |
| `net/KnotGroupServer.kt` | watchdog 35s；ping→pong members-only；transfer() 三方握手 + waiter map + 5s 超时 + transfer_error；dissolve() 发独立消息；owner 角色 role 字段赋值 |
| `net/GroupClient.kt` | 心跳 30s/3s 单次失联；from-id 去重；新事件 Dissolve/TransferOffer/TransferError/NewService（GroupEvent 扩变体）；sendTransferReady() |
| `data/AppState.kt` | handleEvent 新分支：Dissolve→0.8s 后 reset（不回发 ws）；TransferOffer→起 standby 服务+回 ready；TransferError→静默关 standby；NewService→切换成员会话到新 addr；TransferFailed→toastKey；承接候选 = role==member 序列 |
| `ui/screens/GroupTabs.kt` | 皇冠判定 `i==0` → `m.role=="owner"`（已确认现状为位置判定，需改） |
| `ui/Strings.kt` | 增 `transfer_failed`、`dissolving` 等码的三语 |

### 2.4 桌面 UI（knot-desktop/ui）

| 文件 | 改动 |
|---|---|
| `i18n.js` | 增 transfer_failed 等码三语 |
| `group.js` | 皇冠判定改 role；toast 码映射增 TRANSFER_FAILED/DISSOLVED |
| `home.js` / `common.js` | 发现列表字段兼容 members 展示（可选：显示成员数） |

## 3. 事件与错误码总表（v2）

**CoreEvent / GroupEvent**：`Members / Clipboard / Kicked / Dissolve / TransferOffer{pin} / TransferError / NewService{addr,pin,owner} / TransferFailed(本地) / Error`

**UI 码**：`WRONG_PIN / ALREADY_IN_GROUP / OWNER_ONLY / NO_LAN / GROUP_LOST / PROMOTED / RECONNECTING / RECONNECTED / KICKED / DISSOLVED / TRANSFER_FAILED`

## 4. 测试计划（knot-core 集成测试，`net.rs` tests）

1. `owner_transfer_and_reconnect` → 重写为三方握手版：offer→ready→new_service→成员迁移断言
2. `dissolve_flow`：群主 dissolve → 成员收 Dissolve 事件（0.8s 定时属 UI 层，core 只验消息）
3. `transfer_timeout`：offer 后不 ready → 5s 后 TransferFailed，群主服务仍存活、成员未受扰
4. `transfer_late_ready`：5s 后迟到 ready → 目标收 TransferError
5. `clipboard_self_echo_ignored`：from==local_id 不抛事件
6. `heartbeat_params`：常量正确性（30s/3s/35s，防回归）

Android：`compileDebugKotlin` + 真机三设备联调脚本化场景（建群/扫码/复制/踢人/转让/解散/断网承接）。

## 5. 实施顺序（每步编译验证）

1. `protocol.rs` + 常量 → `cargo check`
2. `discovery.rs` v2 → `cargo check`
3. `net/server.rs` + `net/client.rs` + CoreEvent → `cargo test`（测试 1–6）
4. src-tauri（commands/bridge/lib）→ `cargo check` + 桌面冒烟
5. Android net 层四文件 → `compileDebugKotlin`
6. Android AppState/UI + 桌面 UI 码表 → 编译 + 三设备真机联调
7. ws-design.md 状态改「已实现」、README 版本说明
