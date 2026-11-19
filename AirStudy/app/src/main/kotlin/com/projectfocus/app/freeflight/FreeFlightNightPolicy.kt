package com.projectfocus.app.freeflight

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.app.MainActivity

/** Source w4.g.Y: explicit global2/1 precedes source map-style, unlike Auth u0.F. */
internal object FreeFlightNightPolicy {
    fun isNight(context: Context): Boolean = when (explicitMode(context)) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> HomeMapStyleSelection.isNight(context)
    }

    private fun explicitMode(context: Context): Int {
        val global = AppCompatDelegate.getDefaultNightMode()
        if (global == AppCompatDelegate.MODE_NIGHT_YES || global == AppCompatDelegate.MODE_NIGHT_NO) return global
        // Own's actual persisted ThemeMode is consumed by Main's local delegate. Adapt that
        // real explicit user override to the source global role, without reading an unwritten key.
        return (context as? MainActivity)?.delegate?.localNightMode ?: global
    }
}
