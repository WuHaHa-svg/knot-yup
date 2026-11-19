package com.knotyup.android.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knotyup.android.net.Discovery
import com.knotyup.android.ui.components.KnotEnter
import kotlinx.coroutines.delay

/**
 * 首次启动引导（单页）：欢迎头部 + 实时准备清单 + 开始使用。
 *
 * 清单项是「状态检查」而非权限轰炸：
 * - 局域网：live 轮询，连上 Wi-Fi 自动打勾（不阻塞开始，仅提示）
 * - 通知权限：仅 API 33+ 展示（以下版本系统默认授予）；全 App 唯一申请入口
 *   （入群时不再重复弹框）；既用于常驻同步通知，也用于后台剪贴板悬浮提醒。
 *   priming 文案在前、系统弹窗在后；用户拒绝过系统弹窗后按钮转为「去系统设置」深跳
 * 底部「重要说明」为按问题场景的国产 ROM 手动设置引导：
 * ob_note_body_1 扫描问题 → 本地网络权限；ob_note_body_2 通知问题 → 悬浮通知权限。
 */
@Composable
internal fun OnboardingScreen(
    t: (String) -> String,
    onStart: () -> Unit,
) {
    val context = LocalContext.current

    // ── 局域网：live 检查（每 1s 重试，连上自动打勾）──
    var lanOk by remember { mutableStateOf(Discovery.localIpAddress() != null) }
    LaunchedEffect(lanOk) {
        while (!lanOk) {
            delay(1_000)
            lanOk = Discovery.localIpAddress() != null
        }
    }

    // ── 运行时权限（API 33+）：通知（常驻同步通知）+ 附近设备（局域网发现）──
    // priming → 系统弹窗；拒绝过 → 不烧第二次机会，按钮转「去系统设置」深跳
    val needRuntimePerms = Build.VERSION.SDK_INT >= 33
    var notifGranted by remember {
        mutableStateOf(!needRuntimePerms || hasPermission(context, android.Manifest.permission.POST_NOTIFICATIONS))
    }
    var notifDenied by remember { mutableStateOf(false) }
    val notifLauncher = permissionLauncher(
        onResult = { granted ->
            notifGranted = granted
            if (!granted) notifDenied = true
        },
    )
    var nearbyGranted by remember {
        mutableStateOf(!needRuntimePerms || hasPermission(context, android.Manifest.permission.NEARBY_WIFI_DEVICES))
    }
    var nearbyDenied by remember { mutableStateOf(false) }
    val nearbyLauncher = permissionLauncher(
        onResult = { granted ->
            nearbyGranted = granted
            if (!granted) nearbyDenied = true
        },
    )

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 欢迎头部：品牌渐变标题 + 副标题
        KnotEnter(delayMillis = 0) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val accent = MaterialTheme.colorScheme.primary
                val accent2 = lerp(accent, Color(0xFFA855F7), 0.45f)
                Text(
                    t("onboard_title"),
                    fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(
                        brush = Brush.linearGradient(listOf(accent, accent2)),
                    ),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    t("onboard_sub"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // ── 准备清单卡 ──
        KnotEnter(delayMillis = 90, modifier = Modifier.fillMaxWidth()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(t("onboard_checklist"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))

                    // 局域网
                    CheckRow(
                        title = t("ob_lan"),
                        done = lanOk,
                        status = if (lanOk) t("ob_lan_ok") else t("ob_lan_hint"),
                    )
                    Spacer(Modifier.height(12.dp))

                    // 通知权限（33+；以下版本系统默认授予，直接显示已就绪）
                    if (needRuntimePerms) {
                        PermRow(
                            t = t,
                            title = t("ob_notif"),
                            hint = t("ob_notif_hint"),
                            granted = notifGranted,
                            denied = notifDenied,
                            context = context,
                            onGrant = { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                        )
                    } else {
                        CheckRow(title = t("ob_notif"), done = true, status = t("ob_notif_ok_low_api"))
                    }
                    Spacer(Modifier.height(12.dp))

                    // 附近设备权限（33+）：局域网发现扫描需要；以下版本无此运行时权限
                    if (needRuntimePerms) {
                        PermRow(
                            t = t,
                            title = t("ob_nearby"),
                            hint = t("ob_nearby_hint"),
                            granted = nearbyGranted,
                            denied = nearbyDenied,
                            context = context,
                            onGrant = { nearbyLauncher.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES) },
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    // 国产 ROM 私有权限提示（系统设置里的安全中心开关，App 侧无法主动申请）
                    Text(t("ob_note_title"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        t("ob_note_body_1"),
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        t("ob_note_body_2"),
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // 开始使用：准备清单全部就绪才可点击（局域网 + 通知 + 附近设备；低版本权限项默认就绪）
        val ready = lanOk && notifGranted && nearbyGranted
        KnotEnter(delayMillis = 180) {
            Button(
                onClick = onStart,
                enabled = ready,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            ) { Text(t("ob_start"), fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** 运行时权限清单行：priming 文案 + 授权/已就绪/去设置三态按钮。 */
@Composable
private fun PermRow(
    t: (String) -> String,
    title: String,
    hint: String,
    granted: Boolean,
    denied: Boolean,
    context: android.content.Context,
    onGrant: () -> Unit,
) {
    CheckRow(title = title, done = granted, status = hint) {
        if (granted) {
            OutlinedButton(
                onClick = {},
                enabled = false,
                shape = RoundedCornerShape(9.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(t("ob_granted"), fontSize = 11.sp, maxLines = 1) }
        } else if (denied) {
            // 系统弹窗已被拒：不烧第二次机会，转系统设置
            OutlinedButton(
                onClick = { openAppSettings(context) },
                shape = RoundedCornerShape(9.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(t("ob_settings"), fontSize = 11.sp, maxLines = 1) }
        } else {
            Button(
                onClick = onGrant,
                shape = RoundedCornerShape(9.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(t("ob_grant"), fontSize = 11.sp, maxLines = 1) }
        }
    }
}

/** 权限请求 launcher 封装（结果回调统一处理）。 */
@Composable
private fun permissionLauncher(onResult: (Boolean) -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { onResult(it) }

/** 清单行：状态圆点 + 标题/副文字 + 右侧动作。 */
@Composable
private fun CheckRow(
    title: String,
    done: Boolean,
    status: String,
    action: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(9.dp).background(
                if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                CircleShape,
            )
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                status, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action()
    }
}

private fun hasPermission(context: android.content.Context, permission: String): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
        )
    } catch (_: ActivityNotFoundException) {
    }
}
