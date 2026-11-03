package com.knotyup.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.knotyup.android.clipboard.ClipboardCapability
import com.knotyup.android.data.AppState
import com.knotyup.android.net.Discovery
import com.knotyup.android.net.GroupAd
import com.knotyup.android.net.textHash
import com.knotyup.android.ui.KnotYupTheme
import com.knotyup.android.ui.Motion
import com.knotyup.android.ui.Strings
import com.knotyup.android.ui.components.Header
import com.knotyup.android.ui.screens.AppDialogs
import com.knotyup.android.ui.screens.GroupBody
import com.knotyup.android.ui.screens.HomeBody
import com.knotyup.android.ui.screens.ScannerScreen
import com.knotyup.android.ui.screens.SplashScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 组合根：Activity 壳 + App 级组合。
 * 消息面（ws 会话/心跳/事件/剪贴板监听/发现扫描）全部在进程级 AppState，
 * 本文件只负责 UI 组装、导航、弹窗与副作用编排（见 ui/screens、ui/components）。
 */

/** 导航路由。 */
private object Routes {
    const val HOME = "home"
    const val GROUP = "group"
    const val SCANNER = "scanner"
}

@OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : ComponentActivity() {
    // 焦点变化时重新隐藏状态栏：Android 10 及国产 OEM（MIUI/EMUI/Funtouch/ColorOS）
    // 会在弹窗/键盘/切后台回来后擅自把状态栏显示回来，必须每次补 hide
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) com.knotyup.android.ui.FullScreen.keepFullScreen(window)
    }

    override fun onStart() {
        super.onStart()
        AppState.setInForeground(true)
    }

    override fun onStop() {
        super.onStop()
        AppState.setInForeground(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏（沉浸式）：onCreate 尽早配置，避免启动瞬间状态栏闪现
        com.knotyup.android.ui.FullScreen.applyFullScreen(window)
        // 群地址解析需要系统 ConnectivityManager 兜底（见 Discovery.localIpAddress(context)）
        AppState.appContext = applicationContext
        // 进程级运行时（事件处理/成员会话/剪贴板监听）：幂等，Activity 重建不影响消息链路
        AppState.startRuntime()
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
    val joining by AppState.joining.collectAsState()
    val clipMode = AppState.clipMode.collectAsState().value
    val discovered = AppState.discovered.collectAsState().value
    var addr by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var sendText by remember { mutableStateOf(sharedText ?: "") }
    var confirmLeave by remember { mutableStateOf(false) }
    var pendingKick by remember { mutableStateOf<String?>(null) }
    var pinDialogFor by remember { mutableStateOf<GroupAd?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var showShizukuGuide by remember { mutableStateOf(false) }
    // rememberSaveable：横竖屏旋转（Activity 重建）不重播开屏动画
    var showSplash by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }

    // 启动页 → 主界面（Crossfade 优雅过渡）
    Crossfade(targetState = showSplash, animationSpec = tween(500)) { splash ->
        if (splash) {
            SplashScreen { showSplash = false }
            return@Crossfade
        }

    // 手动/弹窗/扫码共用的入群流程：点击瞬间 Loading → 收起键盘 → 0.5s 后启动进程级成员会话
    // （连接/心跳/事件处理全在 AppState，横竖屏切换不中断；入群 5s 超时亦在会话内处理）
    val appView = LocalView.current
    fun startJoin(addrValue: String, pinValue: String) {
        AppState.startRuntime()
        AppState.setJoining(true) // Loading 立即出现（不等 0.5s）
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(appView.windowToken, 0)
        scope.launch {
            delay(500)
            AppState.startMemberSession(addrValue, pinValue)
        }
    }

    // 群组前台服务：入群期间保进程存活（后台剪贴板轮询 + ws 连接），退出/被踢停止
    val notifPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(session.joined) {
        if (session.joined) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            com.knotyup.android.service.KnotForegroundService.start(context, t("sync_notif"))
        } else {
            com.knotyup.android.service.KnotForegroundService.stop(context)
        }
    }

    // Sonner 轻提示：toast 请求 → 顶部弹出（key 型请求按当前语言解析 i18n 文案并替换 {name} 等）
    val toaster = com.dokar.sonner.rememberToasterState()
    LaunchedEffect(toastMsg) {
        toastMsg?.let { req ->
            val raw = req.key?.let { t(it) } ?: req.msg ?: return@let
            val text = req.subs.entries.fold(raw) { acc, (k, v) -> acc.replace("{$k}", v) }
            toaster.show(
                message = text,
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
                .windowInsetsPadding(WindowInsets.displayCutout),
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
                        role = session.role, clipMode = clipMode,
                        onModeClick = { showShizukuGuide = true },
                        groupAddr = session.addr, groupPin = session.pin,
                        onKick = { pendingKick = it },
                        onCopy = { text ->
                            // 点击历史复制：先同步指纹闸门，避免被监听器当作本机复制再发回群
                            AppState.clipGuard = textHash(text)
                            scope.launch { ClipboardCapability.setText(context, text) }
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

        // 返回键拦截：群页面 back = 弹退出群确认（成员：退出；群主：转让/解散）。
        // 必须组合在 NavHost 之后——后注册的回调优先生效，否则导航栈 pop 回首页
        androidx.activity.compose.BackHandler(enabled = session.joined) {
            confirmLeave = true
        }

        // Sonner 顶部轻提示（独立于内容层的弹出队列）：显式 TopCenter + 顶部留出刘海安全距离
        com.dokar.sonner.Toaster(
            state = toaster,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(top = 10.dp),
        )

        // ── App 级弹窗集合（平台 Dialog：原生窗口层 + 系统遮罩）──
        AppDialogs(
            t = t, session = session, joining = joining,
            confirmLeave = confirmLeave, pendingKick = pendingKick,
            pinDialogFor = pinDialogFor, showQr = showQr, showShizukuGuide = showShizukuGuide,
            onDismissConfirmLeave = { confirmLeave = false },
            onConfirmLeaveOk = { selected ->
                confirmLeave = false
                val ownerLeave = AppState.isOwner()
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
            onPendingKick = { pendingKick = it },
            onPinDialogFor = { pinDialogFor = it },
            onShowQr = { showQr = it },
            onShowShizukuGuide = { showShizukuGuide = it },
            startJoin = { a, p -> startJoin(a, p) },
        )
    }
    } // Crossfade
}
