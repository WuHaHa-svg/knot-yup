# KnotYup WebSocket 群组通信设计

> 对应代码：`knot-core/src/net.rs`、`knot-core/src/protocol.rs`、`knot-core/src/crypto.rs`（桌面端）；
> `knot-android/.../net/KnotGroupServer.kt`、`GroupClient.kt`、`Protocol.kt`（Android 端）。
> 本文描述**当前实际实现**，末节列出已知取舍与可疑点供分析。

## 1. 总览

- **拓扑**：星型。群主 = WebSocket 服务端（监听 TCP），成员 = 客户端；所有消息经群主转发。
- **端口**：群主端口动态分配（入群/建群时系统分配或预留）；发现广播走 UDP 45691——群主每 1s 常规广播（另有建群突发）、扫描端每 5s 一个周期（窗口直出：收到即在线，未收到即剔除，误剔下窗自愈）。
- **技术栈**：桌面端 tokio + tokio-tungstenite；Android 端 Java-WebSocket（服务端）+ OkHttp（客户端）。
- **消息编码**：JSON 文本帧。结构 `{"type": "snake_case 标签", ...}`。
- **双端互通**：桌面群 ↔ Android 群任意组合，协议一致。

## 2. 消息协议

| 消息 | 方向 | 加密 | 时机与作用 |
|---|---|---|---|
| `join` | 客户端 → 群主 | **明文** | 入群握手，携带 `pin` + `device`（id/name/platform/**addr**）。addr = 本机预留的「承接群主」监听地址（ip:port），选主重连用 |
| `join_result` | 群主 → 客户端 | 明文 | ok=false 时带 reason（`WRONG_PIN`）；ok=true 带当前成员快照 |
| `members` | 群主 → 全员 | 加密 | 成员表变更广播。**列表 [0] 恒为群主自己**（成员顺序见 §6） |
| `clipboard` | 成员 → 群主 → 广播 | 加密 | 剪贴板同步。`payload = {kind, text, hash}`，hash = sha256 前 16 字节 hex，接收端防回环去重 |
| `kick` | 群主 → 目标设备 | 加密 | reason：`KICKED`（移出）/ `GROUP_DISSOLVED`（解散复用 kick 通道） |
| `owner_change` | 群主 → 全员 | 加密 | 群主退出前指定 `new_owner`；成员收到后断开、走选主/重连流程 |
| `ping` / `pong` | — | — | 协议中已定义但**实际未启用**（见 §6） |
| `encrypted` | 双向 | — | 加密信封：`data = base64(nonce[12] ‖ AES-256-GCM 密文)`，内部装上述任意消息 |

**加密原则**：握手（join / join_result）明文，其余全部装 `encrypted` 信封。
群主转发剪贴板时**原样转发密文**（不解密再加密），所有成员同 PIN 同密钥可解。

## 3. 加密设计

- 算法：AES-256-GCM，nonce 12 字节随机，每次加密独立。
- 密钥：`SHA-256("knotyup-v1|" + PIN)` —— 域分离的 PIN 派生。
- 代码注释中的规划：M1.x 升级为 QR 配对 ECDH 前向保密，当前仅保证局域网嗅探拿不到明文。
- 后果：**知道 PIN = 知道密钥**；PIN 本身又在明文 join 里传（见 §6-1）。

## 4. 生命周期时序

### 4.1 建群（群主侧）
1. 预留端口（先 bind 再关，或 Android 直接 `ServerSocket(0)` 常驻 standby）。
2. 启动 WS 服务端 → 生成 PIN（SecureRandom 6 位）→ 开始 UDP 广播（2.5s 周期，附建群突发）。
3. 成员表初始化为 `[owner]`。

### 4.2 入群（成员侧）
1. `ws://ip:port` 连接 → 发明文 `join{pin, device(含 standby addr)}`。
2. 收 `join_result{ok}`；失败（WRONG_PIN）即断开。
3. 成功后进入读循环，事件抛 UI：Members / Clipboard / Kicked / OwnerChange / Error / Disconnected。
4. Android 客户端主动退出标志 `intentionalClose`：置位后断开不再上报（避免误报「连接失败」）。

### 4.3 剪贴板同步
```
成员 A 复制 → A 发 encrypted{clipboard} → 群主：
  ① 解密 → 通知本机 UI（入历史）
  ② 原密文转发给除 A 外所有成员
接收端 → 解密 → hash 与「指纹闸门」比对（防写入剪贴板被当成新复制回环）→ 写系统剪贴板
群主自己复制 → 直接加密广播全员
```

### 4.4 退出 / 踢人 / 解散
- **成员退出**：直接断开 ws。群主侧读循环结束 → 移出成员表 → 广播 members。
- **群主转让后退出**：广播 `owner_change{new_owner}` → 停服务（不再发解散）。
- **踢人**：发加密 `kick{KICKED}` → 移出并广播 members（Android 会主动 close 连接）。
- **解散**：全员发 `kick{GROUP_DISSOLVED}` → 停服务。

### 4.5 群主容灾（意外掉线 / 收到 owner_change）
所有成员统一走 `handleOwnerLost`：
1. 候选 = `members.drop(1)`（去掉 [0] 旧群主）取**第一个**；收到显式 `owner_change` 时候选 = new_owner。
2. 候选是自己 → 释放 standby 端口 → 用**同 PIN** 承接为群主（成员表其余人靠重连回来）。
3. 否则 → 提示 RECONNECTING → 双重循环尝试：10 轮 × 依次连每个候选的 `device.addr`，间隔 500ms/候选、1500ms/轮。
4. 成功即 RECONNECTED；10 轮失败 → 群丢失（GROUP_LOST）。

## 5. 双端实现差异

| 项 | 桌面（Rust） | Android |
|---|---|---|
| 服务端成员容器 | `HashMap`（**无序**） | `LinkedHashMap`（插入序） |
| 踢人后是否主动断开目标连接 | 否（移出发送通道，靠后续超时） | 是（send 后 `close()`） |
| 解散/转让后是否主动断开成员 | 否（只停 accept，存量连接不关） | 是（send 后逐个 `close()`） |
| 错误消息 | 英文串直传 UI | `GroupEvent.Error` + Log |
| 客户端断开事件 | 无显式 Disconnected（Error 代替） | `onClosed → Disconnected`，`onFailure → Error` |
| ALREADY_IN_GROUP / NO_LAN 前置检查 | Tauri 命令层 | AppState/UI 层 |

## 6. 已知取舍与可疑点（供分析）

1. **PIN 明文传输 + PIN 即密钥**：明文 join 里带 PIN，抓包可直接拿到；且 6 位数字 PIN 的密钥空间只有 10⁶，离线暴力解密一个 `encrypted` 信封即可穷举。加密目前只防「顺手嗅探」，不防主动攻击者。
2. **Rust 端成员表无序 vs 选主规则依赖顺序**：选主取 `members.drop(1)[0]`，但 `HashMap` 迭代顺序不稳定——同一成员列表在不同设备上候选可能不同（Android LinkedHashMap 是插入序）。成员顺序来源于群主的 member_infos，实际由**群主的容器**决定：桌面群主广播的顺序不稳定，Android 群主稳定。跨端建群时各成员看到的候选顺序一致（都来自同一广播），但**不同次广播顺序可能变**，掉线瞬间的候选一致性没有保证。
3. **无应用层心跳**：协议定义了 ping/pong 但 Rust 端收到 Ping 是空操作、双端都不主动发。断线感知完全依赖 TCP 超时（可能分钟级），选主触发会滞后。
4. **广播无背压**：Rust 端 `tx.try_send`（channel 容量 64）满了静默丢弃，无重试无通知；大文本/高频复制时可能静默丢消息。
5. **剪贴板消息无大小上限**：`text` 直接整个塞 JSON + base64，无分块（规划 M4 分块直传，当前仅文本尚可）。
6. **重复 join 未防重**：同一 device.id 二次 join 直接覆盖旧条目（HashMap insert），旧连接成为僵尸直到 TCP 断开。
7. **密文原样转发的语义**：群主转发成员密文时所有成员用同一密钥可解——任何成员拿到 PIN 就能解所有历史流量（无每会话密钥隔离）。
8. **解散与踢人复用 kick 通道**：靠 reason 字符串区分（`GROUP_DISSOLVED` vs `KICKED`），UI 据此给不同文案；语义耦合但实现简单。
9. **Rust 端 stop() 不关存量连接**（解散/转让后）：成员若没收到最后的加密消息（如刚好丢包），只能等 TCP 超时才触发选主，与 Android 行为不一致。
10. **选主重连的候选地址时效性**：`device.addr` 是入群时的 IP，DHCP 换 IP / 换网络后失效，重连必然失败。

## 7. UI 层错误码映射

`WRONG_PIN` / `ALREADY_IN_GROUP` / `OWNER_ONLY` / `NO_LAN` / `GROUP_DISSOLVED` / `GROUP_LOST` / `PROMOTED` / `RECONNECTING` / `RECONNECTED` / `KICKED` —— 均在双端 UI 层做 i18n 映射，协议层只传码。
