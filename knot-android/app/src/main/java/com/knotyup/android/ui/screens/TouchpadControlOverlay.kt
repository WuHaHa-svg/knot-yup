package com.knotyup.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.knotyup.android.data.AppState
import com.knotyup.android.data.TouchpadSettings
import com.knotyup.android.ui.components.KnotConfirm

/**
 * 触摸板控制覆层：独立全屏页面，盖住 Header 与 Tab（作为 App 根 Box 的最后一层渲染）。
 * 页面本身不展示任何组件，只有纯色背景（按 OLED 设置取黑/深灰）。
 *
 * 强制"传感器横屏"：允许 90°/270° 两个横向角度（随设备旋转切换），但不接受竖屏。
 * 进入即锁横屏、退出即恢复系统方向——本组件只在"控制中"时组合，故 DisposableEffect
 * 生命周期恰好等于控制会话，无需快照判断。
 * 退出仅通过系统返回键（弹确认），确认后释放全局控制状态。
 */
@Composable
internal fun TouchpadControlOverlay(t: (String) -> String) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { TouchpadSettings.init(ctx) }
    val oled by remember { mutableStateOf(TouchpadSettings.oled) }
    var confirmExit by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val activity = ctx as? android.app.Activity
        activity?.requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 进入提示 + 旋转提示：旋转横屏（90↔270）会重建 Activity，用 rememberSaveable 跨重建记住状态。
    // 只在观察到横屏时才算"进入完成"（首次进入可能仍是竖屏，等自动转横屏后再提示），
    // 避免把"进入时的自动转横屏"误报成"方向已更改"。
    var started by rememberSaveable { mutableStateOf(false) }
    var lastRot by rememberSaveable { mutableIntStateOf(-1) }
    val rotation = @Suppress("DEPRECATION")
    (ctx as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation ?: 0
    LaunchedEffect(rotation) {
        val landscape = rotation == android.view.Surface.ROTATION_90 ||
            rotation == android.view.Surface.ROTATION_270
        if (!landscape) return@LaunchedEffect
        if (!started) {
            AppState.toastKey("touchpad_started")
            started = true
            lastRot = rotation
        } else if (rotation != lastRot) {
            AppState.toastKey("touchpad_orientation_changed")
            lastRot = rotation
        }
    }

    // 群页面返回拦截已在 MainActivity 据 controllingId 禁用，这里独占消费返回键
    BackHandler(enabled = !confirmExit) { confirmExit = true }
    if (confirmExit) {
        KnotConfirm(
            title = t("touchpad_exit_control"),
            message = t("touchpad_exit_control_msg"),
            okText = t("touchpad_exit_control"),
            cancelText = t("confirm_cancel"),
            danger = true,
            onOk = { AppState.setTouchpadControlling(null); confirmExit = false },
            onCancel = { confirmExit = false },
        )
    }

    Box(Modifier.fillMaxSize().background(if (oled) Color(0xFF000000) else Color(0xFF212121)))
}
