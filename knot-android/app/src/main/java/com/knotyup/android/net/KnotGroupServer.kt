package com.knotyup.android.net

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * Android 端群主服务端（与 knot-core net.rs 的 GroupServer 行为一致）：
 * 星型拓扑、PIN 入群、AES-GCM 加密封装、成员表广播、移出、解散。
 */
class KnotGroupServer(
    port: Int,
    private val pin: String,
    private val owner: DeviceInfo,
    private val onEvent: (GroupEvent) -> Unit,
) : WebSocketServer(InetSocketAddress(port)) {

    private val cipher = KnotCipher(pin)
    private val members = LinkedHashMap<String, Pair<DeviceInfo, WebSocket>>()

    override fun onStart() {}

    override fun onOpen(ws: WebSocket, handshake: ClientHandshake) {}

    override fun onClose(ws: WebSocket, code: Int, reason: String, remote: Boolean) {
        val id = members.entries.firstOrNull { it.value.second == ws }?.key ?: return
        members.remove(id)
        broadcastMembers()
    }

    override fun onMessage(ws: WebSocket, message: String) {
        val senderId = members.entries.firstOrNull { it.value.second == ws }?.key
        val msg = Msg.parse(message) ?: return

        if (senderId == null) {
            // 入群握手（明文）
            if (msg is Msg.Join && msg.pin == pin) {
                members[msg.device.id] = msg.device to ws
                ws.send(Msg.JoinResult(true, null, memberList()).toJson())
                broadcastMembers()
            } else {
                ws.send(Msg.JoinResult(false, "WRONG_PIN", emptyList()).toJson())
                ws.close(1008, "WRONG_PIN")
            }
            return
        }

        val inner = if (msg is Msg.Encrypted) Msg.parse(cipher.decrypt(msg.data) ?: return) else msg
        if (inner is Msg.Clipboard) {
            onEvent(GroupEvent.Clipboard(inner.from, inner.payload))
            // 原样转发密文给其他成员
            members.values.forEach { (d, w) ->
                if (d.id != senderId) runCatching { w.send(message) }
            }
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

    /** 本机（群主）剪贴板变化 → 加密广播。 */
    fun broadcastClipboard(text: String) {
        val payload = ClipboardPayload("text", text, textHash(text))
        val sealed = Msg.Clipboard(owner.id, payload).sealed(cipher).toJson()
        members.values.forEach { (_, w) -> runCatching { w.send(sealed) } }
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

    /** 转让群主并退出：通知成员新群主后直接停止（不发解散通知）。 */
    fun transfer(newOwner: DeviceInfo) {
        val sealed = Msg.OwnerChange(newOwner).sealed(cipher).toJson()
        members.values.forEach { (_, w) -> runCatching { w.send(sealed); w.close() } }
        members.clear()
        runCatching { stop(0) }
    }

    /** 解散群：通知成员后关闭。 */
    fun dissolve() {
        val sealed = Msg.Kick("GROUP_DISSOLVED").sealed(cipher).toJson()
        members.values.forEach { (_, w) -> runCatching { w.send(sealed); w.close() } }
        members.clear()
        runCatching { stop(0) }
    }

    /** 实际监听端口（构造传 0 时由系统分配）。 */
    fun boundPort(): Int = port
}
