package com.projectfocus.app.auth

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.projectfocus.app.R
import com.projectfocus.app.home.HomeMapStyleSelection

/**
 * Source auth local-night/map-style, bar colors, content Insets and language-recreate rules.
 * Own storage uses the actual Home selection. Application locales are the real AppCompat
 * consumer, not a new unwritten preference or a claim that language-settings UI is complete.
 */
internal class SourceAuthWindow(private val activity: AppCompatActivity) {
    private var languageSnapshot: String? = null

    fun beforeCreate() {
        activity.delegate.localNightMode = if (HomeMapStyleSelection.isNight(activity))
            AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
    }

    @Suppress("DEPRECATION")
    fun bindContent() {
        // Own SDK35 edge-to-edge adapter: the source listener owns absolute content padding.
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val background = activity.getColor(R.color.pf_auth_background)
        activity.window.statusBarColor = background
        activity.window.navigationBarColor = background
        val content = activity.findViewById<android.view.View>(android.R.id.content)
        content.setBackgroundResource(R.color.pf_auth_background)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(7) // Original mask7; current AndroidX systemBars also includes512.
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
        languageSnapshot = applicationLanguage()
        // Icon polarity is inherited from the scoped source Material theme.
        // The four original onCreate methods do not unconditionally force light glyphs.
    }

    fun onResume() {
        val current = applicationLanguage()
        val previous = languageSnapshot
        languageSnapshot = current
        if (previous != null && previous != current) activity.recreate()
    }

    private fun applicationLanguage(): String =
        AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { "system" }
}
