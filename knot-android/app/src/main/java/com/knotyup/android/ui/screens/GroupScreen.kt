package com.knotyup.android.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.knotyup.android.data.HistoryItem
import com.knotyup.android.net.DeviceInfo
import com.knotyup.android.ui.components.KnotEnter
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/** 群页 tab 数（群信息/剪切板/文件/触摸板）；末页下标 = PAGE_COUNT - 1。 */
private const val PAGE_COUNT = 4
private const val LAST_PAGE = PAGE_COUNT - 1

/** 群页面：左右分页（群信息 / 剪贴板）+ 悬浮 Tab 栏。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupBody(
    t: (String) -> String,
    members: List<DeviceInfo>,
    history: List<HistoryItem>,
    sendText: String,
    role: String,
    groupAddr: String,
    groupPin: String,
    onKick: (String, String) -> Unit,
    onCopy: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSend: () -> Unit,
    onLeave: () -> Unit,
) {
    // 各页当前位置（浮点：0..LAST_PAGE 及滑动中间态）。用普通 MutableState 作为权威值：
    // 手势里可直接同步赋值（零调度延迟、不抖），动画用一个临时 Animatable 驱动并回写。
    // 页面不交给 HorizontalPager——各页同时组合、永不卸载。
    // rememberSaveable：触摸板控制页强制横屏会触发 Activity 重建，须保住页码（否则回到第 1 页）
    val pagePos = rememberSaveable { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var pageAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // seen 集合留作双保险（页面不卸载，正常用不到，但无害）
    val seenClipItems = remember { mutableSetOf<Long>() }
    val seenFileItems = remember { mutableSetOf<String>() }

    // 平滑切页：取消进行中的动画。用 tween 单调缓动（物理上不会过冲回弹）
    fun goTo(page: Int) {
        pageAnimJob?.cancel()
        pageAnimJob = scope.launch {
            Animatable(pagePos.floatValue).animateTo(
                page.toFloat(), tween(220, easing = FastOutSlowInEasing)
            ) { pagePos.floatValue = value }
        }
    }

    // imePadding：Android 15 强制 edge-to-edge，adjustResize 不再自动让位键盘，
    // 由 padding 把整页顶到键盘上方，避免输入框被遮。
    // 点击输入框外部空白区：清焦点 → 键盘收起；点输入框本身时它消费事件，此处不触发。
    Box(
        Modifier
            .fillMaxSize()
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            // 内容区：各页叠放同时组合（不卸载），用 translationX + alpha 做视差转场；
            // 左右滑动改 pagePos、松手吸附到最近页。
            Box(
                Modifier
                    .fillMaxSize()
                    // 内容区横滑切页：手写手势，阈值仅 4px（远小于系统 8dp slop），
                    // 判定放宽为 |dx| > |dy|*0.7，斜滑/小幅起手都容易触发。
                    // 关键：判定后直接在手势协程内 snapTo/animateTo（不 launch），
                    // 避免多协程并发改 Animatable 造成抖动。
                    .pointerInput(widthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startPage = pagePos.floatValue.roundToInt().coerceIn(0, LAST_PAGE)
                            var decided = false
                            var horizontal = false
                            var totalX = 0f
                            var totalY = 0f
                            var sumDx = 0f // 全程累计横向位移（判定后仍累加），用于松手时判定是否切页
                            var selfConsumed = false // 父已判定切页并 consume，区别于子控件 consume
                            val thresholdPx = 4f
                            val swipePx = widthPx * 0.12f // 稍划即切：超过页宽 12%（约 130px）就算一次翻页
                            while (true) {
                                // Main pass：子控件（如设置页 Slider）先处理，若它 consume 了横向拖动，
                                // 父在这里看到 isConsumed 就整段让行——解决 Slider 与切页手势冲突。
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                // 某帧没有本 pointer（系统事件/多指）时跳过该帧，绝不能 break——
                                // break 会让手势偶发中断，表现为「难以触发」。
                                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                                if (!change.pressed) break
                                // 子控件消费了本次拖动（Slider/列表等）→ 放弃切页判定，整段跟随
                                if (!selfConsumed && change.isConsumed) {
                                    horizontal = false
                                    decided = true
                                    continue
                                }
                                // 用系统 previousPosition：比手动记账精确，且每帧 delta 稳定
                                val dx = change.position.x - change.previousPosition.x
                                val dy = change.position.y - change.previousPosition.y
                                if (!decided) {
                                    totalX += dx
                                    totalY += dy
                                    if (kotlin.math.abs(totalX) > thresholdPx || kotlin.math.abs(totalY) > thresholdPx) {
                                        decided = true
                                        horizontal = kotlin.math.abs(totalX) > kotlin.math.abs(totalY) * 0.7f
                                    }
                                }
                                if (decided && horizontal) {
                                    change.consume()
                                    selfConsumed = true
                                    sumDx += dx
                                    // 直接同步赋值（手势协程内可改 MutableState，无调度延迟、不抖）
                                    pagePos.floatValue = (pagePos.floatValue - dx / widthPx).coerceIn(0f, LAST_PAGE.toFloat())
                                }
                            }
                            if (horizontal) {
                                // 稍划即切：累计位移超阈值就往滑动方向切相邻一页（基于起始页，
                                // 而非「现位置过半」），没划够则回原页。手指左滑 sumDx<0 → 页码 +1。
                                val dir = if (kotlin.math.abs(sumDx) > swipePx) {
                                    if (sumDx < 0) 1 else -1
                                } else 0
                                goTo((startPage + dir).coerceIn(0, LAST_PAGE))
                            }
                        }
                    }
            ) {
                // 各页无条件组合、永不卸载——这是本次重构的核心。
                // 每页相对当前位置 offset = page - pagePos，决定横向位移与淡出。
                listOf(
                    @Composable {
                        DevicesTab(t, members, role, groupAddr, groupPin, onKick, onLeave)
                    },
                    @Composable {
                        ClipboardTab(t, members, history, sendText, seenClipItems, onCopy, onSendText, onSend)
                    },
                    @Composable {
                        FileTab(t, members, seenFileItems)
                    },
                    @Composable {
                        TouchpadTab(t, members)
                    },
                ).forEachIndexed { page, content ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // 在 layer 阶段读 pagePos，不触发组合重组（只更新变换矩阵，动画更稳不抖）
                                val offset = page - pagePos.floatValue
                                translationX = offset * widthPx
                                val d = offset.absoluteValue.coerceIn(0f, 1f)
                                alpha = 1f - 0.75f * d
                                val scale = 1f - 0.12f * d
                                scaleX = scale
                                scaleY = scale
                            }
                    ) { content() }
                }
            }
        }
        // 悬浮 Tab 栏：点击切页；入场从底部升起（AnimatedVisibility 原生合成阴影）
        KnotEnter(
            delayMillis = 220,
            slideDistance = 0.5f,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            GroupTabBar(
                t = t,
                page = pagePos.floatValue,
                onSelect = { i -> goTo(i) },
            )
        }
    }
}

/** 独立悬浮分段 Tab 栏：容器 + tab item + 激活指示块。
 *
 *  交互完全自治，不直接操作页面容器（悬浮组件与内容页解耦）：
 *  - 点 item / 内容页左右滑：page 由外部传入（页面位置），色块跟随；
 *  - 在 tab 栏上横滑：只改本地 [dragOffset] 让色块跟手，页面纹丝不动；
 *  - 松手：按色块最终位置算目标 tab，回调 [onSelect] 让外部切页，同时色块偏移动画归零。
 *  拖动期间零携程操作页面位置，避免高频 launch 冲击动画状态机导致抖动。 */
@Composable
internal fun GroupTabBar(
    t: (String) -> String,
    page: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val items = listOf(
        t("group_info") to com.knotyup.android.R.drawable.ic_devices,
        t("clipboard") to com.knotyup.android.R.drawable.ic_clip,
        t("files") to com.knotyup.android.R.drawable.ic_file,
        t("touchpad") to com.knotyup.android.R.drawable.ic_touchpad,
    )
    // tab 拖动产生的本地页偏移（单位=tab）；仅影响色块显示，不驱动页面位置。
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val settleAnim = remember { Animatable(0f) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // pointerInput 闭包只启动一次，会捕获创建时的 page（旧值）。用 rememberUpdatedState
    // 让闭包每次读到最新 page——否则在剪切板页(page=1)拖动仍用旧的 page=0 做 clamp，色块不动。
    val pageState = rememberUpdatedState(page)
    // 色块视觉位置：页面位置 + 拖动偏移，clamp 到 [0, LAST_PAGE]（PAGE_COUNT 段）
    val visualPos = (page + dragOffset).coerceIn(0f, LAST_PAGE.toFloat())

    // 松手后把色块偏移从当前值平滑归零（与页面切换动画并行）
    fun settle() {
        settleJob?.cancel()
        settleJob = scope.launch {
            settleAnim.snapTo(dragOffset)
            settleAnim.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 420f)) {
                dragOffset = value
            }
        }
    }

    BoxWithConstraints(modifier) {
        // 容器宽度自适应可用空间（不再写死 348dp——窄屏下固定宽度会溢出、右边被挤到贴边）。
        // PAGE_COUNT 段等宽 + (PAGE_COUNT-1) 段间隙，色块位移用同一套尺寸算，左右内边距严格对称。
        val outerPad = 4.dp
        val gap = 8.dp
        val containerW = minOf(maxWidth, 360.dp) // 上限 360dp，宽屏也不至于过宽
        val innerW = containerW - outerPad * 2
        val segW = (innerW - gap * (PAGE_COUNT - 1)) / PAGE_COUNT
        val stepPx = with(LocalDensity.current) { (segW + gap).toPx() }
        val segPx = with(LocalDensity.current) { segW.toPx() }

        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            ),
            modifier = Modifier.width(containerW),
        ) {
            Box(
                Modifier
                    .padding(outerPad)
                    .height(44.dp)
                    // 在 tab 栏上横滑：只累加本地 dragOffset 让色块跟手，绝不驱动页面位置。
                    // key 用 stepPx：尺寸变化时重建闭包，避免捕获旧 segW/stepPx
                    .pointerInput(stepPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { startOffset ->
                                // 打断上一次未完成的归零动画
                                settleJob?.cancel()
                                // 按下瞬间色块立即跳到手指底下：落点 x 换算成 tab 位置，
                                // 以色块中心对齐落点；clamp 保证色块不出栏。页面不动，等松手再决定切换。
                                val desiredPos = ((startOffset.x - segPx / 2f) / stepPx)
                                    .coerceIn(0f, LAST_PAGE.toFloat())
                                dragOffset = desiredPos - pageState.value
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val curPage = pageState.value
                                // dragAmount 是本次事件增量，累加；clamp 使色块停在栏内
                                dragOffset = (dragOffset + dragAmount / stepPx)
                                    .coerceIn(-curPage, LAST_PAGE.toFloat() - curPage)
                            },
                            onDragEnd = {
                                // 按色块最终位置吸附到最近 tab：只回调外部切页，自己不动页面位置
                                val raw = (pageState.value + dragOffset).coerceIn(0f, LAST_PAGE.toFloat())
                                onSelect(raw.roundToInt().coerceIn(0, LAST_PAGE))
                                settle()
                            },
                            onDragCancel = { settle() },
                        )
                    }
            ) {
                // 激活块（跟随页面位置 + tab 拖动偏移；拖动期间页面不动，只有色块跟手）
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(segW)
                        .graphicsLayer { translationX = visualPos * stepPx }
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer),
                )
                // tab items（item 之间以容器内边距等宽的间隙隔开）
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    items.forEachIndexed { i, (label, iconRes) ->
                        val active = visualPos > i - 0.5f && visualPos < i + 0.5f
                        val fg = if (active) androidx.compose.material3.MaterialTheme.colorScheme.primary
                        else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        // 无 indication：点击不出淡灰 ripple，仅色块与文字色变化表示激活
                        val noRipple = remember { MutableInteractionSource() }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clickable(
                                    interactionSource = noRipple,
                                    indication = null,
                                    onClick = { onSelect(i) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.Image(
                                    androidx.compose.ui.res.painterResource(iconRes), null, Modifier.size(15.dp),
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(fg),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    label, fontSize = 12.5.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = fg, maxLines = 1, softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
