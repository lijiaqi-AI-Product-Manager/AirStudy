package com.projectfocus.app.testing

import android.app.Application
import com.projectfocus.app.ProjectFocusApp
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment

/** Runs AFTER each class's own @After, and BEFORE Robolectric resets SQLite/SDK state. */
class RealApplicationLifetimeRule : ExternalResource() {
    override fun after() {
        val actual: Application = RuntimeEnvironment.getApplication()
        check(actual is ProjectFocusApp) { "This rule requires the real production Application" }
        shutdownRealApplicationAfterTest(actual)
    }
}
