package com.knotyup.android.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.knotyup.android.R
import com.knotyup.android.clipboard.ClipboardCapability
import com.knotyup.android.data.AppState
import com.knotyup.android.net.DeviceInfo
import com.knotyup.android.net.GroupAd
import com.knotyup.android.ui.Strings

/** 弹窗集合：确认 / PIN 入群 / 群二维码 / Shizuku 引导 / 入群 Loading。 */

/** KnotConfirm 内部使用的文案辅助（避免在非参数位置捕获 t）。 */
private fun t0(key: String): String = Strings.t(Strings.currentLang, key)

/** 桌面端同款确认弹窗：半透明遮罩 + 白卡 + 主操作/危险操作按钮。可选成员勾选（群主转让）。 */
@Composable
internal fun KnotConfirm(
    title: String,
    message: String,
    okText: String,
    cancelText: String,
    danger: Boolean = true,
    members: List<DeviceInfo> = emptyList(),
    onOk: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogCard(Modifier.padding(horizontal = 40.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(message, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
                    // 可选成员勾选列表
                    if (members.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        members.forEach { m ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected == m.id) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { selected = if (selected == m.id) null else m.id }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selected == m.id,
                                    onCheckedChange = { selected = if (it) m.id else null },
                                )
                                Text(m.name, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) { Text(cancelText, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        Button(
                            onClick = { onOk(selected) },
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            colors = if (danger) ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFDEEF0),
                                contentColor = Color(0xFFD94559),
                            ) else ButtonDefaults.buttonColors(),
                        ) { Text(if (selected != null) t0("transfer_leave") else okText) }
                    }
                }
            }
        }
    }
}

/** 加入群（PIN）弹窗，风格与桌面端一致。 */
@Composable
internal fun PinDialog(
    t: (String) -> String,
    group: GroupAd,
    onJoin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pinValue by remember { mutableStateOf("") }
    // 键盘收起要用弹窗自身窗口的 token
    val dialogView = LocalView.current
    val dialogContext = LocalContext.current
    fun hideKeyboard() {
        val imm = dialogContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(dialogView.windowToken, 0)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogCard(Modifier.padding(horizontal = 40.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(t("join_group"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    // 群信息：两行裸文本（上行群名，下行 IP:端口），无容器包裹
                    Text(
                        group.name,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        group.addr,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinValue, onValueChange = { pinValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(t("pin_hint"), fontSize = 13.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(10.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) { Text(t("confirm_cancel"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        Button(
                            onClick = {
                                if (pinValue.isBlank()) {
                                    AppState.toast(t("fill_pin"), 2)
                                } else {
                                    hideKeyboard()
                                    onJoin(pinValue.trim())
                                }
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) { Text(t("btn_join"), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** 群信息二维码弹窗：上二维码 + 下群地址/PIN（对齐桌面端分享弹层）。 */
@Composable
internal fun QrDialog(t: (String) -> String, addr: String, pin: String, onClose: () -> Unit) {
    val payload = org.json.JSONObject().apply {
        put("v", 1); put("app", "knotyup"); put("addr", addr); put("pin", pin)
    }.toString()
    val qrBitmap = remember(payload) { generateQrBitmap(payload, 512) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogCard(Modifier.padding(horizontal = 40.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
                onClick = onClose,
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    qrBitmap?.let {
                        Image(
                            it.asImageBitmap(),
                            null,
                            Modifier.size(216.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    QrInfoRow(t("group_addr"), addr)
                    Spacer(Modifier.height(6.dp))
                    QrInfoRow(t("group_pin"), pin)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        t("qr_hint"), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** ZXing 生成二维码 Bitmap（含白底）。 */
internal fun generateQrBitmap(text: String, size: Int): android.graphics.Bitmap? {
    return runCatching {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
        )
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) 0xFF1D2129.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        bmp
    }.getOrNull()
}

/** Shizuku 后台剪贴板引导弹窗：说明 + 打开 Shizuku + 授权；授权成功自动关闭。 */
@Composable
internal fun ShizukuGuideDialog(
    t: (String) -> String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val active = ClipboardCapability.isShizukuActive()
    // 授权成功（模式翻转为 SHIZUKU）→ 自动关闭弹窗
    val clipMode by com.knotyup.android.data.AppState.clipMode.collectAsState()
    LaunchedEffect(clipMode) {
        if (clipMode == ClipboardCapability.Mode.SHIZUKU) onDismiss()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogCard(Modifier.padding(horizontal = 40.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(t("shizuku_guide_title"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t("shizuku_guide_body"),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row {
                        // 打开 Shizuku App（已激活则隐藏此按钮）
                        if (!active) {
                            Text(
                                t("open_shizuku"),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        runCatching {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_MAIN
                                                ).apply {
                                                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                                                    setPackage("moe.shizuku.privileged.api")
                                                }
                                            )
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        // 授权（需 Shizuku 已运行）
                        Text(
                            t("grant_permission"),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    ClipboardCapability.requestShizukuPermission()
                                }
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                .padding(vertical = 10.dp),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** 不可关闭的等待弹窗（加入群 / 转让群共用，文案键可换）。 */
@Composable
internal fun JoiningDialog(t: (String) -> String, textKey: String = "joining") {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        DialogCard {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 32.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        t(textKey), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
