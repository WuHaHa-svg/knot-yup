package com.knotyup.android

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knotyup.android.clipboard.ClipboardCapability
import com.knotyup.android.data.AppState
import com.knotyup.android.net.DeviceInfo
import com.knotyup.android.net.Discovery
import com.knotyup.android.net.GroupAd
import com.knotyup.android.net.GroupEvent
import com.knotyup.android.net.textHash
import com.knotyup.android.data.HistoryItem
import com.knotyup.android.ui.KnotYupTheme
import com.knotyup.android.ui.Strings
import com.knotyup.android.ui.THEMES
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

@OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent?.takeIf { it.action == android.content.Intent.ACTION_SEND }
            ?.getStringExtra(android.content.Intent.EXTRA_TEXT)

        setContent {
            val context = LocalContext.current
            // 窗口宽度分级：Compact 手机竖屏；Medium/Expanded（平板/横屏）首页双栏对齐桌面端
            val windowSizeClass = androidx.compose.material3.windowsizeclass.calculateWindowSizeClass(this)
            val wide = windowSizeClass.widthSizeClass !=
                androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact
            var themeId by remember { mutableStateOf(AppState.loadTheme(context) ?: "indigo") }
            var lang by remember {
                mutableStateOf(AppState.loadLang(context) ?: run {
                    val locale = resources.configuration.locales[0]
                    when {
                        locale.language != "zh" -> "en"
                        locale.country in listOf("TW", "HK", "MO") -> "zh-Hant"
                        else -> "zh-Hans"
                    }
                })
            }
            val t = { key: String -> Strings.t(lang, key) }

            KnotYupTheme(themeId) {
                // 状态栏图标颜色随系统明暗
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                        .isAppearanceLightStatusBars = !dark
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(t, lang = lang, wide = wide, themeId = themeId,
                        onTheme = { themeId = it; AppState.saveTheme(context, it) },
                        onLang = { lang = it; AppState.saveLang(context, it) }, sharedText)
                }
            }
        }
    }
}

@Composable
private fun App(
    t: (String) -> String,
    lang: String,
    wide: Boolean,
    themeId: String,
    onTheme: (String) -> Unit,
    onLang: (String) -> Unit,
    sharedText: String?,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val session by AppState.session.collectAsState()
    val history by AppState.history.collectAsState()
    val toastMsg by AppState.toast.collectAsState()
    var discovered by remember { mutableStateOf<List<GroupAd>>(emptyList()) }
    var addr by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    var sendText by remember { mutableStateOf(sharedText ?: "") }
    var confirmLeave by remember { mutableStateOf(false) }
    var pendingKick by remember { mutableStateOf<String?>(null) }
    var pinDialogFor by remember { mutableStateOf<GroupAd?>(null) }

    // 返回键拦截：群页面 back 不退群，仅退到系统主界面（等同 Home 键），不触发任何 ws 操作
    val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
    androidx.activity.compose.BackHandler(enabled = session.joined) {
        activity?.moveTaskToBack(true)
    }

    // 手动/弹窗共用的入群流程
    fun startJoin(addrValue: String, pinValue: String) {
        joining = true
        scope.launch {
            // 记录会话（重连/承接群主需要同 PIN），预留端口并随 join 上报地址
            AppState.onJoining(addrValue, pinValue)
            AppState.setStandby(java.net.ServerSocket(0))
            AppState.client.join(addrValue, pinValue, AppState.deviceWithStandbyAddr()).collect { result ->
                result.fold({ event ->
                    AppState.emit(event)
                    when (event) {
                        is GroupEvent.Members -> joining = false
                        is GroupEvent.Kicked, GroupEvent.Disconnected -> joining = false
                        else -> {}
                    }
                }, { e ->
                    AppState.toast(
                        when (e.message) {
                            "WRONG_PIN" -> t("wrong_pin")
                            else -> "${t("join_failed")}\n${e.message ?: ""}"
                        }
                    )
                    joining = false
                })
            }
        }
    }
    // 剪贴板指纹闸门[0]：最近一次同步的内容指纹（收/发都记录），防止「写入被当成本机复制」回环
    val clipGuard = remember { arrayOf("") }

    // 事件处理（含写本机剪贴板，需要 context）
    LaunchedEffect(Unit) {
        AppState.events.collect { event ->
            when (event) {
                is GroupEvent.Members -> {
                    // 成员差量 → 加入/离开提示（首次同步与自身变化不提示）
                    val prev = AppState.session.value.members
                    if (prev.isNotEmpty()) {
                        val prevIds = prev.map { it.id }.toSet()
                        val newIds = event.members.map { it.id }.toSet()
                        event.members.filter { it.id !in prevIds && it.id != AppState.device.id }
                            .forEach { AppState.toast(t("member_joined").replace("{name}", it.name)) }
                        prev.filter { it.id !in newIds && it.id != AppState.device.id }
                            .forEach { AppState.toast(t("member_left").replace("{name}", it.name)) }
                    }
                    AppState.applyEvent(event)
                }
                is GroupEvent.Clipboard -> {
                    clipGuard[0] = event.payload.hash
                    AppState.applyEvent(event)
                    ClipboardCapability.setText(context, event.payload.text)
                }
                is GroupEvent.Kicked -> {
                    AppState.reset()
                    AppState.toast(if (event.reason == "GROUP_DISSOLVED") t("group_dissolved") else t("kicked"))
                }
                is GroupEvent.OwnerChange -> handleOwnerLost(t, scope, event.newOwner)
                GroupEvent.Disconnected -> handleOwnerLost(t, scope, null)
                is GroupEvent.Error -> {
                    // 成员期连接错误（如未收到转让通知即断开）→ 同样走选主
                    val s = AppState.session.value
                    android.util.Log.w("KnotYup", "ws error: ${event.message}")
                    if (s.joined && s.role == "member") {
                        handleOwnerLost(t, scope, null)
                    } else {
                        AppState.toast(event.message)
                    }
                }
            }
        }
    }

    // 发现监听：仅在首页（未入群）且页面处于 RESUMED 时扫描，离开即停
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(session.joined, lifecycleOwner) {
        if (!session.joined) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                Discovery.listen(context).collect { discovered = it }
            }
        }
    }

    // 前台剪贴板监听：本机复制 → 发送到群
    LaunchedEffect(session.joined) {
        if (session.joined) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val listener = ClipboardManager.OnPrimaryClipChangedListener {
                val text = ClipboardCapability.getText(context) ?: return@OnPrimaryClipChangedListener
                val hash = textHash(text)
                // 跳过刚写入的远端同步内容（指纹相同）
                if (text.isNotEmpty() && hash != clipGuard[0]) {
                    clipGuard[0] = hash
                    val sent = if (AppState.isOwner()) {
                        AppState.server?.broadcastClipboard(text)
                        AppState.server != null
                    } else {
                        AppState.client.sendClipboard(text)
                    }
                    if (sent) AppState.addLocalHistory(text)
                }
            }
            cm.addPrimaryClipChangedListener(listener)
            try {
                while (true) delay(60_000) // 保持监听直到离开组合/入群状态变化
            } finally {
                cm.removePrimaryClipChangedListener(listener)
            }
        }
    }

    // toast 自动消失
    LaunchedEffect(toastMsg) {
        if (toastMsg != null) {
            delay(2200)
            AppState.clearToast()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Header(onTheme, onLang, lang, themeId)
            if (session.joined) {
                GroupBody(t, session.members, history, sendText,
                    role = session.role,
                    groupAddr = session.addr, groupPin = session.pin,
                    onKick = { pendingKick = it },
                    onCopy = { text ->
                        // 点击历史复制：先同步指纹闸门，避免被监听器当作本机复制再发回群
                        clipGuard[0] = textHash(text)
                        ClipboardCapability.setText(context, text)
                        AppState.toast(t("copied"))
                    },
                    onSendText = { sendText = it },
                    onSend = {
                        if (sendText.isNotBlank()) {
                            val sent = if (AppState.isOwner()) {
                                AppState.server?.broadcastClipboard(sendText)
                                AppState.server != null
                            } else {
                                AppState.client.sendClipboard(sendText)
                            }
                            if (sent) {
                                AppState.addLocalHistory(sendText)
                                AppState.toast(t("sent"))
                                sendText = ""
                            }
                        }
                    },
                    onLeave = { confirmLeave = true })
            } else {
                // key：从群页面返回首页时重新挂载，重播入场动画
                key(session.joined) {
                    HomeBody(
                    t = t, wide = wide, discovered = discovered,
                    addr = addr, pin = pin, joining = joining,
                    onAddr = { addr = it }, onPin = { pin = it },
                    onPickGroup = { pinDialogFor = it },
                    onCreate = {
                        val port = AppState.reservePort()
                        val pin2 = java.security.SecureRandom().nextInt(900_000) + 100_000
                        AppState.becomeOwner(pin2.toString(), port)
                        AppState.toast(t("group_created").replace("{pin}", pin2.toString()))
                    },
                    onJoin = {
                        if (addr.isBlank() || pin.isBlank()) {
                            AppState.toast(t("fill_addr_pin"))
                        } else {
                            startJoin(addr, pin)
                        }
                    },
                    )
                }
            }
        }

        // Toast
        toastMsg?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .background(Color(0xE61D2129), RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(msg, color = Color.White, fontSize = 13.sp)
            }
        }

        // 退出群二次确认（自绘弹窗，风格与桌面端一致）
        if (confirmLeave) {
            val ownerLeave = AppState.isOwner()
            KnotConfirm(
                title = t(if (ownerLeave) "dissolve" else "confirm_leave_title"),
                message = t(if (ownerLeave) "transfer_hint" else "confirm_leave_msg"),
                okText = t("confirm_ok"),
                cancelText = t("confirm_cancel"),
                members = if (ownerLeave) {
                    AppState.session.value.members.filter { it.id != AppState.device.id }
                } else emptyList(),
                onOk = { selected ->
                    confirmLeave = false
                    if (ownerLeave) {
                        if (selected != null) {
                            AppState.transferOwner(selected)
                        } else {
                            AppState.dissolveOwner()
                        }
                    } else {
                        AppState.reset()
                    }
                    AppState.toast(t("left"))
                },
                onCancel = { confirmLeave = false },
            )
        }

        // 移出成员二次确认
        pendingKick?.let { kickId ->
            val m = AppState.session.value.members.firstOrNull { it.id == kickId }
            KnotConfirm(
                title = t("kick"),
                message = t("confirm_kick_msg").replace("{name}", m?.name ?: ""),
                okText = t("confirm_ok"),
                cancelText = t("confirm_cancel"),
                onOk = {
                    AppState.server?.kick(kickId)
                    pendingKick = null
                },
                onCancel = { pendingKick = null },
            )
        }

        // 加入群（PIN）弹窗：点击发现的群直接弹起
        pinDialogFor?.let { group ->
            PinDialog(
                t = t,
                group = group,
                onJoin = { pinValue ->
                    pinDialogFor = null
                    startJoin(group.addr, pinValue)
                },
                onDismiss = { pinDialogFor = null },
            )
        }
    }
}

/** 桌面端同款确认弹窗：半透明遮罩 + 白卡 + 主操作/危险操作按钮。可选成员勾选（群主转让）。 */
@Composable
private fun KnotConfirm(
    title: String,
    message: String,
    okText: String,
    cancelText: String,
    danger: Boolean = true,
    members: List<DeviceInfo> = emptyList(),
    onOk: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        // 遮罩
        Box(Modifier.fillMaxSize().background(Color(0x52101216)))
        Box(Modifier.align(Alignment.Center).padding(horizontal = 40.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 24.dp,
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(message, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
                    // 可选成员勾选列表
                    if (members.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        members.forEach { m ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected == m.id) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { selected = if (selected == m.id) null else m.id }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selected == m.id,
                                    onCheckedChange = { selected = if (it) m.id else null },
                                )
                                Text(m.name, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) { Text(cancelText, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        Button(
                            onClick = { onOk(selected) },
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            colors = if (danger) ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFDEEF0),
                                contentColor = Color(0xFFD94559),
                            ) else ButtonDefaults.buttonColors(),
                        ) { Text(if (selected != null) t0("transfer_leave") else okText) }
                    }
                }
            }
        }
    }
}

/** KnotConfirm 内部使用的文案辅助（避免在非参数位置捕获 t）。 */
private fun t0(key: String): String = com.knotyup.android.ui.Strings.t(com.knotyup.android.ui.Strings.currentLang, key)

// —— UI 组件 ————————————————————————————

/** 选主与重连：群主意外离开或主动转让。 */
private fun handleOwnerLost(
    t: (String) -> String,
    scope: kotlinx.coroutines.CoroutineScope,
    explicit: DeviceInfo?,
) {
    val s = AppState.session.value
    val candidates = explicit?.let { listOf(it) } ?: s.members.drop(1) // [0] 是旧群主
    android.util.Log.d("KnotYup", "owner lost: explicit=${explicit?.name}, candidates=${candidates.map { "${it.name}@${it.addr}" }}")
    if (candidates.isEmpty()) {
        AppState.reset()
        AppState.toast(t("group_lost"))
        return
    }
    if (candidates[0].id == AppState.device.id) {
        // 当选：用预留端口承接为群主
        val port = AppState.standby?.localPort ?: AppState.reservePort()
        AppState.becomeOwner(s.pin, port)
        AppState.toast(t("promoted"))
    } else {
        // 其余成员：依次尝试候选地址重连
        AppState.toast(t("reconnecting"))
        val pin_ = s.pin
        scope.launch {
            for (attempt in 0 until 10) {
                for (c in candidates) {
                    if (c.id == AppState.device.id || c.addr.isNullOrBlank()) continue
                    var connected = false
                    android.util.Log.d("KnotYup", "reconnect attempt=$attempt -> ${c.name}@${c.addr}")
                    try {
                        AppState.client.join(c.addr, pin_, AppState.deviceWithStandbyAddr()).collect { res ->
                            res.fold({ ev ->
                                if (ev is GroupEvent.Members && !connected) {
                                    connected = true
                                    AppState.toast(t("reconnected"))
                                }
                                AppState.emit(ev)
                            }, { e ->
                                android.util.Log.w("KnotYup", "reconnect failed: ${e.message}")
                            })
                        }
                    } catch (_: Exception) {
                    }
                    if (connected) return@launch // 正常入群；若再次断开，Disconnected 事件会重新触发选主
                    kotlinx.coroutines.delay(500)
                }
                kotlinx.coroutines.delay(1500)
            }
            AppState.reset()
            AppState.toast(t("group_lost"))
        }
    }
}

@Composable
private fun Header(onTheme: (String) -> Unit, onLang: (String) -> Unit, lang: String, themeId: String) {
    var themeOpen by remember { mutableStateOf(false) }
    var langOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌：渐变方块 + 绳结图标（对齐桌面端顶栏）
        val accent = MaterialTheme.colorScheme.primary
        val accent2 = androidx.compose.ui.graphics.lerp(accent, Color(0xFFA855F7), 0.45f)
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent, accent2))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_knot),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("KnotYup", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.weight(1f))

        // 主题色：点击弹出锚定色板（与桌面端 pop 一致）
        Box {
            HeaderIconBtn(onClick = { themeOpen = true }) {
                Image(painterResource(R.drawable.ic_palette), "theme", Modifier.size(18.dp))
            }
            DropdownMenu(
                expanded = themeOpen,
                onDismissRequest = { themeOpen = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
            ) {
                // 4×2 色板网格，当前主题带描边圈标识（对齐桌面端 .swatch.active）
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    THEMES.chunked(4).forEach { rowThemes ->
                        Row {
                            rowThemes.forEach { (id, color) ->
                                val active = id == themeId
                                Box(
                                    Modifier
                                        .padding(5.dp)
                                        .size(if (active) 30.dp else 24.dp)
                                        .then(
                                            if (active) Modifier
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .border(
                                                    2.dp,
                                                    MaterialTheme.colorScheme.primary,
                                                    androidx.compose.foundation.shape.CircleShape,
                                                )
                                                .padding(3.dp)
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(color, androidx.compose.foundation.shape.CircleShape)
                                            .clickable { onTheme(id); themeOpen = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // 语言：点击弹出菜单，当前语言高亮
        Box {
            HeaderIconBtn(onClick = { langOpen = true }) {
                Text(Strings.langLabels[lang] ?: "简", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(
                expanded = langOpen,
                onDismissRequest = { langOpen = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
            ) {
                Strings.langs.forEach { l ->
                    val active = l == lang
                    DropdownMenuItem(
                        text = {
                            Text(
                                Strings.langLabels[l] ?: l,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = if (active) {
                            { Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        } else null,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        onClick = { onLang(l); langOpen = false },
                    )
                }
            }
        }
    }
}

/** 顶栏小圆钮（30dp 圆角、描边、居中内容），对应桌面端 .icon-btn。 */
@Composable
private fun HeaderIconBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(30.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** 挂载动画：上浮（弹性 spring）+ 淡入，交错紧密、连贯柔和；key() 重新进入组合即重播。 */
@Composable
fun Modifier.riseIn(delayMillis: Int = 0): Modifier {
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val offset = remember { androidx.compose.animation.core.Animatable(30f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch {
                kotlinx.coroutines.delay(delayMillis.toLong())
                // 缓入淡入，与位移时长相近，避免"先亮后动"的割裂感
                alpha.animateTo(
                    1f,
                    androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.CubicBezierEasing(.35f, 0f, .25f, 1f)),
                )
            }
            launch {
                kotlinx.coroutines.delay(delayMillis.toLong())
                // 无过冲弹性：起步快、收尾柔，链条感强
                offset.animateTo(
                    0f,
                    androidx.compose.animation.core.spring(
                        dampingRatio = 0.9f,
                        stiffness = 130f,
                        visibilityThreshold = 0.1f,
                    ),
                )
            }
        }
    }
    return this.then(
        Modifier.graphicsLayer {
            translationY = offset.value
            this.alpha = alpha.value
        }
    )
}

/** 首页：Hero + 双卡片（对齐桌面端布局逻辑；窄屏上下、宽屏左右）。 */
@Composable
private fun HomeBody(
    t: (String) -> String,
    wide: Boolean,
    discovered: List<GroupAd>,
    addr: String, pin: String, joining: Boolean,
    onAddr: (String) -> Unit, onPin: (String) -> Unit,
    onPickGroup: (GroupAd) -> Unit,
    onJoin: () -> Unit, onCreate: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Hero：主标题（第二段渐变）+ 副标题
        val accent = MaterialTheme.colorScheme.primary
        val accent2 = androidx.compose.ui.graphics.lerp(accent, Color(0xFFA855F7), 0.45f)
        Text(
            buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp)) { append(t("tagline_1")) }
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        fontWeight = FontWeight.Bold, fontSize = 26.sp,
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent, accent2)),
                    )
                ) { append(t("tagline_2")) }
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.riseIn(0),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            t("tagline_sub"), fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.riseIn(70),
        )
        Spacer(Modifier.height(24.dp))

        if (wide) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).riseIn(140),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                JoinCard(t, addr, pin, joining, Modifier.weight(1f), onAddr, onPin, onJoin, onCreate)
                DiscoveredCard(t, discovered, Modifier.weight(1f), onPickGroup)
            }
        } else {
            JoinCard(t, addr, pin, joining, Modifier.fillMaxWidth().padding(horizontal = 24.dp).riseIn(140), onAddr, onPin, onJoin, onCreate)
            Spacer(Modifier.height(16.dp))
            DiscoveredCard(t, discovered, Modifier.fillMaxWidth().padding(horizontal = 24.dp).riseIn(210), onPickGroup)
        }
    }
}

/** 卡片头：彩色图标 + 标题 + 副文字（对齐桌面端卡片结构）。 */
@Composable
private fun CardHead(iconRes: Int, iconBg: Color, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text(subtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

/** 左卡：加入 / 创建。 */
@Composable
private fun JoinCard(
    t: (String) -> String,
    addr: String, pin: String, joining: Boolean,
    modifier: Modifier,
    onAddr: (String) -> Unit, onPin: (String) -> Unit,
    onJoin: () -> Unit, onCreate: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(20.dp)) {
            CardHead(
                iconRes = R.drawable.ic_link,
                iconBg = Color(0xFFF0FDF4),
                title = t("join_group"),
                subtitle = t("join_desc"),
            )
            OutlinedTextField(
                value = addr, onValueChange = onAddr, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(t("addr_hint"), fontSize = 13.sp) }, singleLine = true,
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pin, onValueChange = onPin, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(t("pin_hint"), fontSize = 13.sp) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCreate,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Image(
                        painterResource(R.drawable.ic_plus), null, Modifier.size(14.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(6.dp))
                    // 通用处理：单行不换行（固定高度 + 换行会被裁切），宽度不足时省略号
                    Text(t("btn_create"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = onJoin, enabled = !joining,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Text(t("btn_join"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 右卡：局域网发现的群（上下结构列表项，点击弹 PIN）。 */
@Composable
private fun DiscoveredCard(
    t: (String) -> String,
    discovered: List<GroupAd>,
    modifier: Modifier,
    onPickGroup: (GroupAd) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(20.dp)) {
            CardHead(
                iconRes = R.drawable.ic_devices,
                iconBg = Color(0xFFEFF6FF),
                title = t("discovered"),
                subtitle = t("discovering"),
            )
            if (discovered.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painterResource(R.drawable.ic_devices), null,
                            Modifier.size(28.dp).then(Modifier),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t("no_discovered"), fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                ) {
                    discovered.forEach { g ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPickGroup(g) }
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(6.dp).background(
                                    MaterialTheme.colorScheme.primary,
                                    androidx.compose.foundation.shape.CircleShape,
                                )
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    g.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    g.addr, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 加入群（PIN）弹窗，风格与桌面端一致。 */
@Composable
private fun PinDialog(
    t: (String) -> String,
    group: GroupAd,
    onJoin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pinValue by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0x52101216)).clickable(enabled = false) {})
        Box(Modifier.align(Alignment.Center).padding(horizontal = 40.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 24.dp,
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(t("join_group"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${group.name} · ${group.addr}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinValue, onValueChange = { pinValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(t("pin_hint"), fontSize = 13.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(10.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) { Text(t("confirm_cancel"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        Button(
                            onClick = { if (pinValue.isNotBlank()) onJoin(pinValue.trim()) },
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) { Text(t("btn_join"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupBody(
    t: (String) -> String,
    members: List<DeviceInfo>,
    history: List<HistoryItem>,
    sendText: String,
    role: String,
    groupAddr: String,
    groupPin: String,
    onKick: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSend: () -> Unit,
    onLeave: () -> Unit,
) {
    val shizuku = remember { ClipboardCapability.isShizukuActive() && ClipboardCapability.isShizukuPermissionGranted() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                if (shizuku) t("shizuku_mode_hint") else t("fg_mode_hint"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        item {
            // 群主信息卡
            if (role == "owner") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t("group_addr"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(groupAddr, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t("group_pin"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(groupPin, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(t("devices"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            members.forEachIndexed { i, m ->
                val self = m.id == AppState.device.id
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(32.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            m.name.take(1).uppercase(), color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (self) {
                                Spacer(Modifier.width(6.dp))
                                Text(t("you"), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            if (i == 0) {
                                Spacer(Modifier.width(6.dp))
                                Image(
                                    painter = painterResource(R.drawable.ic_crown),
                                    contentDescription = "owner",
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(t("owner"), fontSize = 10.sp, color = Color(0xFFB45309))
                            }
                        }
                        Text(m.platform, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (role == "owner" && !self) {
                        TextButton(onClick = { onKick(m.id) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                            Text(t("kick"), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        item {
            Text(t("clipboard"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(t("click_hint"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        if (history.isEmpty()) {
            item {
                Text(
                    "${t("empty_1")}\n${t("empty_2")}", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        } else {
            items(history) { item ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onCopy(item.text) }
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    val name = members.firstOrNull { it.id == item.from }?.name
                        ?: if (item.from == AppState.device.id) AppState.device.name else item.from.take(6)
                    Text(
                        name, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(item.text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sendText, onValueChange = onSendText,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(t("send_hint"), fontSize = 13.sp) },
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSend, shape = RoundedCornerShape(10.dp), modifier = Modifier.height(52.dp)) {
                    Text(t("send"))
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) { Text(t(if (role == "owner") "dissolve" else "leave")) }
        }
    }
}
