package com.knotyup.android.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knotyup.android.net.Discovery
import com.knotyup.android.ui.Strings
import com.knotyup.android.ui.components.PhoneMock
import com.knotyup.android.ui.components.SceneLan
import com.knotyup.android.ui.components.SceneBattery
import com.knotyup.android.ui.components.SceneNearby
import com.knotyup.android.ui.components.SceneNotification
import com.knotyup.android.ui.components.SceneReady
import com.knotyup.android.ui.components.SceneWelcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 首次启动引导：全屏 swiper（5 页），强制顺序。
 *
 * - 每页：大标题 + 描述 + 动态模拟手机屏幕（独立动画）+ 分页点 + 动作按钮；
 * - 强制顺序：只有完成当前页动作才解锁下一页（maxUnlocked），手势不能越界；
 * - 局域网 live 轮询；通知/附近设备权限 API 33+ 申请，拒绝后按钮转「去系统设置」；
 * - 末页「开始使用」需全部就绪；国产 ROM 长文说明收进末页折叠区。
 */
@Composable
internal fun OnboardingScreen(
    t: (String) -> String,
    lang: String,
    onLang: (String) -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 状态检查（与旧版一致）──
    var lanOk by remember { mutableStateOf(Discovery.localIpAddress() != null) }
    LaunchedEffect(lanOk) {
        while (!lanOk) {
            delay(1_000)
            lanOk = Discovery.localIpAddress() != null
        }
    }
    val needRuntimePerms = Build.VERSION.SDK_INT >= 33
    var notifGranted by remember {
        mutableStateOf(!needRuntimePerms || hasPermission(context, android.Manifest.permission.POST_NOTIFICATIONS))
    }
    var notifDenied by remember { mutableStateOf(false) }
    val notifLauncher = permissionLauncher { granted ->
        notifGranted = granted
        if (!granted) notifDenied = true
    }
    var nearbyGranted by remember {
        mutableStateOf(!needRuntimePerms || hasPermission(context, android.Manifest.permission.NEARBY_WIFI_DEVICES))
    }
    var nearbyDenied by remember { mutableStateOf(false) }
    val nearbyLauncher = permissionLauncher { granted ->
        nearbyGranted = granted
        if (!granted) nearbyDenied = true
    }

    // ── swiper 状态 ──
    var page by remember { mutableIntStateOf(0) }
    var maxUnlocked by remember { mutableIntStateOf(0) }
    val pageAnim = remember { Animatable(0f) }
    var pagePos by remember { mutableFloatStateOf(0f) }
    var noteExpanded by remember { mutableStateOf(true) }

    fun goTo(target: Int) {
        val dest = target.coerceIn(0, 5).toFloat()
        page = target.toFloat().roundToInt()
        scope.launch { pageAnim.animateTo(dest, tween(300)) { pagePos = value } }
    }
    fun unlockNext() {
        if (page < 5) {
            maxUnlocked = maxOf(maxUnlocked, page + 1)
            goTo(page + 1)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))

        // ── 5 页叠放，按 pagePos 位移 + 淡出 ──
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds() // 裁掉相邻页溢出的按钮/内容
        ) {
            listOf(
                @Composable { OnboardPage(index = 0, pos = pagePos,
                    title = t("ob_p0_title"), desc = t("ob_p0_desc"),
                    screen = { SceneWelcome(t) },
                    actionLabel = t("ob_next"), primaryAction = false,
                    onAction = { unlockNext() }) },
                @Composable { OnboardPage(index = 1, pos = pagePos,
                    title = t("ob_lan"), desc = if (lanOk) t("ob_lan_ok") else t("ob_lan_hint"),
                    screen = { SceneLan(lanOk, t) },
                    actionLabel = t("ob_next"), primaryAction = false,
                    onAction = { unlockNext() }) },
                @Composable { OnboardPage(index = 2, pos = pagePos,
                    title = t("ob_notif"), desc = t("ob_p_notif_desc"),
                    screen = { SceneNotification(t) },
                    actionLabel = when {
                        notifGranted -> t("ob_next")
                        notifDenied -> t("ob_settings")
                        else -> t("ob_grant")
                    },
                    primaryAction = !notifGranted && !notifDenied,
                    onAction = {
                        when {
                            notifGranted -> unlockNext()
                            notifDenied -> openAppSettings(context)
                            else -> notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }) },
                @Composable { OnboardPage(index = 3, pos = pagePos,
                    title = t("ob_nearby"), desc = t("ob_p_nearby_desc"),
                    screen = { SceneNearby(t) },
                    actionLabel = when {
                        nearbyGranted -> t("ob_next")
                        nearbyDenied -> t("ob_settings")
                        else -> t("ob_grant")
                    },
                    primaryAction = !nearbyGranted && !nearbyDenied,
                    onAction = {
                        when {
                            nearbyGranted -> unlockNext()
                            nearbyDenied -> openAppSettings(context)
                            else -> nearbyLauncher.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                        }
                    }) },
                @Composable { OnboardPage(index = 4, pos = pagePos,
                    title = t("ob_battery"), desc = t("ob_battery_desc"),
                    screen = { SceneBattery(t) },
                    actionLabel = t("ob_battery_go"), primaryAction = true,
                    secondaryLabel = t("ob_next"), onSecondary = { unlockNext() },
                    extra = {
                        Text(t("ob_battery_hint"), fontSize = 10.sp, lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    onAction = { openBatterySettings(context) }) },
                @Composable { OnboardPage(index = 5, pos = pagePos,
                    title = t("ob_p4_title"), desc = t("ob_p4_desc"),
                    screen = { SceneReady(t) },
                    actionLabel = t("ob_start"),
                    primaryAction = false,
                    extra = {
                        Text(
                            t("ob_note_title"),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { noteExpanded = !noteExpanded },
                        )
                        if (noteExpanded) {
                            Spacer(Modifier.height(4.dp))
                            Text(t("ob_note_body_1"), fontSize = 10.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(t("ob_note_body_2"), fontSize = 10.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onAction = { if (lanOk && notifGranted && nearbyGranted) onStart() }) },
            ).forEach { it() }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            repeat(6) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (i == page) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                i == page -> MaterialTheme.colorScheme.primary
                                i <= maxUnlocked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            }
                        )
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    // ── 左上角语言切换（复用 AppState 语言，切换即触发 MainActivity 重组）──
    var langMenuOpen by remember { mutableStateOf(false) }
    Box(Modifier.align(Alignment.TopStart).padding(top = 12.dp, start = 16.dp)) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .clickable { langMenuOpen = true }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                Strings.langLabels[lang] ?: "简",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = langMenuOpen,
            onDismissRequest = { langMenuOpen = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Strings.langs.forEach { l ->
                DropdownMenuItem(
                    text = {
                        Text(
                            Strings.langLabels[l] ?: l,
                            color = if (l == lang) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { onLang(l); langMenuOpen = false },
                )
            }
        }
    }
    }
}

/** 单页：标题 + 描述 + 模拟手机 + 动作按钮 + 可选附加区。 */
@Composable
private fun OnboardPage(
    index: Int,
    pos: Float,
    title: String,
    desc: String,
    screen: @Composable () -> Unit,
    actionLabel: String,
    primaryAction: Boolean,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    extra: @Composable () -> Unit = {},
    onAction: () -> Unit,
) {
    val offset = index - pos
    val abs = kotlin.math.abs(offset)
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = offset * size.width
                alpha = (1f - abs * 0.6f).coerceIn(0f, 1f)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        val accent = MaterialTheme.colorScheme.primary
        val accent2 = lerp(accent, Color(0xFFA855F7), 0.45f)
        Text(
            title,
            fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
            style = androidx.compose.ui.text.TextStyle(brush = Brush.linearGradient(listOf(accent, accent2))),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            desc, fontSize = 12.5.sp, lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        PhoneMock(modifier = Modifier.weight(1f, fill = true), screen = screen)
        Spacer(Modifier.height(12.dp))
        extra()
        Spacer(Modifier.height(10.dp))
        if (primaryAction) {
            // 授权按钮：实心 primary 强调
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) { Text(actionLabel, fontWeight = FontWeight.SemiBold) }
        } else {
            // 其余按钮（继续/去设置/开始）：纯文字风格
            TextButton(
                onClick = onAction,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) { Text(actionLabel, fontWeight = FontWeight.SemiBold) }
        }
        // 次按钮（如后台保活页的「继续」）：纯文字，始终可点
        if (secondaryLabel != null) {
            TextButton(
                onClick = onSecondary,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            ) { Text(secondaryLabel, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun permissionLauncher(onResult: (Boolean) -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { onResult(it) }

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

/** 打开系统「忽略电池优化」设置：标准 Intent，失败兜底到应用详情页（国产 ROM 也可能落到这里）。 */
private fun openBatterySettings(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
        )
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
            )
        } catch (_: Exception) {
        }
    }
}
