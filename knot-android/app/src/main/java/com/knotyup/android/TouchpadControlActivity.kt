package com.knotyup.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
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
import com.knotyup.android.touchpad.TouchpadClient
import com.knotyup.android.ui.KnotYupTheme
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
    LaunchedEffect(Unit) { TouchpadSettings.init(ctx) }
    val oled = TouchpadSettings.oled
    var confirmExit by remember { mutableStateOf(false) }
    val client = remember { TouchpadClient() }

    // 建立触控板专用 TCP：从成员表取目标设备的 touchpad_addr，PIN 作握手令牌
    val controllingId by AppState.touchpadControllingId.collectAsState()
    val session by AppState.session.collectAsState()
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

    // 全屏触摸采集：单指滑动 → move；双指滑动 → scroll；轻点 → click。
    // 独立 Activity 的 window 与群页隔离，触摸不会触发切 tab。
    Box(
        Modifier
            .fillMaxSize()
            .background(if (oled) Color(0xFF000000) else Color(0xFF212121))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var totalDx = 0f
                    var totalDy = 0f
                    var everMulti = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        var dx = 0f
                        var dy = 0f
                        for (c in event.changes) {
                            if (c.pressed) {
                                dx += c.position.x - c.previousPosition.x
                                dy += c.position.y - c.previousPosition.y
                                c.consume()
                            }
                        }
                        if (event.changes.size > 1) everMulti = true
                        totalDx += dx
                        totalDy += dy
                        if (everMulti) {
                            if (dx != 0f || dy != 0f) client.scroll(dx.toInt(), dy.toInt())
                        } else {
                            if (dx != 0f || dy != 0f) client.move(dx.toInt(), dy.toInt())
                        }
                        if (event.changes.all { !it.pressed }) break
                    }
                    if (kotlin.math.abs(totalDx) < 8f && kotlin.math.abs(totalDy) < 8f && !everMulti) {
                        client.click("left")
                    }
                }
            }
    )
}
