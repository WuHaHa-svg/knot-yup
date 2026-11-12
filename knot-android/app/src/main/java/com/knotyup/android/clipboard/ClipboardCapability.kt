package com.knotyup.android.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Android 剪贴板能力分层（见 docs/ARCHITECTURE.md）：
 *
 *  1. ShizukuProvider   — shell uid 代理 IClipboard，后台读写（M2.2 已接入 binder 代理）
 *  2. ForegroundProvider — 前台读写：ClipboardManager 在有焦点时完全可用
 *  3.（规划）ImeProvider — 内置输入法
 *
 * 路由规则：binder 活着 + 已授权 → Shizuku 后台模式（读写均绕开焦点限制）；
 * 任何失败（未运行/未授权/transact 异常）自动降级前台模式，调用方无感。
 *
 * 线程约定：suspend 接口内部统一切 Dispatchers.IO（binder transact 是阻塞调用，
 * Shizuku binder 代理禁止主线程）；前台模式顺带同线程处理，行为一致。
 */
object ClipboardCapability {

    enum class Mode { SHIZUKU, FOREGROUND }

    /** 模式变化回调（Shizuku binder 收到/断开/授权结果变化时触发，UI 刷新徽章用）。 */
    private val listeners = mutableListOf<(Mode) -> Unit>()

    fun currentMode(): Mode =
        if (isShizukuActive()) Mode.SHIZUKU else Mode.FOREGROUND

    fun isShizukuActive(): Boolean = try {
        Shizuku.pingBinder() && isShizukuPermissionGranted()
    } catch (_: Exception) {
        false
    }

    /** 本机 Shizuku 服务是否在运行（仅看 binder，不看授权）：授权按钮的启用前提。 */
    fun isShizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    fun isShizukuPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /** 请求 Shizuku 授权（结果经 Shizuku 系统回调 → [onModeMaybeChanged]）。 */
    fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(0)
        } catch (_: Exception) {
        }
    }

    /**
     * 注册 Shizuku 生命周期监听（binder 收到/失活/授权结果），变化时回调所有 listener。
     * 幂等：重复调用只注册一次；随进程存活，无需反注册。
     */
    fun observeModeChanges(onChange: (Mode) -> Unit) {
        listeners.add(onChange)
        if (listeners.size > 1) return // 已注册过系统回调
        try {
            Shizuku.addBinderReceivedListenerSticky { notifyModeChanged() }
            Shizuku.addBinderDeadListener { notifyModeChanged() }
            Shizuku.addRequestPermissionResultListener { _, _ -> notifyModeChanged() }
        } catch (_: Exception) {
        }
    }

    private fun notifyModeChanged() {
        val mode = currentMode()
        listeners.forEach { runCatching { it(mode) } }
    }

    /** 写剪贴板：Shizuku 后台写（失败降级前台写）。 */
    suspend fun setText(context: Context, text: String) = withContext(Dispatchers.IO) {
        if (isShizukuActive()) {
            try {
                ShizukuProvider.setText(text)
                return@withContext
            } catch (e: Exception) {
                android.util.Log.w("KnotYup", "Shizuku 写剪贴板失败，降级前台：${e.message}")
            }
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("knotyup", text))
    }

    /** 读剪贴板：Shizuku 后台读（失败降级前台读）。 */
    suspend fun getText(context: Context): String? = withContext(Dispatchers.IO) {
        if (isShizukuActive()) {
            try {
                return@withContext ShizukuProvider.getText()
            } catch (e: Exception) {
                android.util.Log.w("KnotYup", "Shizuku 读剪贴板失败，降级前台：${e.message}")
            }
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = cm.primaryClip?.getItemAt(0) ?: return@withContext null
        item.coerceToText(context)?.toString()
    }

    /**
     * 剪贴板指纹（SHIZUKU 模式轮询用）：Shizuku 摘要指纹优先；
     * 不可用则返回前台 description 的近似指纹。
     */
    suspend fun fingerprint(context: Context): String? = withContext(Dispatchers.IO) {
        if (isShizukuActive()) {
            try {
                return@withContext ShizukuProvider.descriptionFingerprint()
            } catch (_: Exception) {
            }
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClipDescription?.let { "${it.timestamp}/${it.hashCode()}" }
    }
}
