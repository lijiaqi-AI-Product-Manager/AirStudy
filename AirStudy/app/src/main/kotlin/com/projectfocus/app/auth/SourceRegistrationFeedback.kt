package com.projectfocus.app.auth

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.projectfocus.app.R

/** Actual custom Toast path from w4.g.W0/U0/X0 -> T0, not a Dialog or discarded text. */
internal object SourceRegistrationFeedback {
    enum class Tone { SUCCESS, ERROR, WARNING }

    @Suppress("DEPRECATION")
    fun show(activity: Activity, titleRes: Int, messageRes: Int, tone: Tone, vararg args: Any) {
        if (activity.isFinishing || activity.isDestroyed) return // destroyed is an Own late-callback fence.
        val title = activity.getString(titleRes)
        val message = if (args.isEmpty()) activity.getString(messageRes) else activity.getString(messageRes, *args)
        val root = LayoutInflater.from(activity).inflate(R.layout.pf_auth_global_toast_item, null, false)
        val titleView = root.findViewById<TextView>(R.id.pf_auth_toast_title)
        val messageView = root.findViewById<TextView>(R.id.pf_auth_toast_message)
        val icon = root.findViewById<ImageView>(R.id.pf_auth_toast_icon)
        if (title.isNotEmpty()) {
            titleView.text = title
            titleView.visibility = View.VISIBLE
            if (message.isNotEmpty() && message != title) {
                messageView.text = message
                messageView.visibility = View.VISIBLE
            } else messageView.visibility = View.GONE
        } else {
            titleView.visibility = View.GONE
            messageView.text = message
        }

        val night = sourceNight(activity)
        val iconResource: Int
        val toneResource: Int
        when (tone) {
            Tone.SUCCESS -> { iconResource = R.drawable.pf_auth_ic_toast_success; toneResource =
                if (night) R.color.pf_auth_notice_success_dark else R.color.pf_auth_notice_success_light }
            Tone.ERROR -> { iconResource = R.drawable.pf_auth_ic_toast_error; toneResource =
                if (night) R.color.pf_auth_notice_error_dark else R.color.pf_auth_notice_error_light }
            Tone.WARNING -> { iconResource = R.drawable.pf_auth_ic_toast_warning; toneResource =
                if (night) R.color.pf_auth_notice_warning_dark else R.color.pf_auth_notice_warning_light }
        }
        val accent = activity.getColor(toneResource)
        icon.setImageResource(iconResource)
        icon.setColorFilter(accent)
        titleView.setTextColor(activity.getColor(if (night) R.color.pf_auth_notice_title_dark else R.color.pf_auth_notice_title_light))
        messageView.setTextColor(activity.getColor(if (night) R.color.pf_auth_notice_message_dark else R.color.pf_auth_notice_message_light))
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 22f // Source raw pixels, not22dp.
            setColor(activity.getColor(if (night) R.color.pf_auth_notice_background_dark else R.color.pf_auth_notice_background_light))
            setStroke(1, withAlpha(accent, if (night) 120 else 88))
        }
        (icon.parent as? View)?.takeIf { it !== icon }?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(accent, if (night) 58 else 34))
        }
        Toast(activity).apply {
            view = root
            duration = 3000 // Exact source API argument; Android decides the effective duration.
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0,
                (60f * activity.resources.displayMetrics.density + 0.5f).toInt())
        }.show()
    }

    /** Own AndroidX API bridge for original c1.f's static Delegate field + resource fallback. */
    fun sourceNight(activity: Activity): Boolean = when (AppCompatDelegate.getDefaultNightMode()) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
