package com.knotyup.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.knotyup.android.data.AppState
import com.knotyup.android.data.TouchpadSettings
import com.knotyup.android.touchpad.Haptics
import com.knotyup.android.touchpad.ParticleSystem
import com.knotyup.android.touchpad.TouchTrail
import com.knotyup.android.touchpad.TouchpadClient
import com.knotyup.android.ui.KnotYupTheme
import com.knotyup.android.ui.components.ParticleCanvas
import com.knotyup.android.ui.components.TouchTrailCanvas
import com.knotyup.android.ui.components.particlePalette
import com.knotyup.android.ui.Strings
import com.knotyup.android.ui.components.KnotConfirm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * 触摸板控制页：独立 Activity（自己一个 window，全屏横屏）。
 * 与群页面完全隔离——触摸事件不会共享给群页的切 tab 手势，返回键也只作用于本页。
 * 进入时由 TouchpadTab 设置 AppState.touchpadControllingId 后 startActivity；
 * 退出（返回键确认）时清状态并 finish()。
 */
class TouchpadControlActivity : ComponentActivity() {

    /** 旋转事件计数（每次显示旋转 +1）；Compose 层监听它弹"方向已更改"提示。 */
    private val rotationEvents = MutableStateFlow(0)

    /**
     * sensorLandscape 下 90↔270 切换时 Configuration.orientation 始终是 landscape，
     * onConfigurationChanged 不可靠。DisplayManager 的 onDisplayChanged 在显示旋转实际变化时触发，
     * 与 Activity 重建 / configChanges 无关——这是检测两个横屏角度切换的正确机制。
     */
    private var lastRotation = -1
    private val displayManager: DisplayManager by lazy {
        getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            @Suppress("DEPRECATION")
            val rot = displayManager.getDisplay(displayId)?.rotation ?: return
            if (lastRotation != -1 && rot != lastRotation) {
                rotationEvents.value++
            }
            lastRotation = rot
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 真全屏：内容延伸到状态栏/导航栏之后，并隐藏 systemBars（游戏式沉浸）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 记录初始旋转角度并注册监听（onDisplayChanged 在 90↔270 切换时可靠触发）
        @Suppress("DEPRECATION")
        lastRotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: -1
        displayManager.registerDisplayListener(displayListener, null)

        // 组合期间即读触摸板设置（粒子/OLED/自然滚动），setContent 前同步初始化持久化。
        TouchpadSettings.init(applicationContext)
        Haptics.init(applicationContext)
        val lang = AppState.loadLang(this) ?: "zh-Hans"
        val themeId = AppState.loadTheme(this)
        val t = { key: String -> Strings.t(lang, key) }

        setContent {
            KnotYupTheme(themeId) {
                ControlScreen(
                    t = t,
                    rotationEvents = rotationEvents,
                    onExit = {
                        AppState.setTouchpadControlling(null)
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    /** 从后台回到前台时系统可能重新显示 systemBars，这里再隐藏一次。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 交给 Compose BackHandler 弹确认；这里不直接 finish
        onBackPressedDispatcher.onBackPressed()
    }
}

@Composable
private fun ControlScreen(
    t: (String) -> String,
    rotationEvents: MutableStateFlow<Int>,
    onExit: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val oled = TouchpadSettings.oled
    val naturalScroll = TouchpadSettings.naturalScroll
    // 粒子配置（设置即时生效：remember 读取时取值，设置页在另一个页面，进控制页时读最新）
    val particlesOn = TouchpadSettings.particles
    val particlePalette = particlePalette(TouchpadSettings.particleColor)
    val particleRandomColor = TouchpadSettings.particleColor == "random"
    val particleSizeDp = TouchpadSettings.particleSize
    val particleCount = TouchpadSettings.particleCount
    val particleSpeed = TouchpadSettings.particleSpeed
    val particleTrail = TouchpadSettings.particleTrail
    val showTouchTrail = TouchpadSettings.touchTrail
    val trailPalette = particlePalette(TouchpadSettings.trailColor)
    val trailRandomColor = TouchpadSettings.trailColor == "random"
    val trailWidth = TouchpadSettings.trailWidth
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // 屏幕像素尺寸（横屏全屏）：涟漪据此计算铺满全屏的扩散距离
    val dm = ctx.resources.displayMetrics
    val screenW = dm.widthPixels.toFloat()
    val screenH = dm.heightPixels.toFloat()
    val particleSystem = remember { ParticleSystem() }
    val touchTrail = remember { TouchTrail() }
    var confirmExit by remember { mutableStateOf(false) }
    val client = remember { TouchpadClient() }
    // 粒子发射回调：只做 system.emit，纯视觉、不阻塞采集
    val emitParticle: (Float, Float) -> Unit = { x, y ->
        if (particlesOn) particleSystem.emit(
            x, y, particleSizeDp * density, particleCount,
            particlePalette, particleRandomColor, particleSpeed, screenW, screenH,
        )
    }
    // 轨迹采集回调：每帧把触点坐标喂给轨迹引擎（单指/双指按 pressedCount 着色）
    val feedTrail: (List<Triple<Long, Float, Float>>) -> Unit = { changes ->
        if (showTouchTrail) touchTrail.feed(changes, trailPalette, trailRandomColor)
    }
    // 单指长按计时器：到点震动+按下（进入拖动）
    val singleHold = remember {
        HoldTracker(Handler(Looper.getMainLooper()), LONG_PRESS_MS) {
            client.down("left")
            Haptics.tick()
        }
    }
    // 双指长按计时器：到点震动+触发调度中心开关
    val doubleHold = remember {
        HoldTracker(Handler(Looper.getMainLooper()), DOUBLE_HOLD_MS) {
            Haptics.tick()
            client.shortcut("mission_control")
        }
    }
    DisposableEffect(Unit) {
        onDispose { singleHold.cancel(); doubleHold.cancel() }
    }

    // 建立触控板专用 TCP：从成员表取目标设备的 touchpad_addr，PIN 作握手令牌
    val controllingId by AppState.touchpadControllingId.collectAsState()
    val session by AppState.session.collectAsState()
    // 群连接断开时直接退出控制页：本页是独立 window，看不到 MainActivity 的"已退出群"提示
    val connLost by AppState.connLost.collectAsState()
    LaunchedEffect(connLost) {
        if (connLost) {
            client.disconnect()
            onExit()
        }
    }
    LaunchedEffect(Unit) {
        val id = controllingId
        val dev = id?.let { did -> session.members.firstOrNull { it.id == did } }
        val addr = dev?.touchpadAddr
        val pin = session.pin
        if (addr.isNullOrBlank() || pin.isBlank()) {
            Toast.makeText(ctx, t("touchpad_connect_failed"), Toast.LENGTH_SHORT).show()
        } else {
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.connect(addr, pin, 0, 0, AppState.device.id)
            }
            if (!ok) Toast.makeText(ctx, t("touchpad_connect_failed"), Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(Unit) { onDispose { client.disconnect() } }

    // 进入提示
    LaunchedEffect(Unit) { Toast.makeText(ctx, t("touchpad_started"), Toast.LENGTH_SHORT).show() }

    // 旋转提示：监听 Activity 的配置变化事件（跳过初始值 0），TCP 连接不受影响
    val rotationCount by rotationEvents.collectAsState()
    LaunchedEffect(rotationCount) {
        if (rotationCount > 0) {
            Toast.makeText(ctx, t("touchpad_orientation_changed"), Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(enabled = !confirmExit) { confirmExit = true }
    if (confirmExit) {
        KnotConfirm(
            title = t("touchpad_exit_control"),
            message = t("touchpad_exit_control_msg"),
            okText = t("touchpad_exit_control"),
            cancelText = t("confirm_cancel"),
            danger = true,
            onOk = { client.disconnect(); onExit() },
            onCancel = { confirmExit = false },
        )
    }

    // 全屏触摸采集：由 handleGesture 识别单指/双指手势。
    // 独立 Activity 的 window 与群页隔离，触摸不会触发切 tab。
    Box(
        Modifier
            .fillMaxSize()
            .background(if (oled) Color(0xFF000000) else Color(0xFF212121))
            .pointerInput(Unit) {
                awaitEachGesture {
                    handleGesture(client, naturalScroll, singleHold, doubleHold, emitParticle, feedTrail)
                }
            }
    ) {
        // 粒子渲染层：独立协程 60fps，只读取 system 状态；与上方采集完全解耦
        ParticleCanvas(particleSystem, enabled = particlesOn, trail = particleTrail)
        // 触摸轨迹渲染层：单指紫 / 双指青绿，与粒子层独立叠加
        TouchTrailCanvas(
            trail = touchTrail,
            enabled = showTouchTrail,
            width = trailWidth,
        )
    }
}

/**
 * 单个手势的完整识别状态机（运行在 AwaitPointerEventScope 内）。
 *
 * 手势分类：
 * - 单指轻点 → 左键 click
 * - 单指滑动 → move（光标）；单指按住满 800ms（位移不超阈值）→ 立即震动+进入拖动
 * - 双指轻点 → 右键 click（不震动）；双指滑动 → scroll（按 naturalScroll 决定方向）
 * - 双指长按（按住 800ms 不移动）→ 调度中心开关 shortcut（震动）
 *
 * 长按用独立 Handler 定时器（HoldTracker，不取消 awaitPointerEvent）：
 * - 单指按住满 800ms 且未移动 → 震动+按下进入拖动
 * - 双指按住满 800ms 且未移动 → 震动+触发调度中心
 * 双指轻点(<800ms) → 右键；双指滑动(超阈值) → 滚动，都会取消长按计时。
 * 绝不能用 withTimeout 包 awaitPointerEvent——超时会取消事件等待、破坏 AwaitPointerEventScope
 * 的事件流，导致手势事件丢失。
 * 移动阈值 MOVE_SLOP=12px：累计位移低于它的轻微抖动不识别为移动，会取消长按计时。
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleGesture(
    client: TouchpadClient,
    naturalScroll: Boolean,
    singleHold: HoldTracker,
    doubleHold: HoldTracker,
    emitParticle: (Float, Float) -> Unit,
    feedTrail: (List<Triple<Long, Float, Float>>) -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    down.consume()
    emitParticle(down.position.x, down.position.y) // 按下瞬间发一簇
    // 每个新手势先清掉上一段手势残留的 fired——否则上次双指长按后 fired 恒为 true，
    // 后续手势第一帧就被判成 "double"（该模式不发送任何事件），表现为触摸板失效。
    singleHold.reset()
    doubleHold.reset()
    singleHold.schedule() // 单指按下即开始长按计时
    var maxPointers = 1
    var totalDx = 0f
    var totalDy = 0f
    // 手势模式："" 未定 / move / scroll / drag / double
    var mode = ""
    var dragging = false
    var doubleFired = false // 双指长按是否已触发（避免同一手势重复触发）
    var lastX = down.position.x // 最后触点坐标（抬起时在此处触发涟漪）
    var lastY = down.position.y

    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        var dx = 0f
        var dy = 0f
        val pressedCount = event.changes.count { it.pressed }
        if (event.changes.size > maxPointers) maxPointers = event.changes.size
        // 指针数：恰好 2 指才启动双指长按计时；单指/3指以上都取消
        // （双指轻点/滑动时靠位移阈值或松手来取消计时，见下方 mode 判定）
        when (pressedCount) {
            2 -> { singleHold.cancel(); if (!doubleFired && !doubleHold.fired) doubleHold.schedule() }
            else -> { doubleHold.cancel() } // 单指靠 singleHold；3指以上不触发任何长按
        }
        val touchPoints = ArrayList<Triple<Long, Float, Float>>()
        for (c in event.changes) {
            if (c.pressed) {
                dx += c.position.x - c.previousPosition.x
                dy += c.position.y - c.previousPosition.y
                lastX = c.position.x
                lastY = c.position.y
                touchPoints.add(Triple(c.id.value, c.position.x, c.position.y))
                c.consume()
            }
        }
        // 轨迹：单指/双指按当前按下指数着色
        feedTrail(touchPoints)
        totalDx += dx
        totalDy += dy
        val movedEnough = kotlin.math.abs(totalDx) >= MOVE_SLOP ||
            kotlin.math.abs(totalDy) >= MOVE_SLOP
        val step = kotlin.math.abs(dx) > 0.5f || kotlin.math.abs(dy) > 0.5f

        if (mode.isEmpty()) {
            when {
                singleHold.fired -> { mode = "drag"; dragging = true; doubleHold.cancel() }
                doubleHold.fired -> { mode = "double"; doubleFired = true; singleHold.cancel() }
                maxPointers >= 2 && movedEnough -> { mode = "scroll"; singleHold.cancel(); doubleHold.cancel() }
                maxPointers == 1 && movedEnough -> { mode = "move"; singleHold.cancel() }
            }
        }
        // 双指滑动（超阈值）取消长按计时，不当作长按
        if (mode == "scroll" && movedEnough) doubleHold.cancel()

        when (mode) {
            "move", "drag" -> if (step) client.move(dx.toInt(), dy.toInt())
            "scroll" -> if (step) {
                val scrollY = if (naturalScroll) dy else -dy
                client.scroll(dx.toInt(), scrollY.toInt())
            }
        }
        if (event.changes.all { !it.pressed }) {
            emitParticle(lastX, lastY) // 抬起瞬间在最后触点位置触发一次涟漪
            break
        }
    }

    singleHold.cancel()
    doubleHold.cancel()

    // 手势结束：按最终模式结算（注：长按动作已在计时器到点时执行）
    when {
        dragging -> client.up("left") // 拖动结束，抬起
        mode == "double" -> Unit // 调度中心已由双指长按触发，此处不重复
        mode.isEmpty() -> {
            if (maxPointers >= 2) {
                client.click("right") // 双指轻点 → 右键（不震动）
            } else {
                client.click("left")
            }
        }
    }
}

private const val LONG_PRESS_MS = 800L  // 单指长按进入拖动的时长
private const val DOUBLE_HOLD_MS = 800L  // 双指长按触发调度中心的时长
private const val MOVE_SLOP = 12f       // 累计位移阈值(px)：低于此的抖动不识别为移动

/** 长按计时器：独立 Handler 定时，到点置 fired 并执行回调；不干扰 Compose 的事件接收。 */
private class HoldTracker(
    private val handler: Handler,
    private val delayMs: Long,
    private val onFire: () -> Unit,
) {
    @Volatile var fired = false
        private set
    private val runnable = Runnable {
        fired = true
        onFire()
    }
    fun schedule() {
        handler.removeCallbacks(runnable) // 先清旧的，避免同一手势多次 schedule 累积
        fired = false
        handler.postDelayed(runnable, delayMs)
    }
    fun cancel() {
        handler.removeCallbacks(runnable)
    }
    /** 新手势开始时清掉上一段手势残留的 fired，避免污染本次判定。 */
    fun reset() {
        handler.removeCallbacks(runnable)
        fired = false
    }
}
