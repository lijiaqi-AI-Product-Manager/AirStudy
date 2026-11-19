package com.projectfocus.service.focus.freeflight.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.projectfocus.service.focus.R

enum class FreeFlightNotificationRequirement { READY, POST_NOTIFICATIONS, APP_NOTIFICATIONS, CHANNEL, MANAGER_UNAVAILABLE }
data class FreeFlightNotificationAccess(val requirement: FreeFlightNotificationRequirement, val channelId: String? = null)

/** Source keepalive notification and actual OS checks, shared by preparation and the flight. */
object FreeFlightKeepAliveNotification {
    const val CHANNEL_ID = "focus_keep_alive"
    private const val LEGACY_CHANNEL_ID = "projectfocus_freeflight_runtime"

    fun access(context: Context): FreeFlightNotificationAccess {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return FreeFlightNotificationAccess(FreeFlightNotificationRequirement.POST_NOTIFICATIONS)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled())
            return FreeFlightNotificationAccess(FreeFlightNotificationRequirement.APP_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return FreeFlightNotificationAccess(FreeFlightNotificationRequirement.MANAGER_UNAVAILABLE)
        // Preserve a previous explicit user block when moving the Own debug channel to the source
        // channel. Never manufacture an enabled replacement to bypass the notification settings.
        val blocked = listOf(CHANNEL_ID, LEGACY_CHANNEL_ID).firstOrNull {
            manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
        }
        return if (blocked != null) FreeFlightNotificationAccess(FreeFlightNotificationRequirement.CHANNEL, blocked)
            else FreeFlightNotificationAccess(FreeFlightNotificationRequirement.READY)
    }

    fun ensureChannel(context: Context) {
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        check(manager.getNotificationChannel(LEGACY_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE) {
            "The previously blocked notification channel requires actual user action"
        }
        if (manager.getNotificationChannel(CHANNEL_ID) == null) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.freeflight_source_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
    }

    fun build(context: Context): Notification {
        ensureChannel(context)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_focus_notification)
            .setContentText(context.getString(R.string.freeflight_source_notification))
            .setOngoing(true).setOnlyAlertOnce(true).setPriority(Notification.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            builder.setContentIntent(PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }
}
