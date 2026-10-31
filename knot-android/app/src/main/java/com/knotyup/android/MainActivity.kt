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
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.knotyup.android.ui.Motion

/** 导航路由。 */
private object Routes {
    const val HOME = "home"
    const val GROUP = "group"
    const val SCANNER = "scanner"
}

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
                // 全屏：隐藏状态栏（下滑可临时呼出）
                androidx.compose.runtime.SideEffect {
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                    controller.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
    var showQr by remember { mutableStateOf(false) }
    // rememberSaveable：横竖屏旋转（Activity 重建）不重播开屏动画
    var showSplash by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }

    // 启动页 → 主界面（Crossfade 优雅过渡）
    androidx.compose.animation.Crossfade(targetState = showSplash, animationSpec = androidx.compose.animation.core.tween(500)) { splash ->
        if (splash) {
            SplashScreen { showSplash = false }
            return@Crossfade
        }

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
                        },
                        kind = 2,
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
                    AppState.toast(if (event.reason == "GROUP_DISSOLVED") t("group_dissolved") else t("kicked"), 2)
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
                        AppState.toast(event.message, 2)
                    }
                }
            }
        }
    }

    // 发现监听：过滤本机 IP 的群（自己广播的群不显示）；仅在首页且 RESUMED 时扫描
    val myIp = Discovery.localIpAddress()
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(session.joined, lifecycleOwner) {
        if (!session.joined) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                Discovery.listen(context).collect { list ->
                    discovered = if (myIp == null) list else list.filter { !it.addr.startsWith("$myIp:") }
                }
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

    // Sonner 轻提示：toast 请求 → 顶部弹出（独立窗口层，不被弹窗遮罩挡住）
    val toaster = com.dokar.sonner.rememberToasterState()
    LaunchedEffect(toastMsg) {
        toastMsg?.let { req ->
            toaster.show(
                message = req.msg,
                type = when (req.kind) {
                    1 -> com.dokar.sonner.ToastType.Success
                    2 -> com.dokar.sonner.ToastType.Error
                    else -> com.dokar.sonner.ToastType.Normal
                },
            )
            AppState.clearToast()
        }
    }

    // ── 导航（会话驱动）：入群 → 群页面；离群/被踢 → 回首页重新挂载（重播入场动画）──
    val navController = rememberNavController()
    LaunchedEffect(session.joined) {
        if (session.joined) {
            if (navController.currentDestination?.route != Routes.GROUP) {
                navController.navigate(Routes.GROUP) { launchSingleTop = true }
            }
        } else if (navController.currentDestination?.route == Routes.GROUP) {
            navController.popBackStack(Routes.HOME, inclusive = false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                // 全屏后避开刘海/挖孔
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout),
        ) {
            Header(
                onTheme, onLang, lang, themeId,
                onScan = { navController.navigate(Routes.SCANNER) }, showScan = !session.joined,
                showShare = session.joined, onShare = { showQr = true },
            )
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.weight(1f),
                enterTransition = { Motion.pageEnter() },
                exitTransition = { Motion.pageExit() },
                popEnterTransition = { Motion.pagePopEnter() },
                popExitTransition = { Motion.pagePopExit() },
            ) {
                composable(Routes.HOME) {
                    HomeBody(
                        t = t, wide = wide, discovered = discovered,
                        addr = addr, pin = pin, joining = joining,
                        onAddr = { addr = it }, onPin = { pin = it },
                        onPickGroup = { pinDialogFor = it },
                        onCreate = {
                            // 功能全量依赖局域网：无局域网 IPv4 时提示并阻止建群
                            if (Discovery.localIpAddress() == null) {
                                AppState.toast(t("no_lan"), 2)
                                return@HomeBody
                            }
                            val port = AppState.reservePort()
                            val pin2 = java.security.SecureRandom().nextInt(900_000) + 100_000
                            AppState.becomeOwner(pin2.toString(), port)
                            AppState.toast(t("group_created").replace("{pin}", pin2.toString()), 1)
                        },
                        onJoin = {
                            if (addr.isBlank() || pin.isBlank()) {
                                AppState.toast(t("fill_addr_pin"), 2)
                            } else {
                                startJoin(addr, pin)
                            }
                        },
                    )
                }
                composable(Routes.GROUP) {
                    GroupBody(t, session.members, history, sendText,
                        role = session.role,
                        groupAddr = session.addr, groupPin = session.pin,
                        onKick = { pendingKick = it },
                        onCopy = { text ->
                            // 点击历史复制：先同步指纹闸门，避免被监听器当作本机复制再发回群
                            clipGuard[0] = textHash(text)
                            ClipboardCapability.setText(context, text)
                            AppState.toast(t("copied"), 1)
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
                                    AppState.toast(t("sent"), 1)
                                    sendText = ""
                                }
                            }
                        },
                        onLeave = { confirmLeave = true })
                }
                // 扫码：全屏目的地，底部滑入/滑出
                composable(
                    Routes.SCANNER,
                    enterTransition = {
                        slideInVertically(tween(Motion.SLOW, easing = Motion.Easing)) { it } +
                            fadeIn(tween(Motion.NORMAL, easing = Motion.Easing))
                    },
                    popExitTransition = {
                        slideOutVertically(tween(Motion.NORMAL, easing = Motion.Easing)) { it } +
                            fadeOut(tween(Motion.FAST))
                    },
                ) {
                    ScannerScreen(
                        t = t,
                        onResult = { addrValue, pinValue ->
                            navController.popBackStack()
                            if (addrValue.isNotBlank()) {
                                startJoin(addrValue, pinValue)
                            }
                        },
                        onClose = { navController.popBackStack() },
                    )
                }
            }
        }

        // Sonner 顶部轻提示（独立于内容层的弹出队列）：显式 TopCenter + 顶部留出刘海安全距离
        com.dokar.sonner.Toaster(
            state = toaster,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout)
                .padding(top = 10.dp),
        )

        // ── 弹窗（平台 Dialog：原生窗口层 + 系统遮罩，动画只作用于卡片内容）──

        // 退出群二次确认
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

        // 群信息二维码弹窗
        if (showQr && session.joined) {
            QrDialog(
                t = t,
                addr = session.addr,
                pin = session.pin,
                onClose = { showQr = false },
            )
        }

        // 加入群 Loading（不可关闭）
        if (joining) {
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                ),
            ) {
                DialogCard {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            Modifier.padding(horizontal = 32.dp, vertical = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp),
                                strokeWidth = 3.dp,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                t("joining"), fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    } // Crossfade
}

/** 群信息二维码弹窗：上二维码 + 下群地址/PIN（对齐桌面端分享弹层）。 */
@Composable
private fun QrDialog(t: (String) -> String, addr: String, pin: String, onClose: () -> Unit) {
    val payload = org.json.JSONObject().apply {
        put("v", 1); put("app", "knotyup"); put("addr", addr); put("pin", pin)
    }.toString()
    val qrBitmap = remember(payload) { generateQrBitmap(payload, 512) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogCard(Modifier.padding(horizontal = 40.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
                onClick = onClose,
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    qrBitmap?.let {
                        Image(
                            it.asImageBitmap(),
                            null,
                            Modifier.size(216.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    QrInfoRow(t("group_addr"), addr)
                    Spacer(Modifier.height(6.dp))
                    QrInfoRow(t("group_pin"), pin)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        t("qr_hint"), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** ZXing 生成二维码 Bitmap（含白底）。 */
private fun generateQrBitmap(text: String, size: Int): android.graphics.Bitmap? {
    return runCatching {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
        )
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) 0xFF1D2129.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        bmp
    }.getOrNull()
}

/** 扫码加群：CameraX 预览 + ZXing 解析二维码（knotyup 入群载荷）。 */
@Composable
private fun ScannerScreen(
    t: (String) -> String,
    onResult: (addr: String, pin: String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    androidx.compose.runtime.DisposableEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(android.Manifest.permission.CAMERA)
        onDispose { }
    }
    // 返回键由导航处理（popBackStack = 关闭扫码）

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    val previewView = androidx.camera.view.PreviewView(ctx)
                    val providerFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = androidx.camera.core.Preview.Builder().build().apply {
                            setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = androidx.camera.core.ImageAnalysis.Builder()
                            .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(ctx.mainExecutor) { image ->
                            decodeQr(image)?.let { payload ->
                                val (addr, pin) = parseJoinPayload(payload)
                                if (addr != null) {
                                    analysis.clearAnalyzer()
                                    onResult(addr, pin ?: "")
                                }
                            }
                            image.close()
                        }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }
                    }, ctx.mainExecutor)
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 取景框 + 提示
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(240.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(18.dp)),
            )
            Spacer(Modifier.height(20.dp))
            Text(t("scan_hint"), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
        }

        // 关闭按钮
        Surface(
            onClick = onClose,
            shape = RoundedCornerShape(9.dp),
            color = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("✕", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

/** YUV → ZXing 解码；命中返回二维码文本。 */
private fun decodeQr(image: androidx.camera.core.ImageProxy): String? {
    return runCatching {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val source = com.google.zxing.PlanarYUVLuminanceSource(
            bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
        )
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        com.google.zxing.MultiFormatReader().apply {
            setHints(mapOf(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
        }.decodeWithState(bitmap).text
    }.getOrNull()
}

/** knotyup 入群载荷解析：{"v":1,"app":"knotyup","addr":"ip:port","pin":"..."} */
private fun parseJoinPayload(text: String): Pair<String?, String?> {
    return runCatching {
        val o = org.json.JSONObject(text)
        if (o.optString("app") == "knotyup") {
            o.getString("addr") to o.optString("pin")
        } else null to null
    }.getOrDefault(null to null)
}

/**
 * 启动页（纯白底，三幕动画）：
 * 1) 黑色绳结逐笔「打出」
 * 2) 打结完成后 KnotYup 文字浮现（黑色）
 * 3) 绳结与文字一起染上品牌渐变色，随后关闭启动动画
 */
@Composable
private fun SplashScreen(onFinish: () -> Unit) {
    val draw = remember { androidx.compose.animation.core.Animatable(0f) }        // 打结进度
    val word = "KnotYup"
    // 第二幕：逐字蹦跳（每个字独立的透明度 + 上浮弹簧）
    val charAlphas = remember { word.map { androidx.compose.animation.core.Animatable(0f) } }
    val charOffsets = remember { word.map { androidx.compose.animation.core.Animatable(26f) } }
    val colorPhase = remember { androidx.compose.animation.core.Animatable(0f) }   // 渐变上色

    LaunchedEffect(Unit) {
        // 第一幕：打结
        draw.animateTo(
            1f,
            androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.CubicBezierEasing(.4f, 0f, .2f, 1f)),
        )
        // 第二幕：文字逐字蹦跳（低阻尼弹簧回弹，交错 55ms）
        kotlinx.coroutines.coroutineScope {
            word.forEachIndexed { i, _ ->
                launch {
                    kotlinx.coroutines.delay(i * 55L)
                    launch { charAlphas[i].animateTo(1f, androidx.compose.animation.core.tween(200)) }
                    charOffsets[i].animateTo(
                        0f,
                        androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 420f),
                    )
                }
            }
        }
        kotlinx.coroutines.delay(250)
        // 第三幕：渐变上色
        colorPhase.animateTo(1f, androidx.compose.animation.core.tween(650))
        kotlinx.coroutines.delay(400)
        onFinish()
    }

    val accent = MaterialTheme.colorScheme.primary
    val accent2 = androidx.compose.ui.graphics.lerp(accent, Color(0xFFA855F7), 0.45f)
    val gradient = androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent, accent2))
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 绳结：4 条笔画独立 Path，按总长预算顺序截取；黑色描线 + 渐变叠层淡入
            val strokes = remember {
                listOf(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(9f, 15f); lineTo(9f, 3f); cubicTo(9f, 3f, 4f, 5f, 4f, 10f)
                    },
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(15f, 9f); lineTo(15f, 21f); cubicTo(15f, 21f, 20f, 19f, 20f, 14f)
                    },
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(6f, 18f); cubicTo(6f, 18f, 9f, 20f, 12f, 18f)
                    },
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(18f, 6f); cubicTo(18f, 6f, 15f, 4f, 12f, 6f)
                    },
                )
            }
            val measure = remember { androidx.compose.ui.graphics.PathMeasure() }
            val segment = remember { androidx.compose.ui.graphics.Path() }
            androidx.compose.foundation.Canvas(Modifier.size(150.dp)) {
                segment.reset()
                val lens = strokes.map { p ->
                    measure.setPath(p, false)
                    measure.length
                }
                val total = lens.sum()
                var budget = total * draw.value
                strokes.forEachIndexed { i, p ->
                    if (budget <= 0f) return@forEachIndexed
                    val take = kotlin.math.min(budget, lens[i])
                    if (take > 0f) {
                        measure.setPath(p, false)
                        measure.getSegment(0f, take, segment, true)
                        budget -= take
                    }
                }

                val scale = size.minDimension / 24f * 0.9f
                val dx = (size.width - 24f * scale) / 2f
                val dy = (size.height - 24f * scale) / 2f
                withTransform({
                    translate(dx, dy)
                    scale(scale, scale, androidx.compose.ui.geometry.Offset.Zero)
                }) {
                    val style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.7f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    // 黑色基底
                    drawPath(segment, color = Color.Black, style = style)
                    // 渐变叠层（第三幕淡入）
                    if (colorPhase.value > 0f) {
                        drawPath(
                            segment, brush = gradient, alpha = colorPhase.value, style = style,
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            // 文字：逐字蹦跳（黑色基底 + 渐变叠层）
            Box {
                Row {
                    word.forEachIndexed { i, ch ->
                        Text(
                            text = ch.toString(),
                            color = Color.Black,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.graphicsLayer {
                                alpha = charAlphas[i].value
                                translationY = charOffsets[i].value
                            },
                        )
                    }
                }
                if (colorPhase.value > 0f) {
                    Row(
                        Modifier.graphicsLayer { alpha = colorPhase.value },
                    ) {
                        word.forEach { ch ->
                            Text(
                                text = ch.toString(),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    brush = gradient,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}


/** 弹窗卡片入场动画：官方 AnimatedVisibility（scaleIn + fadeIn），阴影原生合成。 */
@Composable
private fun DialogCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val visibleState = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = Motion.dialogEnter(),
    ) {
        content()
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
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogCard(Modifier.padding(horizontal = 40.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
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
        AppState.toast(t("group_lost"), 2)
        return
    }
    if (candidates[0].id == AppState.device.id) {
        // 当选：用预留端口承接为群主
        val port = AppState.standby?.localPort ?: AppState.reservePort()
        AppState.becomeOwner(s.pin, port)
        AppState.toast(t("promoted"), 1)
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
                                    AppState.toast(t("reconnected"), 1)
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
            AppState.toast(t("group_lost"), 2)
        }
    }
}

@Composable
private fun Header(
    onTheme: (String) -> Unit,
    onLang: (String) -> Unit,
    lang: String,
    themeId: String,
    onScan: () -> Unit,
    showScan: Boolean,
    showShare: Boolean,
    onShare: () -> Unit,
) {
    var themeOpen by remember { mutableStateOf(false) }
    var langOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
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

        // 扫码加群（仅首页显示）
        if (showScan) {
            HeaderIconBtn(onClick = onScan) {
                Image(painterResource(R.drawable.ic_camera), "scan", Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
        }

        // 群信息二维码（仅群页面显示）
        if (showShare) {
            HeaderIconBtn(onClick = onShare) {
                Image(painterResource(R.drawable.ic_qr), "qr", Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
        }

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
                shadowElevation = 0.dp,
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
                shadowElevation = 0.dp,
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

/** 挂载入场动画：官方 AnimatedVisibility（fade + slide），阴影由框架原生合成、无闪烁。 */
@Composable
fun KnotEnter(
    delayMillis: Int = 0,
    slideDistance: Float = 0.22f, // 相对自身高度的位移比例
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val visibleState = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = Motion.mountEnter(delayMillis, slideDistance),
    ) {
        content()
    }
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 36.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // 顶部对齐：容器高度变化只把下方元素往下挤，上方元素位置稳定不跳
        verticalArrangement = Arrangement.Top,
    ) {
        // Hero：主标题（第二段渐变）+ 副标题
        val accent = MaterialTheme.colorScheme.primary
        val accent2 = androidx.compose.ui.graphics.lerp(accent, Color(0xFFA855F7), 0.45f)
        KnotEnter(delayMillis = 0) {
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
            )
        }
        Spacer(Modifier.height(6.dp))
        KnotEnter(delayMillis = 70) {
            Text(
                t("tagline_sub"), fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))

        if (wide) {
            KnotEnter(delayMillis = 140, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    JoinCard(t, addr, pin, joining, Modifier.weight(1f), onAddr, onPin, onJoin, onCreate)
                    DiscoveredCard(t, discovered, Modifier.weight(1f), onPickGroup)
                }
            }
        } else {
            KnotEnter(delayMillis = 140, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                JoinCard(t, addr, pin, joining, Modifier.fillMaxWidth(), onAddr, onPin, onJoin, onCreate)
            }
            Spacer(Modifier.height(16.dp))
            KnotEnter(delayMillis = 210, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                DiscoveredCard(t, discovered, Modifier.fillMaxWidth(), onPickGroup)
            }
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
        shadowElevation = 0.dp,
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
        shadowElevation = 0.dp,
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
                        // 空态图标不着色（灰色半透明，与桌面端一致）
                        Image(
                            painterResource(R.drawable.ic_devices), null,
                            Modifier.size(28.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            ),
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogCard(Modifier.padding(horizontal = 40.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(t("join_group"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    // 群信息：两行裸文本（上行群名，下行 IP:端口），无容器包裹
                    Text(
                        group.name,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        group.addr,
                        fontSize = 12.sp,
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
                            onClick = {
                                if (pinValue.isBlank()) {
                                    AppState.toast(t("fill_pin"), 2)
                                } else {
                                    onJoin(pinValue.trim())
                                }
                            },
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
    val pagerState = androidx.compose.foundation.pager.rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        // 内容区：全屏延伸，左右滑动切换 Tab；页间转场（缩放 + 淡入淡出视差）
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageOffset = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).absoluteValue.coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 离场页明显缩小 + 变淡，进场页恢复 —— 层次感转场
                        val scale = 1f - 0.12f * pageOffset
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.75f * pageOffset
                    }
            ) {
                when (page) {
                    0 -> DevicesTab(t, members, role, onKick, onLeave)
                    else -> ClipboardTab(t, members, history, sendText, onCopy, onSendText, onSend)
                }
            }
        }
        // 悬浮 Tab 栏：点击切换；入场从底部升起（AnimatedVisibility 原生合成阴影）
        KnotEnter(
            delayMillis = 220,
            slideDistance = 0.5f,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            GroupTabBar(
                t = t,
                page = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                onSelect = { i -> scope.launch { pagerState.animateScrollToPage(i) } },
            )
        }
    }
}

/** 悬浮分段 Tab 栏：容器 + tab item + 激活指示块（点击切换，块随页面偏移联动）。 */
@Composable
private fun GroupTabBar(
    t: (String) -> String,
    page: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        t("devices") to R.drawable.ic_devices,
        t("clipboard") to R.drawable.ic_clip,
    )
    val pos = page

    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val containerW = 248.dp
        val gap = 8.dp // item 间隔 = 容器内边距
        val segW = (containerW - 8.dp - gap) / 2

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier.width(containerW),
        ) {
            Box(Modifier.padding(4.dp).height(44.dp)) {
                // 激活块（可拖动元素）
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(segW)
                        .graphicsLayer { translationX = pos * (segW + gap).toPx() }
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
                // tab items（item 之间以容器内边距等宽的间隙隔开）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEachIndexed { i, (label, iconRes) ->
                        val active = pos > i - 0.5f && pos < i + 0.5f
                        val fg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painterResource(iconRes), null, Modifier.size(15.dp),
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(fg),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    label, fontSize = 12.5.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = fg, maxLines = 1, softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 底部 Tab 按钮：胶囊样式，选中态主题色。 */
@Composable
private fun GroupTabBtn(label: String, iconRes: Int, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = modifier.height(46.dp),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(iconRes), null, Modifier.size(16.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(fg),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label, fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = fg, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Tab 1：设备列表 + 退出群。 */
@Composable
private fun DevicesTab(
    t: (String) -> String,
    members: List<DeviceInfo>,
    role: String,
    onKick: (String) -> Unit,
    onLeave: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 96.dp),
    ) {
        Text(t("devices"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        members.forEachIndexed { i, m ->
            val self = m.id == AppState.device.id
            // 平台头像：macOS / Windows / Android 各自的图标与配色
            val (iconRes, bg) = when (m.platform) {
                "macos" -> R.drawable.ic_os_mac to Color(0xFFECECF1)
                "windows" -> R.drawable.ic_os_win to Color(0xFFE7F0FB)
                "android" -> R.drawable.ic_os_android to Color(0xFFE6F4EA)
                else -> R.drawable.ic_devices to MaterialTheme.colorScheme.surfaceVariant
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).background(bg, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(iconRes), null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        )
                        if (self) {
                            Spacer(Modifier.width(6.dp))
                            Image(
                                painter = painterResource(R.drawable.ic_devices),
                                contentDescription = "self",
                                modifier = Modifier.size(12.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary),
                            )
                        }
                        if (i == 0) {
                            Spacer(Modifier.width(6.dp))
                            Image(painterResource(R.drawable.ic_crown), "owner", Modifier.size(13.dp))
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
        OutlinedButton(
            onClick = onLeave,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) { Text(t(if (role == "owner") "dissolve" else "leave"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
    }
}

/** Tab 2：剪贴板（历史 + 发送）。 */
@Composable
private fun ClipboardTab(
    t: (String) -> String,
    members: List<DeviceInfo>,
    history: List<HistoryItem>,
    sendText: String,
    onCopy: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 96.dp),
    ) {
        Text(t("clipboard"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(t("click_hint"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (history.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(
                    "${t("empty_1")}\n${t("empty_2")}", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            history.forEach { item ->
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
                Text(t("send"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
