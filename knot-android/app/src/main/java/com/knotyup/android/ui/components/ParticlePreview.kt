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
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    val density = LocalDensity.current.density
    val system = remember { ParticleSystem() }
    var wPx by remember { mutableFloatStateOf(0f) }
    var hPx by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableFloatStateOf(0f) }

    // replayKey 变化 → 在容器中心发射一次
    LaunchedEffect(replayKey) {
        if (wPx > 0f && hPx > 0f) {
            system.emit(
                wPx / 2f, hPx / 2f, sizeDp * density, count,
                palette, randomColor, speedPx, wPx, hPx,
            )
        }
    }
    // 首次布局完成后也播一次
    LaunchedEffect(wPx, hPx) {
        if (wPx > 0f && hPx > 0f && replayKey == 0) {
            system.emit(
                wPx / 2f, hPx / 2f, sizeDp * density, count,
                palette, randomColor, speedPx, wPx, hPx,
            )
        }
    }

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
