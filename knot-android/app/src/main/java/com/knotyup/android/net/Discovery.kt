package com.knotyup.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class GroupAd(val id: String, val name: String, val addr: String)

/** UDP 广播发现（端口 45691，与桌面端 discovery.rs 一致）。 */
object Discovery {
    const val PORT = 45691

    /** 持续监听局域网广播；每 2 秒发出一次当前可见群列表（含过期剔除）。 */
    fun listen(): Flow<List<GroupAd>> = flow {
        val socket = DatagramSocket(PORT).apply { broadcast = true }
        val buf = ByteArray(1024)
        val seen = HashMap<String, Pair<GroupAd, Long>>()
        try {
            while (!Thread.currentThread().isInterrupted()) {
                socket.soTimeout = 2000
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
                    // 超时即周期触发列表刷新
                }
                val now = System.currentTimeMillis()
                seen.entries.removeAll { (_, v) -> now - v.second > 5000 }
                emit(seen.values.map { it.first }.sortedBy { it.name })
            }
        } finally {
            socket.close()
        }
    }.flowOn(Dispatchers.IO)
}
