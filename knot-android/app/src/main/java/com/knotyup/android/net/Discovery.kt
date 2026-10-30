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
 * 模型（killStep 渐进剔除）：
 * - 群主每 2.5s 广播一次
 * - 监听端每 1s 一个扫描周期：周期内收到的群命中（killStep 清零/新增），
 *   未命中的 killStep +1，连续累积到 5（约 5s 无广播）才正式剔除；
 *   再收到即复活并清零——应对 Wi-Fi 广播丢包导致的误剔除
 *
 * 生命周期约定：Flow 被 collect 时开始扫描，取消时停止。
 * 持有 MulticastLock（多数安卓设备省电时会丢弃广播包）。
 * 健壮性：旧 socket 释放滞后时绑定端口带重试；异常不上抛（避免 UI 崩溃）。
 */
object Discovery {
    const val PORT = 45691
    private const val ANNOUNCE_INTERVAL_MS = 2_500L
    private const val SCAN_INTERVAL_MS = 1_000L
    private const val KILL_THRESHOLD = 5

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

    /** 广播目标：全局广播 + 本机各网卡的子网广播地址（应对多网卡/热点下全局广播不可达）。 */
    private fun broadcastTargets(): List<java.net.InetAddress> {
        val targets = mutableListOf(java.net.InetAddress.getByName("255.255.255.255"))
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                if (nif.isUp && !nif.isLoopback) {
                    nif.interfaceAddresses.forEach { ia ->
                        ia.broadcast?.let { targets.add(it) }
                    }
                }
            }
        }
        return targets.distinct()
    }

    /** 群主：周期广播本群（建群初期密集突发，快速建立可见性；collect 开始，取消停止）。 */
    fun announce(ad: GroupAd): Flow<Unit> = flow<Unit> {
        val socket = DatagramSocket().apply { broadcast = true }
        val json = org.json.JSONObject()
        json.put("id", ad.id); json.put("name", ad.name); json.put("addr", ad.addr)
        val bytes = "KNOTYUP1|$json".toByteArray(Charsets.UTF_8)

        fun sendAll() {
            broadcastTargets().forEach { target ->
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, PORT)) }
            }
        }

        try {
            // 突发期：前 2s 内每 400ms 一发
            for (d in listOf(0L, 400L, 800L, 1200L, 1600L)) {
                if (!currentCoroutineContext().isActive) return@flow
                if (d > 0) delay(d)
                sendAll()
            }
            // 常规期：每 2.5s 一发
            while (currentCoroutineContext().isActive) {
                sendAll()
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
        // 保持射频清醒，避免省电模式丢广播
        @Suppress("DEPRECATION")
        val wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "knotyup-scan")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        // 绑定端口（带重试：上一次扫描的 socket 可能尚未完全释放）
        var socket: DatagramSocket? = null
        var attempt = 0
        while (socket == null) {
            if (!currentCoroutineContext().isActive) {
                runCatching { lock?.release() }
                runCatching { wifiLock?.release() }
                return@flow
            }
            try {
                socket = DatagramSocket(PORT).apply { broadcast = true }
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
        // id -> (群信息, killStep)
        val seen = HashMap<String, Pair<GroupAd, Int>>()
        var emitted: List<GroupAd> = emptyList()
        try {
            while (currentCoroutineContext().isActive) {
                // —— 一个扫描周期：收集周期内收到的广播 ——
                val cycleStart = System.currentTimeMillis()
                val got = HashMap<String, GroupAd>()
                while (true) {
                    val remain = SCAN_INTERVAL_MS - (System.currentTimeMillis() - cycleStart)
                    if (remain <= 0) break
                    socket.soTimeout = remain.toInt()
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (text.startsWith("KNOTYUP1|")) {
                            val o = JSONObject(text.removePrefix("KNOTYUP1|"))
                            got[o.getString("id")] =
                                GroupAd(o.getString("id"), o.getString("name"), o.getString("addr"))
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        break // 周期结束
                    }
                }

                // —— 周期末结算 ——
                var changed = false
                // 命中：新增或清零（信息变化时更新）
                for ((id, ad) in got) {
                    val cur = seen[id]
                    if (cur == null) {
                        seen[id] = ad to 0
                        changed = true
                    } else {
                        if (cur.first.addr != ad.addr || cur.first.name != ad.name) {
                            seen[id] = ad to 0
                            changed = true
                        } else {
                            seen[id] = cur.first to 0
                        }
                    }
                }
                // 未命中：killStep 累积，达到阈值正式剔除
                val missed = seen.filterKeys { it !in got }.keys.toList()
                for (id in missed) {
                    val (ad, kill) = seen.getValue(id)
                    if (kill + 1 >= KILL_THRESHOLD) {
                        seen.remove(id)
                        changed = true
                    } else {
                        seen[id] = ad to kill + 1
                    }
                }

                if (changed) {
                    val list = seen.values.map { it.first }.sortedBy { it.name }
                    if (list != emitted) {
                        emitted = list
                        emit(list)
                    } else {
                        changed = false
                    }
                }
            }
        } finally {
            socket.close()
            runCatching { lock?.release() }
            runCatching { wifiLock?.release() }
        }
    }.flowOn(Dispatchers.IO)
}
