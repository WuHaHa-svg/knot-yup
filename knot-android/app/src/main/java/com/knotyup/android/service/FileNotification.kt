package com.knotyup.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.knotyup.android.MainActivity
import com.knotyup.android.data.AppState
import com.knotyup.android.ui.Strings

/**
 * 后台收到群内新共享文件时的悬浮通知（heads-up）。
 *
 * 仅当 App 在后台时由 AppState 触发：前台用普通 toast 提示，不打扰。
 * 标题为「{name} 共享了文件」，正文为文件名；内容区点击回 App，
 * 右侧「下载」按钮经 [FileDownloadReceiver] 从供种者 P2P 拉取该文件。
 * 新消息复用同一通知 ID（覆盖上一条），避免后台刷屏堆积。
 */
object FileNotification {
    private const val CHANNEL_ID = "knotyup_file"
    private const val NOTIF_ID = 4
    const val ACTION_DOWNLOAD = "com.knotyup.android.DOWNLOAD_FILE"
    const val EXTRA_SEED_ID = "seed_id"
    const val EXTRA_FILE_ID = "file_id"
    const val EXTRA_NAME = "name"
    const val EXTRA_SHA = "sha256"
    const val EXTRA_SIZE = "size"
    const val EXTRA_MIME = "mime"
    const val EXTRA_ADDED_AT = "added_at"

    /** @param fromName 共享者设备名；@param fileName 文件名（正文）。 */
    fun show(context: Context, seedId: String, fromName: String, fileName: String, fileId: String,
             sha256: String, size: Long, mime: String, addedAt: Long) {
        val lang = AppState.loadLang(context) ?: "zh-Hans"
        val t = { key: String -> Strings.t(lang, key) }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(nm, t("file_notif_channel"))

        val openPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 下载按钮 → 广播（SharedFile 各字段放 extra；requestCode 用 fileId hash 让不同文件各自更新）
        val dlIntent = Intent(context, FileDownloadReceiver::class.java).apply {
            action = ACTION_DOWNLOAD
            putExtra(EXTRA_SEED_ID, seedId)
            putExtra(EXTRA_FILE_ID, fileId)
            putExtra(EXTRA_NAME, fileName)
            putExtra(EXTRA_SHA, sha256)
            putExtra(EXTRA_SIZE, size)
            putExtra(EXTRA_MIME, mime)
            putExtra(EXTRA_ADDED_AT, addedAt)
        }
        val dlPi = PendingIntent.getBroadcast(
            context, fileId.hashCode(), dlIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = t("file_shared").replace("{name}", if (fromName.isBlank()) "??" else fromName)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(fileName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fileName))
            .setContentIntent(openPi)
            .addAction(android.R.drawable.stat_sys_download, t("file_notif_download"), dlPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { nm.notify(NOTIF_ID, notif) }
    }

    /** 下载成功通知（后台）：标题「{name} 下载成功」，正文「路径：Download/knotShare」。 */
    fun showDownloaded(context: Context, fileName: String) {
        val lang = AppState.loadLang(context) ?: "zh-Hans"
        val t = { key: String -> Strings.t(lang, key) }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(nm, t("file_notif_channel"))

        val openPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = t("download_ok").replace("{name}", fileName)
        val body = t("download_path")
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { nm.notify(NOTIF_ID, notif) }
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(NOTIF_ID) }
    }

    private fun createChannel(nm: NotificationManager, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "群内新共享文件（悬浮通知）"
            setShowBadge(false)
        }.let { nm.createNotificationChannel(it) }
    }
}
