package com.knotyup.android.data

import android.content.Context
import com.knotyup.android.file.FileIndex
import com.knotyup.android.file.P2pFileService
import com.knotyup.android.net.DeviceInfo
import com.knotyup.android.net.SharedFile
import com.knotyup.android.net.Discovery
import com.knotyup.android.net.GroupAd
import com.knotyup.android.net.GroupClient
import com.knotyup.android.net.GroupEvent
import com.knotyup.android.net.KnotGroupServer
import com.knotyup.android.net.TaskEvent
import com.knotyup.android.net.TaskStatus
import com.knotyup.android.net.TransferTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.util.UUID

data class HistoryItem(val from: String, val name: String, val text: String, val atMs: Long)

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

    /** 本机设备信息（fileList 随本机共享文件增删而变；成员表即文件列表真相源） */
    @Volatile
    var device = DeviceInfo(
        id = UUID.randomUUID().toString(),
        name = "${android.os.Build.MODEL} · Android",
    )
        private set

    val client = GroupClient()

    /** 本地共享文件夹（设备级持久，跨群复用） */
    private var _fileIndex: FileIndex? = null
    val fileIndex: FileIndex
        get() = _fileIndex ?: error("FileIndex 未初始化（appContext 未注入）")

    /** P2P 供种/下载服务（设备级常驻） */
    val p2pFile = P2pFileService(
        indexProvider = { fileIndex },
        localIdProvider = { device.id },
        contextProvider = { appContext ?: error("appContext 未注入") },
        onEvent = { ev -> reportSeederEvent(ev) },
    )

    /** 群文件列表（按 device_id 分组；UI 文件 tab 观察） */
    private val _groupFiles = MutableStateFlow<Map<String, List<SharedFile>>>(emptyMap())
    val groupFiles: StateFlow<Map<String, List<SharedFile>>> = _groupFiles

    /** 群传输任务数组（群主权威；全量快照替换） */
    private val _transferTasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val transferTasks: StateFlow<List<TransferTask>> = _transferTasks

    /** 触摸板正在控制的桌面设备 id（null=未控制）。提升到全局以便 MainActivity 的返回拦截感知。 */
    private val _touchpadControllingId = MutableStateFlow<String?>(null)
    val touchpadControllingId: StateFlow<String?> = _touchpadControllingId
    fun setTouchpadControlling(id: String?) { _touchpadControllingId.value = id }

    /** 群主服务端（role == owner 时存在） */
    var server: KnotGroupServer? = null
        private set

    /** 被转让者暂存的 standby 服务句柄（transfer_error 时静默关闭；new_service 后转正） */
    var standbyServer: KnotGroupServer? = null
        private set

    /** 成员期预留端口（承接群主用） */
    var standby: ServerSocket? = null
        private set

    private var respondJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    // ── 进程级会话协程（横竖屏切换/Activity 重建不中断，UI 层不再持有任何 ws/消息协程）──
    private var memberJob: Job? = null
    private var watcherJob: Job? = null

    /** 最近一次升主时间（unix 毫秒）：升主后老成员迁移涌入会连环触发 member_joined，6s 内静默 */
    @Volatile
    private var promotedAt = 0L

    /** 转让进行中（UI 观察此流显示全屏 Loading）：转出方静默等待迁移完成，终态只弹一条提示；
     *  置位期间成员差量不提示（看到的是成员迁移潮） */
    private val _transferring = MutableStateFlow(false)
    val transferring = _transferring.asStateFlow()
    private var runtimeJob: Job? = null

    /** 剪贴板指纹闸门：最近一次同步内容指纹（收/发都记录），防「写入被当成本机复制」回环 */
    @Volatile
    var clipGuard = ""

    /** 回声闸门（文本级）：最近一次「远端同步 / 本机主动复制」写入的文本。
     *  监听器读到相同内容时静默——指纹闸门之外的第二道保险 */
    @Volatile
    var lastSyncText: String? = null

    /** 入群 Loading（进程级：旋转不重置） */
    private val _joining = MutableStateFlow(false)
    val joining: StateFlow<Boolean> = _joining

    /** 连接断开（弹窗标记）：UI 观察显示「连接已断开」弹窗，用户选重新加入或退出群 */
    private val _connLost = MutableStateFlow(false)
    val connLost = _connLost.asStateFlow()

    /** 入群 Loading 开关：UI 点击瞬间置 true（三条入群路径共用），成员会话启动后由事件收口 */
    fun setJoining(v: Boolean) {
        _joining.value = v
    }

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
        val subtitle: String? = null, // 可选副标题（如共享者副标题显示文件名）
    )

    private val _toast = MutableStateFlow<ToastReq?>(null)
    val toast: StateFlow<ToastReq?> = _toast

    /** 网络事件总线（连接协程 → UI 协程）。 */
    private val _events = MutableSharedFlow<GroupEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun emit(event: GroupEvent) {
        _events.tryEmit(event)
    }

    fun toast(msg: String, kind: Int = 0, subtitle: String? = null) {
        _toast.value = ToastReq(msg, kind, subtitle = subtitle)
    }

    /** i18n 键 toast（进程层消息处理用，UI 层按当前语言解析）。 */
    fun toastKey(key: String, kind: Int = 0, subs: Map<String, String> = emptyMap(), subtitle: String? = null) {
        _toast.value = ToastReq(key = key, kind = kind, subs = subs, subtitle = subtitle)
    }

    fun clearToast() {
        _toast.value = null
    }

    fun isOwner(): Boolean = _session.value.role == "owner"

    /** 供种端任务事件 → 群主（本地）或 ws 上报（成员）。 */
    private fun reportSeederEvent(ev: TaskEvent) {
        if (isOwner()) {
            server?.localTaskEvent(ev)
            return
        }
        when (ev) {
            is TaskEvent.Queued -> client.sendTaskQueued(ev.taskId)
            is TaskEvent.Started -> client.sendTaskStarted(ev.taskId)
            is TaskEvent.Progress -> {
                val p = if (ev.total == 0L) 0 else (ev.transferred * 100 / ev.total).toInt().coerceAtMost(100)
                client.sendTaskProgress(ev.taskId, p, ev.transferred)
            }
            is TaskEvent.Done -> client.sendTaskFinish(ev.taskId)
            is TaskEvent.Failed -> client.sendTaskFailed(ev.taskId, ev.error)
        }
    }

    /** 成员入群时记录（重连/承接群主时需要同一 PIN 与地址）。 */
    fun onJoining(addr: String, pin: String) {
        _session.value = _session.value.copy(role = "member", addr = addr, pin = pin)
    }

    fun setStandby(s: ServerSocket?) {
        standby?.let { runCatching { it.close() } }
        standby = s
    }

    /** 初始化文件共享（appContext 注入后调用，幂等） */
    fun initFiles(ctx: Context) {
        if (_fileIndex == null) _fileIndex = FileIndex(ctx)
    }

    /** 带预留地址的设备信息（入群用），含 P2P 供种地址与本机 fileList */
    fun deviceWithStandbyAddr(): DeviceInfo {
        val port = standby?.localPort
        val ip = Discovery.localIpAddress(appContext)
        val fileAddr = p2pFile.startServer().let { p -> ip?.let { "$it:$p" } }
        // 入群携带本机文件列表（成员表即文件列表真相源）
        device = device.copy(fileList = fileIndex.sharedFiles())
        return device.copy(
            addr = if (port != null && ip != null) "$ip:$port" else null,
            fileAddr = fileAddr,
        )
    }

    fun applyEvent(event: GroupEvent) {
        when (event) {
            is GroupEvent.Members -> _session.value = _session.value.copy(
                members = event.members,
                joined = true,
            )
            is GroupEvent.Clipboard -> _history.value = (
                listOf(HistoryItem(event.from, event.fromName, event.payload.text, System.currentTimeMillis())) + _history.value
                ).take(50)
            else -> {}
        }
    }

    fun addLocalHistory(text: String) {
        _history.value = (
            listOf(HistoryItem(device.id, device.name, text, System.currentTimeMillis())) + _history.value
        ).take(50)
    }

    // ── 进程级运行时（事件处理 / 成员会话 / 本机剪贴板监听，全部与 UI 生命周期解耦）──

    /**
     * 启动进程级运行时：事件处理协程 + 入群状态驱动监听启停。
     * MainActivity onCreate 调用一次（幂等）；此后 Activity 任意重建都不再影响消息链路。
     */
    fun startRuntime() {
        if (runtimeJob?.isActive == true) return
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
            // v2 拉取式扫描：3s 周期（REQ → 收集 2s → 静默 1s），整轮直出
            Discovery.scan(ctx).collect { list ->
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
                // 升主后 6s 静默期：老成员迁移涌入造成的差量不提示（迁移潮不是「有人入群」）
                val promotedRecently =
                    promotedAt > 0 && System.currentTimeMillis() - promotedAt < 6_000L
                // 转让进行中：转出方看到的是成员迁移潮，同样不提示
                if (prev.isNotEmpty() && !promotedRecently && !_transferring.value) {
                    val prevIds = prev.map { it.id }.toSet()
                    val newIds = event.members.map { it.id }.toSet()
                    event.members.filter { it.id !in prevIds && it.id != device.id }
                        .forEach { toastKey("member_joined", subs = mapOf("name" to it.name)) }
                    prev.filter { it.id !in newIds && it.id != device.id }
                        .forEach { toastKey("member_left", subs = mapOf("name" to it.name)) }
                }
                // fileList 内嵌在成员表：成员表即文件列表唯一真相源，从它全量重建
                _groupFiles.value = event.members.associate { it.id to it.fileList }
                applyEvent(event)
                _joining.value = false
            }
            is GroupEvent.Clipboard -> {
                // 回声/重复去重：指纹与最近一次相同（自己发出内容的回声、群主转发的重复消息）
                // 直接忽略——不追加历史、不通知，阻断"B/C 互发放大"链路
                if (event.payload.hash == clipGuard) return
                clipGuard = event.payload.hash
                applyEvent(event) // 前后台都记历史
                val text = event.payload.text
                if (inForeground) {
                    // 前台：直接写本机剪贴板；文本闸门防止监听器把它当成「本机复制」再发回群
                    appContext?.let {
                        runCatching { com.knotyup.android.clipboard.ClipboardCapability.setText(it, text) }
                        lastSyncText = text
                    }
                } else {
                    // 后台：Android 禁止读剪贴板，也不静默写（用户无感）→ 发悬浮通知告知，
                    // 由用户点「复制」按钮才写入（见 ClipboardCopyReceiver）
                    val fromName = event.fromName
                    appContext?.let {
                        android.util.Log.d("KnotYup", "后台收到剪贴板 from=$fromName len=${text.length} → 悬浮通知")
                        com.knotyup.android.service.ClipboardNotification.show(it, text, fromName)
                    }
                }
            }
            is GroupEvent.FileAdd -> {
                val cur = _groupFiles.value.toMutableMap()
                val list = cur.getOrDefault(event.from, emptyList()).toMutableList()
                if (list.none { it.fileId == event.file.fileId }) list.add(event.file)
                cur[event.from] = list
                _groupFiles.value = cur
                // 他人共享：前台 toast「XXX共享了文件」+ 文件名副标题；后台发悬浮通知（含下载按钮）
                if (event.from != device.id) {
                    val fromName = event.fromName
                    if (inForeground) {
                        toastKey("file_shared", 1, mapOf("name" to fromName), event.file.name)
                    } else {
                        appContext?.let { ctx ->
                            val f = event.file
                            com.knotyup.android.service.FileNotification.show(
                                ctx, event.from, fromName, f.name, f.fileId,
                                f.sha256, f.size, f.mime, f.addedAt,
                            )
                        }
                    }
                }
            }
            is GroupEvent.FileRemove -> {
                val cur = _groupFiles.value.toMutableMap()
                cur[event.from]?.let { list ->
                    cur[event.from] = list.filterNot { it.fileId in event.fileIds }
                }
                _groupFiles.value = cur
            }
            is GroupEvent.FileTasks -> {
                _transferTasks.value = event.tasks
                // 数据面中断：本机正在下载的任务若在全量快照中已 cancelled，触发 abort
                // （第三方取消场景：本机没调 cancelTask，只能靠快照感知）
                event.tasks.forEach { t ->
                    if (t.status == TaskStatus.CANCELLED) {
                        if (p2pFile.isDownloading(t.taskId)) p2pFile.cancelDownload(t.taskId)
                        // 本机供种该任务时同样中止（第三方取消的权威兜底）
                        p2pFile.cancelSeed(t.taskId)
                    }
                }
            }
            is GroupEvent.Kicked -> {
                reset()
                toastKey("kicked", 2)
                _joining.value = false
            }
            GroupEvent.Dissolve -> {
                // 解散：0.8s 缓冲后自动退出群（不回发任何 ws 消息）
                android.util.Log.d("KnotYup", "收到解散通知，0.8s 后退出")
                val delayMs = com.knotyup.android.net.Proto.DISSOLVE_EXIT_MS
                scope.launch {
                    delay(delayMs)
                    reset()
                    // 解散是群主主动操作的结果，普通提示即可，不用错误色
                    toastKey("group_dissolved")
                }
            }
            is GroupEvent.TransferOffer -> {
                // 被转让者：standby 端口起服务 → 通过原 ws 回 ready
                handleTransferOffer(event.pin)
            }
            GroupEvent.TransferError -> {
                // 转让已失败（迟到 ready）：静默关闭 standby 服务，不影响成员状态
                android.util.Log.d("KnotYup", "转让失败（transfer_error）：静默关闭 standby 服务")
                standbyServer?.let { runCatching { it.stop(0) } }
                standbyServer = null
            }
            is GroupEvent.NewService -> {
                if (event.owner.id == device.id) {
                    // 本机即新群主：standby 服务转正（绝不能自连接入自己的群 → 那会造出重复成员）
                    android.util.Log.d("KnotYup", "收到 new_service（owner=自己）：standby 服务转正")
                    promoteStandby()
                } else {
                    // 其他成员：断开旧连接 → 用同 PIN 连接新服务
                    android.util.Log.d("KnotYup", "收到 new_service addr=${event.addr} → 迁移")
                    migrateToNewService(event.addr, event.pin)
                }
            }
            GroupEvent.TransferFailed -> {
                // 群主侧本地事件：转让失败，原群继续
                _transferring.value = false
                toastKey("transfer_failed", 2)
            }
            GroupEvent.TransferCompleted -> {
                // 群主侧本地事件：转让完成，清会话回首页（终态统一提示，Loading 关闭）
                android.util.Log.d("KnotYup", "转让完成：清会话回首页")
                _transferring.value = false
                reset()
                toastKey("left")
            }
            is GroupEvent.Error -> {
                android.util.Log.w("KnotYup", "ws error: ${event.message}")
                val s = session.value
                // 连接断开：不再自动走选主重连，交给用户决策（弹窗：重新加入 / 退出群）
                when {
                    event.message.startsWith("connection closed") && s.joined && s.role == "member" ->
                        _connLost.value = true
                    else -> toastKey("op_failed", 2)
                }
            }
        }
    }

    /** 成员会话：连接 + 心跳 + 事件桥接（含 5s 入群超时）。进程级，旋转不中断。 */
    fun startMemberSession(addr: String, pin: String) {
        memberJob?.cancel()
        client.leave() // 防双实例：确保旧连接取消
        _joining.value = true
        p2pFile.updatePin(pin) // 供种服务切换到新群的 PIN，端口不变
        memberJob = scope.launch {
            onJoining(addr, pin)
            setStandby(ServerSocket(0))
            // 本次连接是否已拿到成员表：重新加入时旧会话 joined 仍为 true，
            // 超时判断不能依赖 session.joined，必须看本次握手结果
            var gotMembers = false
            val collector = launch {
                client.join(addr, pin, deviceWithStandbyAddr()).collect { result ->
                    result.fold({ event ->
                        if (event is GroupEvent.Members && !gotMembers) {
                            gotMembers = true
                            // fileList 已随 join 携带，无需单独 announce
                        }
                        emit(event)
                    }, { e ->
                        android.util.Log.w("KnotYup", "join failed: ${e.message}")
                        if (session.value.joined && session.value.role == "member") {
                            // 已入群后的连接失败（心跳失联/重新加入失败）→ 弹窗交用户决策
                            _connLost.value = true
                            _joining.value = false
                        } else {
                            toastKey(if (e.message == "WRONG_PIN") "wrong_pin" else "join_failed", 2)
                            _joining.value = false
                        }
                    })
                }
            }
            // 入群超时：5s 内未收到成员表即判失败
            delay(5000)
            if (_joining.value && !gotMembers) {
                android.util.Log.w("KnotYup", "join timeout (5s): $addr")
                collector.cancel()
                client.leave()
                if (session.value.joined && session.value.role == "member") {
                    // 重新加入超时：回到断连弹窗让用户再决策，不无限 Loading
                    _connLost.value = true
                } else {
                    toastKey("join_failed", 2)
                }
                _joining.value = false
            }
        }
    }

    /**
     * 本机剪贴板监听（仅前台系统回调）：Android 10+ 后台无焦点读不到剪贴板，
     * 故不再轮询，只注册 OnPrimaryClipChangedListener；切后台现在是省电的，
     * 群内新内容由悬浮通知告知（见 handleEvent 的 Clipboard 分支）。
     */
    fun restartClipWatcher() {
        watcherJob?.cancel()
        val ctx = appContext ?: return
        watcherJob = scope.launch {
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

    /**
     * 通知「复制」按钮：写入剪贴板并同步双闸门，避免监听器把这次写入当成本机复制再发回群。
     * 在 IO 线程调用（BroadcastReceiver 侧已切 IO）。
     */
    fun copyFromNotification(context: Context, text: String) {
        if (text.isEmpty()) return
        clipGuard = com.knotyup.android.net.textHash(text)
        lastSyncText = text
        runCatching { com.knotyup.android.clipboard.ClipboardCapability.setTextBlocking(context, text) }
    }

    /** 本机剪贴板变化 → 群广播（含指纹 + 文本双闸门防回环）。 */
    private suspend fun sendLocalClip(text: String) {
        val hash = com.knotyup.android.net.textHash(text)
        if (text.isEmpty() || text == lastSyncText || hash == clipGuard) return
        clipGuard = hash
        lastSyncText = text
        android.util.Log.d("KnotYup", "本机复制 → 广播 len=${text.length} hash=${hash.take(8)}")
        val sent = if (isOwner()) {
            server?.broadcastClipboard(text)
            server != null
        } else {
            client.sendClipboard(text)
        }
        if (sent) addLocalHistory(text)
    }

    // ── 文件共享 ───────────────────

    /** 共享本机文件（SAF URI）：算 sha256 → 登记本地清单 → 广播 file_add。返回新条目。 */
    suspend fun shareFile(uri: android.net.Uri): SharedFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ctx = appContext ?: error("appContext 未注入")
        val cr = ctx.contentResolver
        // 文件名必须查 DISPLAY_NAME：content:// 的 lastPathSegment 是 provider 内部文档 ID
        // （如 "43"），既不是名字也没有扩展名，会导致文件 tab 显示错名 + 类型图标映射失效
        val name = runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "file"
        // 持久化读授权：SAF 临时授权重启即失效，不持久化会导致重启后供种读不到（文件不可达）
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val size = try {
            cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (_: Exception) { 0L }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val sha = cr.openInputStream(uri)?.use { p2pFile.sha256Stream(it) } ?: error("无法读取文件")
        val file = SharedFile(
            fileId = java.util.UUID.randomUUID().toString(),
            name = name, size = size, sha256 = sha, mime = mime,
            addedAt = System.currentTimeMillis(),
        )
        fileIndex.add(com.knotyup.android.file.LocalFile(file, uri.toString()))
        broadcastFileAdd(file)
        file
    }

    /** 删除本地共享条目（磁盘文件不动）+ 广播 + 更新群列表。 */
    fun removeSharedFiles(ids: List<String>) {
        fileIndex.remove(ids)
        if (isOwner()) server?.broadcastFileRemove(ids) else client.sendFileRemove(ids)
        device = device.copy(fileList = device.fileList.filterNot { it.fileId in ids })
        _groupFiles.value = _groupFiles.value.mapValues { (_, list) -> list.filterNot { it.fileId in ids } }
    }

    /** 下载成功提示：前台 toast（标题「XX下载成功」+ 副标题路径），后台发悬浮通知。 */
    fun notifyDownloaded(fileName: String) {
        if (inForeground) {
            val ctx = appContext
            val lang = ctx?.let { loadLang(it) } ?: "zh-Hans"
            val pathSub = com.knotyup.android.ui.Strings.t(lang, "download_path")
            toastKey("download_ok", 1, mapOf("name" to fileName), subtitle = pathSub)
        } else {
            appContext?.let { com.knotyup.android.service.FileNotification.showDownloaded(it, fileName) }
        }
    }

    /** 悬浮通知「下载」按钮：从 extra 重建 SharedFile → P2P 下载 → toast 结果。IO 线程调用。 */
    fun downloadFromNotification(context: Context, seedId: String, fileId: String, name: String,
                                 sha256: String, size: Long, mime: String, addedAt: Long) {
        if (appContext == null) appContext = context.applicationContext
        val file = com.knotyup.android.net.SharedFile(fileId, name, size, sha256, mime, addedAt)
        kotlinx.coroutines.runBlocking {
            try {
                downloadSharedFile(seedId, file)
                notifyDownloaded(name)
            } catch (e: Exception) {
                toastKey("transfer_failed", 2)
            }
        }
    }

    /** 从供种者下载文件 → 写公共 Downloads/knotShare。纯本地行为：不登记共享清单、不广播、不成为供种者。 */
    suspend fun downloadSharedFile(seedId: String, file: SharedFile): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ctx = appContext ?: error("appContext 未注入")
        val seedMember = session.value.members.firstOrNull { it.id == seedId }
        val seedAddr = seedMember?.fileAddr ?: error("供种者不在线")
        val seedName = seedMember!!.name
        // 传输任务诞生（下载方上报 queuing）；task_id 随 P2P 请求帧传给供种方
        val taskId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val task = TransferTask(
            taskId, file.fileId, file.name, file.size, file.mime,
            seedId, seedName, device.id, device.name,
            TaskStatus.QUEUING, 0, 0L, "", "", "",
            now, now,
        )
        if (isOwner()) server?.localTaskAdd(task) else client.sendTaskAdd(task)
        val resolver = ctx.contentResolver
        val collection = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, file.mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/knotShare")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("创建下载文件失败")
        // 下载进度常驻通知：无论成功/失败，结束都 cancel
        com.knotyup.android.service.DownloadProgressNotification.show(ctx, file.name)
        try {
            val out = resolver.openOutputStream(uri) ?: error("打开输出流失败")
            try {
                p2pFile.download(seedAddr, seedId, file.fileId, out,
                    onProgress = { received, total ->
                        com.knotyup.android.service.DownloadProgressNotification.update(ctx, file.name, received, total)
                    },
                    taskId = taskId,
                )
            } finally {
                out.close()
            }
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            // 本机主动取消不报 Failed——群主已置 cancelled；其它失败照常上报（与供种方双保险）
            if (e !is com.knotyup.android.file.DownloadCancelledException) {
                val err = e.message ?: "下载失败"
                if (isOwner()) server?.localTaskEvent(TaskEvent.Failed(taskId, err))
                else client.sendTaskFailed(taskId, err)
            }
            throw e
        } finally {
            com.knotyup.android.service.DownloadProgressNotification.cancel(ctx)
        }
        // 下载即终点：不再登记/广播，文件仅落盘到 Downloads/knotShare
        uri.toString()
    }

    /** 取消传输：群内任一成员可取消 queuing/transferring 的任务。 */
    fun cancelTask(taskId: String) {
        // 本机正在下载该任务 → 立即 abort（关闭 socket，阻塞读退出并清理 MediaStore）
        p2pFile.cancelDownload(taskId)
        // 本机供种该任务 → 立即中止写块
        p2pFile.cancelSeed(taskId)
        // 上报群主：群主置 cancelled + 广播全量
        if (isOwner()) server?.localTaskCancel(taskId, device.id, device.name)
        else client.sendTaskCancel(taskId)
        // 本机主动取消成功：轻提示 + 文件名副标题（第三方取消走快照，不弹）
        val name = _transferTasks.value.firstOrNull { it.taskId == taskId }?.name.orEmpty()
        toastKey("task_cancel_ok", 1, subtitle = name)
    }

    private fun broadcastFileAdd(file: SharedFile) {
        if (isOwner()) server?.broadcastFileAdd(file) else client.sendFileAdd(file)
        device = device.copy(fileList = if (device.fileList.none { it.fileId == file.fileId }) device.fileList + file else device.fileList)
        _groupFiles.value = _groupFiles.value.toMutableMap().also { m ->
            val list = m.getOrDefault(device.id, emptyList()).toMutableList()
            if (list.none { it.fileId == file.fileId }) list.add(file)
            m[device.id] = list
        }
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
        p2pFile.updatePin(pin)
        val filePort = p2pFile.startServer()
        val ipAddr = Discovery.localIpAddress(appContext)
        val fileAddr = ipAddr?.let { "$it:$filePort" }
        // 群主本地文件列表写入 device.fileList（成员表即文件列表真相源）
        device = device.copy(fileList = fileIndex.sharedFiles())
        _groupFiles.value = mapOf(device.id to device.fileList)
        val owner = device.copy(addr = null, fileAddr = fileAddr)
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
            startRespond(addr)
        } else {
            android.util.Log.w("KnotYup", "becomeOwner: 无法解析本机局域网 IP，群主已承接但暂不广播（地址显示为 :$boundPort）")
            _session.value = SessionState(
                role = "owner", addr = ":$boundPort", pin = pin,
                members = listOf(device), joined = true,
            )
        }
        return _session.value.addr to boundPort
    }

    /** 停止发现应答与服务端。 */
    fun stopServer() {
        respondJob?.cancel()
        respondJob = null
        server?.let { runCatching { it.stop(0) } }
        server = null
    }

    /** v2 群主被动应答发现请求（成员数实时取自服务端）。 */
    private fun startRespond(addr: String) {
        respondJob?.cancel()
        val ctx = appContext ?: return
        val s = server ?: return
        scope.launch {
            respondJob = runCatching {
                Discovery.respond(ctx, GroupAd(device.id, device.name, addr), memberCount = { s.memberCount() })
            }.getOrNull()
        }
    }

    /** 群主解散并重置。 */
    fun dissolveOwner() {
        server?.dissolve()
        stopServer()
        reset()
    }

    /**
     * 群主转让（v2 三方握手）：发起 offer 后立即返回，不退出群。
     * 成功 → NewService 广播 + 服务停止（本机经 reset 回首页）；失败 → TransferFailed 提示，原群继续。
     */
    fun transferOwner(targetId: String) {
        val target = _session.value.members.firstOrNull { it.id == targetId } ?: return
        val s = server ?: return
        _transferring.value = true
        s.transfer(target.id)
    }

    /** 被转让者：standby 端口起服务 → 通过原 ws 回 ready。失败不回（群主 5s 超时即转让失败）。 */
    private fun handleTransferOffer(pin: String) {
        val ctx = appContext ?: return
        // 先取端口再关预留 socket（关后 localPort 即失效）
        val port = standby?.let { l ->
            val p = runCatching { l.localPort }.getOrDefault(0)
            runCatching { l.close() }
            p
        } ?: reservePort()
        standby = null
        device = device.copy(fileList = fileIndex.sharedFiles())
        val dev = device.copy(role = "owner")
        val s = KnotGroupServer(port, pin, dev, ::emit)
        s.start()
        standbyServer = s
        val ip = Discovery.localIpAddress(ctx)
        if (ip != null) {
            val addr = "$ip:${s.boundPort()}"
            android.util.Log.d("KnotYup", "standby 服务就绪 $addr -> transfer_ready")
            client.sendTransferReady(addr)
        } else {
            // 无法解析地址：不起响应直接关服务（群主超时判失败）
            android.util.Log.w("KnotYup", "transfer_offer: 无法解析本机 IP，放弃承接")
            runCatching { s.stop(0) }
            standbyServer = null
        }
    }

    /** 其他成员：断开旧连接 → 用同 PIN 连接新服务（复用成员会话机制，保留 standby 承接资格）。 */
    private fun migrateToNewService(addr: String, pin: String) {
        android.util.Log.d("KnotYup", "迁移到新服务 $addr")
        startMemberSession(addr, pin)
    }

    /** 被转让者收到 new_service（owner=自己）后转正：standby 服务 → 正式群主会话 + 发现应答。 */
    private fun promoteStandby() {
        val s = standbyServer
        if (s == null) {
            // standby 已被清理（异常路径）：直接回首页
            android.util.Log.w("KnotYup", "promoteStandby: standby 服务不存在，清会话")
            reset()
            return
        }
        standbyServer = null
        val ctx = appContext
        val ip = ctx?.let { Discovery.localIpAddress(it) }
        val addr = if (ip != null) "$ip:${s.boundPort()}" else ":${s.boundPort()}"
        memberJob?.cancel()
        memberJob = null
        client.leave()
        server = s
        _session.value = SessionState(
            role = "owner", addr = addr, pin = _session.value.pin,
            members = listOf(device.copy(role = "owner")), joined = true,
        )
        if (ip != null) startRespond(addr)
        promotedAt = System.currentTimeMillis()
        toastKey("promoted", 1)
    }

    /** 连接断开弹窗 → 重新加入：用会话里记录的地址 + PIN 重走完整入群流程。 */
    fun reconnect() {
        val s = session.value
        if (!s.joined || s.addr.isBlank()) return
        _connLost.value = false
        startMemberSession(s.addr, s.pin)
    }

    /** 连接断开弹窗 → 退出群。 */
    fun leaveAfterConnLost() {
        _connLost.value = false
        reset()
        toastKey("left")
    }

    fun reset() {
        _transferring.value = false
        _connLost.value = false
        memberJob?.cancel()
        memberJob = null
        watcherJob?.cancel()
        watcherJob = null
        standbyServer?.let { runCatching { it.stop(0) } }
        standbyServer = null
        client.leave()
        stopServer()
        standby?.let { runCatching { it.close() } }
        standby = null
        _session.value = SessionState()
        _history.value = emptyList()
        _transferTasks.value = emptyList()
        _joining.value = false
    }

    // 偏好：主题色 / 语言 / 首次引导 key
    private const val PREFS = "knotyup"
    private const val KEY_THEME = "theme"
    private const val KEY_LANG = "lang"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_TP_GUIDE_DONE = "tp_guide_done"

    /** 首次启动引导是否已完成（完成/开始使用后置位，永不再现）。 */
    fun isOnboarded(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ONBOARDED, false)

    fun saveOnboarded(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDED, true).apply()

    /** 触摸板引导是否已完成（首次点击连接设备时展示一次）。 */
    fun isTouchpadGuideDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TP_GUIDE_DONE, false)

    fun saveTouchpadGuideDone(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TP_GUIDE_DONE, true).apply()

    fun saveTheme(context: Context, id: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, id).apply()

    fun loadTheme(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)

    fun saveLang(context: Context, id: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, id).apply()

    fun loadLang(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, null)
}
