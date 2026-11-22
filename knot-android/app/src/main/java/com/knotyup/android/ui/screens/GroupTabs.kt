package com.knotyup.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knotyup.android.R
import com.knotyup.android.data.AppState
import com.knotyup.android.data.HistoryItem
import com.knotyup.android.data.TouchpadSettings
import com.knotyup.android.net.DeviceInfo
import com.knotyup.android.ui.Motion
import com.knotyup.android.ui.components.InfoRow
import com.knotyup.android.ui.components.KnotConfirm

/**
 * Tab 1：群信息卡（地址/PIN）+ 设备列表 + 退出群。
 *
 * 布局分两段：上部 [weight] 滚动区承载群信息与设备列表；退出/解散按钮固定在底部，
 * 不随列表滚动。外层 padding(bottom = 96.dp) 为悬浮 Tab 栏留出空间。
 */
@Composable
internal fun DevicesTab(
    t: (String) -> String,
    members: List<DeviceInfo>,
    role: String,
    groupAddr: String,
    groupPin: String,
    onKick: (String, String) -> Unit,
    onLeave: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 96.dp),
    ) {
        // ── 上部：滚动区（群信息 + 设备列表）──
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            Text(t("group_info"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            // 群信息卡：地址 / PIN
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                InfoRow(t("group_addr"), groupAddr, mono = true)
                Spacer(Modifier.height(7.dp))
                InfoRow(t("group_pin"), groupPin, mono = true)
            }
            Spacer(Modifier.height(10.dp))
            Text(t("devices"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            members.forEach { m ->
                val self = m.id == AppState.device.id
                // 平台头像：macOS / Windows / Android 各自的图标与配色
                // 深色模式：浅色 chip 底降为低透明度；mac 图标近黑，深色下 tint 提亮
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                val (iconRes, rawBg) = when (m.platform) {
                    "macos" -> R.drawable.ic_os_mac to Color(0xFFECECF1)
                    "windows" -> R.drawable.ic_os_win to Color(0xFFE7F0FB)
                    "android" -> R.drawable.ic_os_android to Color(0xFFE6F4EA)
                    else -> R.drawable.ic_devices to MaterialTheme.colorScheme.surfaceVariant
                }
                val bg = if (dark) rawBg.copy(alpha = 0.22f) else rawBg
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
                        Image(
                            painterResource(iconRes), null, Modifier.size(18.dp),
                            colorFilter = if (m.platform == "macos" && dark) {
                                ColorFilter.tint(Color(0xFFE8E8ED))
                            } else null,
                        )
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
                        TextButton(onClick = { onKick(m.id, m.name) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                            Text(t("kick"), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        // ── 下部：固定操作区（退出 / 解散）──
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onLeave,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) { Text(t(if (role == "owner") "dissolve" else "leave"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * Tab 2：剪贴板（上部历史列表 + 下部固定发送区）。
 *
 * 布局分两段：历史列表在 [weight] 滚动区；输入框 + 发送按钮固定在底部，
 * 键盘弹出时列表区收缩、输入区始终可见。外层 padding(bottom = 96.dp) 让出悬浮 Tab 栏。
 *
 * 挂载动画约束：三 tab 已重构为 Box 叠放、页面永不卸载，页内 remember 保活。
 * [seenClipItems] 作为双保险保留（已见条目不重播动画）；即便页面不 dispose，
 * 真新条目仍会首次播一次、切回时 shown 保持 true 不重播。勿移除该参数。
 */
@Composable
internal fun ClipboardTab(
    t: (String) -> String,
    members: List<DeviceInfo>,
    history: List<HistoryItem>,
    sendText: String,
    seenClipItems: MutableSet<Long>,
    onCopy: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 96.dp),
    ) {
        // ── 上部：滚动区（标题 + 历史列表）──
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
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
                    // key = 条目身份：新记录插入头部时 remember 跟着条目走，旧条目位移不重播。
                    // 动画只播一次：由 GroupBody 的 seenClipItems 决定初值——
                    // 首次见到的条目初值 false → 播动画；切页回来条目已在集合中 → 初值 true，不重播。
                    key(item.atMs) {
                        var shown by remember { mutableStateOf(item.atMs in seenClipItems) }
                        LaunchedEffect(Unit) {
                            seenClipItems.add(item.atMs)
                            shown = true
                        }
                        AnimatedVisibility(
                            visible = shown,
                            enter = fadeIn(tween(220)) +
                                slideInVertically(tween(240, easing = Motion.Easing)) { -it / 3 } +
                                expandVertically(tween(200)),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onCopy(item.text) }
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    item.name, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(item.text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        // ── 下部：固定发送区（输入框 + 软键盘发送键）──
        // 发送动作交给键盘右下角的 Send 键（不同输入法标签可能是「发送/Send/回车」），
        // 屏幕上不再放独立按钮，输入框占满宽度。
        Spacer(Modifier.height(12.dp))
        com.knotyup.android.ui.components.KnotInput(
            value = sendText, onValueChange = onSendText,
            modifier = Modifier.fillMaxWidth(),
            placeholder = t("send_hint"),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
    }
}

/**
 * Tab 4：触摸板。上部设备列表（可连接的桌面设备）+ 下部三个设置开关。
 * 布局参照 [DevicesTab]（标题 + 卡片 + 同容器滚动）。
 *
 * T0（本期）只做 UI 骨架与本机一对一占用状态；真实 TCP 控制在 T1 接入。
 */
@Composable
internal fun TouchpadTab(t: (String) -> String, members: List<DeviceInfo>) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { TouchpadSettings.init(ctx) }

    // 设置开关本地 state（读自持久化；点击同时写回 TouchpadSettings）
    var haptics by remember { mutableStateOf(TouchpadSettings.haptics) }
    var oled by remember { mutableStateOf(TouchpadSettings.oled) }
    var particles by remember { mutableStateOf(TouchpadSettings.particles) }

    // 待确认连接的设备；确认后只置全局控制状态，控制页由 MainActivity 的独立全屏覆盖层渲染
    var pendingDevice by remember { mutableStateOf<DeviceInfo?>(null) }

    val desktops = members.filter { it.platform == "windows" || it.platform == "macos" }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 96.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            // ── 设备列表 ──
            Text(t("touchpad_devices"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            if (desktops.isEmpty()) {
                Text(t("touchpad_no_devices"), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            desktops.forEach { m ->
                val (iconRes, rawBg) = when (m.platform) {
                    "macos" -> R.drawable.ic_os_mac to Color(0xFFECECF1)
                    else -> R.drawable.ic_os_win to Color(0xFFE7F0FB)
                }
                val bg = if (dark) rawBg.copy(alpha = 0.22f) else rawBg
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { pendingDevice = m }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).background(bg, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center) {
                        Image(painterResource(iconRes), null, Modifier.size(18.dp),
                            colorFilter = if (m.platform == "macos" && dark) ColorFilter.tint(Color(0xFFE8E8ED)) else null)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        Text(m.platform, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(t("touchpad_connect"), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(12.dp))
            // ── 触摸板设置列表 ──
            Text(t("touchpad_settings"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                SettingSwitch(t("touchpad_haptics"), t("touchpad_haptics_tip"), haptics, t, onToggle = {
                    haptics = it; TouchpadSettings.haptics = it
                })
                SettingSwitch(t("touchpad_oled"), t("touchpad_oled_tip"), oled, t, onToggle = {
                    oled = it; TouchpadSettings.oled = it
                })
                SettingSwitch(t("touchpad_particles"), t("touchpad_particles_tip"), particles, t, onToggle = {
                    particles = it; TouchpadSettings.particles = it
                })
            }
        }
    }

    // 连接确认弹窗（复用统一组件）
    pendingDevice?.let { dev ->
        KnotConfirm(
            title = t("touchpad"),
            message = t("touchpad_confirm_connect").replace("{name}", dev.name),
            okText = t("confirm_ok"),
            cancelText = t("confirm_cancel"),
            danger = false,
            onOk = { AppState.setTouchpadControlling(dev.id); pendingDevice = null },
            onCancel = { pendingDevice = null },
        )
    }
}

/** 设置列表一行：标题 + 右侧开关。 */
@Composable
private fun SettingSwitch(
    title: String,
    tip: String,
    checked: Boolean,
    t: (String) -> String,
    onToggle: (Boolean) -> Unit,
) {
    // 整行可点击切换；左侧文本分 title + tip 两段，右侧用状态文字替代 Switch 组件
    Row(
        Modifier.fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            Text(tip, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            t(if (checked) "setting_on" else "setting_off"),
            fontSize = 12.sp,
            color = if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
