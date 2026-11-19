package com.projectfocus.app.auth

import androidx.appcompat.app.AppCompatDelegate

/** Public setDefaultNightMode rejects -100; restore its equivalent system-following default. */
internal fun restoreAuthTestNightMode(previous: Int) {
    AppCompatDelegate.setDefaultNightMode(
        if (previous == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else previous,
    )
}
