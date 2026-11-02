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

/** 群事件（上行到 UI）。 */
sealed class GroupEvent {
    data class Members(val members: List<DeviceInfo>) : GroupEvent()
    data class Clipboard(val from: String, val payload: ClipboardPayload) : GroupEvent()
    data class Kicked(val reason: String) : GroupEvent()
    data class OwnerChange(val newOwner: DeviceInfo) : GroupEvent()
    data class Error(val message: String) : GroupEvent()
    data object Disconnected : GroupEvent()
}

/** 群客户端：连接群主 WebSocket，自动处理加密封装/解封。 */
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

    /** 连接并加入群。返回事件流；流的取消（collect 结束）即断开。 */
    fun join(addr: String, pin: String, device: DeviceInfo): Flow<Result<GroupEvent>> = callbackFlow {
        intentionalClose = false
        localDevice = device
        cipher = KnotCipher(pin)
        val url = if (addr.startsWith("ws://")) addr else "ws://$addr"
        val request = Request.Builder().url(url).build()
        // 防双实例：新连接前确保旧连接取消（遗留 socket 会与心跳/事件竞争）
        ws?.cancel()
        // 心跳共享状态：最近一次被回执的 ping 序号（onMessage 更新，心跳协程检查）
        var lastPongSeq = 0L

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
                        android.util.Log.d("KnotYup", "<- pong#${msg.seq} members=${msg.members.size}")
                        lastPongSeq = msg.seq
                        // Pong 携带群状态快照 → 同步成员表
                        trySend(Result.success(GroupEvent.Members(msg.members)))
                    }
                    is Msg.Clipboard -> trySend(Result.success(GroupEvent.Clipboard(msg.from, msg.payload)))
                    is Msg.Kick -> {
                        trySend(Result.success(GroupEvent.Kicked(msg.reason ?: "")))
                        close()
                    }
                    is Msg.OwnerChange -> {
                        trySend(Result.success(GroupEvent.OwnerChange(msg.newOwner)))
                        close()
                    }
                    else -> {}
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!intentionalClose) trySend(Result.failure(t))
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!intentionalClose) trySend(Result.success(GroupEvent.Disconnected))
                close()
            }
        })
        ws = socket

        // 心跳：每 5s 发一次 Ping；3s 内未收到对应 seq 的 Pong 计一次未达；
        // 连续 3 次未达 → 判定群主失联，断开连接（onFailure → 选主流程）
        launch {
            android.util.Log.i("KnotYup", "心跳任务启动（5s 间隔 / 3s 超时 / 3 次未达失联）")
            var seq = 0L
            var misses = 0
            while (true) {
                delay(5_000)
                seq++
                android.util.Log.d("KnotYup", "-> ping#$seq")
                socket.send(Msg.Ping(device, seq, System.currentTimeMillis()).toJson())
                val deadline = System.currentTimeMillis() + 3_000
                var acked = false
                while (System.currentTimeMillis() < deadline) {
                    if (lastPongSeq >= seq) { acked = true; break }
                    delay(300)
                }
                if (acked) {
                    misses = 0
                } else {
                    misses++
                    android.util.Log.w("KnotYup", "ping#$seq 3s 未回执，连续未达 $misses/3")
                    if (misses >= 3) {
                        android.util.Log.w("KnotYup", "心跳判定群主失联（连续 3 次未回执）")
                        socket.cancel() // 触发 onFailure → 现有选主逻辑
                        break
                    }
                }
            }
        }

        awaitClose {
            socket.cancel()
            ws = null
        }
    }

    /** 发送本机剪贴板变化（加密信封）。 */
    fun sendClipboard(text: String): Boolean {
        val dev = localDevice ?: return false
        val c = cipher ?: return false
        val socket = ws ?: return false
        val payload = ClipboardPayload("text", text, textHash(text))
        return socket.send(Msg.Clipboard(dev.id, payload).sealed(c).toJson())
    }

    fun leave() {
        intentionalClose = true
        ws?.cancel()
        ws = null
    }
}
