package com.knotyup.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knotyup.android.R
import com.knotyup.android.ui.Strings
import com.knotyup.android.ui.THEMES

/** 顶部栏：品牌 + 扫码/群二维码入口 + 主题色板 + 语言切换（对齐桌面端顶栏）。 */
@Composable
internal fun Header(
    onTheme: (String) -> Unit,
    onLang: (String) -> Unit,
    lang: String,
    themeId: String,
    onScan: () -> Unit,
    showScan: Boolean,
    showShare: Boolean,
    onShare: () -> Unit,
) {
    var themeOpen by remember { mutableStateOf(false) }
    var langOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌：渐变方块 + 绳结图标（对齐桌面端顶栏）
        val accent = MaterialTheme.colorScheme.primary
        val accent2 = lerp(accent, Color(0xFFA855F7), 0.45f)
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(accent, accent2))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_knot),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(Color.White),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("KnotYup", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.weight(1f))

        // 扫码加群（仅首页显示）
        if (showScan) {
            HeaderIconBtn(onClick = onScan) {
                Image(painterResource(R.drawable.ic_scan), "scan", Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
        }

        // 群信息二维码（仅群页面显示）
        if (showShare) {
            HeaderIconBtn(onClick = onShare) {
                Image(painterResource(R.drawable.ic_qr), "qr", Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
        }

        // 主题色：点击弹出锚定色板（与桌面端 pop 一致）
        Box {
            HeaderIconBtn(onClick = { themeOpen = true }) {
                Image(painterResource(R.drawable.ic_palette), "theme", Modifier.size(18.dp))
            }
            DropdownMenu(
                expanded = themeOpen,
                onDismissRequest = { themeOpen = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                // 4×2 色板网格，当前主题带描边圈标识（对齐桌面端 .swatch.active）
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    THEMES.chunked(4).forEach { rowThemes ->
                        Row {
                            rowThemes.forEach { (id, color) ->
                                val active = id == themeId
                                Box(
                                    Modifier
                                        .padding(5.dp)
                                        .size(if (active) 30.dp else 24.dp)
                                        .then(
                                            if (active) Modifier
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .border(
                                                    2.dp,
                                                    MaterialTheme.colorScheme.primary,
                                                    androidx.compose.foundation.shape.CircleShape,
                                                )
                                                .padding(3.dp)
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(color, androidx.compose.foundation.shape.CircleShape)
                                            .clickable { onTheme(id); themeOpen = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // 语言：点击弹出菜单，当前语言高亮
        Box {
            HeaderIconBtn(onClick = { langOpen = true }) {
                Text(
                    Strings.langLabels[lang] ?: "简",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    // 与扫码/主题图标同色调（#6B7280 系），顶栏入口视觉统一
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = langOpen,
                onDismissRequest = { langOpen = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Strings.langs.forEach { l ->
                    val active = l == lang
                    DropdownMenuItem(
                        text = {
                            Text(
                                Strings.langLabels[l] ?: l,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = if (active) {
                            { Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        } else null,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        onClick = { onLang(l); langOpen = false },
                    )
                }
            }
        }
    }
}
