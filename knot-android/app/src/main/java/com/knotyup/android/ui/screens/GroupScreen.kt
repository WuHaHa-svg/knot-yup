package com.knotyup.android.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
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
    onKick: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSend: () -> Unit,
    onLeave: () -> Unit,
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // 已播过挂载动画的条目时间戳：提升到 pager 之外，HorizontalPager 切页 dispose 页面时不丢失。
    // 切回剪贴板页时列表项据此直接以 visible 初始，不会重播动画（只有真正新条目才播）。
    val seenClipItems = remember { mutableSetOf<Long>() }
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
        // 内容区：全屏延伸，左右滑动切换 Tab；页间转场（缩放 + 淡入淡出视差）
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageOffset = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).absoluteValue.coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 离场页明显缩小 + 变淡，进场页恢复 —— 层次感转场
                        val scale = 1f - 0.12f * pageOffset
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.75f * pageOffset
                    }
            ) {
                when (page) {
                    0 -> DevicesTab(t, members, role, groupAddr, groupPin, onKick, onLeave)
                    else -> ClipboardTab(t, members, history, sendText, seenClipItems, onCopy, onSendText, onSend)
                }
            }
        }
        // 悬浮 Tab 栏：点击切换；入场从底部升起（AnimatedVisibility 原生合成阴影）
        KnotEnter(
            delayMillis = 220,
            slideDistance = 0.5f,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            GroupTabBar(
                t = t,
                page = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                onSelect = { i -> scope.launch { pagerState.animateScrollToPage(i) } },
            )
        }
    }
}

/** 独立悬浮分段 Tab 栏：容器 + tab item + 激活指示块。
 *
 *  交互完全自治，不直接操作 pager（悬浮组件与内容页解耦）：
 *  - 点 item / 内容页左右滑：page 由外部传入（pager 位置），色块跟随；
 *  - 在 tab 栏上横滑：只改本地 [dragOffset] 让色块跟手，页面纹丝不动；
 *  - 松手：按色块最终位置算目标 tab，回调 [onSelect] 让外部切页，同时色块偏移动画归零。
 *  拖动期间零携程操作 pager，避免高频 launch 冲击 pager 状态机导致崩溃。 */
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
    )
    // tab 拖动产生的本地页偏移（单位=tab）；仅影响色块显示，不驱动 pager。
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val settleAnim = remember { Animatable(0f) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // pointerInput 闭包只启动一次，会捕获创建时的 page（旧值）。用 rememberUpdatedState
    // 让闭包每次读到最新 page——否则在剪切板页(page=1)拖动仍用旧的 page=0 做 clamp，色块不动。
    val pageState = rememberUpdatedState(page)
    // 色块视觉位置：pager 位置 + 拖动偏移，clamp 到 [0,1]
    val visualPos = (page + dragOffset).coerceIn(0f, 1f)

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

    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val containerW = 248.dp
        val gap = 8.dp // item 间隔 = 容器内边距
        val segW = (containerW - 8.dp - gap) / 2

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
                    .padding(4.dp)
                    .height(44.dp)
                    // 在 tab 栏上横滑：只累加本地 dragOffset 让色块跟手，绝不驱动 pager。
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { startOffset ->
                                // 打断上一次未完成的归零动画
                                settleJob?.cancel()
                                // 按下瞬间色块立即跳到手指底下：落点 x 换算成 tab 位置，
                                // 以色块中心对齐落点；clamp 保证色块不出栏。页面不动，等松手再决定切换。
                                val stepPx = (segW + gap).toPx()
                                val desiredPos = ((startOffset.x - segW.toPx() / 2f) / stepPx)
                                    .coerceIn(0f, 1f)
                                dragOffset = desiredPos - pageState.value
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val stepPx = (segW + gap).toPx()
                                val curPage = pageState.value
                                // dragAmount 是本次事件增量，累加；clamp 使色块停在栏内
                                dragOffset = (dragOffset + dragAmount / stepPx)
                                    .coerceIn(-curPage, 1f - curPage)
                            },
                            onDragEnd = {
                                // 按色块最终位置吸附到最近 tab：只回调外部切页，自己不动 pager
                                val raw = (pageState.value + dragOffset).coerceIn(0f, 1f)
                                onSelect(raw.roundToInt().coerceIn(0, 1))
                                settle()
                            },
                            onDragCancel = { settle() },
                        )
                    }
            ) {
                // 激活块（跟随 pager 位置 + tab 拖动偏移；拖动期间页面不动，只有色块跟手）
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(segW)
                        .graphicsLayer { translationX = visualPos * (segW + gap).toPx() }
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
