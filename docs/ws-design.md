# KnotYup v2 群组通信设计（发现 + WebSocket）

> 状态：**v2 已实现**（双端代码落地，knot-core 集成测试 6/6 通过）。双端需同版发布。
> 消息面代码：`knot-core/src/{protocol.rs, net/server.rs, net/client.rs, discovery.rs}`；
> `knot-android/.../net/{Protocol.kt, KnotGroupServer.kt, GroupClient.kt, Discovery.kt}`。
> 本文件是协议唯一事实来源。

## 1. 总览

- **拓扑**：星型。群主 = WebSocket 服务端；成员 = 客户端；剪贴板消息经群主转发。
- **端口**：发现 UDP 45691（拉取式 REQ/RESP）；ws 端口群主动态分配。
- **发现模型（v2 拉取式）**：扫描端周期发 REQ，群主被动应答 RESP。群主不再周期广播。
- **心跳**：30s 一次，pong 携带成员表 + 身份；单次 3s 超时即判失联。
- **编码**：JSON 文本帧 `{"type":"snake_case", ...}`。**只有剪贴板 `clipboard` 装 `encrypted` 信封**（AES-256-GCM，密钥 = SHA256("knotyup-v1|PIN")）；其余消息（含 join/ping/pong/members 与文件相关 file_add/file_remove）明文——文件列表随成员表明文传输，接受局域网嗅探风险。

## 2. 发现协议（UDP 45691，KNOTYUP2）

### 2.1 报文

| 方向 | 报文 | 载荷 |
|---|---|---|
| 扫描端 → 广播 | `KNOTYUP2\|REQ\|{json}` | `{scan_id, ts_ms}` |
| 群主 → 单播（回源） | `KNOTYUP2\|RESP\|{json}` | `{id, name, addr, members, ver:2, ts_ms}` |

- RESP 单播回 REQ 的源地址:源端口（recvfrom 直取）。
- `members`：群内设备数（含群主），供扫描端展示。
- `ver`：协议版本，收到非 2 的 RESP 忽略（向前演进用）。

### 2.2 扫描端状态机（仅首页且前台运行）

```
发 REQ（1 个 UDP 广播包，全局广播 + 各网卡子网广播）
  → 收集窗口 2s：窗口内收到的全部 RESP = 本轮结果，整轮直出（无历史状态、无剔除抖动）
  → 静默 1s
  → 回到「发 REQ」……                          // 周期 3s
```

- **启停规则**：进入群页面（群主或成员）→ 停扫描；退群回首页 → **立即补一轮**，随后进入周期。
- 空闲流量：每设备 3s 1 个广播包。

### 2.3 群主端

- 建群成功 → 在发现端口监听 REQ，收到即回 RESP；解散/转让完成 → 停止监听。
- 不再有广播突发/常规广播逻辑。

## 3. ws 消息总表

### 3.1 DeviceInfo（成员对象，出现在所有含成员表的消息里）

```json
{
  "id": "9f2c1a4e-…",            // 设备唯一 id（UUID，入群首次生成，会话期不变）
  "name": "Jackey 的 Mac",       // 展示名
  "platform": "macos",           // macos / windows / android
  "addr": "10.0.0.5:35217",     // 本机 standby 服务地址（成员上报；群主/未预留时为 null）
  "file_addr": "10.0.0.5:47113",// 文件共享 P2P 供种地址（与 ws/standby 端口独立）
  "role": "owner",              // owner | member（服务端权威赋值；join 时上报省略，默认 member）
  "file_list": [{…SharedFile…}] // 该成员共享的文件列表（文件夹随成员走）
}
```
**成员表即文件列表唯一真相源**：`file_list` 内嵌在 DeviceInfo，随 join/join_result/ping/pong/members 同步；服务端不再单独维护文件快照，客户端收到成员表即从各 `file_list` 拼出全群文件夹。

### 3.2 消息表（JSON 示例列均为完整载荷；encrypted 信封内的业务消息结构即示例中的 message 字段）

| 消息 | 方向 | 加密 | 载荷示例 |
|---|---|---|---|
| `join` | 成员→群主 | 明文 | `{"type":"join","pin":"510443","device":{"id":"…","name":"Meizu 17","platform":"android","addr":"10.0.0.9:41235"}}` |
| `join_result` | 群主→成员 | 明文 | `{"type":"join_result","ok":true,"members":[{…owner…},{…member…}]}`（失败：`{"type":"join_result","ok":false,"reason":"WRONG_PIN","members":[]}`） |
| `ping` | 成员→群主 | 明文 | `{"type":"ping","device":{…DeviceInfo…},"seq":7,"ts_ms":1760000000000}` |
| `pong` | 群主→成员 | 明文 | `{"type":"pong","members":[{…},{…}],"seq":7,"ts_ms":1760000000031}`——seq 回应对应 ping；成员表快照即状态同步（角色看各成员的 role） |
| `clipboard` | 成员→群主→广播 | 加密 | `{"type":"clipboard","from":"<发送者 device.id>","payload":{"kind":"text","text":"hello","hash":"a1b2c3…(16 hex)"}}` |
| `members` | 群主→全员 | 明文 | `{"type":"members","members":[{…},{…}]}`——成员表含各自 file_list，即文件列表全量真相源 |
| `kick` | 群主→目标 | 加密 | `{"type":"kick","reason":"KICKED"}` |
| `dissolve` | 群主→全员 | 加密 | `{"type":"dissolve"}` |
| `transfer_offer` | 群主→被转让者 | 加密 | `{"type":"transfer_offer","pin":"510443"}` |
| `transfer_ready` | 被转让者→群主 | 加密 | `{"type":"transfer_ready","addr":"10.0.0.9:41235"}` |
| `transfer_error` | 群主→被转让者 | 加密 | `{"type":"transfer_error"}`——转让已失败（群主收到 ready 已超时），目标静默关闭多余的 standby 服务 |
| `new_service` | 群主→全员 | 加密 | `{"type":"new_service","addr":"10.0.0.9:41235","pin":"510443","owner":{…DeviceInfo…}}` |
| `encrypted` | 双向 | — | `{"type":"encrypted","data":"<base64(nonce‖AES-256-GCM 密文)>"}` |
| `file_add` | 任意→群主→全员 | 明文 | `{"type":"file_add","from":"<id>","file":{…SharedFile…}}`——自己文件夹新增（**仅主动上传**；下载完成不再触发、不产生新 file_id） |
| `file_remove` | 任意→群主→全员 | 明文 | `{"type":"file_remove","from":"<id>","file_ids":["<uuid>",…]}`——删除自己文件夹内的一条或多条（带设备 + id 数组；仅移清单，不动磁盘） |

> 文件共享的完整设计（设备文件夹=供种者、`file_list` 内嵌成员表=唯一真相源、两级目录 UI、P2P 分块直传、跨群可见）见 [file-sharing.md](file-sharing.md)。上表两条（`file_add`/`file_remove`）仅覆盖文件增删的**增量控制面信令**；全量列表走成员表；文件本体走成员直连，群主零字节中转。已删 `file_announce` / `file_list`（file_list 内嵌成员表）、`file_seed` / `file_req`（文件夹归属即供种者，点谁的文件直接连谁）与 `SharedFile` 的 `owner` / `seeders` 字段。

> 群主身份判定：`members` 中 `role=="owner"` 的那条（不再依赖数组位置）。

## 4. 心跳与失联

- **成员侧**：每 **30s** 发 ping；发出后 **3s** 内未收到对应 seq 的 pong → **判群主失联（单次即判）** → 断连弹窗（见 §6.4）。
- **群主侧看门狗**：每连接 **35s**（30s + 3s 超时 + 余量）无任何入站消息 → 断开连接并广播 members（假死剔除）。
- 读循环退出（被踢/服务端关闭/出错）时心跳任务静默结束，不误报失联。
- Android 从后台切回前台且在群页面时，立即补发一次 ping（`GroupClient.pingNow()`，序号与心跳共用）。

## 5. 剪贴板同步

```
本机复制（watcher 检测到）
  ① 先写入自己的剪贴板记录（本地历史）
  ② 发 clipboard{from=自己 id} → 群主广播全员
接收方
  from == 自己 id ？→ 忽略（不进历史、不写剪贴板）   ← 主去重：from 比对
  hash == clipGuard？→ 忽略（回声/重复）              ← 指纹闸门
  前台             → 进历史 + 写本机剪贴板（同步双闸门）
  后台（Android）   → 进历史 + 悬浮通知（不写剪贴板）；点「复制」才写入并同步闸门
群内点击历史条目复制
  → 只写本机剪贴板，不发任何 ws（双闸门防本机 watcher 回环）
```

Android 侧闸门为双层：`clipGuard`（内容哈希）+ `lastSyncText`（文本级回声）；前台写入、
后台通知复制、点击历史复制三条路径都即时同步，系统监听器读到相同内容即静默。
受 Android 10+ 后台读剪贴板限制，后台不轮询/不读，改由悬浮通知告知（见 ARCHITECTURE.md）。

## 6. 生命周期事件

### 6.1 成员加入 / 离开 / 被踢
- 群主在 join 成功、连接断开、kick 执行后**立即广播 `members`**。
- 各成员收到即时更新设备列表 + toast，不等心跳。

### 6.2 解散（0.8s 缓冲）
```
群主：广播 dissolve → 自行关 ws、退出群页面（不等回执）
成员：收到 dissolve → 0.8s 后自动关 ws、退出群页面（不回发任何消息）
```

### 6.3 转让（三方握手，确定性迁移）
```
群主 → 被转让者: transfer_offer{pin}
被转让者：收到 → standby 端口起 ws 服务
        → 通过原 ws 回 transfer_ready{addr}
群主：收到 ready → 广播 new_service{addr, pin, owner}
     → 自行关 ws、退出群页面
其他成员：收到 new_service → 断开旧连接 → 用同 PIN 连接新 addr 入群
被转让者：作为新群主等待成员连入
```
- **失败兜底（5s 超时）**：
  - 群主发出 `transfer_offer` 后 **5s** 内未收到 `transfer_ready` → 提示转让失败，**不关闭 ws、不离开群**，原群继续。
  - 若 **5s 后**才收到迟到的 `transfer_ready` → 群主向目标回 `transfer_error`；目标收到后**静默关闭**多余的 standby ws 服务并退出转让流程（不提示、不影响自身成员状态）。
  - 被转让者 5s 内起服务失败/主动放弃 → 同样走转让失败路径（群主侧超时或收到异常即提示失败）。

### 6.4 群主失联 → 断连弹窗（无自动重连/承接）

当前不支持自动重连与群主顺位承接；成员判失联（§4）或 ws 出错断开 → **双端统一弹「连接已断开」弹窗**：

- 弹窗显示群主名称（取成员表 `role=="owner"`），按钮「重新加入」（主操作）/「退出群」（描边）。
- **重新加入**：用会话内记录的 addr + PIN 重走完整入群流程，全程静默 Loading；
  - 成功 / 已在群中（`ALREADY_IN_GROUP`）→ 按成功处理，关 Loading 刷新，无 toast；
  - **5s** 内未拿到成员表 → 判失败，关 Loading 弹窗重现（不无限 Loading），无 toast。
- **退出群** → 清 UI 回首页 → toast。
- 双端行为一致：Android `AppState.connLost` StateFlow + ConnLostDialog；
  桌面 bridge take 成员会话 → `knot://conn-lost {addr, pin, owner}` → 前端 for(;;) 弹窗循环。
- 升主（承接者转正为群主，`promoted`）与转让迁移（`new_service`）场景**不走**断连弹窗。

## 7. 双端实现差异（实现时保持一致）

| 项 | 约定 |
|---|---|
| 发现扫描 | 双端同一状态机（3s 周期）；Android 前台判定用 setInForeground，桌面用会话状态轮询 |
| 心跳定时器精度 | Android 协程 delay / Rust tokio sleep；后台冻结由 FGS + WakeLock 兜底（Android） |
| transfer 三方握手 | 双端消息语义逐字段一致；超时常量 3s 双端统一 |
| UI 错误码 | WRONG_PIN / ALREADY_IN_GROUP / OWNER_ONLY / NO_LAN / GROUP_LOST / PROMOTED / KICKED / DISSOLVED / TRANSFERRED / TRANSFER_FAILED / MIGRATED（桌面迁移完成）—— 双端 i18n 映射 |

## 8. 遗留问题（不在本次范围）

- PIN 明文传输 + 10⁶ 密钥空间（规划 ECDH + QR 配对）
- `try_send` 无背压：小信令仍走 try_send；文件本体改走 P2P 直连 + TCP 背压（见 file-sharing.md）
- 剪贴板无分块上限：文本量大时仍需分片（文件共享通道就绪后可复用其分块能力）
