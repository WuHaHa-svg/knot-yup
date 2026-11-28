package com.knotyup.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.knotyup.android.data.AppState
import com.knotyup.android.ui.KnotYupTheme
import com.knotyup.android.ui.Strings
import kotlinx.coroutines.delay

/**
 * 触摸板首次使用引导页：全屏横屏，动画演示手势 → 逐步教学 → 完成后进控制页。
 * 由 GroupTabs 连接确认时首次进入；完成写 [AppState.saveTouchpadGuideDone]。
 */
class TouchpadGuideActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
        val lang = AppState.loadLang(this) ?: "zh-Hans"
        val themeId = AppState.loadTheme(this)
        val t = { key: String -> Strings.t(lang, key) }

        setContent {
            KnotYupTheme(themeId) {
                GuideScreen(
                    t = t,
                    onFinish = {
                        AppState.saveTouchpadGuideDone(this)
                        AppState.setTouchpadControlling(deviceId.ifBlank { null })
                        startActivity(Intent(this, TouchpadControlActivity::class.java))
                        finish()
                    },
                    onSkip = {
                        AppState.saveTouchpadGuideDone(this)
                        AppState.setTouchpadControlling(deviceId.ifBlank { null })
                        startActivity(Intent(this, TouchpadControlActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }
}

private data class GuideStep(val titleKey: String, val descKey: String)

/** 引导页切换：平移 + 淡入淡出（前进从右滑入，后退从左滑入）。 */
private fun androidx.compose.animation.AnimatedContentTransitionScope<Int>.guideTransition() =
    if (targetState > initialState) {
        (slideInHorizontally(animationSpec = tween(340)) { full -> full } +
            fadeIn(animationSpec = tween(340))) togetherWith
            (slideOutHorizontally(animationSpec = tween(340)) { full -> -full } +
                fadeOut(animationSpec = tween(340)))
    } else {
        (slideInHorizontally(animationSpec = tween(340)) { full -> -full } +
            fadeIn(animationSpec = tween(340))) togetherWith
            (slideOutHorizontally(animationSpec = tween(340)) { full -> full } +
                fadeOut(animationSpec = tween(340)))
    }


@Composable
private fun GuideScreen(t: (String) -> String, onFinish: () -> Unit, onSkip: () -> Unit) {
    val steps = listOf(
        GuideStep("tpg_1_title", "tpg_1_desc"),
        GuideStep("tpg_2_title", "tpg_2_desc"),
        GuideStep("tpg_3_title", "tpg_3_desc"),
        GuideStep("tpg_4_title", "tpg_4_desc"),
        GuideStep("tpg_5_title", "tpg_5_desc"),
        GuideStep("tpg_6_title", "tpg_6_desc"),
        GuideStep("tpg_7_title", "tpg_7_desc"),
    )
    var step by remember { mutableIntStateOf(0) }
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 14.dp)
    ) {
        // ① 操作标题（淡入淡出切换；首标题 welcome/sub 已删除）
        AnimatedContent(
            targetState = step,
            transitionSpec = { guideTransition() },
            label = "guideTitle",
        ) { page ->
            Column {
                Text(t(steps[page].titleKey), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    t(steps[page].descKey), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ② 预览内容（占大头）
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = { guideTransition() },
                label = "guideStep",
            ) { page ->
                when (page) {
                    0 -> DemoSingleFinger(t, primary, onSurface)
                    1 -> DemoLongPress(t, primary, onSurface)
                    2 -> DemoTwoFinger(t, primary, onSurface)
                    3 -> DemoCorner(t, primary, onSurface)
                    4 -> DemoKeypadToggle(t, primary, onSurface)
                    5 -> DemoKeypadSwipe(t, primary, onSurface)
                    6 -> DemoKeypad(t, primary, onSurface)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ③ swiper 进度 + 底部按钮 同一行
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            // 圆点进度（左侧）
            Row(verticalAlignment = Alignment.CenterVertically) {
                steps.indices.forEach { i ->
                    val selected by animateFloatAsState(if (i == step) 1f else 0.4f)
                    Box(
                        Modifier.padding(horizontal = 3.dp)
                            .size(if (i == step) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(primary.copy(alpha = selected)),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { step-- },
                enabled = step > 0,
                shape = RoundedCornerShape(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(t("tpg_prev"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            TextButton(
                onClick = {
                    if (step == steps.lastIndex) onFinish() else step++
                },
                shape = RoundedCornerShape(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    if (step == steps.lastIndex) t("tpg_start") else "${t("tpg_next")} ›",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
/** ── 互联演示舞台：左=手机(手势) → 中=无线信号 → 右=桌面(效果) ── */

/** 演示舞台外框：手机屏 + 流动信号 + 桌面屏，突出互联关系 */
@Composable
private fun GuideStage(
    t: (String) -> String,
    primary: Color,
    onSurface: Color,
    phone: @Composable () -> Unit,
    desktop: @Composable () -> Unit,
    onlyPhone: Boolean = false,
    phoneWidth: androidx.compose.ui.unit.Dp = 240.dp,
    phoneHeight: androidx.compose.ui.unit.Dp = 126.dp,
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // ① 手机：手写横屏 PhoneMock（照搬组件样式，内容不旋转）
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LandscapePhoneMock(phone, width = phoneWidth, height = phoneHeight)
                Spacer(Modifier.height(2.dp))
                Text(t("tpg_dev_phone"), fontSize = 12.sp, color = onSurface.copy(alpha = 0.7f))
            }

            if (!onlyPhone) {
            // ② 中间互联信号：流动光点 + 双向箭头
            val inf = rememberInfiniteTransition(label = "link")
            val flow by inf.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Restart),
                label = "flow",
            )
            Column(
                Modifier.width(72.dp).padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { i ->
                        val p = (flow + i * 0.33f) % 1f
                        Box(
                            Modifier.size(7.dp).clip(CircleShape)
                                .background(primary.copy(alpha = 0.25f + 0.75f * (1f - kotlin.math.abs(p - 0.5f) * 2f))),
                        )
                        if (i < 2) Spacer(Modifier.width(4.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("⇄", fontSize = 26.sp, color = primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(t("tpg_link"), fontSize = 10.sp, color = primary.copy(alpha = 0.8f))
            }

            }

            if (!onlyPhone) {
            // ③ 桌面
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(200.dp, 145.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1A1A1C))   // 黑框
                        .padding(4.dp),
                ) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).background(Color.White), contentAlignment = Alignment.Center) { desktop() }
                }
                Spacer(Modifier.height(2.dp))
                // 显示器底座（深色）
                Box(Modifier.width(32.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF1A1A1C))) {}
                Box(Modifier.width(56.dp).height(2.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF1A1A1C))) {}
                Spacer(Modifier.height(1.dp))
                Text(t("tpg_dev_desktop"), fontSize = 12.sp, color = onSurface.copy(alpha = 0.7f))
            }
            }
        }
    }
}

/**
 * 横屏版 PhoneMock：照搬 App 使用引导 PhoneMock 的机身/白屏/状态栏/侧键样式，
 * 直接以横屏绘制（不旋转组件，避免内部内容跟着旋转错乱）。宽高比 ≈ 1.95:1。
 */
@Composable
private fun LandscapePhoneMock(
    screen: @Composable () -> Unit,
    width: androidx.compose.ui.unit.Dp = 240.dp,
    height: androidx.compose.ui.unit.Dp = 126.dp,
) {
    val bodyColor = Color(0xFF1A1A1C)
    val statusColor = Color(0xFF888780)
    val pw = width
    val ph = height
    Box(contentAlignment = Alignment.Center) {
        // 机身（深色圆角）+ 3dp 边框 + 白屏
        Box(
            Modifier.size(pw, ph)
                .clip(RoundedCornerShape(10.dp))
                .background(bodyColor)
                .padding(3.dp),
        ) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp)).background(Color.White)) {
                // 状态栏：9:41 + 信号点（横屏仍水平显示在顶部）
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 0.dp).offset(y = (-4).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("9:41", fontSize = 7.sp, color = statusColor)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(4.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(3.dp))
                    Box(Modifier.size(4.dp).clip(CircleShape).background(statusColor))
                }
                // 屏幕内容区（状态栏下方）
                Box(Modifier.fillMaxSize().padding(top = 11.dp), contentAlignment = Alignment.Center) { screen() }
            }
        }
        // 电源键：上边中间靠左
        Box(
            Modifier.offset(x = (-pw * 0.08f), y = (-ph / 2f) - 3.dp)
                .width(20.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(bodyColor),
        ) {}
        // 音量键：下边中间靠左（两段）
        Row(Modifier.offset(x = (-pw * 0.14f), y = (ph / 2f) + 3.dp)) {
            Box(Modifier.width(15.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(bodyColor)) {}
            Spacer(Modifier.width(7.dp))
            Box(Modifier.width(15.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(bodyColor)) {}
        }
    }
}

/** 数字键盘示意（占容器右侧 1/3 宽）：4 行 3 列，可高亮 "2"。 */
@Composable
private fun KeypadGraphic(primary: Color, highlight: Float = 0f, highlightKey: String = "") {
    Column(Modifier.fillMaxHeight().fillMaxWidth(1f).clip(RoundedCornerShape(4.dp))
        .border(0.5.dp, primary.copy(alpha = 0.5f), RoundedCornerShape(4.dp))) {
        val rows = listOf(
            listOf("7", "8", "9"), listOf("4", "5", "6"),
            listOf("1", "2", "3"), listOf("0", ".", "⌫"),
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                row.forEach { key ->
                    Box(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, primary.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                        Text(key, color = if (key == highlightKey) primary.copy(alpha = 0.3f + 0.7f * highlight) else primary,
                            fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 手指圆点（手机屏内） */
@Composable
private fun Finger(primary: Color, offsetX: Float, offsetY: Float, alpha: Float = 1f, scale: Float = 1f) {
    Box(
        Modifier.offset { androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .size(18.dp).scale(scale).alpha(alpha)
            .clip(CircleShape)
            .background(primary.copy(alpha = 0.35f))
            .border(2.dp, primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(primary))
    }
}

/** 标准鼠标指针（左上箭头）：Canvas 绘制，小巧 */
@Composable
private fun MouseCursor(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(13.dp)) {
        val p = Path().apply {
            moveTo(0f, 0f)      // 尖端左上
            lineTo(0f, 15f)     // 左边缘
            lineTo(4f, 10.5f)   // 内凹
            lineTo(7f, 16f)
            lineTo(9.5f, 14.5f)
            lineTo(6.5f, 9.5f)
            lineTo(12f, 8.5f)   // 右边缘
            close()
        }
        drawPath(p, color)
    }
}

/** ① 单指移动：手机手指滑动 → 桌面标准鼠标指针跟随 */
@Composable
private fun DemoSingleFinger(t: (String) -> String, primary: Color, onSurface: Color) {
    val inf = rememberInfiniteTransition(label = "single")
    val dx by inf.animateFloat(
        initialValue = -36f, targetValue = 36f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dx",
    )
    GuideStage(t, primary, onSurface,
        phone = {
            Box(Modifier.fillMaxSize(0.9f).clip(RoundedCornerShape(6.dp)))
            Box(Modifier.fillMaxWidth(0.5f).height(1.5.dp).background(primary.copy(alpha = 0.3f)))
            Finger(primary, dx, -4f)
        },
        desktop = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(onSurface.copy(alpha = 0.04f)))
            MouseCursor(primary, Modifier.offset { androidx.compose.ui.unit.IntOffset((dx * 1.3f).toInt(), 2) })
        },
    )
}

/** ② 长按拖拽：手机手指按住拖动 → 桌面窗口卡片跟着移动 */
@Composable
private fun DemoLongPress(t: (String) -> String, primary: Color, onSurface: Color) {
    val inf = rememberInfiniteTransition(label = "drag")
    val dx by inf.animateFloat(
        initialValue = -26f, targetValue = 26f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dx",
    )
    GuideStage(t, primary, onSurface,
        phone = {
            Box(Modifier.fillMaxSize(0.9f).clip(RoundedCornerShape(6.dp)))
            // 拖拽轨迹虚线
            Box(Modifier.fillMaxWidth(0.55f).height(1.5.dp).background(primary.copy(alpha = 0.3f)))
            Finger(primary, dx, 0f)
        },
        desktop = {
            Box(Modifier.fillMaxSize()) {
                // 背景
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(onSurface.copy(alpha = 0.04f))) {}
                // 被拖拽的小窗口：标题栏(左侧鼠标icon) + 内容区；居中±36dp不出屏
                Box(
                    Modifier.align(Alignment.Center)
                        .size(88.dp, 58.dp)
                        .offset { androidx.compose.ui.unit.IntOffset((dx * 1.4f).toInt(), 0) }
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                        .border(1.5.dp, primary, RoundedCornerShape(6.dp)),
                ) {
                    Column {
                        // 标题栏：左侧鼠标指针 icon
                        Row(
                            Modifier.fillMaxWidth().height(18.dp)
                                .background(primary.copy(alpha = 0.3f))
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MouseCursor(primary, Modifier)
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.size(4.dp).clip(CircleShape).background(primary.copy(alpha = 0.7f))) {}
                        }
                        // 内容区
                        Box(Modifier.fillMaxWidth().weight(1f).background(primary.copy(alpha = 0.1f)))
                    }
                }
            }
        },
    )
}

/** ③ 双指滚动：手机双指上下 → 桌面文档滚动 */
@Composable
private fun DemoTwoFinger(t: (String) -> String, primary: Color, onSurface: Color) {
    val inf = rememberInfiniteTransition(label = "two")
    val dy by inf.animateFloat(
        initialValue = 20f, targetValue = -20f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dy",
    )
    GuideStage(t, primary, onSurface,
        phone = {
            Box(Modifier.fillMaxSize(0.9f).clip(RoundedCornerShape(6.dp)))
            Finger(primary, -22f, dy - 7f, alpha = 0.9f)
            Finger(primary, 18f, dy + 7f, alpha = 0.9f)
        },
        desktop = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(onSurface.copy(alpha = 0.04f))) {
                Column(
                    Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(0, (dy * 1.2f).toInt()) }
                        .padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(8) { Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(3.dp)).background(onSurface.copy(alpha = 0.22f))) }
                }
            }
        },
    )
}

/**
 * ④ 右下角方块 → 调度中心（Mac 式展开/收起动画）。
 * 节奏：全屏单窗口 → 方块按压 → 窗口缩成卡片+其他卡片从背后散出 → 停顿 →
 *       方块按压 → 卡片收回大卡片背后 → 大卡片放大回全屏 → 停顿 → 循环。
 */
@Composable
private fun DemoCorner(t: (String) -> String, primary: Color, onSurface: Color) {
    val expand = remember { Animatable(0f) }   // 0=全屏单窗口, 1=三卡片散开
    val press = remember { Animatable(0f) }    // 0=常态, 1=方块按下
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)                                                            // 全屏窗口展示
            press.animateTo(1f, tween(160))                                       // 方块按下
            expand.animateTo(1f, tween(750, easing = FastOutSlowInEasing))         // 展开
            press.animateTo(0f, tween(160))                                       // 方块弹回
            delay(900)                                                            // 调度中心展开态
            press.animateTo(1f, tween(160))                                       // 方块再按下
            expand.animateTo(0f, tween(750, easing = FastOutSlowInEasing))         // 收起
            press.animateTo(0f, tween(160))                                       // 方块弹回
            delay(500)                                                            // 全屏态停顿
        }
    }
    val e by expand.asState()
    val pr by press.asState()

    GuideStage(t, primary, onSurface,
        phone = {
            Box(Modifier.fillMaxSize(0.9f).clip(RoundedCornerShape(6.dp))) {
                // 快捷方块：press 时缩小模拟按压
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .offset(x = (-6).dp, y = (-5).dp)
                        .size(28.dp, 22.dp)
                        .scale(1f - pr * 0.2f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(primary.copy(alpha = 0.35f + pr * 0.25f))
                        .border(1.5.dp, primary, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("⌘", color = primary, fontSize = 12.sp) }
            }
        },
        desktop = {
            // 电脑白屏内容区 192×137dp：必须 contentAlignment=Center，否则卡片从左上角算 offset
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(onSurface.copy(alpha = 0.04f)),
                contentAlignment = Alignment.Center,
            ) {
                // 3 张"窗口"：e=0 全部重叠居中(全屏大窗口 180×120)，e=1 缩成卡片向中间靠拢、整体略偏右下
                repeat(3) { i ->
                    val cardW = lerp(180f, 52f, e)
                    val cardH = lerp(120f, 40f, e)
                    val cardX = lerp(0f, 8f + (i - 1) * 48f, e)
                    val cardY = lerp(0f, if (i == 1) 34f else -14f, e)
                    // i=0 始终可见（主窗口）；i>0 从大卡片背后淡出
                    val alpha = if (i == 0) 1f else e
                    Box(
                        Modifier.size(cardW.dp, cardH.dp)
                            .offset(x = cardX.dp, y = cardY.dp)
                            .clip(RoundedCornerShape(lerp(6f, 3f, e).dp))
                            .background(primary.copy(alpha = (0.25f + 0.12f * i) * alpha))
                            .border(1.dp, primary.copy(alpha = 0.6f * alpha), RoundedCornerShape(lerp(6f, 3f, e).dp)),
                    )
                }
            }
        },
    )
}

/** ⑤ 数字键盘：手机键盘输入 → 桌面文本框出现数字 */
@Composable
private fun DemoKeypad(t: (String) -> String, primary: Color, onSurface: Color) {
    val inf = rememberInfiniteTransition(label = "keypad")
    val hl by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "hl",
    )
    GuideStage(t, primary, onSurface,
        phone = {
            // 手机横屏：左侧触摸区 + 右侧 1/3 数字键盘（不是全屏键盘）
            Box(Modifier.fillMaxSize(0.9f).clip(RoundedCornerShape(6.dp))) {
                Box(Modifier.fillMaxWidth(0.34f).fillMaxHeight().align(Alignment.TopEnd).offset(x = (-8).dp)) {
                    KeypadGraphic(primary, highlight = hl, highlightKey = "2")
                }
                val d6 = LocalDensity.current.density
                Finger(primary, 12f * d6, 22f * d6, alpha = 0.9f)
            }
        },
        desktop = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(onSurface.copy(alpha = 0.04f))) {
                Box(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(4.dp))
                    .background(onSurface.copy(alpha = 0.1f))
                    .border(1.dp, primary.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .align(Alignment.TopCenter).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart) {
                    Text("2", fontSize = 13.sp, color = primary.copy(alpha = 0.3f + 0.7f * hl), fontWeight = FontWeight.Bold)
                }
                Text(t("tpg_type"), fontSize = 10.sp, color = primary.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.BottomCenter))
            }
        },
    )
}

/**
 * ⑤ 双击快捷方块开/关数字键盘：两次按压脉冲 → 键盘滑入；再双击 → 键盘滑出。
 */
@Composable
private fun DemoKeypadToggle(t: (String) -> String, primary: Color, onSurface: Color) {
    val kb = remember { Animatable(0f) }     // 0=键盘关闭, 1=键盘打开
    val press = remember { Animatable(0f) }  // 方块按压 0→1→0
    suspend fun doubleTap() {
        repeat(2) {
            press.animateTo(1f, tween(110))
            press.animateTo(0f, tween(110))
            delay(70)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)                                                        // 关闭态
            doubleTap()                                                        // 双击
            kb.animateTo(1f, tween(450, easing = FastOutSlowInEasing))         // 键盘滑入
            delay(1000)                                                        // 打开态
            doubleTap()                                                        // 双击
            kb.animateTo(0f, tween(450, easing = FastOutSlowInEasing))         // 键盘滑出
            delay(400)                                                        // 关闭态
        }
    }
    val k by kb.asState()
    val pr by press.asState()

    GuideStage(t, primary, onSurface,
        phone = {
            Box(Modifier.fillMaxSize(0.9f).clip(RoundedCornerShape(6.dp))) {
                // 键盘：kb=0 滑出屏幕右侧(+120dp)，kb=1 停在右侧(-8dp)
                Box(
                    Modifier.fillMaxWidth(0.34f).fillMaxHeight()
                        .align(Alignment.TopEnd)
                        .offset(x = lerp(120f, -8f, k).dp),
                ) { KeypadGraphic(primary) }
                // 快捷方块：双击按压反馈
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .offset(x = lerp(-6f, -125f, k).dp, y = (-5).dp)
                        .size(28.dp, 22.dp)
                        .scale(1f - pr * 0.2f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(primary.copy(alpha = 0.35f + pr * 0.25f))
                        .border(1.5.dp, primary, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("⌘", color = primary, fontSize = 12.sp) }
            }
        },
        desktop = {},
        onlyPhone = true,
        phoneWidth = 340.dp,
        phoneHeight = 178.dp,
    )
}

/**
 * ⑤ 键盘横滑切换 + 快捷方块跟随（合并原第5/7页）。
 * 节奏：滑动手势(手指) → 键盘+方块位移 → 停顿 → 反向滑动手势 → 键盘+方块位移 → 停顿。
 * 两段位移用相同 tween(500, FastOutSlowInEasing)，速度一致。
 */
@Composable
private fun DemoKeypadSwipe(t: (String) -> String, primary: Color, onSurface: Color) {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            phase.animateTo(1f, tween(600, easing = LinearEasing))               // 滑动1：手指在右键盘向左滑
            phase.animateTo(2f, tween(500, easing = FastOutSlowInEasing))         // 位移1：键盘右→左 + 方块跟随
            delay(350)                                                            // 停顿
            phase.animateTo(3f, tween(600, easing = LinearEasing))               // 滑动2：手指在左键盘向右滑
            phase.animateTo(4f, tween(500, easing = FastOutSlowInEasing))         // 位移2：键盘左→右 + 方块跟随（同速）
            delay(350)                                                            // 停顿
            phase.snapTo(0f)
        }
    }
    val ph = phase.value
    // 键盘位置：0=右, 1=左（只在位移阶段变化）
    val kbPos = when {
        ph < 1f -> 0f                    // 滑动1，键盘保持右
        ph < 2f -> ph - 1f              // 位移1：右→左
        ph < 3f -> 1f                   // 滑动2，键盘保持左
        else -> 4f - ph                 // 位移2：左→右
    }
    // 手指：只在滑动阶段可见
    val sliding = ph < 1f || (ph >= 2f && ph < 3f)
    val fingerAlpha = if (sliding) 0.95f else 0f
    val fingerSlide = when {
        ph < 1f -> -(ph * 42f)           // 右键盘：向左滑
        ph < 3f -> ((ph - 2f) * 42f)     // 左键盘：向右滑
        else -> 42f
    }
    val density = LocalDensity.current.density

    GuideStage(t, primary, onSurface,
        phone = {
            Box(Modifier.fillMaxSize(0.94f).clip(RoundedCornerShape(6.dp))) {
                // ① 键盘：kbPos=0 右、1 左
                Box(
                    Modifier.fillMaxWidth(0.34f).fillMaxHeight()
                        .align(Alignment.TopEnd)
                        .offset(x = (-8).dp - (kbPos * 197).dp),
                ) {
                    KeypadGraphic(primary)
                    // 手指在键盘内：只在滑动阶段可见
                    Box(Modifier.align(Alignment.Center)) {
                        Finger(primary, fingerSlide * density, 0f, alpha = fingerAlpha)
                    }
                }
                // ② 快捷方块：跟随键盘，始终在可见区右下角；icon 居中
                val squareOffsetDp = -121f + kbPos * 113f
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .offset(x = squareOffsetDp.dp, y = (-6).dp)
                        .size(28.dp, 22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(primary.copy(alpha = 0.45f))
                        .border(1.5.dp, primary, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("⌘", color = primary, fontSize = 12.sp, textAlign = TextAlign.Center) }
            }
        },
        desktop = {},
        onlyPhone = true,
        phoneWidth = 340.dp,
        phoneHeight = 178.dp,
    )
}
