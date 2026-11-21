package com.knotyup.android.net

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** 群事件（上行到 UI/会话层，与 knot-core CoreEvent v2 对应）。 */
sealed class GroupEvent {
    data class Members(val members: List<DeviceInfo>) : GroupEvent()
    data class Clipboard(val from: String, val fromName: String, val payload: ClipboardPayload) : GroupEvent()
    data class Kicked(val reason: String) : GroupEvent()
    /** 解散：会话层 0.8s 后自动退出（不回发任何消息） */
    object Dissolve : GroupEvent()
    /** 收到转让邀请（本机为被转让者）：起 standby 服务后回 TransferReady */
    data class TransferOffer(val pin: String) : GroupEvent()
    /** 转让已失败（迟到 ready）：静默关闭 standby 服务 */
    object TransferError : GroupEvent()
    /** 全员迁移到新服务：断开当前连接、用同 PIN 连接新 addr */
    data class NewService(val addr: String, val pin: String, val owner: DeviceInfo) : GroupEvent()
    /** 转让失败（群主侧本地事件，不经网络） */
    object TransferFailed : GroupEvent()
    /** 转让成功（群主侧本地事件）：服务已停 → 会话层清会话回首页 */
    object TransferCompleted : GroupEvent()
    data class Error(val message: String) : GroupEvent()
    /** 某设备文件夹新增文件 */
    data class FileAdd(val from: String, val fromName: String, val file: SharedFile) : GroupEvent()
    /** 某设备删除文件条目 */
    data class FileRemove(val from: String, val fromName: String, val fileIds: List<String>) : GroupEvent()
    /** 传输任务数组全量快照（群主广播；客户端整体替换） */
    data class FileTasks(val tasks: List<TransferTask>) : GroupEvent()
}

/** 群客户端 v2：连接群主 WebSocket，自动处理加密封装/解封、心跳（30s/单次 3s 失联）、回声去重。 */
class GroupClient(
    private val client: OkHttpClient = OkHttpClient(),
) {
    private var ws: WebSocket? = null
    private var cipher: KnotCipher? = null

    /** 主动退出标志：置位后不再向上抛连接断开/错误事件 */
    @Volatile
    private var intentionalClose = false

    var localDevice: DeviceInfo? = null
        private set

    /** ping 序号（心跳协程与 pingNow 共用，避免前台即时 ping 与心跳序号撞车） */
    private val pingSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** 连接并加入群。返回事件流；流的取消（collect 结束）即断开。 */
    fun join(addr: String, pin: String, device: DeviceInfo): Flow<Result<GroupEvent>> = callbackFlow {
        intentionalClose = false
        localDevice = device
        cipher = KnotCipher(pin)
        val url = if (addr.startsWith("ws://")) addr else "ws://$addr"
        val request = Request.Builder().url(url).build()
        // 防双实例：新连接前确保旧连接取消（遗留 socket 会与心跳/事件竞争）
        ws?.cancel()
        // 心跳共享状态：最近一次被回执的 ping 序号（onMessage 更新，心跳协程检查）。
        // 写在 OkHttp 读线程、读在心跳协程——必须用原子量（普通变量跨线程无可见性保证）
        val lastPongSeq = java.util.concurrent.atomic.AtomicLong(0)
        val echoId = device.id

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 明文握手（协议规定）
                webSocket.send(Msg.Join(pin, device).toJson())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                var msg = Msg.parse(text) ?: return
                if (msg is Msg.Encrypted) {
                    val inner = cipher?.decrypt((msg as Msg.Encrypted).data)
                        ?.let { Msg.parse(it) } ?: return
                    msg = inner
                }
                when (msg) {
                    is Msg.JoinResult -> {
                        if (msg.ok) trySend(Result.success(GroupEvent.Members(msg.members)))
                        else {
                            trySend(Result.failure(IllegalStateException(msg.reason ?: "REJECTED")))
                            close()
                        }
                    }
                    is Msg.Members -> trySend(Result.success(GroupEvent.Members(msg.members)))
                    is Msg.Pong -> {
                        lastPongSeq.set(msg.seq)
                        // Pong 携带成员表快照 → 心跳即状态同步
                        trySend(Result.success(GroupEvent.Members(msg.members)))
                    }
                    is Msg.Clipboard -> {
                        // from-id 回声去重：自己发出去的广播不再上报
                        if (msg.from == echoId) return
                        trySend(Result.success(GroupEvent.Clipboard(msg.from, msg.fromName, msg.payload)))
                    }
                    is Msg.Kick -> {
                        trySend(Result.success(GroupEvent.Kicked(msg.reason ?: "")))
                        close()
                    }
                    Msg.Dissolve -> {
                        trySend(Result.success(GroupEvent.Dissolve))
                        close()
                    }
                    is Msg.TransferOffer -> trySend(Result.success(GroupEvent.TransferOffer(msg.pin)))
                    Msg.TransferError -> trySend(Result.success(GroupEvent.TransferError))
                    is Msg.NewService -> {
                        trySend(Result.success(GroupEvent.NewService(msg.addr, msg.pin, msg.owner)))
                        close()
                    }
                    is Msg.FileAdd -> trySend(Result.success(GroupEvent.FileAdd(msg.from, msg.fromName, msg.file)))
                    is Msg.FileRemove -> trySend(Result.success(GroupEvent.FileRemove(msg.from, msg.fromName, msg.fileIds)))
                    is Msg.FileTasks -> trySend(Result.success(GroupEvent.FileTasks(msg.tasks)))
                    else -> {}
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!intentionalClose) trySend(Result.failure(t))
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!intentionalClose) {
                    // 视为连接断开：抛 Error 触发上层的承接（与 Rust 侧语义一致）
                    trySend(Result.success(GroupEvent.Error("connection closed: server closed connection")))
                }
                close()
            }
        })
        ws = socket

        // 心跳：每 30s 发一次 Ping；3s 内未收到对应 seq 的 Pong —— 单次即判群主失联 → 断开触发承接
        launch {
            android.util.Log.i("KnotYup", "心跳任务启动（30s 间隔 / 3s 超时 / 单次失联）")
            while (true) {
                delay(Proto.HEARTBEAT_MS)
                val seq = pingSeq.incrementAndGet()
                android.util.Log.d("KnotYup", "-> ping#$seq")
                socket.send(Msg.Ping(device, seq, System.currentTimeMillis()).toJson())
                val deadline = System.currentTimeMillis() + Proto.PONG_TIMEOUT_MS
                var acked = false
                while (System.currentTimeMillis() < deadline) {
                    if (lastPongSeq.get() >= seq) { acked = true; break }
                    delay(300)
                }
                if (!acked) {
                    android.util.Log.w("KnotYup", "ping#$seq 3s 未回执 → 判定群主失联")
                    socket.cancel() // 触发 onFailure → 承接流程
                    break
                }
            }
        }

        awaitClose {
            socket.cancel()
            ws = null
        }
    }

    /** 立即发一次心跳（后台切回前台且在群页面时调用）：Pong 携带成员表快照，即刻刷新状态。
     *  只发不查——失联判定仍由 30s 心跳协程负责，这里只求快速同步。 */
    fun pingNow(): Boolean {
        val dev = localDevice ?: return false
        val socket = ws ?: return false
        val seq = pingSeq.incrementAndGet()
        android.util.Log.d("KnotYup", "-> ping#$seq (前台即时)")
        return socket.send(Msg.Ping(dev, seq, System.currentTimeMillis()).toJson())
    }

    /** 发送本机剪贴板变化（加密信封）。 */
    fun sendClipboard(text: String): Boolean {
        val dev = localDevice ?: return false
        val c = cipher ?: return false
        val socket = ws ?: return false
        val payload = ClipboardPayload("text", text, textHash(text))
        return socket.send(Msg.Clipboard(dev.id, dev.name, payload).sealed(c).toJson())
    }

    /** 转让第二步：standby 服务就绪后，通过原 ws 回告群主。 */
    fun sendTransferReady(addr: String): Boolean {
        val c = cipher ?: return false
        val socket = ws ?: return false
        return socket.send(Msg.TransferReady(addr).sealed(c).toJson())
    }

    /** 自己文件夹新增。 */
    fun sendFileAdd(file: SharedFile): Boolean {
        val dev = localDevice ?: return false
        val c = cipher ?: return false
        val socket = ws ?: return false
        return socket.send(Msg.FileAdd(dev.id, dev.name, file).sealed(c).toJson())
    }

    /** 删除自己文件夹内条目。 */
    fun sendFileRemove(fileIds: List<String>): Boolean {
        val dev = localDevice ?: return false
        val c = cipher ?: return false
        val socket = ws ?: return false
        return socket.send(Msg.FileRemove(dev.id, dev.name, fileIds).sealed(c).toJson())
    }

    /** 传输中心消息统一发送（明文，不进加密信封）。 */
    private fun sendTaskMsg(msg: Msg): Boolean {
        val socket = ws ?: return false
        return socket.send(msg.toJson())
    }

    /** ① 任务诞生（下载方）：status=queuing。 */
    fun sendTaskAdd(task: TransferTask): Boolean = sendTaskMsg(Msg.FileTaskAdd(task))
    /** ② 供种方确认排队。 */
    fun sendTaskQueued(taskId: String): Boolean = sendTaskMsg(Msg.FileTaskQueued(taskId))
    /** ③ 供种方开始发送。 */
    fun sendTaskStarted(taskId: String): Boolean = sendTaskMsg(Msg.FileTaskStarted(taskId))
    /** ④ 供种方节流进度。 */
    fun sendTaskProgress(taskId: String, progress: Int, transferred: Long): Boolean =
        sendTaskMsg(Msg.FileTaskProgress(taskId, progress, transferred))
    /** ⑤ 供种方完成。 */
    fun sendTaskFinish(taskId: String): Boolean = sendTaskMsg(Msg.FileTaskFinish(taskId))
    /** ⑥ 失败（双方都报，幂等）。 */
    fun sendTaskFailed(taskId: String, error: String): Boolean {
        val dev = localDevice ?: return false
        return sendTaskMsg(Msg.FileTaskFailed(taskId, error, dev.id))
    }

    fun leave() {
        intentionalClose = true
        ws?.cancel()
        ws = null
    }
}
