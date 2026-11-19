package com.projectfocus.app.auth

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.projectfocus.app.R
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.core.ui.R as UiR

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
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val background = activity.getColor(UiR.color.sky_canvas)
        activity.window.statusBarColor = background
        activity.window.navigationBarColor = background
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.background = AuthSkyDrawable(activity)
        content.getChildAt(0)?.background = null
        activity.findViewById<View>(R.id.pf_auth_btn_back).contentDescription =
            activity.getString(R.string.flight_preparation_back)
        activity.findViewById<View>(R.id.pf_auth_tv_title)?.let { title ->
            SkySurfaces.card(title.parent as View, radius = 24f, elevation = 4f, solid = true)
        }
        if (!ValueAnimator.areAnimatorsEnabled()) disableLayoutAnimation(content)

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.captionBar() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
        languageSnapshot = applicationLanguage()
    }

    private fun disableLayoutAnimation(group: ViewGroup) {
        group.layoutAnimation = null
        for (i in 0 until group.childCount) {
            (group.getChildAt(i) as? ViewGroup)?.let(::disableLayoutAnimation)
        }
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
