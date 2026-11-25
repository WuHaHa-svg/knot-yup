package com.knotyup.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.knotyup.android.touchpad.TouchTrail

/**
 * 触摸轨迹渲染层（触摸板控制页叠加层）：
 * - 独立协程 60fps 更新淡出 + tick 触发重绘，不触发重组；
 * - 颜色来自轨迹自身（设置决定：固定/随机），粗细由 width 参数控制；
 * - enabled=false 时直接 return，不启动帧循环（省电）。
 */
@Composable
internal fun TouchTrailCanvas(
    trail: TouchTrail,
    enabled: Boolean,
    width: Float,
) {
    if (!enabled) return
    var tick by mutableFloatStateOf(0f)
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                trail.update(dt)
                tick += 1f
            }
        }
    }
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        @Suppress("UNUSED_EXPRESSION") tick
        trail.forEachStroke { points, argb ->
            val base = Color(argb)
            for (i in 1 until points.size) {
                val p1 = points[i - 1]
                val p2 = points[i]
                val alpha = ((p1.alpha + p2.alpha) / 2f) * 0.85f
                drawLine(
                    color = base.copy(alpha = alpha),
                    start = Offset(p1.x, p1.y),
                    end = Offset(p2.x, p2.y),
                    strokeWidth = width * alpha + 0.5f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
