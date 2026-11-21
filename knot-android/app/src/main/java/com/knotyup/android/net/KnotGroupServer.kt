package com.knotyup.android.net

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Android 端群主服务端 v2（与 knot-core net/server.rs 行为一致）：
 * 星型拓扑、PIN 入群、AES-GCM 加密封装、成员表广播、移出、解散（独立消息）、
 * 转让三方握手（offer→ready→new_service，5s 超时兜底）、35s 心跳看门狗。
 */
class KnotGroupServer(
    port: Int,
    private val pin: String,
    owner: DeviceInfo,
    private val onEvent: (GroupEvent) -> Unit,
) : WebSocketServer(InetSocketAddress(port)) {

    private val cipher = KnotCipher(pin)
    private var owner = owner.copy(role = "owner") // 服务端权威赋值；file_list 随本机文件增删变化
    private val members = LinkedHashMap<String, Pair<DeviceInfo, WebSocket>>()
    // 传输任务数组（群主权威；终态锁定，保留到群解散）
    private val tasks = ConcurrentHashMap<String, TransferTask>()
    // 心跳看门狗：每连接最近活跃时间（任何入站消息都算活跃）
    private val lastActive = ConcurrentHashMap<WebSocket, Long>()
    // 转让回执等待表：device_id → future（transfer() 注册，onMessage 收到 TransferReady 完成）
    private val transferWaiters = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "knot-heartbeat-watchdog").apply { isDaemon = true }
    }

    override fun onStart() {
        // 成员每 30s 心跳，超过 35s（心跳 + 3s 超时 + 余量）无入站消息即判定假死
        watchdog.scheduleAtFixedRate({ checkIdle() }, 5, 5, TimeUnit.SECONDS)
    }

    override fun onOpen(ws: WebSocket, handshake: ClientHandshake) {
        lastActive[ws] = System.currentTimeMillis()
    }

    override fun onClose(ws: WebSocket, code: Int, reason: String, remote: Boolean) {
        lastActive.remove(ws)
        val id = members.entries.firstOrNull { it.value.second == ws }?.key ?: return
        members.remove(id) // fileList 内嵌在成员表里，移除成员即移除其文件夹
        transferWaiters.remove(id)?.cancel(true)
        broadcastMembers()
    }

    private fun checkIdle() {
        val now = System.currentTimeMillis()
        lastActive.entries.filter { now - it.value > Proto.WATCHDOG_MS }.forEach { (ws, since) ->
            android.util.Log.w("KnotYup", "心跳看门狗：连接空闲超 ${(now - since) / 1000}s，关闭")
            runCatching { ws.close(1000, "HEARTBEAT_TIMEOUT") } // → 对端 onClose，本地 onClose 走移除广播
        }
    }

    override fun onMessage(ws: WebSocket, message: String) {
        lastActive[ws] = System.currentTimeMillis()
        val senderEntry = members.entries.firstOrNull { it.value.second == ws }
        val msg = Msg.parse(message) ?: return

        if (senderEntry == null) {
            // 入群握手（明文）
            if (msg is Msg.Join && msg.pin == pin) {
                val dev = msg.device.copy(role = "member") // 服务端权威赋值
                members[dev.id] = dev to ws
                ws.send(Msg.JoinResult(true, null, memberList()).toJson())
                // fileList 已内嵌在成员表里，JoinResult.members 自带全群文件列表
                // 传输任务数组不内嵌成员表，入群后补发一条全量快照（明文）
                ws.send(Msg.FileTasks(taskSnapshot()).toJson())
                broadcastMembers()
            } else {
                ws.send(Msg.JoinResult(false, "WRONG_PIN", emptyList()).toJson())
                ws.close(1008, "WRONG_PIN")
            }
            return
        }
        val senderId = senderEntry.key

        val inner = if (msg is Msg.Encrypted) Msg.parse(cipher.decrypt(msg.data) ?: return) else msg
        when (inner) {
            is Msg.Ping -> {
                // 回执：成员表快照即状态同步（群主身份看 role 字段）
                android.util.Log.d("KnotYup", "<- ping#${inner.seq} -> pong#${inner.seq}")
                runCatching {
                    ws.send(Msg.Pong(memberList(), inner.seq, System.currentTimeMillis()).toJson())
                }
            }
            is Msg.Clipboard -> {
                onEvent(GroupEvent.Clipboard(inner.from, inner.fromName, inner.payload))
                // 原样转发密文给其他成员
                members.values.forEach { (d, w) ->
                    if (d.id != senderId) runCatching { w.send(message) }
                }
            }
            is Msg.FileAdd -> {
                // 成员表是文件列表唯一真相源：更新发送者 DeviceInfo.file_list
                updateMemberFiles(inner.from) { list ->
                    if (list.none { it.fileId == inner.file.fileId }) list.add(inner.file)
                }
                onEvent(GroupEvent.FileAdd(inner.from, inner.fromName, inner.file))
                members.values.forEach { (d, w) ->
                    if (d.id != senderId) runCatching { w.send(message) }
                }
            }
            is Msg.FileRemove -> {
                updateMemberFiles(inner.from) { list ->
                    list.removeAll { f -> f.fileId in inner.fileIds }
                }
                onEvent(GroupEvent.FileRemove(inner.from, inner.fromName, inner.fileIds))
                members.values.forEach { (d, w) ->
                    if (d.id != senderId) runCatching { w.send(message) }
                }
            }
            is Msg.FileTaskAdd -> {
                if (!tasks.containsKey(inner.task.taskId)) tasks[inner.task.taskId] = inner.task
                broadcastTasks()
            }
            is Msg.FileTaskQueued -> {
                applyTask(inner.taskId) { t ->
                    if (t.status == TaskStatus.QUEUING) t.copy(updatedAt = System.currentTimeMillis()) else t
                }
                broadcastTasks()
            }
            is Msg.FileTaskStarted -> {
                applyTask(inner.taskId) { t ->
                    if (t.status == TaskStatus.QUEUING) t.copy(
                        status = TaskStatus.TRANSFERRING, updatedAt = System.currentTimeMillis(),
                    ) else t
                }
                broadcastTasks()
            }
            is Msg.FileTaskProgress -> {
                applyTask(inner.taskId) { t ->
                    if (t.status == TaskStatus.TRANSFERRING && inner.progress >= t.progress) t.copy(
                        progress = inner.progress.coerceAtMost(100), transferred = inner.transferred,
                        updatedAt = System.currentTimeMillis(),
                    ) else t
                }
                broadcastTasks()
            }
            is Msg.FileTaskFinish -> {
                applyTask(inner.taskId) { t ->
                    if (t.status == TaskStatus.TRANSFERRING) t.copy(
                        status = TaskStatus.DONE, progress = 100, updatedAt = System.currentTimeMillis(),
                    ) else t
                }
                broadcastTasks()
            }
            is Msg.FileTaskFailed -> {
                // 终态锁定：已 done/failed 的任务忽略后到终态
                applyTask(inner.taskId) { t ->
                    if (t.status != TaskStatus.DONE && t.status != TaskStatus.FAILED) t.copy(
                        status = TaskStatus.FAILED, error = inner.error, updatedAt = System.currentTimeMillis(),
                    ) else t
                }
                broadcastTasks()
            }
            is Msg.TransferReady -> {
                // 转让回执：有等待者则完成握手；无（已超时）则回 transfer_error
                val waiter = transferWaiters.remove(senderId)
                if (waiter != null) {
                    android.util.Log.d("KnotYup", "<- transfer_ready addr=${inner.addr}")
                    waiter.complete(inner.addr)
                } else {
                    android.util.Log.d("KnotYup", "<- transfer_ready（迟到）-> transfer_error")
                    runCatching { ws.send(Msg.TransferError.sealed(cipher).toJson()) }
                }
            }
            else -> {}
        }
    }

    override fun onError(ws: WebSocket?, ex: Exception) {}

    private fun memberList(): List<DeviceInfo> = listOf(owner) + members.values.map { it.first }

    private fun broadcastMembers() {
        val list = memberList()
        val sealed = Msg.Members(list).sealed(cipher).toJson()
        members.values.forEach { (_, w) -> runCatching { w.send(sealed) } }
        onEvent(GroupEvent.Members(list))
    }

    /** 实时成员数（发现应答用）。 */
    fun memberCount(): Int = 1 + members.size

    /** 群主本机登记新任务（下载方为群主自己时）。 */
    fun localTaskAdd(task: TransferTask) {
        if (!tasks.containsKey(task.taskId)) tasks[task.taskId] = task
        broadcastTasks()
    }

    /** 群主本机任务事件（群主自己下载/供种时无 ws 自连接，走这里）。 */
    fun localTaskEvent(ev: TaskEvent) {
        when (ev) {
            is TaskEvent.Queued -> applyTask(ev.taskId) { it.copy(updatedAt = System.currentTimeMillis()) }
            is TaskEvent.Started -> applyTask(ev.taskId) { t ->
                if (t.status == TaskStatus.QUEUING) t.copy(status = TaskStatus.TRANSFERRING, updatedAt = System.currentTimeMillis()) else t
            }
            is TaskEvent.Progress -> applyTask(ev.taskId) { t ->
                if (t.status == TaskStatus.TRANSFERRING) {
                    val p = if (ev.total == 0L) 0 else (ev.transferred * 100 / ev.total).toInt().coerceAtMost(100)
                    if (p >= t.progress) t.copy(progress = p, transferred = ev.transferred, updatedAt = System.currentTimeMillis()) else t
                } else t
            }
            is TaskEvent.Done -> applyTask(ev.taskId) { t ->
                if (t.status == TaskStatus.TRANSFERRING) t.copy(status = TaskStatus.DONE, progress = 100, updatedAt = System.currentTimeMillis()) else t
            }
            is TaskEvent.Failed -> applyTask(ev.taskId) { t ->
                if (t.status != TaskStatus.DONE && t.status != TaskStatus.FAILED) t.copy(
                    status = TaskStatus.FAILED, error = ev.error, updatedAt = System.currentTimeMillis(),
                ) else t
            }
        }
        broadcastTasks()
    }

    /** 按 task_id 原地修改任务（不存在忽略）。 */
    private fun applyTask(taskId: String, mutate: (TransferTask) -> TransferTask) {
        val t = tasks[taskId] ?: return
        tasks[taskId] = mutate(t)
    }

    /** 任务数组快照（按创建时间排序）。 */
    fun taskSnapshot(): List<TransferTask> =
        tasks.values.sortedBy { it.createdAt }

    /** 广播全量任务数组（明文）+ 通知本机 UI。 */
    fun broadcastTasks() {
        val list = taskSnapshot()
        val json = Msg.FileTasks(list).toJson()
        members.values.forEach { (_, w) -> runCatching { w.send(json) } }
        onEvent(GroupEvent.FileTasks(list))
    }

    /** 本机（群主）剪贴板变化 → 加密广播。 */
    fun broadcastClipboard(text: String) {
        val payload = ClipboardPayload("text", text, textHash(text))
        val sealed = Msg.Clipboard(owner.id, owner.name, payload).sealed(cipher).toJson()
        members.values.forEach { (_, w) -> runCatching { w.send(sealed) } }
    }

    /** 群主本机新增文件 → 更新 owner.file_list + 加密广播。 */
    fun broadcastFileAdd(file: SharedFile) {
        if (owner.fileList.none { it.fileId == file.fileId }) {
            owner = owner.copy(fileList = owner.fileList + file)
        }
        val sealed = Msg.FileAdd(owner.id, owner.name, file).sealed(cipher).toJson()
        members.values.forEach { (_, w) -> runCatching { w.send(sealed) } }
        onEvent(GroupEvent.FileAdd(owner.id, owner.name, file))
    }

    /** 群主本机删除文件 → 更新 owner.file_list + 加密广播。 */
    fun broadcastFileRemove(fileIds: List<String>) {
        owner = owner.copy(fileList = owner.fileList.filterNot { it.fileId in fileIds })
        val sealed = Msg.FileRemove(owner.id, owner.name, fileIds).sealed(cipher).toJson()
        members.values.forEach { (_, w) -> runCatching { w.send(sealed) } }
        onEvent(GroupEvent.FileRemove(owner.id, owner.name, fileIds))
    }

    /** 更新成员表里某设备 DeviceInfo.file_list（Pair 不可变，remove + put）。 */
    private fun updateMemberFiles(deviceId: String, mutate: (MutableList<SharedFile>) -> Unit) {
        val entry = members[deviceId] ?: return
        val list = entry.first.fileList.toMutableList()
        mutate(list)
        members[deviceId] = entry.first.copy(fileList = list) to entry.second
    }

    /** 移出成员。 */
    fun kick(id: String) {
        val e = members.remove(id) ?: return
        runCatching {
            e.second.send(Msg.Kick("KICKED").sealed(cipher).toJson())
            e.second.close()
        }
        broadcastMembers()
    }

    /** 解散（v2）：广播独立 dissolve 消息后关闭。成员收到后 0.8s 自行退出，不回发。 */
    fun dissolve() {
        val sealed = Msg.Dissolve.sealed(cipher).toJson()
        members.values.forEach { (_, w) -> runCatching { w.send(sealed); w.close() } }
        members.clear()
        watchdog.shutdownNow()
        runCatching { stop(0) }
    }

    /**
     * 转让（v2 三方握手，异步）：发 offer → 等 ready（5s 超时）→ 广播 new_service → 关闭。
     * 失败（超时/目标不存在/迟到）经 GroupEvent.TransferFailed 上报，原群继续（不关闭本服务）。
     */
    fun transfer(targetId: String) {
        val target = members[targetId]?.first
        if (target == null) {
            android.util.Log.w("KnotYup", "transfer: 目标 $targetId 不在成员表")
            onEvent(GroupEvent.TransferFailed)
            return
        }
        val targetConn = members[targetId]!!.second
        // 1. 发 offer
        runCatching { targetConn.send(Msg.TransferOffer(pin).sealed(cipher).toJson()) }
        android.util.Log.d("KnotYup", "-> transfer_offer -> ${target.name}")

        // 2. 注册等待者 → 5s 超时
        val future = CompletableFuture<String>()
        transferWaiters[targetId] = future
        Thread({
            try {
                val addr = future.get(Proto.TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                // 3a. ready：广播 new_service（owner=target，role=owner）后关闭
                android.util.Log.d("KnotYup", "转让就绪 -> new_service 广播 addr=$addr")
                val newOwner = target.copy(role = "owner")
                val ns = Msg.NewService(addr, pin, newOwner).sealed(cipher).toJson()
                members.values.forEach { (_, w) -> runCatching { w.send(ns) } }
                Thread.sleep(300) // 给出站消息一点时间
                members.values.forEach { (_, w) -> runCatching { w.close() } }
                members.clear()
                watchdog.shutdownNow()
                runCatching { stop(0) }
                // 通知会话层：旧群主清会话回首页
                onEvent(GroupEvent.TransferCompleted)
            } catch (_: Exception) {
                // 3b. 超时/取消：转让失败，原群继续
                if (transferWaiters.remove(targetId) != null) {
                    android.util.Log.w("KnotYup", "transfer: $targetId 未在 5s 内 ready，转让失败")
                    onEvent(GroupEvent.TransferFailed)
                }
                // 已被移除 = 迟到 ready 已按 transfer_error 处理（onMessage 分支），不再上报
            }
        }, "knot-transfer").apply { isDaemon = true }.start()
    }

    /** 实际监听端口（构造传 0 时由系统分配）。 */
    fun boundPort(): Int = port
}
