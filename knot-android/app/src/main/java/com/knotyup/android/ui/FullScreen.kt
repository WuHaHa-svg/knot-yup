package com.knotyup.android.ui

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 全屏（沉浸式）适配模块——独立于业务，MainActivity 只在两个时机调用：
 *
 * 1. [applyFullScreen]：onCreate 时一次性配置（布局不避让系统栏 + 隐藏状态栏 + 刘海延伸）
 * 2. [keepFullScreen]：onWindowFocusChanged(hasFocus=true) 时重新隐藏——
 *    Android 10 及国内 OEM（MIUI/EMUI/Funtouch/ColorOS）在焦点变化
 *    （弹窗/键盘/切后台回来）后会擅自把状态栏显示回来，必须每次补 hide。
 *
 * 黑条问题（API 29+）：windowLightStatusBar + 透明状态栏会触发系统
 * 「对比度强制」黑色 scrim，已在 values-v29/themes.xml 用
 * enforceStatusBarContrast/enforceNavigationBarContrast=false 关闭。
 */
object FullScreen {

    fun applyFullScreen(window: Window) {
        // 布局铺满整个屏幕（含状态栏/导航栏区域），系统栏仅作透明浮层
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 刘海/挖孔区域允许绘制延伸（values-v27 主题里也配了 shortEdges）
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideStatusBar(window)
    }

    /** 焦点恢复时重隐藏状态栏（防 OEM 复活）。 */
    fun keepFullScreen(window: Window) {
        hideStatusBar(window)
    }

    private fun hideStatusBar(window: Window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // 深色图标在暗色主题下不可见，状态栏本就常隐藏，统一用浅色图标即可
        controller.isAppearanceLightStatusBars = false
        controller.hide(WindowInsetsCompat.Type.statusBars())
        // 下拉临时呼出、松手自动消失；个别 OEM 不支持则由 keepFullScreen 兜底恢复沉浸
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
