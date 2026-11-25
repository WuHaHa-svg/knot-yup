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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.knotyup.android.touchpad.TouchTrail
import com.knotyup.android.touchpad.TrailPoint

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
        trail.forEachStroke { points, argb -> drawTrailStroke(points, argb, width) }
        drawTrailSparks(trail)
    }
}

/**
 * 轨迹绘制（触摸板控制页与设置预览共用）——连续四边形带 + 溅射粒子：
 * - 相邻采样点构成共享边界的四边形 → 一条连续带，无圆头接缝；
 * - 两端变细变淡：钟形 bell=sin(πt)，带宽/浓度在两端最小、中间最大（纺锤形）；
 * - 横截面中间浓两边淡：外层宽淡 + 内层实色；
 * - 溅射粒子由独立系统管理（见 drawTrailSparks），不在此函数按帧重算；
 * - 整体随最新点 age 淡出；纯色填充不依赖渐变刷（短轨迹渐变会退化）。
 */
internal fun DrawScope.drawTrailStroke(points: List<TrailPoint>, argb: Int, width: Float) {
    if (points.size < 2) return
    val n = points.size
    val step = (n / 28).coerceAtLeast(1)
    val idx = ArrayList<Int>()
    var k = 0
    while (k < n) { idx.add(k); k += step }
    if (idx.last() != n - 1) idx.add(n - 1)
    val sampled = idx.map { points[it] }
    val m = sampled.size
    if (m < 2) return

    val overallFade = points.last().alpha * 0.9f
    val base = Color(argb)

    // 每个采样点：法线、半宽(钟形两端细)、浓度(钟形两端淡)
    val nx = FloatArray(m)
    val ny = FloatArray(m)
    val half = FloatArray(m)
    val dens = FloatArray(m)
    for (i in 0 until m) {
        val p = sampled[i]
        val prev = sampled[maxOf(0, i - 1)]
        val next = sampled[minOf(m - 1, i + 1)]
        val dx = next.x - prev.x
        val dy = next.y - prev.y
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
        nx[i] = -dy / len
        ny[i] = dx / len
        val t = idx[i] / (n - 1).toFloat()
        val bell = kotlin.math.sin(t * 3.14159f).toFloat() // 0(旧端)→1(中)→0(新端)
        half[i] = width * (0.15f + 0.85f * bell) * 0.5f
        dens[i] = p.alpha * (0.15f + 0.85f * bell)
    }

    // 两层四边形带：外层柔边(1.9×/0.28) + 内层芯(1×/1)
    for (layer in 0..1) {
        val scale = if (layer == 0) 1.9f else 1f
        val alphaScale = if (layer == 0) 0.28f else 1f
        for (i in 0 until m - 1) {
            val p1 = sampled[i]
            val p2 = sampled[i + 1]
            val h1 = half[i] * scale
            val h2 = half[i + 1] * scale
            val a = ((dens[i] + dens[i + 1]) / 2f) * alphaScale * overallFade
            if (a < 0.01f) continue
            val path = Path().apply {
                moveTo(p1.x + nx[i] * h1, p1.y + ny[i] * h1)
                lineTo(p2.x + nx[i + 1] * h2, p2.y + ny[i + 1] * h2)
                lineTo(p2.x - nx[i + 1] * h2, p2.y - ny[i + 1] * h2)
                lineTo(p1.x - nx[i] * h1, p1.y - ny[i] * h1)
                close()
            }
            drawPath(path, base.copy(alpha = a.coerceIn(0f, 1f)))
        }
    }


}

/**
 * 溅射粒子渲染（与轨迹共用 Canvas）：粒子由 [TouchTrail] 在采点时溅出，
 * 生成后独立运动/消失，与轨迹点生命周期无关。细小、明亮，颜色跟轨迹一致。
 */
internal fun DrawScope.drawTrailSparks(trail: TouchTrail) {
    trail.forEachSpark { x, y, radius, alpha, argb ->
        drawCircle(Color(argb).copy(alpha = alpha), radius, Offset(x, y))
    }
}
