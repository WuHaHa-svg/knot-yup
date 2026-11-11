package com.knotyup.android.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 启动页（液态流动背景，两幕动画）：
 * 1) 黑色绳结逐笔「打出」
 * 2) 打结完成后 KnotYup 文字逐字蹦跳出现，随后关闭启动动画
 */
@Composable
internal fun SplashScreen(onFinish: () -> Unit) {
    val draw = remember { androidx.compose.animation.core.Animatable(0f) }        // 打结进度
    val word = "KnotYup"
    // 第二幕：逐字蹦跳（每个字独立的透明度 + 上浮弹簧）
    val charAlphas = remember { word.map { androidx.compose.animation.core.Animatable(0f) } }
    val charOffsets = remember { word.map { androidx.compose.animation.core.Animatable(26f) } }

    LaunchedEffect(Unit) {
        // 第一幕：打结
        draw.animateTo(
            1f,
            tween(1100, easing = androidx.compose.animation.core.CubicBezierEasing(.4f, 0f, .2f, 1f)),
        )
        // 第二幕：文字逐字蹦跳（低阻尼弹簧回弹，交错 55ms）
        coroutineScope {
            word.forEachIndexed { i, _ ->
                launch {
                    delay(i * 55L)
                    launch { charAlphas[i].animateTo(1f, tween(200)) }
                    charOffsets[i].animateTo(
                        0f,
                        androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 420f),
                    )
                }
            }
        }
        delay(400)
        onFinish()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 液态流动背景（共享组件，与首页一致）
        com.knotyup.android.ui.components.LiquidBackground()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 绳结：4 条笔画独立 Path，按总长预算顺序截取
            val strokes = remember {
                listOf(
                    Path().apply {
                        moveTo(9f, 15f); lineTo(9f, 3f); cubicTo(9f, 3f, 4f, 5f, 4f, 10f)
                    },
                    Path().apply {
                        moveTo(15f, 9f); lineTo(15f, 21f); cubicTo(15f, 21f, 20f, 19f, 20f, 14f)
                    },
                    Path().apply {
                        moveTo(6f, 18f); cubicTo(6f, 18f, 9f, 20f, 12f, 18f)
                    },
                    Path().apply {
                        moveTo(18f, 6f); cubicTo(18f, 6f, 15f, 4f, 12f, 6f)
                    },
                )
            }
            val measure = remember { PathMeasure() }
            val segment = remember { Path() }
            Canvas(Modifier.size(150.dp)) {
                segment.reset()
                val lens = strokes.map { p ->
                    measure.setPath(p, false)
                    measure.length
                }
                val total = lens.sum()
                var budget = total * draw.value
                strokes.forEachIndexed { i, p ->
                    if (budget <= 0f) return@forEachIndexed
                    val take = min(budget, lens[i])
                    if (take > 0f) {
                        measure.setPath(p, false)
                        measure.getSegment(0f, take, segment, true)
                        budget -= take
                    }
                }

                val scale = size.minDimension / 24f * 0.9f
                val dx = (size.width - 24f * scale) / 2f
                val dy = (size.height - 24f * scale) / 2f
                withTransform({
                    translate(dx, dy)
                    scale(scale, scale, Offset.Zero)
                }) {
                    drawPath(
                        segment,
                        color = Color.Black,
                        style = Stroke(width = 1.7f, cap = StrokeCap.Round),
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            // 文字：逐字蹦跳（黑色）
            Row {
                word.forEachIndexed { i, ch ->
                    Text(
                        text = ch.toString(),
                        color = Color.Black,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.graphicsLayer {
                            alpha = charAlphas[i].value
                            translationY = charOffsets[i].value
                        },
                    )
                }
            }
        }
    }
}

// 液态流动背景已抽为共享组件 ui/components/LiquidBackground.kt（开屏与首页共用）
