package com.projectfocus.app.testing

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.ProjectFocusApp
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import timber.log.Timber

/** Real Application logger ownership; other components' trees must survive. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [34])
class ApplicationLoggingLifetimeTest {
    @After fun cleanup() {
        shutdownRealApplicationAfterTest(ApplicationProvider.getApplicationContext<ProjectFocusApp>())
    }

    @Test fun actualApplicationTerminationReleasesOnlyItsOwnLoggerAndIsIdempotent() {
        val app = ApplicationProvider.getApplicationContext<ProjectFocusApp>()
        val own = ReflectionHelpers.getField<Timber.Tree>(app, "ownedDebugTree")
        assertThat(own).isNotNull();assertThat(Timber.forest()).contains(own)
        val foreign = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) = Unit
        }
        Timber.plant(foreign)
        try {
            app.onTerminate();app.onTerminate()
            assertThat(Timber.forest()).doesNotContain(own)
            assertThat(Timber.forest()).contains(foreign)
        } finally {
            Timber.uproot(foreign)
        }
    }
}
