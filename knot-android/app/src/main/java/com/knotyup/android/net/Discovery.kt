package com.knotyup.android.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException

/**
 * 局域网组发现 v2（拉取式，与 knot-core discovery.rs 一致）：
 * 扫描端周期广播 REQ（→ 收集回应 2s → 静默 1s，周期 3s，每轮整轮直出）；
 * 群主建群后在发现端口被动监听 REQ、收到即单播回源 RESP。群主不再周期广播。
 */
data class GroupAd(val id: String, val name: String, val addr: String, val members: Int = 0)

object Discovery {
    const val PORT = 45691
    private const val PREFIX_REQ = "KNOTYUP2|REQ|"
    private const val PREFIX_RESP = "KNOTYUP2|RESP|"
    private const val COLLECT_WINDOW_MS = 2_000L
    private const val QUIET_GAP_MS = 1_000L

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

    /**
     * 本机局域网 IPv4（带系统兜底）：先走网卡枚举；拿不到（部分 OEM 接口上报时序
     * 异常 / 热点环境）再用 ConnectivityManager 按 Wi-Fi/以太网 transport 查
     * LinkProperties。群地址 / standby 地址等「要广播给别人」的场合必须用本方法，
     * 严禁回落到 0.0.0.0 之类的假地址污染发现协议。
     */
    fun localIpAddress(context: Context?): String? {
        localIpAddress()?.let { return it }
        if (context == null) return null
        return runCatching {
            @Suppress("DEPRECATION") // allNetworks 在 API 31 标记废弃但同步可用，替代 API 是异步回调不适用此处
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return null
            val transports = intArrayOf(
                android.net.NetworkCapabilities.TRANSPORT_WIFI,
                android.net.NetworkCapabilities.TRANSPORT_ETHERNET,
                android.net.NetworkCapabilities.TRANSPORT_VPN, // VPN 覆盖下的 Wi-Fi 也算（IP 在 VPN 接口上）
            )
            for (t in transports) {
                val net = cm.allNetworks.firstOrNull { n ->
                    cm.getNetworkCapabilities(n)?.hasTransport(t) == true
                } ?: continue
                val lp = cm.getLinkProperties(net) ?: continue
                lp.linkAddresses.firstOrNull { a ->
                    a.address is java.net.Inet4Address && !a.address.isLoopbackAddress
                }?.let { return it.address.hostAddress }
            }
            null
        }.getOrNull()
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

    /**
     * 扫描端状态机（仅首页前台时由 AppState 启动）：
     * 发 REQ（1 个广播包）→ 收集 2s（本轮 RESP 集合=结果，整轮直出）→ 静默 1s → 循环。
     * 生命周期约定：Flow 被 collect 时开始，取消时停止（socket/锁随 finally 释放）。
     */
    fun scan(context: Context): Flow<List<GroupAd>> = flow<List<GroupAd>> {
        // applicationContext 防止 Activity 泄漏；锁保证广播包在省电模式下可达
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("knotyup-discovery")?.apply {
            setReferenceCounted(false); acquire()
        }

        var socket: DatagramSocket? = null
        var attempt = 0
        while (socket == null) {
            if (!currentCoroutineContext().isActive) {
                runCatching { lock?.release() }
                return@flow
            }
            try {
                // 源端口固定：群主的 RESP 单播回源需要（其实 recvfrom 用的是这个 socket）
                socket = DatagramSocket().apply { broadcast = true }
            } catch (_: SocketException) {
                attempt += 1
                if (attempt >= 20) {
                    runCatching { lock?.release() }
                    return@flow
                }
                delay(300)
            }
        }

        val buf = ByteArray(1024)
        var emitted: List<GroupAd> = emptyList()
        try {
            while (currentCoroutineContext().isActive) {
                // —— 发 REQ ——
                val req = "$PREFIX_REQ${JSONObject().put("scan_id", java.util.UUID.randomUUID().toString()).put("ts_ms", System.currentTimeMillis())}"
                val bytes = req.toByteArray(Charsets.UTF_8)
                broadcastTargets().forEach { target ->
                    runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, PORT)) }
                }

                // —— 收集窗口 2s：本轮所有 RESP = 结果 ——
                val deadline = System.currentTimeMillis() + COLLECT_WINDOW_MS
                val got = HashMap<String, GroupAd>()
                while (System.currentTimeMillis() < deadline) {
                    val remain = (deadline - System.currentTimeMillis()).toInt()
                    socket.soTimeout = remain.coerceAtLeast(1)
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = text.removePrefix(PREFIX_RESP).takeIf { text.startsWith(PREFIX_RESP) } ?: continue
                        runCatching {
                            val o = JSONObject(json)
                            got[o.getString("id")] = GroupAd(
                                o.getString("id"), o.getString("name"),
                                o.getString("addr"), o.optInt("members"),
                            )
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        break // 窗口结束
                    }
                }

                // —— 本轮结算：整轮直出 ——
                val list = got.values.sortedBy { it.name }
                if (list != emitted) {
                    emitted = list
                    emit(list)
                }

                // —— 静默 1s ——
                delay(QUIET_GAP_MS)
            }
        } finally {
            socket.close()
            runCatching { lock?.release() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 群主端被动应答：在发现端口监听 REQ，收到即单播回源 RESP（含实时成员数）。
     * 返回应答协程的 Job（取消即停止）；socket 端口与扫描共用 45691。
     */
    suspend fun respond(context: Context, ad: GroupAd, memberCount: () -> Int): kotlinx.coroutines.Job {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("knotyup-respond")?.apply {
            setReferenceCounted(false); acquire()
        }
        // 端口重试：上一实例的 socket 可能尚未完全释放
        var socket: DatagramSocket? = null
        var attempt = 0
        while (socket == null) {
            if (!currentCoroutineContext().isActive) {
                runCatching { lock?.release() }
                throw CancellationException("respond cancelled before bind")
            }
            try {
                socket = DatagramSocket(PORT).apply { broadcast = true }
            } catch (_: SocketException) {
                attempt += 1
                if (attempt >= 20) {
                    runCatching { lock?.release() }
                    return SupervisorJob().apply { complete() } // 放弃应答（已完成的空 Job）
                }
                delay(300)
            }
        }
        val sock = socket
        val respTemplate = JSONObject()
            .put("id", ad.id).put("name", ad.name).put("addr", ad.addr)
        return CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val buf = ByteArray(512)
            try {
                while (currentCoroutineContext().isActive) {
                    sock.soTimeout = 1000 // 1s 粒度轮询取消信号
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (!text.startsWith(PREFIX_REQ)) continue
                        // 注意前缀：扫描端按 KNOTYUP2|RESP| 剥离后解析 JSON
                        val resp = PREFIX_RESP + respTemplate.put("members", memberCount())
                            .put("ts_ms", System.currentTimeMillis()).toString()
                        val bytes = resp.toByteArray(Charsets.UTF_8)
                        runCatching {
                            sock.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // 继续循环（检查 isActive）
                    }
                }
            } finally {
                sock.close()
                runCatching { lock?.release() }
            }
        }
    }
}
