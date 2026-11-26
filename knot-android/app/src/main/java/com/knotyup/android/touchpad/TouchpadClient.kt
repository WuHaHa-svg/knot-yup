package com.knotyup.android.touchpad

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 触摸板专用 UDP 客户端（手机 → 桌面被控端）。
 * 换行分隔 JSON 帧；hello 首包带 token=群 PIN；seq 单调递增；心跳保活。
 * 无连接、无重传：move/scroll 等高频事件丢帧无感，点击/按键也不重发。
 * 发送在独立 IO 协程，UI 侧只入队，不阻塞。
 */
class TouchpadClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort = 0
    private var sendJob: Job? = null
    private var heartbeatJob: Job? = null
    private val queue = ConcurrentLinkedQueue<String>()
    private var seq = 0L
    @Volatile private var connected = false

    /** 连接桌面：addr 形如 "192.168.1.5:45700"，token 为群 PIN。UDP 无握手等待，本地绑定成功即返回 true。 */
    fun connect(addr: String, token: String, screenW: Int, screenH: Int, deviceId: String): Boolean {
        disconnect()
        val parts = addr.split(":")
        if (parts.size != 2) return false
        val port = parts[1].toIntOrNull() ?: return false
        return try {
            val ds = DatagramSocket() // 随机本地端口
            socket = ds
            serverAddress = InetAddress.getByName(parts[0])
            serverPort = port
            connected = true
            // hello 首包带 token（无应答，直接发）
            val hello = JSONObject().apply {
                put("type", "hello")
                put("token", token)
                put("device_id", deviceId)
                put("screen_w", screenW)
                put("screen_h", screenH)
            }
            sendRaw(hello.toString())
            startSender()
            startHeartbeat()
            Log.d(TAG, "UDP 已就绪 $addr")
            true
        } catch (e: Exception) {
            Log.w(TAG, "连接失败: ${e.message}")
            disconnect()
            false
        }
    }

    private fun startSender() {
        sendJob = scope.launch {
            while (isActive && connected) {
                val line = queue.poll()
                if (line == null) { delay(2); continue }
                try {
                    sendRaw(line)
                } catch (e: Exception) {
                    Log.w(TAG, "发送失败: ${e.message}")
                    connected = false
                    break
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive && connected) {
                val ping = JSONObject().apply { put("type", "ping"); put("ts_ms", System.currentTimeMillis()) }
                enqueue(ping.toString())
                delay(2000)
            }
        }
    }

    private fun enqueue(type: String, body: JSONObject.() -> Unit) {
        if (!connected) return
        val o = JSONObject().apply { put("type", type); put("seq", ++seq); body() }
        enqueue(o.toString())
    }

    /** 直接发一个 UDP 数据报（无连接无重传）。 */
    private fun sendRaw(line: String) {
        val addr = serverAddress ?: return
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        socket?.send(DatagramPacket(bytes, bytes.size, addr, serverPort))
    }

    private fun enqueue(line: String) {
        // 背压：队列超过 64 帧时丢弃最旧帧（move/scroll 允许丢，保留最新位置增量）
        while (queue.size >= 64) queue.poll()
        queue.add(line)
    }

    fun move(dx: Int, dy: Int) = enqueue("move") { put("dx", dx); put("dy", dy) }
    fun scroll(dx: Int, dy: Int) = enqueue("scroll") { put("dx", dx); put("dy", dy) }
    fun down(button: String = "left") = enqueue("down") { put("button", button) }
    fun up(button: String = "left") = enqueue("up") { put("button", button) }
    fun click(button: String = "left") = enqueue("click") { put("button", button) }
    /** 系统级左键双击（选中词/打开文件）。 */
    fun doubleClick() = enqueue("double_click") { }
    fun shortcut(code: String) = enqueue("shortcut") { put("code", code) }
    /** 数字键盘：向桌面注入单个字符（0-9 / .）。 */
    fun key(ch: String) = enqueue("key") { put("key", ch) }

    fun disconnect() {
        connected = false
        sendJob?.cancel(); sendJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null; serverAddress = null
        queue.clear()
    }

    companion object { private const val TAG = "TouchpadClient" }
}
