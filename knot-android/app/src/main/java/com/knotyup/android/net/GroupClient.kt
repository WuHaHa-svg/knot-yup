package com.knotyup.android.net

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
                    is Msg.Clipboard -> trySend(Result.success(GroupEvent.Clipboard(msg.from, msg.payload)))
                    is Msg.Kick -> {
                        trySend(Result.success(GroupEvent.Kicked(msg.reason ?: "")))
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
