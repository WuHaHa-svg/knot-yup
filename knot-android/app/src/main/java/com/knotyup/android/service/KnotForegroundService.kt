package com.knotyup.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * 群组前台服务：入群期间常驻，保 ws 连接不被系统冻结——
 * 后台靠它持续接收群消息，新剪贴板经悬浮通知告知（见 ClipboardNotification）。
 * 常驻通知内容：群组同步中；点击回到 App。退出/解散/被踢时停止。
 */
class KnotForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""
        startForeground(NOTIF_ID, buildNotification(text))
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "KnotYup", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.knotyup.android.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("KnotYup")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "knotyup_sync"
        private const val NOTIF_ID = 1
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            runCatching {
                context.startForegroundService(
                    Intent(context, KnotForegroundService::class.java)
                        .putExtra(EXTRA_TEXT, text)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KnotForegroundService::class.java)) }
        }
    }
}
