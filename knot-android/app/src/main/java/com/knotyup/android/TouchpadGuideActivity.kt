package com.knotyup.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
private fun GuideScreen(t: (String) -> String, onFinish: () -> Unit, onSkip: () -> Unit) {
    val steps = listOf(
        GuideStep("tpg_1_title", "tpg_1_desc"),
        GuideStep("tpg_2_title", "tpg_2_desc"),
        GuideStep("tpg_3_title", "tpg_3_desc"),
        GuideStep("tpg_4_title", "tpg_4_desc"),
        GuideStep("tpg_5_title", "tpg_5_desc"),
        GuideStep("tpg_6_title", "tpg_6_desc"),
    )
    var step by remember { mutableIntStateOf(0) }
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        // 顶部：标题 + 跳过
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t("tpg_welcome"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = onSurface)
            Spacer(Modifier.weight(1f))
            Text(
                t("tpg_skip"), fontSize = 13.sp, color = primary,
                modifier = Modifier.clickable { onSkip() }.padding(8.dp),
            )
        }
        Text(t("tpg_welcome_sub"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(12.dp))

        // 中部：动画演示舞台（占大头）
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            when (step) {
                0 -> DemoSingleFinger(primary, onSurface)
                1 -> DemoLongPress(primary, onSurface)
                2 -> DemoTwoFinger(primary, onSurface)
                3 -> DemoCorner(primary, onSurface, keypadOpen = false)
                4 -> DemoCorner(primary, onSurface, keypadOpen = true)
                5 -> DemoKeypad(primary, onSurface)
            }
        }

        Spacer(Modifier.height(14.dp))

        // 文案：标题 + 描述
        Text(t(steps[step].titleKey), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            t(steps[step].descKey), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 21.sp, modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // 步骤圆点
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

        Spacer(Modifier.height(10.dp))

        // 底部按钮
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) {
                Text(
                    t("tpg_prev"), fontSize = 14.sp, color = onSurface,
                    modifier = Modifier.clickable { step-- }.padding(12.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(primary)
                    .clickable {
                        if (step == steps.lastIndex) onFinish() else step++
                    }
                    .padding(horizontal = 28.dp, vertical = 11.dp),
            ) {
                Text(
                    if (step == steps.lastIndex) t("tpg_start") else t("tpg_next"),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** 单个演示用的"手指"圆点 */
@Composable
private fun Finger(primary: Color, offsetX: Float, offsetY: Float, alpha: Float = 1f, scale: Float = 1f) {
    Box(
        Modifier.offset { androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .size(34.dp).scale(scale).alpha(alpha)
            .clip(CircleShape)
            .background(primary.copy(alpha = 0.35f))
            .border(2.dp, primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(primary))
    }
}

/** ① 单指移动：手指左右滑动，光标点跟随 */
@Composable
private fun DemoSingleFinger(primary: Color, onSurface: Color) {
    val inf = rememberInfiniteTransition(label = "single")
    val dx by inf.animateFloat(
        initialValue = -90f, targetValue = 90f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse,
        ),
        label = "dx",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 光标
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((dx * 0.8f).toInt(), 40) }
            .size(16.dp).clip(CircleShape).background(onSurface.copy(alpha = 0.5f)))
        // 轨迹线
        Box(Modifier.fillMaxWidth(0.6f).height(2.dp).background(primary.copy(alpha = 0.25f)))
        Finger(primary, dx, -20f)
    }
}

/** ② 长按拖拽：手指按住，涟漪扩散，选中框出现 */
@Composable
private fun DemoLongPress(primary: Color, onSurface: Color) {
    val inf = rememberInfiniteTransition(label = "long")
    val phase by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Restart),
        label = "phase",
    )
    val held = phase > 0.45f
    val ripple by animateFloatAsState(if (held) 1.6f else 0.6f)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 被选中的窗口框
        Box(
            Modifier.size(140.dp, 80.dp)
                .border(2.dp, if (held) primary else onSurface.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .background(if (held) primary.copy(alpha = 0.12f) else Color.Transparent),
        )
        Finger(primary, 0f, 0f, scale = ripple)
        if (held) {
            Text(
                "✦", fontSize = 28.sp, color = primary,
                modifier = Modifier.offset(x = 46.dp, y = (-46).dp),
            )
        }
    }
}

/** ③ 双指滚动：两根手指上下滑动，内容条移动 */
@Composable
private fun DemoTwoFinger(primary: Color, onSurface: Color) {
    val inf = rememberInfiniteTransition(label = "two")
    val dy by inf.animateFloat(
        initialValue = 45f, targetValue = -45f,
        animationSpec = infiniteRepeatable(
            tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse,
        ),
        label = "dy",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxHeight(0.55f).width(120.dp).offset { androidx.compose.ui.unit.IntOffset(60, dy.toInt()) },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(5) { Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).background(onSurface.copy(alpha = 0.2f))) }
        }
        Finger(primary, -55f, dy - 12f, alpha = 0.85f)
        Finger(primary, 5f, dy - 12f, alpha = 0.85f)
    }
}

/** ④ 右下角方块：演示位置 + 长按/双击提示；keypadOpen 时方块左移 */
@Composable
private fun DemoCorner(primary: Color, onSurface: Color, keypadOpen: Boolean) {
    val inf = rememberInfiniteTransition(label = "corner")
    val pulse by inf.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(Modifier.fillMaxSize()) {
        // 模拟触摸板边界
        Box(Modifier.fillMaxWidth(if (keypadOpen) 0.66f else 1f).fillMaxHeight()
            .border(1.dp, onSurface.copy(alpha = 0.15f), RoundedCornerShape(8.dp)))
        // 键盘打开时右侧示意键盘
        if (keypadOpen) {
            Box(Modifier.fillMaxWidth(0.33f).align(Alignment.TopEnd).fillMaxHeight()
                .background(onSurface.copy(alpha = 0.08f))
                .border(1.dp, primary.copy(alpha = 0.3f)))
        }
        // 方块：跟随可见区右下角
        Box(
            Modifier.align(Alignment.BottomEnd)
                .offset { androidx.compose.ui.unit.IntOffset(if (keypadOpen) -120 else 0, 0) }
                .padding(16.dp)
                .size(64.dp, 40.dp).scale(pulse)
                .clip(RoundedCornerShape(8.dp)).background(primary.copy(alpha = 0.3f))
                .border(2.dp, primary, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("⌘", color = primary, fontSize = 18.sp)
        }
        Finger(primary, 0f, 0f, alpha = 0f) // 占位保持签名一致
    }
}

/** ⑤ 数字键盘：键盘从右滑入 + 横滑切换 + 按键高亮 */
@Composable
private fun DemoKeypad(primary: Color, onSurface: Color) {
    val inf = rememberInfiniteTransition(label = "keypad")
    val slide by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "slide",
    )
    val highlight by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "hl",
    )
    Box(Modifier.fillMaxSize()) {
        // 触摸板
        Box(Modifier.fillMaxWidth().fillMaxHeight().background(onSurface.copy(alpha = 0.05f)))
        // 键盘从右侧滑入
        Column(
            Modifier.fillMaxHeight().fillMaxWidth(0.36f)
                .align(Alignment.TopEnd)
                .offset { androidx.compose.ui.unit.IntOffset(((1f - slide) * 260f).toInt(), 0) }
                .border(1.dp, primary.copy(alpha = 0.4f)),
        ) {
            // (字符, 占格数)：0 占两格、小数点占一格
            val rows = listOf(
                listOf("7" to 1, "8" to 1, "9" to 1),
                listOf("4" to 1, "5" to 1, "6" to 1),
                listOf("1" to 1, "2" to 1, "3" to 1),
                listOf("0" to 1, "." to 1, "⌫" to 1),
            )
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    row.forEach { (key, span) ->
                        Box(
                            Modifier.weight(span.toFloat()).fillMaxHeight()
                                .border(0.5.dp, primary.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                key, color = if (key == "2") primary.copy(alpha = highlight) else primary,
                                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        // 横滑箭头提示
        Text(
            "← →", fontSize = 24.sp, color = primary,
            modifier = Modifier.align(Alignment.Center).offset(x = (-30).dp, y = 90.dp),
        )
    }
}
