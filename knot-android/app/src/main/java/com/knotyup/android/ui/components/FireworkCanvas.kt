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
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.knotyup.android.touchpad.FireworkSystem

/**
 * 烟花渲染层：独立帧循环（withFrameNanos 跟随设备刷新率）推进引擎 + tick 触发重绘。
 * - 火箭：亮色小点 + 拖尾烟雾；爆炸粒子：亮色点 + 重力下沉 + 烟雾尾迹；
 * - 烟雾：灰白半透明圆，缓慢膨胀淡出；
 * - enabled=false 时不启动帧循环、不绘制（省电）。
 */
@Composable
internal fun FireworkCanvas(
    system: FireworkSystem,
    enabled: Boolean,
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
        @Suppress("UNUSED_EXPRESSION") tick
        drawFireworks(system, 1f)
    }
}

/** 烟花绘制（全屏与预览共用）：scale 为预览缩放系数。 */
internal fun DrawScope.drawFireworks(system: FireworkSystem, scale: Float = 1f) {
    system.forEachRenderable { item ->
        val center = Offset(item.x, item.y)
        if (item.kind == FireworkSystem.Kind.SPARK) {
            // 长拖尾：沿历史轨迹点绘制（与烟雾同一路径，下垂时也一致）
            val tx = item.trailX
            val ty = item.trailY
            if (tx != null && ty != null) {
                val n = item.trailCount
                for (k in 0 until n) {
                    val idx = (item.trailHead - 1 - k + FireworkSystem.TRAIL_LEN) % FireworkSystem.TRAIL_LEN
                    val f = k.toFloat() / n
                    drawCircle(
                        color = Color(item.argb).copy(alpha = item.alpha * (1f - f) * 0.7f),
                        radius = item.radius * (1f - f * 0.55f) * scale,
                        center = Offset(tx[idx], ty[idx]),
                    )
                }
            }
        }
        drawCircle(
            color = Color(item.argb).copy(alpha = item.alpha),
            radius = item.radius * scale,
            center = center,
        )
    }
}
