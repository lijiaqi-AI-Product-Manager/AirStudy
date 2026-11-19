package com.projectfocus.service.focus.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.projectfocus.service.focus.R


internal object ShowBlockedNotification {


    private const val BLOCKED_ALERT_ID = 2002
    private const val BLOCKED_CHANNEL_ID = "focus_blocked_alert"

    fun show(context: Context, appLabel: String, categoryLabel: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // 一条独立的高优先级渠道 —— 用户如果嫌吵可以单独关掉它，
        // 而不至于把整个 App 的通知（含前台服务通知）都关掉。

        if (manager.getNotificationChannel(BLOCKED_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    BLOCKED_CHANNEL_ID,
                    context.getString(R.string.focus_blocked_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.focus_blocked_channel_description)
                    enableVibration(true)
                    setShowBadge(false)
                },
            )
        }

        val content = Intent(context, BlockedAppActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockedAppActivity.EXTRA_LABEL, appLabel)
            putExtra(BlockedAppActivity.EXTRA_CATEGORY, categoryLabel)
        }
        val pending = PendingIntent.getActivity(
            context,
            BLOCKED_ALERT_ID,
            content,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = if (categoryLabel != null) {
            context.getString(R.string.focus_notification_blocked_body_category, appLabel, categoryLabel)
        } else {
            context.getString(R.string.focus_notification_blocked_body, appLabel)
        }


        //   注意两边的"高优先级"语义**不等价**：渠道版靠 Channel.importance = HIGH，
        //   旧版靠 setPriority(HIGH)。在 API 26+ 上后者被系统忽略 ——
        //   也就是说那个 else 分支即使在也会失效。
        val builder = Notification.Builder(context, BLOCKED_CHANNEL_ID)

        manager.notify(
            BLOCKED_ALERT_ID,
            builder
                .setSmallIcon(R.drawable.ic_focus_notification)
                .setContentTitle(context.getString(R.string.focus_notification_blocked_title))
                .setContentText(text)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build(),
        )
    }
}
