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
 * 文件下载进度常驻通知（类似浏览器下载）：下拉状态栏可见、右滑不可消除（ongoing），
 * 带进度条与百分比。下载开始 show，逐块 update，结束（成功/失败）cancel。
 * 与「新文件共享」(NOTIF_ID=4)、「下载成功」共用同一渠道但独立 ID，互不覆盖。
 */
object DownloadProgressNotification {
    private const val CHANNEL_ID = "knotyup_download"
    private const val NOTIF_ID = 5
    private var lastPercent = -1 // 节流：仅当整数百分比变化时才刷新通知

    fun show(context: Context, fileName: String) {
        lastPercent = -1
        build(context, fileName, 0)
    }

    /** @param received 已下载字节；@param total 总字节。 */
    fun update(context: Context, fileName: String, received: Long, total: Long) {
        val percent = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 99) else 0
        if (percent == lastPercent) return
        lastPercent = percent
        build(context, fileName, percent)
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(NOTIF_ID) }
        lastPercent = -1
    }

    private fun build(context: Context, fileName: String, percent: Int) {
        val lang = AppState.loadLang(context) ?: "zh-Hans"
        val t = { key: String -> Strings.t(lang, key) }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(nm, t("file_notif_channel"))

        val openPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(t("downloading"))
            .setContentText("$fileName  $percent%")
            .setContentIntent(openPi)
            .setProgress(100, percent, false)
            .setOngoing(true)                 // 常驻：右滑不可消除
            .setOnlyAlertOnce(true)           // 仅首次提示音，进度更新不重复响
            .setPriority(NotificationCompat.PRIORITY_LOW) // 不弹 heads-up，安静待在状态栏
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        runCatching { nm.notify(NOTIF_ID, notif) }
    }

    private fun createChannel(nm: NotificationManager, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
            description = "文件下载进度（常驻）"
            setShowBadge(false)
        }.let { nm.createNotificationChannel(it) }
    }
}
