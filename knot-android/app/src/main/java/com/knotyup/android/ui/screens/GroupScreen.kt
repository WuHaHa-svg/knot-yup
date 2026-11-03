package com.knotyup.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.knotyup.android.clipboard.ClipboardCapability
import com.knotyup.android.data.HistoryItem
import com.knotyup.android.net.DeviceInfo
import com.knotyup.android.ui.components.KnotEnter
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** 群页面：左右分页（群信息 / 剪贴板）+ 悬浮 Tab 栏。 */
@Composable
internal fun GroupBody(
    t: (String) -> String,
    members: List<DeviceInfo>,
    history: List<HistoryItem>,
    sendText: String,
    role: String,
    groupAddr: String,
    groupPin: String,
    clipMode: ClipboardCapability.Mode,
    onModeClick: () -> Unit,
    onKick: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSend: () -> Unit,
    onLeave: () -> Unit,
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
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
                    0 -> DevicesTab(t, members, role, groupAddr, groupPin, clipMode, onModeClick, onKick, onLeave)
                    else -> ClipboardTab(t, members, history, sendText, onCopy, onSendText, onSend)
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

/** 悬浮分段 Tab 栏：容器 + tab item + 激活指示块（点击切换，块随页面偏移联动）。 */
@Composable
internal fun GroupTabBar(
    t: (String) -> String,
    page: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        t("group_info") to com.knotyup.android.R.drawable.ic_devices,
        t("clipboard") to com.knotyup.android.R.drawable.ic_clip,
    )
    val pos = page

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
            Box(Modifier.padding(4.dp).height(44.dp)) {
                // 激活块（可拖动元素）
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(segW)
                        .graphicsLayer { translationX = pos * (segW + gap).toPx() }
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer),
                )
                // tab items（item 之间以容器内边距等宽的间隙隔开）
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    items.forEachIndexed { i, (label, iconRes) ->
                        val active = pos > i - 0.5f && pos < i + 0.5f
                        val fg = if (active) androidx.compose.material3.MaterialTheme.colorScheme.primary
                        else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clickable { onSelect(i) },
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
