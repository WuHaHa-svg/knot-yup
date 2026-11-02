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
    /** applicationContext（MainActivity onCreate 注入）：群地址解析需要系统 ConnectivityManager 兜底 */
    @Volatile
    var appContext: Context? = null

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

    /** 轻提示请求（msg + 类型），由 UI 层的 Sonner Toaster 消费。 */
    data class ToastReq(val msg: String, val kind: Int = 0) // 0=普通 1=成功 2=错误

    private val _toast = MutableStateFlow<ToastReq?>(null)
    val toast: StateFlow<ToastReq?> = _toast

    /** 网络事件总线（连接协程 → UI 协程）。 */
    private val _events = MutableSharedFlow<GroupEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    /** 选主/重连处理中标志：防止 handleOwnerLost 被重复触发（错误分支与事件分支双路到达） */
    @Volatile
    var ownerLostHandling = false

    fun emit(event: GroupEvent) {
        _events.tryEmit(event)
    }

    fun toast(msg: String, kind: Int = 0) {
        _toast.value = ToastReq(msg, kind)
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
        val ip = Discovery.localIpAddress(appContext)
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
        // 解析本机 IP：带 ConnectivityManager 兜底 + 短重试（部分 OEM 网卡上报时序滞后）。
        // 拿不到真实 IP 时绝不能广播 0.0.0.0 假地址污染发现协议——群主照常当，只是暂不广播。
        var ip: String? = null
        for (i in 0 until 5) {
            ip = Discovery.localIpAddress(appContext)
            if (ip != null) break
            Thread.sleep(200)
        }
        val boundPort = s.boundPort()
        if (ip != null) {
            val addr = "$ip:$boundPort"
            _session.value = SessionState(
                role = "owner", addr = addr, pin = pin,
                members = listOf(device), joined = true,
            )
            startAnnounce(addr)
        } else {
            android.util.Log.w("KnotYup", "becomeOwner: 无法解析本机局域网 IP，群主已承接但暂不广播（地址显示为 :$boundPort）")
            _session.value = SessionState(
                role = "owner", addr = ":$boundPort", pin = pin,
                members = listOf(device), joined = true,
            )
        }
        return _session.value.addr to boundPort
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
