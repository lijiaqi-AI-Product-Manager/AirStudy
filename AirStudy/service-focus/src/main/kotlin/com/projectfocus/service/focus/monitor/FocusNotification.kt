package com.projectfocus.service.focus.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.projectfocus.service.focus.R


class FocusNotification {














    fun ensureChannel(context: Context) {

        //   minSdk = 26 = O，通知渠道**一定**存在，不需要"没有渠道"的降级路径。
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(FocusMonitorConfig.CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            FocusMonitorConfig.CHANNEL_ID,
            context.getString(R.string.focus_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.focus_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)

            //   setOngoing(true) 在 channel 上（那是 Notification 的属性）。
            //   channel 上可设的是 lockscreenVisibility，保持默认即可。
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建通知。
     *
     * @param activeAppCount 当前生效的屏蔽 App 数量，显示在副标题
     * @param paused 会话是否暂停中
     */
    fun build(
        context: Context,
        activeAppCount: Int,
        paused: Boolean,
        contentIntent: PendingIntent?,
        pauseIntent: PendingIntent?,
        stopIntent: PendingIntent?,
    ): Notification {


        //     用无渠道构造器发出的通知，系统会丢进一个默认渠道，

        val builder = Notification.Builder(context, FocusMonitorConfig.CHANNEL_ID)

        val title = context.getString(R.string.focus_notification_title)
        val text = when {
            paused -> context.getString(R.string.focus_notification_text_paused)
            activeAppCount > 0 -> context.getString(R.string.focus_notification_text_active, activeAppCount)
            else -> context.getString(R.string.focus_notification_text_idle)
        }

        builder
            .setSmallIcon(R.drawable.ic_focus_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)              // 不可划掉
            .setOnlyAlertOnce(true)        // 更新时不响不震
            .setShowWhen(false)            // 不要「已运行 3 小时」那种时间
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        contentIntent?.let { builder.setContentIntent(it) }

        if (paused) {
            // 暂停中：主按钮是「继续」，不是「结束」

            //   把「结束」放在第一位的设计会导致大量误触。
        } else if (pauseIntent != null) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_focus_pause),
                    context.getString(R.string.focus_action_pause),
                    pauseIntent,
                ).build(),
            )
        }

        if (stopIntent != null) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_focus_stop),
                    context.getString(R.string.focus_action_stop),
                    stopIntent,
                ).build(),
            )
        }

        return builder.build()
    }

    /** 撤回通知（服务停止时） */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(FocusMonitorConfig.NOTIFICATION_ID)
    }

    companion object {
        /** 便捷方法：从一个 Intent 造一个只发一次的 PendingIntent */
        fun pendingIntent(
            context: Context,
            intent: Intent,
            requestCode: Int,
            immutable: Boolean = true,
        ): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (immutable) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_MUTABLE
            return PendingIntent.getService(context, requestCode, intent, flags)
        }
    }
}
