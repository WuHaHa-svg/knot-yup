package com.knotyup.android.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 剪贴板读写（仅前台）。
 *
 * Android 10+ 只有焦点窗口/默认 IME 能读剪贴板，故 App 只在前台通过系统
 * OnPrimaryClipChangedListener 自动同步本机剪贴板；后台不再轮询/读取，
 * 群内新剪贴板改由系统悬浮通知告知，用户点通知内的「复制」按钮才写入。
 * 写入不受焦点限制，[setText] 在前后台均可用（通知复制动作即走这里）。
 *
 * 统一切 IO 线程：剪贴板 binder 调用是同步阻塞的。
 */
object ClipboardCapability {

    /** 写剪贴板（无焦点要求，前后台均可）。 */
    suspend fun setText(context: Context, text: String) = withContext(Dispatchers.IO) {
        setTextBlocking(context, text)
    }

    /** 写剪贴板的同步版本，供已在 IO 线程的通知复制动作直接调用。 */
    fun setTextBlocking(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("knotyup", text))
    }

    /** 读剪贴板（仅前台有效：后台受系统限制，读不到或被拒）。 */
    suspend fun getText(context: Context): String? = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = cm.primaryClip?.getItemAt(0) ?: return@withContext null
        item.coerceToText(context)?.toString()
    }
}
