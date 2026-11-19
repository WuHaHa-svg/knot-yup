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
 * 后台收到群内新剪贴板时的悬浮通知（heads-up）。
 *
 * 仅当 App 在后台时由 AppState 触发：前台直接写本机剪贴板，不打扰用户。
 * 通知标题为「来自 XX」（来源设备名），正文即剪贴板内容本身；内容区点击回 App，
 * 右侧「复制」按钮经 [ClipboardCopyReceiver] 把文本写入剪贴板并同步防回环闸门。
 * 新消息复用同一通知 ID（覆盖上一条），避免后台刷屏堆积。
 */
object ClipboardNotification {
    private const val CHANNEL_ID = "knotyup_clipboard"
    private const val NOTIF_ID = 3
    const val ACTION_COPY = "com.knotyup.android.COPY_CLIPBOARD"
    const val EXTRA_TEXT = "text"

    /** @param fromName 来源设备名；作为通知标题「来自 XX」，为空则用兜底标题。 */
    fun show(context: Context, text: String, fromName: String) {
        val lang = AppState.loadLang(context) ?: "zh-Hans"
        val t = { key: String -> Strings.t(lang, key) }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(nm, t("clipboard_notif_channel"))

        // 内容区点击 → 回到 App（singleTask 复用现有实例）
        val openPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 复制按钮 → 广播（文本放 extra；requestCode 用文本 hash 让不同内容各自更新）
        val copyIntent = Intent(context, ClipboardCopyReceiver::class.java).apply {
            action = ACTION_COPY
            putExtra(EXTRA_TEXT, text)
        }
        val copyPi = PendingIntent.getBroadcast(
            context, text.hashCode(), copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 标题＝来源设备（如「来自 Redmi K30 Pro」）；正文＝剪贴板内容本身
        val title = if (fromName.isBlank()) {
            t("clipboard_notif_title")
        } else {
            t("clipboard_notif_from").replace("{name}", fromName)
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_save, t("clipboard_notif_copy"), copyPi)
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
            description = "群内新剪贴板（悬浮通知）"
            setShowBadge(false)
        }.let { nm.createNotificationChannel(it) }
    }
}
