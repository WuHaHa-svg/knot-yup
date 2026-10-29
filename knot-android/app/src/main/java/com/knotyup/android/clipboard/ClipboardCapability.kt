package com.knotyup.android.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import rikka.shizuku.Shizuku

/**
 * Android 剪贴板能力分层（见 docs/ARCHITECTURE.md）：
 *
 *  1. ShizukuProvider   — shell uid 代理 IClipboard，后台读写（M2.2 接入 binder 代理）
 *  2. ForegroundProvider — 前台读写（当前实现）：ClipboardManager 在有焦点时完全可用
 *  3.（规划）ImeProvider — 内置输入法
 *
 * 当前版本：前台模式 + Shizuku 能力探测与引导。Shizuku 激活后 M2.2 将自动升级为后台模式。
 */
object ClipboardCapability {

    enum class Mode { SHIZUKU, FOREGROUND }

    fun currentMode(): Mode =
        if (isShizukuActive()) Mode.SHIZUKU else Mode.FOREGROUND

    fun isShizukuActive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    fun isShizukuPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /** 前台模式下写入剪贴板（App 在前台时可靠）。 */
    fun setText(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("knotyup", text))
    }

    /** 前台模式下读取剪贴板。 */
    fun getText(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = cm.primaryClip?.getItemAt(0) ?: return null
        return item.coerceToText(context)?.toString()
    }
}
