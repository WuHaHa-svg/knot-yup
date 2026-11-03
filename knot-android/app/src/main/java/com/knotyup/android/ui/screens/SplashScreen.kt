package com.knotyup.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 启动页（纯白底，三幕动画）：
 * 1) 黑色绳结逐笔「打出」
 * 2) 打结完成后 KnotYup 文字逐字蹦跳出现
 * 3) 绳结与文字一起染上品牌渐变色，随后关闭启动动画
 */
@Composable
internal fun SplashScreen(onFinish: () -> Unit) {
    val draw = remember { androidx.compose.animation.core.Animatable(0f) }        // 打结进度
    val word = "KnotYup"
    // 第二幕：逐字蹦跳（每个字独立的透明度 + 上浮弹簧）
    val charAlphas = remember { word.map { androidx.compose.animation.core.Animatable(0f) } }
    val charOffsets = remember { word.map { androidx.compose.animation.core.Animatable(26f) } }
    val colorPhase = remember { androidx.compose.animation.core.Animatable(0f) }   // 渐变上色

    LaunchedEffect(Unit) {
        // 第一幕：打结
        draw.animateTo(
            1f,
            androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.CubicBezierEasing(.4f, 0f, .2f, 1f)),
        )
        // 第二幕：文字逐字蹦跳（低阻尼弹簧回弹，交错 55ms）
        coroutineScope {
            word.forEachIndexed { i, _ ->
                launch {
                    delay(i * 55L)
                    launch { charAlphas[i].animateTo(1f, androidx.compose.animation.core.tween(200)) }
                    charOffsets[i].animateTo(
                        0f,
                        androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 420f),
                    )
                }
            }
        }
        delay(250)
        // 第三幕：渐变上色
        colorPhase.animateTo(1f, androidx.compose.animation.core.tween(650))
        delay(400)
        onFinish()
    }

    val accent = MaterialTheme.colorScheme.primary
    val accent2 = lerp(accent, Color(0xFFA855F7), 0.45f)
    val gradient = Brush.linearGradient(listOf(accent, accent2))
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 绳结：4 条笔画独立 Path，按总长预算顺序截取；黑色描线 + 渐变叠层淡入
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
                    val style = Stroke(
                        width = 1.7f, cap = StrokeCap.Round,
                    )
                    // 黑色基底
                    drawPath(segment, color = Color.Black, style = style)
                    // 渐变叠层（第三幕淡入）
                    if (colorPhase.value > 0f) {
                        drawPath(
                            segment, brush = gradient, alpha = colorPhase.value, style = style,
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            // 文字：逐字蹦跳（黑色基底 + 渐变叠层）
            Box {
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
                if (colorPhase.value > 0f) {
                    Row(
                        Modifier.graphicsLayer { alpha = colorPhase.value },
                    ) {
                        word.forEach { ch ->
                            Text(
                                text = ch.toString(),
                                style = TextStyle(
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    brush = gradient,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
