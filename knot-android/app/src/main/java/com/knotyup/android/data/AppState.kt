package com.knotyup.android.data

import android.content.Context
import com.knotyup.android.net.DeviceInfo
import com.knotyup.android.net.Discovery
import com.knotyup.android.net.GroupAd
import com.knotyup.android.net.GroupClient
import com.knotyup.android.net.GroupEvent
import com.knotyup.android.net.KnotGroupServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.util.UUID

data class HistoryItem(val from: String, val text: String, val atMs: Long)

data class SessionState(
    val role: String = "member", // owner / member
    val addr: String = "",
    val pin: String = "",
    val members: List<DeviceInfo> = emptyList(),
    val joined: Boolean = false,
)

/** 全局会话状态（UI 单源）。 */
object AppState {
    val device = DeviceInfo(
        id = UUID.randomUUID().toString(),
        name = "${android.os.Build.MODEL} · Android",
    )

    val client = GroupClient()

    /** 群主服务端（role == owner 时存在） */
    var server: KnotGroupServer? = null
        private set

    /** 成员期预留端口（承接群主用） */
    var standby: ServerSocket? = null
        private set

    private var announceJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    /** 网络事件总线（连接协程 → UI 协程）。 */
    private val _events = MutableSharedFlow<GroupEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun emit(event: GroupEvent) {
        _events.tryEmit(event)
    }

    fun toast(msg: String) {
        _toast.value = msg
    }

    fun clearToast() {
        _toast.value = null
    }

    fun isOwner(): Boolean = _session.value.role == "owner"

    /** 成员入群时记录（重连/承接群主时需要同一 PIN 与地址）。 */
    fun onJoining(addr: String, pin: String) {
        _session.value = _session.value.copy(role = "member", addr = addr, pin = pin)
    }

    fun setStandby(s: ServerSocket?) {
        standby?.let { runCatching { it.close() } }
        standby = s
    }

    /** 带预留地址的设备信息（入群用）。 */
    fun deviceWithStandbyAddr(): DeviceInfo {
        val port = standby?.localPort
        val ip = Discovery.localIpAddress()
        return if (port != null && ip != null) device.copy(addr = "$ip:$port") else device
    }

    fun applyEvent(event: GroupEvent) {
        when (event) {
            is GroupEvent.Members -> _session.value = _session.value.copy(
                members = event.members,
                joined = true,
            )
            is GroupEvent.Clipboard -> _history.value = (
                listOf(HistoryItem(event.from, event.payload.text, System.currentTimeMillis())) + _history.value
                ).take(50)
            else -> {}
        }
    }

    fun addLocalHistory(text: String) {
        _history.value = (
            listOf(HistoryItem(device.id, text, System.currentTimeMillis())) + _history.value
        ).take(50)
    }

    // ── 群主（创建 / 承接） ───────────────────

    /** 预留一个可用 TCP 端口（先绑定再关闭，稍后由 WS 服务端使用）。 */
    fun reservePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    /** 开启群主服务端（创建群或承接群主）。返回 (地址, 实际端口)。 */
    fun becomeOwner(pin: String, port: Int): Pair<String, Int> {
        // 释放预留端口给 WS 服务端
        standby?.let { runCatching { it.close() } }
        standby = null
        stopServer()
        val owner = device.copy(addr = null)
        val s = KnotGroupServer(port, pin, owner, ::emit)
        s.start()
        server = s
        val ip = Discovery.localIpAddress() ?: "0.0.0.0"
        val addr = "$ip:${s.boundPort()}"
        _session.value = SessionState(
            role = "owner", addr = addr, pin = pin,
            members = listOf(device), joined = true,
        )
        startAnnounce(addr)
        return addr to s.boundPort()
    }

    /** 停止广播与服务端。 */
    fun stopServer() {
        announceJob?.cancel()
        announceJob = null
        server?.let { runCatching { it.stop(0) } }
        server = null
    }

    private fun startAnnounce(addr: String) {
        announceJob?.cancel()
        announceJob = scope.launch {
            Discovery.announce(GroupAd(device.id, device.name, addr)).collect {}
        }
    }

    /** 群主解散并重置。 */
    fun dissolveOwner() {
        server?.dissolve()
        stopServer()
        reset()
    }

    /** 群主转让给指定成员后退群。 */
    fun transferOwner(targetId: String) {
        val target = _session.value.members.firstOrNull { it.id == targetId } ?: run {
            dissolveOwner()
            return
        }
        server?.transfer(target)
        stopServer()
        reset()
    }

    fun reset() {
        client.leave()
        stopServer()
        standby?.let { runCatching { it.close() } }
        standby = null
        _session.value = SessionState()
        _history.value = emptyList()
    }

    // 偏好：主题色 / 语言 key
    private const val PREFS = "knotyup"
    private const val KEY_THEME = "theme"
    private const val KEY_LANG = "lang"

    fun saveTheme(context: Context, id: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, id).apply()

    fun loadTheme(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)

    fun saveLang(context: Context, id: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, id).apply()

    fun loadLang(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, null)
}
