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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import com.knotyup.android.data.AppState
import com.knotyup.android.data.TouchpadSettings
import com.knotyup.android.touchpad.Haptics
import com.knotyup.android.touchpad.ParticleSystem
import com.knotyup.android.touchpad.FireworkSystem
import com.knotyup.android.touchpad.TouchTrail
import com.knotyup.android.touchpad.TouchpadClient
import com.knotyup.android.ui.KnotYupTheme
import com.knotyup.android.ui.components.ParticleCanvas
import com.knotyup.android.ui.components.FireworkCanvas
import com.knotyup.android.ui.components.TouchTrailCanvas
import com.knotyup.android.ui.components.particlePalette
import com.knotyup.android.ui.Strings
import com.knotyup.android.ui.components.KnotConfirm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val particleStyle = TouchpadSettings.particleStyle
    val rippleOn = particleStyle == "ripple"
    val fireworkOn = particleStyle == "firework"
    val particlePalette = particlePalette(TouchpadSettings.particleColor)
    val particleRandomColor = TouchpadSettings.particleColor == "random"
    val particleTrail = TouchpadSettings.particleTrail
    val particleSizeDp = TouchpadSettings.particleSize
    val particleCount = TouchpadSettings.particleCount
    val particleSpeed = TouchpadSettings.particleSpeed
    val showTouchTrail = TouchpadSettings.touchTrail
    val trailPalette = particlePalette(TouchpadSettings.trailColor)
    val trailRandomColor = TouchpadSettings.trailColor == "random"
    val trailWidth = TouchpadSettings.trailWidth
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // 屏幕像素尺寸：用 Compose 实际布局尺寸(zoneW/zoneH，由 onSizeChanged 写入)，
    // 不用 displayMetrics/Configuration——横屏下它们的宽高与真实渲染区域不一致(安全区/刘海)。
    val particleSystem = remember { ParticleSystem() }
    val fireworkSystem = remember { FireworkSystem() }
    val touchTrail = remember { TouchTrail() }
    var confirmExit by remember { mutableStateOf(false) }
    // 数字键盘：默认关闭（全屏触摸区）；三指轻点开关，三指左右滑交换位置
    var keypadOpen by remember { mutableStateOf(false) }
    var keypadOnLeft by remember { mutableStateOf(false) }
    var zoneW by remember { mutableFloatStateOf(0f) } // 触摸区像素尺寸（区域判定用）
    var zoneH by remember { mutableFloatStateOf(0f) }
    var netMove by remember { mutableFloatStateOf(0f) } // 当前手势净位移（长按到点校验拖拽用）
    val client = remember { TouchpadClient() }
    // 粒子发射回调：按风格分发，纯视觉、不阻塞采集
    val emitParticle: (Float, Float) -> Unit = { x, y ->
        if (rippleOn) particleSystem.emit(
            x, y, particleSizeDp * density, particleCount,
            particlePalette, particleRandomColor, particleSpeed, zoneW, zoneH,
        )
        if (fireworkOn) {
            fireworkSystem.launchBottomY = zoneH
            fireworkSystem.launch(x, y, particleSpeed, particlePalette, particleRandomColor)
        }
    }
    // 轨迹采集回调：每帧把触点坐标喂给轨迹引擎（单指/双指按 pressedCount 着色）
    val feedTrail: (List<Triple<Long, Float, Float>>) -> Unit = { changes ->
        if (showTouchTrail) touchTrail.feed(changes, trailPalette, trailRandomColor, trailWidth)
    }
    // 长按计时器（普通区拖拽 / 右下角调度，统一 800ms=HOLD_MS）：
    // 到点先震动提示"已进入拖拽选中"，再按下左键；持续滑动中(净位移大)不触发。
    val singleHold = remember {
        HoldTracker(
            Handler(Looper.getMainLooper()), HOLD_MS,
            onFire = {
                Haptics.tick() // 先震动：提示已进入拖拽选中模式
                client.down("left")
            },
            canFire = { netMove < HOLD_JITTER_SLOP },
        )
    }
    // 右下角单指长按 → 调度中心
    val missionHold = remember {
        HoldTracker(
            Handler(Looper.getMainLooper()), HOLD_MS,
            onFire = { Haptics.tick(); client.shortcut("mission_control") },
            canFire = { netMove < HOLD_JITTER_SLOP },
        )
    }
    // 右下角双击 → 开/关数字键盘（双击判定在 CornerActionPad 内部，这里只做开关动作）
    val onCornerTap: () -> Unit = {
        keypadOpen = !keypadOpen
        client.up("left")
    }
    // 单指轻点双击检测：第一次延迟 250ms 发单击，窗口内第二次(位置差<30px)改发系统双击
    val tapTracker = remember {
        DoubleTapTracker(
            Handler(Looper.getMainLooper()),
            onClick = { client.click("left") },
            onDoubleClick = { client.doubleClick() },
        )
    }
    DisposableEffect(Unit) {
        onDispose { singleHold.cancel(); missionHold.cancel(); tapTracker.cancel() }
    }

    // 建立触控板专用 UDP：从成员表取目标设备的 touchpad_addr，PIN 作握手令牌
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

    // 旋转提示：监听 Activity 的配置变化事件（跳过初始值 0），UDP 连接不受影响
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

    // ── 悬浮叠加布局：触摸板铺满全屏，键盘/右下角方块作为独立悬浮层盖在其上 ──
    // 三层各自 pointerInput 并 consume 自身事件 → 互不干扰；透过半透明键盘可见触摸板特效。
    Box(Modifier.fillMaxSize()
        .onSizeChanged { size -> zoneW = size.width.toFloat(); zoneH = size.height.toFloat() }
        .background(if (oled) Color(0xFF000000) else Color(0xFF212121))) {
        // ① 触摸板层：铺满全屏，只处理纯触摸板手势（requireUnconsumed=true 不吃上层悬浮区的事件）
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        handleTouchpad(
                            client, naturalScroll, singleHold, emitParticle, feedTrail,
                            onNetMove = { netMove = it },
                            onSingleTap = { x, y -> tapTracker.tap(x, y) },
                        )
                    }
                }
        ) {
            ParticleCanvas(particleSystem, enabled = rippleOn, trail = particleTrail)
            FireworkCanvas(fireworkSystem, enabled = fireworkOn)
            TouchTrailCanvas(trail = touchTrail, enabled = showTouchTrail, width = trailWidth)
        }

        // ② 右下角悬浮方块（300×150）：独立处理长按调度 + 双击开关键盘；位置随键盘悬浮侧实时变化
        CornerActionPad(
            client = client,
            missionHold = missionHold,
            onDoubleTap = onCornerTap,
            onNetMove = { netMove = it },
            // 键盘在右时方块左移 1/3 屏宽（贴在触摸板可见区右下角）；否则贴屏幕右下
            offsetXPx = if (keypadOpen && !keypadOnLeft) -zoneW / 3f else 0f,
            modifier = Modifier.align(Alignment.BottomEnd),
        )

        // ③ 数字键盘悬浮层（宽 1/3）：单一位置动画统一控制滑入/滑出与左右切换，无淡化。
        // zoneW>0 守卫：布局尺寸就绪前不组合键盘，避免首帧 closedX=0 导致键盘闪现。
        if (zoneW > 0f) {
            // 键盘在右→打开滑到右侧到位、关闭向右滑出屏幕；键盘在左→打开滑到左侧、关闭向左滑出
            val kbW = zoneW / 3f
            val openX = if (keypadOnLeft) 0f else zoneW - kbW
            val closedX = if (keypadOnLeft) -kbW else zoneW
            val targetX = if (keypadOpen) openX else closedX
            val animX by animateFloatAsState(
                targetValue = targetX,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.82f, stiffness = 240f,
                ),
                label = "kbSlide",
            )
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(1f / 3f)
                    .offset { IntOffset(animX.toInt(), 0) }
            ) {
                NumericKeypad(
                    client, oled,
                    swipeThresholdPx = kbW * 2f / 3f, // 至少滑动两格键盘宽度才触发横滑
                    onSwipe = { keypadOnLeft = !keypadOnLeft },
                )
            }
        }
    }
}

/** 右下角悬浮方块：长按→调度中心，双击→开关键盘；自身消费事件，不影响触摸板层。 */
@Composable
private fun CornerActionPad(
    client: TouchpadClient,
    missionHold: HoldTracker,
    onDoubleTap: () -> Unit,
    onNetMove: (Float) -> Unit,
    offsetXPx: Float,
    modifier: Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val wDp = (CORNER_ZONE_W / density).dp
    val hDp = (CORNER_ZONE_H / density).dp
    var lastTapMs by remember { mutableLongStateOf(0L) }
    Box(
        modifier
            .size(wDp, hDp)
            .offset { IntOffset(offsetXPx.toInt(), 0) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    missionHold.reset()
                    missionHold.schedule()
                    var net = 0f // 净位移（离按下点直线距离），抖动不累积
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        for (c in event.changes) {
                            if (c.pressed) {
                                net = kotlin.math.hypot(
                                    c.position.x - down.position.x,
                                    c.position.y - down.position.y,
                                )
                                c.consume()
                            }
                        }
                        onNetMove(net)
                        if (net >= MOVE_SLOP) missionHold.cancel() // 明显移动 → 取消长按
                        if (event.changes.all { !it.pressed }) break
                    }
                    missionHold.cancel()
                    // 长按已在计时器到点执行调度；此处只处理轻点双击（容差放宽到 DOUBLE_TAP_SLOP）
                    if (!missionHold.fired && net < DOUBLE_TAP_SLOP) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapMs <= DOUBLE_TAP_MS) {
                            onDoubleTap(); lastTapMs = 0L
                        } else {
                            lastTapMs = now
                        }
                    }
                }
            }
    )
}
/**
 * 数字键盘面板：从上至下 789/456/123/0.(0占两格)。
 * - 颜色跟随设置：random=炫彩渐变RGB跑马灯(按键逐个流转)，固定色=色板色；
 * - 点击溅射 8~12 个跟随键色的微小粒子（键盘区内渲染）；
 * - 数字宏：长按数字键触发预定义动作（框架，动作回调留空）；
 * - 面板内横滑 → 交换键盘左右位置；背景透明透出触摸板特效。
 */
@Composable
private fun NumericKeypad(
    client: TouchpadClient,
    oled: Boolean,
    swipeThresholdPx: Float,
    onSwipe: () -> Unit,
) {
    val kbColor = remember { TouchpadSettings.kbColor }
    val particlesOn = remember { TouchpadSettings.kbClickParticles }
    val macroOn = remember { TouchpadSettings.kbMacroEnabled }
    val breathing = remember { TouchpadSettings.kbBreathing }
    val palette = particlePalette(kbColor)
    val rainbow = kbColor == "random"

    // RGB 跑马灯相位：0→1 循环，每个键按索引偏移取色相
    val inf = rememberInfiniteTransition(label = "kbRainbow")
    val phase by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    // 呼吸系数：0.45~1.0 周期明暗；关闭呼吸则常亮 1.0
    val breath = if (breathing) 0.45f + 0.55f * (0.5f + 0.5f * kotlin.math.sin(phase * 6.28318f)) else 1f
    fun keyColor(index: Int): Color {
        val base = if (!rainbow) Color(palette[0]) else {
            val frac = phase + index * 0.08f
            val hue = (frac - kotlin.math.floor(frac)) * 360f
            Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f)))
        }
        // 呼吸只改亮度(RGB 乘系数)，保持色相
        return Color(base.red * breath, base.green * breath, base.blue * breath, base.alpha)
    }

    val rows = listOf(
        listOf("7" to 1, "8" to 1, "9" to 1),
        listOf("4" to 1, "5" to 1, "6" to 1),
        listOf("1" to 1, "2" to 1, "3" to 1),
        listOf("0" to 1, "." to 1, "⌫" to 1),
    )
    Box(
        Modifier.fillMaxHeight()
            .background(Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var totalDx = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        for (c in event.changes) {
                            if (c.pressed) {
                                totalDx += c.position.x - c.previousPosition.x
                                c.consume()
                            }
                        }
                        if (event.changes.all { !it.pressed }) break
                    }
                    if (kotlin.math.abs(totalDx) >= swipeThresholdPx) onSwipe()
                }
            }
    ) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 8.dp, vertical = 12.dp)) {
            rows.forEachIndexed { rowIdx, row ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    row.forEachIndexed { colIdx, (key, span) ->
                        val color = keyColor(rowIdx * 3 + colIdx)
                        // 每个键独立粒子列表（坐标=按键内，天然对应点击位置），范围限制在本格子内
                        val keySparks = remember(key) { androidx.compose.runtime.mutableStateListOf<KbSpark>() }
                        // 帧驱动重绘：粒子字段是可变对象(非State)，改了不触发重组；tick 每帧推进强制 Canvas 重绘
                        var sparkTick by remember { mutableFloatStateOf(0f) }
                        var keyPressed by remember { mutableStateOf(false) }
                        LaunchedEffect(key) {
                            var last = withFrameNanos { it }
                            while (true) {
                                withFrameNanos { now ->
                                    // 跟随设备刷新率：120Hz 设备 120fps、60Hz 设备 60fps
                                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                                    last = now
                                    val it = keySparks.iterator()
                                    while (it.hasNext()) {
                                        val sp = it.next()
                                        sp.x += sp.vx * dt; sp.y += sp.vy * dt
                                        sp.vx *= 0.985f; sp.vy *= 0.985f
                                        sp.hue?.let { sp.hue = (it + sp.hueSpeed * dt) % 360f }
                                        sp.age += dt
                                        if (sp.age >= sp.life) it.remove()
                                    }
                                    if (keySparks.isNotEmpty()) sparkTick += 1f
                                }
                            }
                        }
                        val keyDensity = androidx.compose.ui.platform.LocalDensity.current.density
                        Box(
                            Modifier
                                .weight(span.toFloat())
                                .fillMaxHeight()
                                // 井字形：只画内部网格线(非首行上边/非首列左边)，不画四周外框
                                .drawInnerBorders(color, top = rowIdx > 0, left = colIdx > 0)
                                .pointerInput(key) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        var moved = false
                                        var longFired = false
                                        var repeatJob: kotlinx.coroutines.Job? = null
                                        // 长按：退格键=连续高频删除；其它键=数字宏框架。独立协程不阻塞事件接收
                                        val holdJob = kotlinx.coroutines.GlobalScope.launch {
                                            delay(KB_MACRO_HOLD_MS)
                                            if (!moved) {
                                                longFired = true
                                                if (key == "⌫") {
                                                    // 退格长按：每 60ms 发一次 backspace，直到抬手取消
                                                    repeatJob = launch {
                                                        while (true) {
                                                            client.key("backspace")
                                                            Haptics.tick() // 每次连续退格都震动一下
                                                            delay(KB_REPEAT_INTERVAL_MS)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Main)
                                            for (c in ev.changes) {
                                                if (c.pressed) {
                                                    if (kotlin.math.abs(c.position.x - c.previousPosition.x) > MOVE_SLOP ||
                                                        kotlin.math.abs(c.position.y - c.previousPosition.y) > MOVE_SLOP) moved = true
                                                    c.consume()
                                                }
                                            }
                                            if (ev.changes.all { !it.pressed }) break
                                        }
                                        holdJob.cancel()
                                        repeatJob?.cancel()
                                        if (longFired && macroOn && key != "⌫") {
                                            Haptics.tick() // 数字宏动作框架：具体功能待接入
                                        } else if (!moved && !longFired) {
                                            client.key(if (key == "⌫") "backspace" else key)
                                            Haptics.tick()
                                            keyPressed = true
                                            kotlinx.coroutines.GlobalScope.launch {
                                                delay(110); keyPressed = false
                                            }
                                            if (particlesOn) {
                                                // 直接用按键内坐标，溅射范围限制在一个格子内（低速+短命）
                                                val cx = down.position.x
                                                val cy = down.position.y
                                                repeat(12 + kotlin.random.Random.nextInt(6)) {
                                                    val angle = kotlin.random.Random.nextFloat() * 6.28318f
                                                    // 初速调大→溅射距离远；配合弱阻尼(0.985)缓慢飘移
                                                    val speed = 180f + kotlin.random.Random.nextFloat() * 140f
                                                    // random 模式：每粒子随机色相起点+轮转速度→位移中跑马灯渐变
                                                    // 固定色模式：从鲜亮色板取色，不受呼吸系数影响
                                                    val sparkHue = if (rainbow) kotlin.random.Random.nextFloat() * 360f else null
                                                    val sparkSpeed = if (rainbow) 120f + kotlin.random.Random.nextFloat() * 180f else 0f
                                                    val sparkColor = if (rainbow) Color.Transparent else Color(palette[kotlin.random.Random.nextInt(palette.size)])
                                                    keySparks.add(KbSpark(
                                                        x = cx, y = cy,
                                                        vx = kotlin.math.cos(angle) * speed,
                                                        vy = kotlin.math.sin(angle) * speed,
                                                        color = sparkColor,
                                                        hue = sparkHue,
                                                        hueSpeed = sparkSpeed,
                                                    ))
                                                }
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val textScale by animateFloatAsState(
                                targetValue = if (keyPressed) 1.18f else 1f,
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = 0.4f, stiffness = 600f,
                                ),
                                label = "keyText",
                            )
                            Text(
                                key, color = color, fontSize = 28.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.scale(textScale),
                            )
                            if (particlesOn) {
                                Canvas(Modifier.matchParentSize()) {
                                    @Suppress("UNUSED_EXPRESSION") sparkTick // 读快照：每帧触发重绘
                                    keySparks.forEach { sp ->
                                        val alpha = ((1f - sp.age / sp.life) * 0.65f).coerceIn(0f, 1f)
                                        val drawColor = sp.hue?.let { android.graphics.Color.HSVToColor(FloatArray(4) { i ->
                                            when (i) { 0 -> it; 1 -> 0.85f; 2 -> 1f; else -> alpha }
                                        }) }?.let { Color(it) } ?: sp.color.copy(alpha = alpha)
                                        drawCircle(
                                            color = drawColor,
                                            radius = 0.6f * keyDensity, // 微小粒子
                                            center = Offset(sp.x, sp.y),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 键盘点击溅射粒子。 */
private class KbSpark(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    val color: Color,
    var hue: Float? = null,      // 非 null=跑马灯渐变（random 模式），null=固定色
    val hueSpeed: Float = 0f,    // 色相轮转速度（度/秒）
    var age: Float = 0f,
    val life: Float = 1.1f,
)

/** 井字形边框：只画内部网格线（非首行的上边、非首列的左边），四周无外框。 */
private fun Modifier.drawInnerBorders(color: Color, top: Boolean, left: Boolean): Modifier = this.drawBehind {
    val w = 0.5.dp.toPx()
    if (top) drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), w)
    if (left) drawLine(color, Offset(0f, 0f), Offset(0f, size.height), w)
}
/**
 * 单个手势的完整识别状态机（运行在 AwaitPointerEventScope 内）。
 *
 * 手势分类：
 * - 单指轻点 → 左键 click
 * - 单指滑动 → move（光标）；单指按住满 800ms（位移不超阈值）→ 立即震动+进入拖动
 * - 双指轻点 → 右键 click（不震动）；双指滑动 → scroll（按 naturalScroll 决定方向）
 * - 双指长按（按住 800ms 不移动）→ 调度中心开关 shortcut（震动）
 * - 三指轻点 → 开/关数字键盘；三指左右滑 → 交换键盘左右位置
 *
 * 长按用独立 Handler 定时器（HoldTracker，不取消 awaitPointerEvent）：
 * - 单指按住满 800ms 且未移动 → 震动+按下进入拖动
 * - 双指按住满 800ms 且未移动 → 震动+触发调度中心
 * 双指轻点(<800ms) → 右键；双指滑动(超阈值) → 滚动，都会取消长按计时。
 * 绝不能用 withTimeout 包 awaitPointerEvent——超时会取消事件等待、破坏 AwaitPointerEventScope
 * 的事件流，导致手势事件丢失。
 * 移动阈值 MOVE_SLOP=12px：累计位移低于它的轻微抖动不识别为移动，会取消长按计时。
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleTouchpad(
    client: TouchpadClient,
    naturalScroll: Boolean,
    singleHold: HoldTracker,
    emitParticle: (Float, Float) -> Unit,
    feedTrail: (List<Triple<Long, Float, Float>>) -> Unit,
    onNetMove: (Float) -> Unit,
    onSingleTap: (Float, Float) -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = true)
    down.consume()
    emitParticle(down.position.x, down.position.y)
    singleHold.reset()
    singleHold.schedule()

    var maxPointers = 1
    var totalDx = 0f
    var totalDy = 0f
    var mode = ""
    var dragging = false
    var lastX = down.position.x
    var lastY = down.position.y

    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val pressedCount = event.changes.count { it.pressed }
        if (event.changes.size > maxPointers) maxPointers = event.changes.size
        var dx = 0f
        var dy = 0f
        val touchPoints = ArrayList<Triple<Long, Float, Float>>()
        var mainX = down.position.x
        var mainY = down.position.y
        for (c in event.changes) {
            if (c.pressed) {
                dx += c.position.x - c.previousPosition.x
                dy += c.position.y - c.previousPosition.y
                lastX = c.position.x
                lastY = c.position.y
                if (c.id == down.id) { mainX = c.position.x; mainY = c.position.y }
                touchPoints.add(Triple(c.id.value, c.position.x, c.position.y))
                c.consume()
            }
        }
        feedTrail(touchPoints)
        totalDx += dx
        totalDy += dy
        // 净位移用「当前触点离按下点的直线距离」，长按期间的小幅来回抖动不会累积放大
        onNetMove(kotlin.math.hypot(mainX - down.position.x, mainY - down.position.y))
        val movedEnough = kotlin.math.abs(totalDx) >= MOVE_SLOP ||
            kotlin.math.abs(totalDy) >= MOVE_SLOP
        val step = kotlin.math.abs(dx) > 0.5f || kotlin.math.abs(dy) > 0.5f

        // 多指出现：取消单指长按
        if (pressedCount >= 2) singleHold.cancel()

        if (mode.isEmpty()) {
            when {
                singleHold.fired -> { mode = "drag"; dragging = true }
                maxPointers >= 2 && movedEnough -> { mode = "scroll" }
                maxPointers == 1 && movedEnough -> { mode = "move" }
            }
        }

        when (mode) {
            "move", "drag" -> if (step) client.move(dx.toInt(), dy.toInt())
            "scroll" -> if (step) {
                val scrollY = if (naturalScroll) dy else -dy
                client.scroll(dx.toInt(), scrollY.toInt())
            }
        }
        if (event.changes.all { !it.pressed }) {
            emitParticle(lastX, lastY)
            break
        }
    }

    singleHold.cancel()
    when {
        dragging -> client.up("left")
        mode.isEmpty() && maxPointers == 2 -> client.click("right")
        mode.isEmpty() && maxPointers == 1 -> onSingleTap(lastX, lastY)
    }
}


private const val HOLD_MS = 800L           // 统一长按时长：普通区拖拽 / 右下角调度 共用
private const val MOVE_SLOP = 12f          // 累计位移阈值(px)：低于此的抖动不识别为移动
private const val CORNER_ZONE_W = 300f     // 右下角区域宽(px)
private const val CORNER_ZONE_H = 150f     // 右下角区域高(px)
private const val DOUBLE_TAP_MS = 450L     // 右下角双击间隔(ms)：无单击延迟代价，放宽
private const val TAP_CLICK_DELAY_MS = 250L // 触摸板单击延迟(ms)：等双击窗口，兼顾单击手感
private const val DOUBLE_TAP_SLOP = 80f    // 统一双击容差(px)：两处共用，手机触摸偏差大放宽
private const val KB_MACRO_HOLD_MS = 500L    // 按键长按时长(ms)：退格连续删除 / 数字宏
private const val KB_REPEAT_INTERVAL_MS = 60L // 退格长按连续触发间隔(ms)
private const val HOLD_JITTER_SLOP = 30f   // 统一长按抖动阈值：净位移超过此值(滑动中)则不触发长按

/** 长按计时器：独立 Handler 定时，到点置 fired 并执行回调；不干扰 Compose 的事件接收。 */
private class HoldTracker(
    private val handler: Handler,
    private val delayMs: Long,
    private val onFire: () -> Unit,
    private val canFire: () -> Boolean = { true }, // 到点时校验（如手指持续移动则不触发拖拽）
) {
    @Volatile var fired = false
        private set
    private val runnable = Runnable {
        if (canFire()) {
            fired = true
            onFire()
        }
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

/**
 * 双击检测器：单指轻点先延迟 [TAP_CLICK_DELAY_MS] 发单击；窗口内第二次轻点
 * （位置差 < [DOUBLE_TAP_SLOP]）则取消单击、改发系统双击。
 */
private class DoubleTapTracker(
    private val handler: Handler,
    private val onClick: () -> Unit,
    private val onDoubleClick: () -> Unit,
) {
    private var lastX = 0f
    private var lastY = 0f
    private var pending = false
    private val runnable = Runnable {
        pending = false
        onClick()
    }

    fun tap(x: Float, y: Float) {
        if (pending && kotlin.math.hypot(x - lastX, y - lastY) < DOUBLE_TAP_SLOP) {
            handler.removeCallbacks(runnable)
            pending = false
            onDoubleClick()
        } else {
            handler.removeCallbacks(runnable)
            lastX = x; lastY = y
            pending = true
            handler.postDelayed(runnable, TAP_CLICK_DELAY_MS)
        }
    }

    fun cancel() {
        handler.removeCallbacks(runnable)
        pending = false
    }
}
