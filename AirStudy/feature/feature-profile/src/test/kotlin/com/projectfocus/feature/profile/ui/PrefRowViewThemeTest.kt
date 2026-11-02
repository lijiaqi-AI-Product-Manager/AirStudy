package com.projectfocus.feature.profile.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.ui.R as UiR
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Theme.Pf resource resolution must work before the settings rows can render. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrefRowViewThemeTest {
    @Test
    @Config(qualifiers = "notnight")
    fun `day row loads its theme feedback and can be clicked`() {
        assertFeedbackAndClick(Configuration.UI_MODE_NIGHT_NO)
    }

    @Test
    @Config(qualifiers = "night")
    fun `night row loads its theme feedback and can be clicked`() {
        assertFeedbackAndClick(Configuration.UI_MODE_NIGHT_YES)
    }

    private fun assertFeedbackAndClick(expectedNightMode: Int) {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context = ContextThemeWrapper(application, UiR.style.Theme_Pf)
        assertThat(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            .isEqualTo(expectedNightMode)

        val row = PrefRowView(context)
        assertThat(row.background).isNotNull()
        assertThat(row.background.isStateful).isTrue()
        var clicks = 0
        row.setRowClickable { clicks++ }

        row.isPressed = true
        assertThat(row.background.state.toList()).contains(android.R.attr.state_pressed)
        assertThat(row.performClick()).isTrue()
        assertThat(clicks).isEqualTo(1)

        row.isPressed = false
        assertThat(row.background.state.toList()).doesNotContain(android.R.attr.state_pressed)
    }
}
