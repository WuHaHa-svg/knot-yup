package com.knotyup.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knotyup.android.R
import com.knotyup.android.clipboard.ClipboardCapability
import com.knotyup.android.data.AppState
import com.knotyup.android.data.HistoryItem
import com.knotyup.android.net.DeviceInfo
import com.knotyup.android.ui.components.InfoRow

/** Tab 1：群信息卡（地址/PIN/剪贴板模式）+ 设备列表 + 退出群。 */
@Composable
internal fun DevicesTab(
    t: (String) -> String,
    members: List<DeviceInfo>,
    role: String,
    groupAddr: String,
    groupPin: String,
    clipMode: ClipboardCapability.Mode,
    onModeClick: () -> Unit,
    onKick: (String) -> Unit,
    onLeave: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 96.dp),
    ) {
        Text(t("group_info"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        // 群信息卡：地址 / PIN / 剪贴板模式（前台模式可点击看 Shizuku 引导）
        val shizuku = clipMode == ClipboardCapability.Mode.SHIZUKU
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            InfoRow(t("group_addr"), groupAddr, mono = true)
            Spacer(Modifier.height(7.dp))
            InfoRow(t("group_pin"), groupPin, mono = true)
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("clip_mode"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(6.dp).background(
                        if (shizuku) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(50),
                    )
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    t(if (shizuku) "shizuku_mode_hint" else "fg_mode_hint"),
                    fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    color = if (shizuku) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (!shizuku) {
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { onModeClick() }
                    } else Modifier,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(t("devices"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        members.forEach { m ->
            val self = m.id == AppState.device.id
            // 平台头像：macOS / Windows / Android 各自的图标与配色
            val (iconRes, bg) = when (m.platform) {
                "macos" -> R.drawable.ic_os_mac to Color(0xFFECECF1)
                "windows" -> R.drawable.ic_os_win to Color(0xFFE7F0FB)
                "android" -> R.drawable.ic_os_android to Color(0xFFE6F4EA)
                else -> R.drawable.ic_devices to MaterialTheme.colorScheme.surfaceVariant
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).background(bg, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(iconRes), null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        )
                        if (self) {
                            Spacer(Modifier.width(6.dp))
                            Image(
                                painter = painterResource(R.drawable.ic_devices),
                                contentDescription = "self",
                                modifier = Modifier.size(12.dp),
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                            )
                        }
                        if (m.isOwner) { // v2：群主身份看 role 属性（不再依赖数组位置）
                            Spacer(Modifier.width(6.dp))
                            Image(painterResource(R.drawable.ic_crown), "owner", Modifier.size(13.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(t("owner"), fontSize = 10.sp, color = Color(0xFFB45309))
                        }
                    }
                    Text(m.platform, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (role == "owner" && !self) {
                    TextButton(onClick = { onKick(m.id) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                        Text(t("kick"), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onLeave,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) { Text(t(if (role == "owner") "dissolve" else "leave"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
    }
}

/** Tab 2：剪贴板（历史 + 发送）。 */
@Composable
internal fun ClipboardTab(
    t: (String) -> String,
    members: List<DeviceInfo>,
    history: List<HistoryItem>,
    sendText: String,
    onCopy: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 96.dp),
    ) {
        Text(t("clipboard"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(t("click_hint"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (history.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(
                    "${t("empty_1")}\n${t("empty_2")}", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            history.forEach { item ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onCopy(item.text) }
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    val name = members.firstOrNull { it.id == item.from }?.name
                        ?: if (item.from == AppState.device.id) AppState.device.name else item.from.take(6)
                    Text(
                        name, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(item.text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = sendText, onValueChange = onSendText,
                modifier = Modifier.weight(1f),
                placeholder = { Text(t("send_hint"), fontSize = 13.sp) },
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSend, shape = RoundedCornerShape(10.dp), modifier = Modifier.height(52.dp)) {
                Text(t("send"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
