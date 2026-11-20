package com.knotyup.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 悬浮通知「下载」按钮的接收器（静态注册，见 AndroidManifest.xml）。
 *  从 extra 重建 SharedFile，后台 P2P 拉取并登记为供种者。 */
class FileDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FileNotification.ACTION_DOWNLOAD) return
        val seedId = intent.getStringExtra(FileNotification.EXTRA_SEED_ID) ?: return
        val fileId = intent.getStringExtra(FileNotification.EXTRA_FILE_ID) ?: return
        val name = intent.getStringExtra(FileNotification.EXTRA_NAME) ?: return
        val sha = intent.getStringExtra(FileNotification.EXTRA_SHA) ?: return
        val size = intent.getLongExtra(FileNotification.EXTRA_SIZE, 0L)
        val mime = intent.getStringExtra(FileNotification.EXTRA_MIME) ?: "application/octet-stream"
        val addedAt = intent.getLongExtra(FileNotification.EXTRA_ADDED_AT, 0L)
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            com.knotyup.android.data.AppState.downloadFromNotification(
                appCtx, seedId, fileId, name, sha, size, mime, addedAt
            )
        }
        FileNotification.cancel(appCtx)
    }
}
