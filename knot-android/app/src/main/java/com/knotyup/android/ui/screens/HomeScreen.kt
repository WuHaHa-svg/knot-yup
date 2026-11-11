package com.knotyup.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knotyup.android.R
import com.knotyup.android.net.GroupAd
import com.knotyup.android.ui.components.CardHead
import com.knotyup.android.ui.components.KnotEnter

/** 首页：Hero + 双卡片（对齐桌面端布局逻辑；窄屏上下、宽屏左右）；液态流动背景与开屏一致。 */
@Composable
internal fun HomeBody(
    t: (String) -> String,
    wide: Boolean,
    discovered: List<GroupAd>,
    addr: String, pin: String, joining: Boolean,
    onAddr: (String) -> Unit, onPin: (String) -> Unit,
    onPickGroup: (GroupAd) -> Unit,
    onJoin: () -> Unit, onCreate: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        // 液态流动背景（共享组件，与开屏同款；不随内容滚动）
        com.knotyup.android.ui.components.LiquidBackground()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 36.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // 顶部对齐：容器高度变化只把下方元素往下挤，上方元素位置稳定不跳
            verticalArrangement = Arrangement.Top,
        ) {
        // Hero：主标题（第二段渐变）+ 副标题
        val accent = MaterialTheme.colorScheme.primary
        val accent2 = lerp(accent, Color(0xFFA855F7), 0.45f)
        KnotEnter(delayMillis = 0) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp)) { append(t("tagline_1")) }
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold, fontSize = 26.sp,
                            brush = Brush.linearGradient(listOf(accent, accent2)),
                        )
                    ) { append(t("tagline_2")) }
                },
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(6.dp))
        KnotEnter(delayMillis = 70) {
            Text(
                t("tagline_sub"), fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))

        if (wide) {
            KnotEnter(delayMillis = 140, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    JoinCard(t, addr, pin, joining, Modifier.weight(1f), onAddr, onPin, onJoin, onCreate)
                    DiscoveredCard(t, discovered, Modifier.weight(1f), onPickGroup)
                }
            }
        } else {
            KnotEnter(delayMillis = 140, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                JoinCard(t, addr, pin, joining, Modifier.fillMaxWidth(), onAddr, onPin, onJoin, onCreate)
            }
            Spacer(Modifier.height(16.dp))
            KnotEnter(delayMillis = 210, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                DiscoveredCard(t, discovered, Modifier.fillMaxWidth(), onPickGroup)
            }
        }
        }
    }
}

/** 左卡：加入 / 创建。 */
@Composable
private fun JoinCard(
    t: (String) -> String,
    addr: String, pin: String, joining: Boolean,
    modifier: Modifier,
    onAddr: (String) -> Unit, onPin: (String) -> Unit,
    onJoin: () -> Unit, onCreate: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        // 半透明：让液态背景的色彩从卡片后面微微透出（溶解感）
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shadowElevation = 0.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(20.dp)) {
            CardHead(
                iconRes = R.drawable.ic_link,
                iconBg = Color(0xFFF0FDF4),
                title = t("join_group"),
                subtitle = t("join_desc"),
            )
            OutlinedTextField(
                value = addr, onValueChange = onAddr, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(t("addr_hint"), fontSize = 13.sp) }, singleLine = true,
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pin, onValueChange = onPin, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(t("pin_hint"), fontSize = 13.sp) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCreate,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Image(
                        painterResource(R.drawable.ic_plus), null, Modifier.size(14.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(6.dp))
                    // 通用处理：单行不换行（固定高度 + 换行会被裁切），宽度不足时省略号
                    Text(t("btn_create"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = onJoin, enabled = !joining,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Text(t("btn_join"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 右卡：局域网发现的群（上下结构列表项，点击弹 PIN）。 */
@Composable
private fun DiscoveredCard(
    t: (String) -> String,
    discovered: List<GroupAd>,
    modifier: Modifier,
    onPickGroup: (GroupAd) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        // 半透明：让液态背景的色彩从卡片后面微微透出（溶解感）
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shadowElevation = 0.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(20.dp)) {
            CardHead(
                iconRes = R.drawable.ic_devices,
                iconBg = Color(0xFFEFF6FF),
                title = t("discovered"),
                subtitle = t("discovering"),
            )
            if (discovered.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 空态图标不着色（灰色半透明，与桌面端一致）
                        Image(
                            painterResource(R.drawable.ic_devices), null,
                            Modifier.size(28.dp),
                            colorFilter = ColorFilter.tint(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t("no_discovered"), fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                ) {
                    discovered.forEach { g ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPickGroup(g) }
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(6.dp).background(
                                    MaterialTheme.colorScheme.primary,
                                    CircleShape,
                                )
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    g.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    g.addr, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
