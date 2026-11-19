package com.knotyup.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 悬浮通知「复制」按钮的接收器（静态注册，见 AndroidManifest.xml）。
 * 写入剪贴板并同步防回环闸门：避免这次写入被前台监听器当成「本机复制」再发回群。
 */
class ClipboardCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClipboardNotification.ACTION_COPY) return
        val text = intent.getStringExtra(ClipboardNotification.EXTRA_TEXT) ?: return
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            com.knotyup.android.data.AppState.copyFromNotification(appCtx, text)
        }
        ClipboardNotification.cancel(appCtx)
    }
}
