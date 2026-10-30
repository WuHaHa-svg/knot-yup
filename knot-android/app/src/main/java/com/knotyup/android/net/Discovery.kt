package com.knotyup.android.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException

data class GroupAd(val id: String, val name: String, val addr: String)

/**
 * UDP 广播发现（端口 45691，与桌面端 discovery.rs 一致）。
 *
 * 生命周期约定：Flow 被 collect 时开始扫描，取消时停止。
 * - 持有 MulticastLock（多数安卓设备省电时会丢弃广播包，必须持有才能稳定接收）
 * - 收到广播立即按「最近 5 秒窗口」全量刷新列表
 * - 每 5 秒超时修剪一次：过期的群从列表移除（同样全量刷新）
 *
 * 健壮性：扫描取消后旧 socket 最多滞后 ~1s 才释放，新扫描绑定端口时带重试；
 * 任何异常都不向上抛（避免 UI 崩溃），静默结束并释放资源。
 */
object Discovery {
    const val PORT = 45691
    private const val WINDOW_MS = 5_000L
    private const val ANNOUNCE_INTERVAL_MS = 1_500L

    /** 本机局域网 IPv4（无则 null）。 */
    fun localIpAddress(): String? {
        try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                if (nif.isUp && !nif.isLoopback) {
                    nif.inetAddresses.toList().forEach { addr ->
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address && !addr.isLinkLocalAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** 群主：周期广播本群（collect 开始，取消停止）。 */
    fun announce(ad: GroupAd): Flow<Unit> = flow<Unit> {
        val socket = DatagramSocket().apply { broadcast = true }
        val json = org.json.JSONObject()
        json.put("id", ad.id); json.put("name", ad.name); json.put("addr", ad.addr)
        val bytes = "KNOTYUP1|$json".toByteArray(Charsets.UTF_8)
        try {
            while (currentCoroutineContext().isActive) {
                runCatching {
                    socket.send(DatagramPacket(bytes, bytes.size, java.net.InetAddress.getByName("255.255.255.255"), PORT))
                }
                delay(ANNOUNCE_INTERVAL_MS)
            }
        } finally {
            socket.close()
        }
    }.flowOn(Dispatchers.IO)

    fun listen(context: Context): Flow<List<GroupAd>> = flow {
        // applicationContext 防止 Activity 泄漏
        val wifi =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("knotyup-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        // 绑定端口（带重试：上一次扫描的 socket 可能尚未完全释放）
        var socket: DatagramSocket? = null
        var attempt = 0
        while (socket == null) {
            if (!currentCoroutineContext().isActive) {
                lock?.release()
                return@flow
            }
            try {
                socket = DatagramSocket(PORT).apply {
                    broadcast = true
                    soTimeout = 1000
                }
            } catch (_: SocketException) {
                attempt += 1
                if (attempt >= 20) {
                    // 端口被长期占用（例如本机另一实例），放弃本轮扫描
                    lock?.release()
                    return@flow
                }
                delay(300)
            }
        }

        val buf = ByteArray(1024)
        val seen = HashMap<String, Pair<GroupAd, Long>>() // id -> (ad, lastSeen)
        var emitted: List<GroupAd> = emptyList()
        try {
            while (currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (text.startsWith("KNOTYUP1|")) {
                        val o = JSONObject(text.removePrefix("KNOTYUP1|"))
                        val ad = GroupAd(o.getString("id"), o.getString("name"), o.getString("addr"))
                        seen[ad.id] = ad to System.currentTimeMillis()
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // 周期修剪
                }
                // 全量重建：最近 WINDOW_MS 内可见的群
                val now = System.currentTimeMillis()
                seen.entries.removeAll { (_, v) -> now - v.second > WINDOW_MS }
                val list = seen.values.map { it.first }.sortedBy { it.name }
                if (list != emitted) {
                    emitted = list
                    emit(list)
                }
            }
        } finally {
            socket.close()
            lock?.release()
        }
    }.flowOn(Dispatchers.IO)
}
