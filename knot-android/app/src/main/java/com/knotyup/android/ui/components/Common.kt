package com.knotyup.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import com.knotyup.android.ui.Motion

/** 通用小组件：弹窗卡片容器 / 挂载入场 / 信息行 / 卡片头 / 顶栏小圆钮 / 紧凑输入框。 */

/** 紧凑单行输入框：BasicTextField 自绘描边（M3 OutlinedTextField 无 contentPadding 参数，
 *  内边距固定 16dp 偏松）；此处水平 10 / 垂直 8，聚焦描边切主题色。 */
@Composable
internal fun KnotInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        interactionSource = interaction,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { inner ->
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}

/** 弹窗卡片入场动画：官方 AnimatedVisibility（scaleIn + fadeIn），阴影原生合成。 */
@Composable
internal fun DialogCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val visibleState = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = Motion.dialogEnter(),
    ) {
        content()
    }
}

/** 挂载入场动画：官方 AnimatedVisibility（fade + slide），阴影由框架原生合成、无闪烁。 */
@Composable
fun KnotEnter(
    delayMillis: Int = 0,
    slideDistance: Float = 0.22f, // 相对自身高度的位移比例
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val visibleState = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = Motion.mountEnter(delayMillis, slideDistance),
    ) {
        content()
    }
}

/** 群信息卡内的「标签 + 值」行（值用等宽字体，便于核对地址/PIN）。 */
@Composable
internal fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

/** 卡片头：彩色图标 + 标题 + 副文字（对齐桌面端卡片结构）。 */
@Composable
internal fun CardHead(iconRes: Int, iconBg: Color, title: String, subtitle: String, modifier: Modifier = Modifier) {
    // 深色模式：浅色 chip 底会刺眼，降为同色低透明度（品牌色图标在深浅底都可见）
    val chipBg = if (androidx.compose.foundation.isSystemInDarkTheme()) iconBg.copy(alpha = 0.22f) else iconBg
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(chipBg),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text(subtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

/** 顶栏小圆钮（30dp 圆角、描边、居中内容），对应桌面端 .icon-btn。 */
@Composable
internal fun HeaderIconBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(30.dp),
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) { content() }
    }
}
