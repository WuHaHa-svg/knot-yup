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
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 触摸板专用 TCP 客户端（手机 → 桌面被控端）。
 * 换行分隔 JSON 帧；hello 握手（token=群 PIN）；seq 单调递增；心跳保活。
 * 发送在独立 IO 协程，UI 侧只入队，不阻塞。
 */
class TouchpadClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var sendJob: Job? = null
    private var heartbeatJob: Job? = null
    private val queue = ConcurrentLinkedQueue<String>()
    private var seq = 0L
    @Volatile private var connected = false

    /** 连接桌面：addr 形如 "192.168.1.5:45700"，token 为群 PIN。同步等待握手结果。 */
    fun connect(addr: String, token: String, screenW: Int, screenH: Int, deviceId: String): Boolean {
        disconnect()
        val parts = addr.split(":")
        if (parts.size != 2) return false
        val port = parts[1].toIntOrNull() ?: return false
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(parts[0], port), 3000)
            s.soTimeout = 5000
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            // 读握手应答（在后台，避免阻塞），先发 hello
            val hello = JSONObject().apply {
                put("type", "hello")
                put("token", token)
                put("device_id", deviceId)
                put("screen_w", screenW)
                put("screen_h", screenH)
            }
            writer?.write(hello.toString() + "\n")
            writer?.flush()
            connected = true
            startSender()
            startHeartbeat()
            Log.d(TAG, "已连接 $addr")
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
                    writer?.write(line)
                    writer?.newLine()
                    writer?.flush()
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
        socket = null; writer = null
        queue.clear()
    }

    companion object { private const val TAG = "TouchpadClient" }
}
