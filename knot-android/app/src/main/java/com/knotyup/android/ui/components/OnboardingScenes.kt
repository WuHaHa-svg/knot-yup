package com.knotyup.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knotyup.android.R
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** ① 欢迎：中心 Knot 结 + 四周四大能力（设备/剪贴板/文件/触摸板）依次浮现。 */
@Composable
internal fun SceneWelcome(t: (String) -> String) {
    val accent = MaterialTheme.colorScheme.primary
    val pulse = remember { Animatable(0.92f) }
    LaunchedEffect(Unit) {
        pulse.animateTo(
            1.06f,
            infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureChip(icon = R.drawable.ic_devices, label = "ob_dev", tint = accent, delayMs = 0, t = t)
                FeatureChip(icon = R.drawable.ic_clip, label = "ob_clip", tint = Color(0xFF1D9E75), delayMs = 150, t = t)
            }
            Spacer(Modifier.height(26.dp))
            Box(Modifier.size(72.dp).scale(pulse.value), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_knot),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureChip(icon = R.drawable.ic_file, label = "ob_file", tint = Color(0xFFBA7517), delayMs = 300, t = t)
                FeatureChip(icon = R.drawable.ic_touchpad, label = "ob_touchpad", tint = Color(0xFFD4537E), delayMs = 450, t = t)
            }
        }
    }
}

@Composable
private fun FeatureChip(icon: Int, label: String, tint: Color, delayMs: Int, t: (String) -> String) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); appear.animateTo(1f, tween(400)) }
    Column(
        Modifier
            .alpha(appear.value)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(3.dp))
        Text(t(label), fontSize = 10.sp, color = tint)
    }
}

/** ② 局域网：WiFi 波纹 + 附近设备逐个浮现打勾。 */
@Composable
internal fun SceneLan(connected: Boolean, t: (String) -> String) {
    val accent = MaterialTheme.colorScheme.primary
    val wave = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        wave.animateTo(
            1f,
            infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(90.dp), contentAlignment = Alignment.Center) {
                // 扩散波纹
                Canvas(Modifier.fillMaxSize()) {
                    val maxR = size.minDimension / 2f
                    for (i in 0..1) {
                        val f = (wave.value + i * 0.5f) % 1f
                        drawCircle(
                            color = accent.copy(alpha = (1f - f) * 0.5f),
                            radius = maxR * f,
                            center = Offset(size.width / 2, size.height / 2),
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                    drawCircle(accent, radius = 14.dp.toPx(), center = Offset(size.width / 2, size.height / 2))
                }
            }
            Spacer(Modifier.height(18.dp))
            listOf("MacBook", "Windows", "Android").forEachIndexed { i, name ->
                val appear = remember { Animatable(0f) }
                LaunchedEffect(connected) {
                    delay(200L * (i + 1))
                    appear.animateTo(if (connected) 1f else 0.45f, tween(400))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 3.dp)
                        .alpha(appear.value),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(8.dp))
                    Text(name, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    if (connected) {
                        Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D9E75))
                    }
                }
            }
        }
    }
}

/** ③ 通知权限：悬浮通知从顶部滑入 → 停留 → 上滑消失，循环。 */
@Composable
internal fun SceneNotification(t: (String) -> String) {
    val slide = remember { Animatable(-1f) } // -1=屏外上方 0=停留
    LaunchedEffect(Unit) {
        while (true) {
            slide.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
            delay(1400)
            slide.animateTo(-1f, tween(400, easing = FastOutSlowInEasing))
            delay(600)
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize()) {
        // 背景：一个模糊的桌面层
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            repeat(4) { Box(Modifier.fillMaxWidth().height(18.dp).padding(2.dp).clip(RoundedCornerShape(4.dp)).background(accent.copy(alpha = 0.08f))) }
        }
        // 悬浮通知卡片：从顶部滑入
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .offset(y = (-90f + 130f * (slide.value + 1f)).dp)
                .alpha((slide.value + 1f).coerceIn(0f, 1f))
                .clip(RoundedCornerShape(12.dp))
                .background(accent)
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
                Spacer(Modifier.width(8.dp))
                Text("Knot", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Spacer(Modifier.height(5.dp))
            Text(t("ob_notif_copied"), fontSize = 10.sp, color = Color.White)
            Text(t("ob_copy"), fontSize = 9.sp, color = Color.White.copy(alpha = 0.75f))
        }
    }
}

/** ④ 附近设备：雷达同心圆扩散 + 设备点浮现。 */
@Composable
internal fun SceneNearby(t: (String) -> String) {
    val accent = MaterialTheme.colorScheme.primary
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        sweep.animateTo(1f, infiniteRepeatable(tween(2200), RepeatMode.Restart))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(150.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val maxR = size.minDimension / 2f
            // 同心圆静态刻度
            for (r in listOf(0.35f, 0.7f, 1f)) {
                drawCircle(accent.copy(alpha = 0.25f), radius = maxR * r, center = c, style = Stroke(1.dp.toPx()))
            }
            // 扫描波纹
            val f = sweep.value
            drawCircle(accent.copy(alpha = (1f - f) * 0.6f), radius = maxR * f, center = c, style = Stroke(2.dp.toPx()))
            // 扫描线
            val ang = f * 6.28318f
            drawLine(accent.copy(alpha = 0.7f), c, Offset(c.x + cos(ang) * maxR, c.y + sin(ang) * maxR), 2.dp.toPx())
            // 设备点（雷达上的三个点，随扫描出现）
            for ((px, py) in listOf(0.3f to -0.4f, -0.55f to 0.2f, 0.6f to 0.5f)) {
                drawCircle(Color(0xFF1D9E75), radius = 5.dp.toPx(), center = Offset(c.x + px * maxR, c.y + py * maxR))
            }
        }
    }
}

/** ⑤ 开始：几个勾选圆圈依次弹入汇聚。 */
@Composable
internal fun SceneReady(t: (String) -> String) {
    val accent = MaterialTheme.colorScheme.primary
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(76.dp)
                    .scale(scale.value)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = accent)
            }
            Spacer(Modifier.height(16.dp))
            Row {
                listOf("LAN", "ob_notif", "ob_dev").forEachIndexed { i, label ->
                    val a = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { delay(300L * (i + 1)); a.animateTo(1f, tween(300)) }
                    Text(
                        "✓ ${t(label)}",
                        fontSize = 10.sp,
                        color = Color(0xFF1D9E75),
                        modifier = Modifier.alpha(a.value).padding(horizontal = 5.dp),
                    )
                }
            }
        }
    }
}

/** ⑥ 后台保活：锁屏状态下通知仍能送达——底部锁屏条 + 通知滑入循环。 */
@Composable
internal fun SceneBattery(t: (String) -> String) {
    val accent = MaterialTheme.colorScheme.primary
    val slide = remember { Animatable(-1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            slide.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
            delay(1300)
            slide.animateTo(-1f, tween(400, easing = FastOutSlowInEasing))
            delay(500)
        }
    }
    Box(Modifier.fillMaxSize()) {
        // 屏幕中央：屏幕已关闭提示（锁屏）
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("⚡", fontSize = 28.sp, color = accent)
            }
            Spacer(Modifier.height(8.dp))
            Text(t("ob_battery"), fontSize = 11.sp, color = accent)
        }
        // 通知滑入（屏幕关闭时仍能送达）
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .offset(y = (-80f + 120f * (slide.value + 1f)).dp)
                .alpha((slide.value + 1f).coerceIn(0f, 1f))
                .clip(RoundedCornerShape(10.dp))
                .background(accent)
                .padding(9.dp),
        ) {
            Text("Knot", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(t("ob_notif_copied"), fontSize = 10.sp, color = Color.White)
        }
    }
}

@Composable
private fun DeviceChip(label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_devices), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 9.sp, color = color)
    }
}
