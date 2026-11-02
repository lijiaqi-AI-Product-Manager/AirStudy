package com.projectfocus.app.freeflight

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import com.projectfocus.app.MainActivity
import com.projectfocus.app.R
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightKeepAliveNotification
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightNotificationRequirement
import java.io.Closeable
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Source n0/case17 and case27. Dialog layout uses the current Android host adapter. */
internal class AndroidFreeFlightPreparationPrompts(private val activity: MainActivity) : Closeable {
    private var dialog: AlertDialog? = null
    private var closed = false

    suspend fun firstHint(isCurrent: suspend () -> Boolean): Boolean {
        val preferences = withContext(Dispatchers.IO) { activity.getSharedPreferences(DEVICE_FILE, Context.MODE_PRIVATE).also { it.all } }
        val shown = try { preferences.getBoolean(HINT_SHOWN, false) } catch (_: ClassCastException) { false }
        if (shown) return !closed && isCurrent()
        val body = activity.getString(R.string.pf_ff_battery_opt_warning_message)
        val link = activity.getString(R.string.pf_ff_battery_opt_warning_guide_action)
        val message = SpannableStringBuilder(body).append("\n\n").append(link).apply {
            setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    if (!closed && !activity.isFinishing && !activity.isDestroyed)
                        runCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }, length - link.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (!isCurrent() || !choose(R.string.pf_ff_battery_opt_warning_title, message, R.string.pf_ff_confirm, cancelable = false)) return false
        if (closed || !isCurrent()) return false
        // Confirming this hint is not an actual battery-optimization exemption.
        preferences.edit().putBoolean(HINT_SHOWN, true).apply()
        return true
    }

    suspend fun notification(isCurrent: suspend () -> Boolean): FreeFlightPermissionDecision {
        if (closed || !isCurrent()) return FreeFlightPermissionDecision.Unavailable(activity.getString(R.string.pf_ff_preparation_expired))
        val actual = FreeFlightKeepAliveNotification.access(activity)
        val title: Int
        val message: Int
        val positive: Int
        when (actual.requirement) {
            FreeFlightNotificationRequirement.READY -> return FreeFlightPermissionDecision.Ready
            FreeFlightNotificationRequirement.POST_NOTIFICATIONS -> {
                title = R.string.pf_ff_service_running_title; message = R.string.pf_ff_service_running_message; positive = R.string.pf_ff_permission_allow
            }
            FreeFlightNotificationRequirement.APP_NOTIFICATIONS -> {
                title = R.string.pf_ff_need_notification_permission; message = R.string.pf_ff_need_notification_permission_foreground; positive = R.string.pf_ff_go_to_settings
            }
            FreeFlightNotificationRequirement.CHANNEL -> {
                title = R.string.pf_ff_need_keep_alive_notification; message = R.string.pf_ff_need_notification_channel_permission_foreground; positive = R.string.pf_ff_go_to_settings
            }
            FreeFlightNotificationRequirement.MANAGER_UNAVAILABLE -> return FreeFlightPermissionDecision.Unavailable(activity.getString(R.string.pf_ff_notifications_unavailable))
        }
        if (!choose(title, activity.getText(message), positive) || closed || !isCurrent()) return FreeFlightPermissionDecision.NeedsAction(null)
        return try {
            when (actual.requirement) {
                FreeFlightNotificationRequirement.POST_NOTIFICATIONS -> ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
                FreeFlightNotificationRequirement.APP_NOTIFICATIONS -> activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                FreeFlightNotificationRequirement.CHANNEL -> activity.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, actual.channelId))
                else -> error("The actual notification requirement changed")
            }
            // The source requires another explicit click after system permission/settings returns.
            FreeFlightPermissionDecision.NeedsAction(null)
        } catch (_: Exception) { FreeFlightPermissionDecision.Unavailable(activity.getString(R.string.pf_ff_notifications_unavailable)) }
    }

    private suspend fun choose(title: Int, message: CharSequence, positive: Int, cancelable: Boolean = true): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (closed || activity.isFinishing || activity.isDestroyed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext false
            suspendCancellableCoroutine { continuation ->
                fun finish(value: Boolean) { if (continuation.isActive) continuation.resume(value) }
                val actual = AlertDialog.Builder(activity).setTitle(title).setMessage(message).setCancelable(cancelable)
                    .setPositiveButton(positive) { _, _ -> finish(true) }.apply {
                        if (cancelable) setNegativeButton(R.string.pf_ff_common_cancel) { _, _ -> finish(false) }
                    }.create()
                dialog = actual
                actual.setOnDismissListener { if (dialog === actual) dialog = null; finish(false) }
                continuation.invokeOnCancellation { actual.dismiss() }
                actual.show()
                actual.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
            }
        }

    override fun close() { closed = true; dialog?.dismiss(); dialog = null }

    companion object {
        const val DEVICE_FILE = "app_device_prefs"
        const val HINT_SHOWN = "battery_opt_hint_shown"
    }
}
