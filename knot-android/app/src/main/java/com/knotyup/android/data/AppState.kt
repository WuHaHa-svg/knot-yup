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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    // ── 进程级会话协程（横竖屏切换/Activity 重建不中断，UI 层不再持有任何 ws/消息协程）──
    private var memberJob: Job? = null
    private var watcherJob: Job? = null
    private var runtimeJob: Job? = null

    /** 剪贴板指纹闸门：最近一次同步内容指纹（收/发都记录），防「写入被当成本机复制」回环 */
    @Volatile
    var clipGuard = ""

    /** 入群 Loading（进程级：旋转不重置） */
    private val _joining = MutableStateFlow(false)
    val joining: StateFlow<Boolean> = _joining

    /** 剪贴板能力模式（进程级，随 Shizuku 状态自动切换） */
    private val _clipMode = MutableStateFlow(com.knotyup.android.clipboard.ClipboardCapability.Mode.FOREGROUND)
    val clipMode: StateFlow<com.knotyup.android.clipboard.ClipboardCapability.Mode> = _clipMode

    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session

    /** 局域网发现结果（进程级：旋转不丢列表、不重启扫描周期） */
    private val _discovered = MutableStateFlow<List<GroupAd>>(emptyList())
    val discovered: StateFlow<List<GroupAd>> = _discovered

    /** App 是否前台（MainActivity onStart/onStop 上报）：扫描仅前台且未入群时进行 */
    @Volatile
    private var inForeground = false

    private var scanJob: Job? = null

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history

    /**
     * 轻提示请求（由 UI 层的 Sonner Toaster 消费）。
     * key 非空时由 UI 用当前语言解析 i18n 文案（进程层不持语言环境），subs 做 {name} 之类替换。
     */
    data class ToastReq(
        val msg: String? = null,
        val kind: Int = 0, // 0=普通 1=成功 2=错误
        val key: String? = null,
        val subs: Map<String, String> = emptyMap(),
    )

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

    /** i18n 键 toast（进程层消息处理用，UI 层按当前语言解析）。 */
    fun toastKey(key: String, kind: Int = 0, subs: Map<String, String> = emptyMap()) {
        _toast.value = ToastReq(key = key, kind = kind, subs = subs)
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

    // ── 进程级运行时（事件处理 / 成员会话 / 本机剪贴板监听，全部与 UI 生命周期解耦）──

    /**
     * 启动进程级运行时：事件处理协程 + 剪贴板模式观察 + 入群状态驱动监听启停。
     * MainActivity onCreate 调用一次（幂等）；此后 Activity 任意重建都不再影响消息链路。
     */
    fun startRuntime() {
        if (runtimeJob?.isActive == true) return
        _clipMode.value = com.knotyup.android.clipboard.ClipboardCapability.currentMode()
        com.knotyup.android.clipboard.ClipboardCapability.observeModeChanges { mode ->
            _clipMode.value = mode
            // 模式切换时重启监听（轮询 ↔ 前台回调两种方式互换）
            if (session.value.joined) restartClipWatcher()
        }
        runtimeJob = scope.launch {
            // 事件总线 → 状态应用（原 UI 层事件收集器整体下沉）
            launch {
                events.collect { handleEvent(it) }
            }
            // 入群状态变化 → 本机剪贴板监听启停 + 发现扫描启停
            launch {
                session.map { it.joined }.distinctUntilChanged().collect { joined ->
                    if (joined) restartClipWatcher() else watcherJob?.cancel()
                    restartDiscovery()
                }
            }
        }
    }

    /** 前台状态上报（MainActivity onStart/onStop）：控制发现扫描启停。 */
    fun setInForeground(v: Boolean) {
        inForeground = v
        restartDiscovery()
    }

    /** 发现扫描（进程级）：仅前台且未入群时；过滤本机广播的群。 */
    fun restartDiscovery() {
        scanJob?.cancel()
        scanJob = null
        if (!inForeground || session.value.joined) {
            if (session.value.joined) _discovered.value = emptyList()
            return
        }
        val ctx = appContext ?: return
        scanJob = scope.launch {
            val myIp = Discovery.localIpAddress()
            Discovery.listen(ctx).collect { list ->
                _discovered.value = if (myIp == null) list else list.filter { !it.addr.startsWith("$myIp:") }
            }
        }
    }

    /** 事件统一处理（进程级）：状态应用 + 本机剪贴板写入 + 踢人/失联分支。 */
    private suspend fun handleEvent(event: GroupEvent) {
        when (event) {
            is GroupEvent.Members -> {
                // 成员差量 → 加入/离开提示（首次同步与自身变化不提示）
                val prev = session.value.members
                if (prev.isNotEmpty()) {
                    val prevIds = prev.map { it.id }.toSet()
                    val newIds = event.members.map { it.id }.toSet()
                    event.members.filter { it.id !in prevIds && it.id != device.id }
                        .forEach { toastKey("member_joined", subs = mapOf("name" to it.name)) }
                    prev.filter { it.id !in newIds && it.id != device.id }
                        .forEach { toastKey("member_left", subs = mapOf("name" to it.name)) }
                }
                applyEvent(event)
                _joining.value = false
            }
            is GroupEvent.Clipboard -> {
                clipGuard = event.payload.hash
                applyEvent(event)
                appContext?.let {
                    runCatching { com.knotyup.android.clipboard.ClipboardCapability.setText(it, event.payload.text) }
                }
            }
            is GroupEvent.Kicked -> {
                reset()
                toastKey(if (event.reason == "GROUP_DISSOLVED") "group_dissolved" else "kicked", 2)
                _joining.value = false
            }
            is GroupEvent.OwnerChange -> ownerLost(event.newOwner)
            GroupEvent.Disconnected -> ownerLost(null)
            is GroupEvent.Error -> {
                android.util.Log.w("KnotYup", "ws error: ${event.message}")
                val s = session.value
                if (s.joined && s.role == "member") ownerLost(null) else toastKey("op_failed", 2)
            }
        }
    }

    /** 成员会话：连接 + 心跳 + 事件桥接（含 5s 入群超时）。进程级，旋转不中断。 */
    fun startMemberSession(addr: String, pin: String) {
        memberJob?.cancel()
        client.leave() // 防双实例：确保旧连接取消
        _joining.value = true
        memberJob = scope.launch {
            onJoining(addr, pin)
            setStandby(ServerSocket(0))
            val collector = launch {
                client.join(addr, pin, deviceWithStandbyAddr()).collect { result ->
                    result.fold({ event ->
                        emit(event)
                    }, { e ->
                        android.util.Log.w("KnotYup", "join failed: ${e.message}")
                        val s = session.value
                        if (s.joined && s.role == "member") {
                            // 已入群后的连接失败（心跳失联/群主消失）→ 选主承接
                            ownerLost(null)
                        } else {
                            toastKey(if (e.message == "WRONG_PIN") "wrong_pin" else "join_failed", 2)
                            _joining.value = false
                        }
                    })
                }
            }
            // 入群超时：5s 内未收到成员表即判失败
            delay(5000)
            if (_joining.value && !session.value.joined) {
                android.util.Log.w("KnotYup", "join timeout (5s): $addr")
                collector.cancel()
                client.leave()
                toastKey("join_failed", 2)
                _joining.value = false
            }
        }
    }

    /**
     * 群主容灾（进程级）：候选[0]是自己 → standby 承接；否则依次重连候选地址。
     * 与 UI 完全解耦（原 MainActivity.handleOwnerLost 下沉）。
     */
    private fun ownerLost(explicit: DeviceInfo?) {
        if (ownerLostHandling) {
            android.util.Log.w("KnotYup", "ownerLost 已在处理中，忽略重复触发")
            return
        }
        ownerLostHandling = true
        val s = session.value
        val candidates = explicit?.let { listOf(it) } ?: s.members.drop(1) // [0] 是旧群主
        android.util.Log.d("KnotYup", "owner lost: explicit=${explicit?.name}, candidates=${candidates.map { "${it.name}@${it.addr}" }}")
        if (candidates.isEmpty()) {
            reset()
            toastKey("group_lost", 2)
            ownerLostHandling = false
            return
        }
        if (candidates[0].id == device.id) {
            val port = standby?.localPort ?: reservePort()
            becomeOwner(s.pin, port)
            toastKey("promoted", 1)
            ownerLostHandling = false
        } else {
            toastKey("reconnecting")
            val pin_ = s.pin
            scope.launch {
                for (attempt in 0 until 10) {
                    for (c in candidates) {
                        if (c.id == device.id || c.addr.isNullOrBlank()) continue
                        var connected = false
                        android.util.Log.d("KnotYup", "reconnect attempt=$attempt -> ${c.name}@${c.addr}")
                        try {
                            client.join(c.addr, pin_, deviceWithStandbyAddr()).collect { res ->
                                res.fold({ ev ->
                                    if (ev is GroupEvent.Members && !connected) {
                                        connected = true
                                        toastKey("reconnected", 1)
                                        ownerLostHandling = false
                                    }
                                    emit(ev)
                                }, { e ->
                                    android.util.Log.w("KnotYup", "reconnect failed: ${e.message}")
                                })
                            }
                        } catch (_: Exception) {
                        }
                        if (connected) return@launch // 正常入群；若再次断开，Disconnected 会重新触发选主
                        delay(500)
                    }
                    delay(1500)
                }
                reset()
                toastKey("group_lost", 2)
                ownerLostHandling = false
            }
        }
    }

    /** 本机剪贴板监听（按模式）：SHIZUKU 1s 轮询摘要指纹；FOREGROUND 系统回调。 */
    fun restartClipWatcher() {
        watcherJob?.cancel()
        val ctx = appContext ?: return
        watcherJob = scope.launch {
            if (com.knotyup.android.clipboard.ClipboardCapability.currentMode() ==
                com.knotyup.android.clipboard.ClipboardCapability.Mode.SHIZUKU
            ) {
                var lastFp = com.knotyup.android.clipboard.ClipboardCapability.fingerprint(ctx)
                while (true) {
                    delay(1_000)
                    val fp = com.knotyup.android.clipboard.ClipboardCapability.fingerprint(ctx) ?: continue
                    if (fp == lastFp) continue
                    lastFp = fp
                    com.knotyup.android.clipboard.ClipboardCapability.getText(ctx)?.let { sendLocalClip(it) }
                }
            } else {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                    scope.launch {
                        com.knotyup.android.clipboard.ClipboardCapability.getText(ctx)?.let { sendLocalClip(it) }
                    }
                }
                cm.addPrimaryClipChangedListener(listener)
                try {
                    while (true) delay(60_000)
                } finally {
                    cm.removePrimaryClipChangedListener(listener)
                }
            }
        }
    }

    /** 本机剪贴板变化 → 群广播（含指纹闸门防回环）。 */
    private suspend fun sendLocalClip(text: String) {
        val hash = com.knotyup.android.net.textHash(text)
        if (text.isEmpty() || hash == clipGuard) return
        clipGuard = hash
        val sent = if (isOwner()) {
            server?.broadcastClipboard(text)
            server != null
        } else {
            client.sendClipboard(text)
        }
        if (sent) addLocalHistory(text)
    }

    // ── 群主（创建 / 承接） ───────────────────

    /** 预留一个可用 TCP 端口（先绑定再关闭，稍后由 WS 服务端使用）。 */
    fun reservePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    /** 开启群主服务端（创建群或承接群主）。返回 (地址, 实际端口)。 */
    fun becomeOwner(pin: String, port: Int): Pair<String, Int> {
        // 成员会话终止（承接群主时不再需要客户端连接）
        memberJob?.cancel()
        memberJob = null
        client.leave()
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
        memberJob?.cancel()
        memberJob = null
        watcherJob?.cancel()
        watcherJob = null
        client.leave()
        stopServer()
        standby?.let { runCatching { it.close() } }
        standby = null
        _session.value = SessionState()
        _history.value = emptyList()
        _joining.value = false
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
