package com.knotyup.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.knotyup.android.touchpad.ParticleSystem
import com.knotyup.android.touchpad.TouchTrail
import kotlinx.coroutines.delay

/**
 * 涟漪粒子预览容器（设置弹窗内）。
 *
 * - 独立的 ParticleSystem 实例，边界=预览容器尺寸；
 * - replayKey 每次变化（滑块松手应用新值/切换颜色选项）→ 在容器中心播放一次涟漪；
 * - 与手势采集完全无关，纯视觉。
 */
@Composable
internal fun ParticlePreview(
    replayKey: Int,
    sizeDp: Float,
    count: Int,
    speedPx: Float,
    palette: IntArray,
    randomColor: Boolean,
    trail: Boolean = false,
    // 触摸拖尾演示：trailPalette != null 时，每次预览模拟一小段拖动并显示轨迹
    trailPalette: IntArray? = null,
    trailRandomColor: Boolean = false,
    trailWidth: Float = 20f,
    particlesEnabled: Boolean = true,
    touchTrailEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    val density = LocalDensity.current.density
    val system = remember { ParticleSystem() }
    val touchTrail = remember { TouchTrail() }
    var wPx by remember { mutableFloatStateOf(0f) }
    var hPx by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableFloatStateOf(0f) }

    // replayKey 变化 → 播放一次预览：
    // - 拖尾开：慢速模拟拖动，粒子在拖尾起点/终点各发一次（两次粒子 + 一次拖尾）
    // - 拖尾关：粒子在中心发一次
    // - 粒子关：只画拖尾
    LaunchedEffect(replayKey, wPx, hPx) {
        if (wPx <= 0f || hPx <= 0f) return@LaunchedEffect
        val showTrail = trailPalette != null && touchTrailEnabled
        if (!showTrail) {
            if (particlesEnabled) {
                system.emit(
                    wPx / 2f, hPx / 2f, sizeDp * density, count,
                    palette, randomColor, speedPx, wPx, hPx,
                )
            }
            return@LaunchedEffect
        }
        // 拖尾路径：从左到右、略带弧度；粒子在两端发射，坐标与拖尾对应
        val xStart = wPx * 0.20f
        val xEnd = wPx * 0.80f
        val yBase = hPx * 0.52f
        val arc = hPx * 0.16f
        val frames = 24
        touchTrail.clear()
        // 起点粒子
        if (particlesEnabled) {
            system.emit(xStart, yBase, sizeDp * density, count, palette, randomColor, speedPx, wPx, hPx)
        }
        // 慢速拖动，逐帧喂点形成轨迹
        for (i in 0..frames) {
            val f = i / frames.toFloat()
            val x = xStart + (xEnd - xStart) * f
            val y = yBase + kotlin.math.sin(f * 3.14159f) * arc
            touchTrail.feed(listOf(Triple(99L, x, y)), trailPalette, trailRandomColor)
            delay(60)
        }
        // 终点粒子
        if (particlesEnabled) {
            system.emit(xEnd, yBase, sizeDp * density, count, palette, randomColor, speedPx, wPx, hPx)
        }
        // 抬手
        touchTrail.feed(emptyList(), trailPalette, trailRandomColor)
    }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                system.update(dt)
                if (trailPalette != null && touchTrailEnabled) touchTrail.update(dt)
                tick += 1f
            }
        }
    }

    Box(
        modifier
            .height(height)
            .fillMaxWidth()
            .clipToBounds()
            .background(Color(0xFF1A1A1A))
            .onSizeChanged { wPx = it.width.toFloat(); hPx = it.height.toFloat() }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") tick
            // 触摸拖尾演示：逐点随机色线
            if (trailPalette != null && touchTrailEnabled) {
                touchTrail.forEachStroke { pts, argb ->
                    val base = Color(argb)
                    for (i in 1 until pts.size) {
                        val p1 = pts[i - 1]
                        val p2 = pts[i]
                        val a = ((p1.alpha + p2.alpha) / 2f) * 0.9f
                        drawLine(
                            color = base.copy(alpha = a),
                            start = Offset(p1.x, p1.y),
                            end = Offset(p2.x, p2.y),
                            strokeWidth = trailWidth * a + 0.5f,
                        )
                    }
                }
            }
            system.forEachAlive { x, y, vx, vy, radius, alpha, argb ->
                val col = Color(argb)
                if (trail) {
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
}
