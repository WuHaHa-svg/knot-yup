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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 液态流动背景（共享组件：开屏 + 首页）：
 * 白底之上若干柔和色斑持续流动、彼此交融消散。
 *
 * 永不重复且无跳变的原理：
 * - 每个色斑的每个频率分量（x 主频/x 副频/y 主频/y 副频/呼吸）维护**独立**的相位累加器，
 *   每帧按「帧间隔 × 无理数速度」推进后各自 mod 2π——sin/cos 以 2π 为周期，
 *   独立回绕保证每个分量在回绕点连续，绝无闪跳；
 * - 各分量速度互为无理数倍（π/4、√2、√3、√5、√7、√11、√13、√17…），
 *   无理数比值的联合轨迹在数学上永不复现；数值永远有界，长时间运行精度不劣化。
 * 性能：30fps 节流（跳帧）+ 退后台时 withFrameNanos 无帧回调、循环自动挂起（零后台开销）。
 */
@Composable
internal fun LiquidBackground(dark: Boolean = androidx.compose.foundation.isSystemInDarkTheme()) {
    // 每色斑 5 个独立相位累加器（x主/x副/y主/y副/呼吸），全部有界回绕
    val phases = remember { FloatArray(BLOB_COUNT * PHASE_PER_BLOB) }
    // 各分量速度 = 色斑基速 × 分量倍率（见 LaunchedEffect 内计算），基速互为无理数倍
    val baseSpeeds = floatArrayOf(
        0.275f, 0.4950f, 0.6062f, 0.7826f, 0.9260f, 1.1608f, 1.2620f, 1.4431f,
    ) // π/4、√2、√3、√5、√7、√11、√13、√17，整体 ×0.35（再减半）调慢
    // 分量倍率（也取互异值）：x主 / x副 / y主 / y副 / 呼吸
    val mults = floatArrayOf(1.0f, 1.6180f, 1.3f, 2.4142f, 0.71f)
    // 重绘驱动：tick 是快照状态，Canvas 读它 → 每次更新触发重绘
    var tick by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var frame = 0
        val twoPi = (2.0 * Math.PI).toFloat()
        while (true) {
            withFrameNanos { now ->
                frame++
                if (frame % 2 != 0) return@withFrameNanos // 30fps 节流：跳过一半帧
                val delta = (now - last) / 1_000_000_000f // 含被跳过帧的完整间隔
                last = now
                for (i in 0 until BLOB_COUNT) {
                    for (c in 0 until PHASE_PER_BLOB) {
                        val k = i * PHASE_PER_BLOB + c
                        phases[k] = (phases[k] + delta * baseSpeeds[i] * mults[c]) % twoPi
                    }
                }
                tick += 1f
            }
        }
    }

    // clipToBounds 必不可少：Compose 默认不裁剪绘制内容，大半径色斑会画出组件边界
    // 盖住上方/相邻的其他组件（如首页 Header）——裁剪后色斑只在自身区域内可见
    Canvas(
        Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        @Suppress("UNUSED_EXPRESSION") tick // 读快照状态：tick 变化即触发本绘制块重绘
        // 底色随系统深浅色：浅色纯白 / 深色纯黑（色斑颜色不变）
        drawRect(if (dark) Color.Black else Color.White)
        val w = size.width
        val h = size.height
        val minDim = size.minDimension
        // 色斑定义：基准位置（比例）+ 漂移幅度（比例，xy 各异）+ 半径基准/呼吸幅度（比例）+ 颜色
        data class Blob(
            val bx: Float, val by: Float,
            val ax: Float, val ay: Float,
            val rBase: Float, val rBreathe: Float,
            val color: Color,
        )
        val blobs = listOf(
            Blob(0.16f, 0.22f, 0.13f, 0.10f, 0.62f, 0.10f, Color(0xFFDCE7FB)), // 淡蓝
            Blob(0.86f, 0.28f, 0.11f, 0.14f, 0.58f, 0.12f, Color(0xFFEDE3FB)), // 淡紫
            Blob(0.24f, 0.84f, 0.14f, 0.09f, 0.66f, 0.09f, Color(0xFFDFF6F0)), // 淡青
            Blob(0.80f, 0.82f, 0.10f, 0.13f, 0.56f, 0.13f, Color(0xFFFBE7EE)), // 淡粉
            Blob(0.52f, 0.50f, 0.08f, 0.08f, 0.50f, 0.08f, Color(0xFFFBF3DC)), // 中央淡金（融合用）
            Blob(0.05f, 0.55f, 0.09f, 0.12f, 0.54f, 0.11f, Color(0xFFE3F5E3)), // 淡绿
            Blob(0.55f, 0.08f, 0.12f, 0.09f, 0.60f, 0.10f, Color(0xFFFEFBF2)), // 淡橙（极淡，仅隐约点缀）
            Blob(0.45f, 0.92f, 0.10f, 0.11f, 0.58f, 0.09f, Color(0xFFE4F0FC)), // 淡天蓝
        )
        blobs.forEachIndexed { i, b ->
            val px = phases[i * PHASE_PER_BLOB]
            val pxSub = phases[i * PHASE_PER_BLOB + 1]
            val py = phases[i * PHASE_PER_BLOB + 2]
            val pySub = phases[i * PHASE_PER_BLOB + 3]
            val breath = phases[i * PHASE_PER_BLOB + 4]
            // 双频叠加：主频大漂移 + 副频小幅扰动（各自独立回绕，连续无跳变）
            val cx = w * (b.bx + b.ax * (sin(px) + 0.45f * sin(pxSub + i)))
            val cy = h * (b.by + b.ay * (cos(py + i) + 0.45f * cos(pySub)))
            // 半径呼吸：独立相位驱动涨缩
            val r = minDim * (b.rBase + b.rBreathe * sin(breath + i * 2.1f))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(b.color, Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
        }
    }
}

private const val BLOB_COUNT = 8
private const val PHASE_PER_BLOB = 5
