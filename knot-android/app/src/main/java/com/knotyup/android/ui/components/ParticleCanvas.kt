package com.knotyup.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.knotyup.android.touchpad.ParticleSystem

/**
 * 粒子渲染层（触摸板控制页叠加层）：
 * - 独立协程 60fps（withFrameNanos 不跳帧）更新粒子 + tick 触发重绘，不触发重组；
 * - 只读 ParticleSystem 状态，手势采集层直接 emit 坐标——渲染与采集完全解耦；
 * - 亮色小点画在深色底上（OLED 友好），clipToBounds 防越界；
 * - enabled=false 时不启动帧循环、不绘制（省电）。
 */
@Composable
internal fun ParticleCanvas(
    system: ParticleSystem,
    enabled: Boolean,
    density: Float = 1f,
    trail: Boolean = false,
) {
    if (!enabled) return
    var tick by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                system.update(dt)
                tick += 1f
            }
        }
    }
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        @Suppress("UNUSED_EXPRESSION") tick // 读快照状态：tick 变化触发重绘
        system.forEachAlive { x, y, vx, vy, radius, alpha, argb ->
            val col = Color(argb)
            if (trail) {
                // 拖尾：沿运动反方向画渐隐小圆，间距/长度随速度，无需历史轨迹
                val len = kotlin.math.hypot(vx, vy)
                if (len > 1f) {
                    val ux = vx / len
                    val uy = vy / len
                    val step = (radius * 1.2f).coerceAtLeast(1f)
                    for (k in 1..6) {
                        val f = k / 7f
                        drawCircle(
                            color = col.copy(alpha = alpha * (1f - f) * 0.85f),
                            radius = radius * density * 0.9f,
                            center = Offset(x - ux * step * k, y - uy * step * k),
                        )
                    }
                }
            }
            drawCircle(
                color = col.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = radius * density,
                center = Offset(x, y),
            )
        }
    }
}

/** 设置里的颜色 key → ARGB 调色板。random 返回整盘（emit 时随机取），固定色返回单元素。
 *  统一用高饱和霓虹亮色：清晰、绚丽，在 OLED 纯黑底上格外醒目。 */
fun particlePalette(colorKey: String): IntArray = when (colorKey) {
    "white" -> intArrayOf(0xFFFFFFFF.toInt())
    "primary" -> intArrayOf(0xFFB388FF.toInt()) // 亮紫霓虹
    "red" -> intArrayOf(0xFFFF5252.toInt())     // 亮红
    "blue" -> intArrayOf(0xFF40C4FF.toInt())    // 亮青蓝
    "gold" -> intArrayOf(0xFFFFD740.toInt())    // 亮金黄
    else -> intArrayOf( // random：18 色高饱和霓虹盘
        0xFFFFFFFF.toInt(), 0xFFB388FF.toInt(), 0xFFFF5252.toInt(), 0xFF40C4FF.toInt(),
        0xFFFFD740.toInt(), 0xFF00E676.toInt(), 0xFFFF4081.toInt(), 0xFF00B0FF.toInt(),
        0xFFFF9100.toInt(), 0xFFE040FB.toInt(), 0xFF64FFDA.toInt(), 0xFFFFFF00.toInt(),
        0xFFFF6E40.toInt(), 0xFF7C4DFF.toInt(), 0xFF1DE9B6.toInt(), 0xFFFF1744.toInt(),
        0xFF18FFFF.toInt(), 0xFFD500F9.toInt(),
    )
}
