package com.projectfocus.app.shield

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InstalledShieldAppsTest {
    @Test fun oneEntryPerAppExcludesProtectedAndDisabledPackages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = shadowOf(context.packageManager)
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        fun add(packageName: String, activity: String, label: String, enabled: Boolean = true) {
            manager.addResolveInfoForIntent(launch, ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    this.packageName = packageName
                    name = activity
                    exported = true
                    this.enabled = enabled
                    applicationInfo = ApplicationInfo().apply {
                        this.packageName = packageName
                        nonLocalizedLabel = label
                        this.enabled = enabled
                    }
                }
            })
        }
        add("example.zen", "Main", "Zen")
        add("example.books", "Main", "Books")
        add("example.books", "Recent", "Books")
        add("example.disabled", "Main", "Disabled", enabled = false)
        add("com.android.dialer", "Main", "Phone")
        add("com.android.settings", "Main", "Settings")
        add(context.packageName, "Main", "AirStudy")
        assertEquals(listOf(ShieldApp("example.books", "Books"), ShieldApp("example.zen", "Zen")),
            InstalledShieldApps(context).list(Locale.ENGLISH))
    }
}
