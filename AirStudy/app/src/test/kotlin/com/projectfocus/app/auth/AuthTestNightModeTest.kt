package com.projectfocus.app.auth

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "night")
class AuthTestNightModeTest {
    @Test fun `cleanup removes forced day through the actual supported public AppCompat API`() {
        val before = AppCompatDelegate.getDefaultNightMode()
        try {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED)
            // Reproduce why the previous cleanup leaked day into later Main tests.
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_NO)
            restoreAuthTestNightMode(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            restoreAuthTestNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_YES)
        } finally {
            restoreAuthTestNightMode(before)
        }
    }
}
