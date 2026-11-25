package com.knotyup.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 引导页通用「模拟手机」外壳：机身 + 状态栏 + 侧边按键（电源/音量）+ 屏幕内容 slot。
 * 各页只传屏幕内动画内容，外壳统一。无刘海，机身在可用空间内竖直居中。
 */
@Composable
internal fun PhoneMock(
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    screen: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 机身高度取可用高度的 82%，宽高比约 1:1.95，竖直居中
        val h = maxHeight * 0.82f
        val w = h * 0.513f
        val bodyColor = if (dark) Color(0xFF1A1A1C) else Color(0xFFD8D6E0)
        val screenBg = if (dark) MaterialTheme.colorScheme.surface else Color.White
        val keyColor = bodyColor

        // 机身 + 侧键同处一个居中 Box，侧键用 offset 贴到边缘外
        Box(contentAlignment = Alignment.Center) {
            // 机身
            Box(
                Modifier
                    .size(width = w, height = h)
                    .clip(RoundedCornerShape(26.dp))
                    .background(bodyColor)
                    .padding(6.dp),
            ) {
                // 屏幕
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(21.dp))
                        .background(screenBg),
                ) {
                    // 状态栏：极简左边时间 + 右边信号点（装饰，无刘海）
                    Box(Modifier.fillMaxSize()) {
                        RowStatusBar(dark = dark)
                        Box(Modifier.fillMaxSize().padding(top = 18.dp)) { screen() }
                    }
                }
            }
            // 左侧：音量键（两段等大），贴在机身左缘外，整体上移
            Column(
                Modifier
                    .offset(x = -w / 2f - 3.dp, y = -h * 0.16f)
                    .width(3.dp),
            ) {
                Box(Modifier.height(h * 0.11f).width(3.dp).clip(RoundedCornerShape(2.dp)).background(keyColor))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.height(h * 0.11f).width(3.dp).clip(RoundedCornerShape(2.dp)).background(keyColor))
            }
            // 右侧：电源键，同样上移
            Box(
                Modifier
                    .offset(x = w / 2f + 3.dp, y = -h * 0.13f)
                    .height(h * 0.15f)
                    .width(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(keyColor),
            )
        }
    }
}

/** 极简状态栏（无刘海）：左侧时间，右侧信号点。 */
@Composable
private fun RowStatusBar(dark: Boolean) {
    val c = Color(0xFF888780)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("9:41", fontSize = 9.sp, color = c)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(5.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(3.dp))
        Box(Modifier.size(5.dp).clip(CircleShape).background(c))
    }
}
