package com.projectfocus.app.shield

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.checkbox.MaterialCheckBox
import com.projectfocus.app.MainActivity
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.datastore.PrefKey
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [35], qualifiers = "zh-rCN-w360dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldAppsFlowTest {
    @get:Rule val application = object : ExternalResource() {
        override fun before() = Dispatchers.setMain(Handler.createAsync(Looper.getMainLooper()).asCoroutineDispatcher())
        override fun after() {
            try {
                shutdownRealApplicationAfterTest(ApplicationProvider.getApplicationContext())
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    @Test fun settingsEntrySearchAndCheckboxKeepTheSavedChoiceAfterReturning() {
        val app = ApplicationProvider.getApplicationContext<ProjectFocusApp>()
        runBlocking(Dispatchers.IO) {
            withTimeout(15_000) { app.awaitInitialAccountRestore(); app.awaitInitialAirportSeed() }
        }
        val manager = shadowOf(app.packageManager)
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for ((packageName, label) in listOf("example.reader" to "阅读", "example.mail" to "邮件", "example.social" to "社交")) {
            manager.addResolveInfoForIntent(launch, ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    this.packageName = packageName
                    name = "Main"
                    enabled = true
                    exported = true
                    applicationInfo = ApplicationInfo().apply {
                        this.packageName = packageName
                        nonLocalizedLabel = label
                        enabled = true
                    }
                }
            })
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
                host.navController.navigate(R.id.settingsFragment)
                host.childFragmentManager.executePendingTransactions()
                val choice = openPicker(activity, host)
                val search = choice.findViewById<EditText>(R.id.search)
                val list = choice.findViewById<ListView>(R.id.apps)
                waitFor(activity, "installed applications") { search.isEnabled && list.count >= 4 }
                capture(choice, "shield-picker-day")

                search.setText("reader")
                waitFor(activity, "filtered result") { list.count == 2 && checkbox(list, "阅读") != null }
                checkbox(list, "阅读")!!.performClick()
                val selectedCount = choice.resources.getQuantityString(R.plurals.shield_apps_selected, 1, 1)
                waitFor(activity, "saved selection") {
                    runBlocking(Dispatchers.IO) { withTimeout(1_000) { activity.settingsRepository.read(PrefKey.ShieldedPackages) } } ==
                        setOf("example.reader") && checkbox(list, "阅读")?.isChecked == true &&
                        choice.findViewById<TextView>(R.id.summary).text.toString() == selectedCount
                }
                capture(choice, "shield-picker-selected")
                assertTrue(choice.findViewById<View>(R.id.back).performClick())
                host.childFragmentManager.executePendingTransactions()
                assertEquals(R.id.settingsFragment, host.navController.currentDestination?.id)
                val reopened = openPicker(activity, host)
                val reopenedList = reopened.findViewById<ListView>(R.id.apps)
                waitFor(activity, "choice after returning") { checkbox(reopenedList, "阅读")?.isChecked == true }

                val query = reopened.findViewById<EditText>(R.id.search)
                query.setText("matching-no-app")
                waitFor(activity, "empty search") {
                    reopenedList.count == 1 && reopened.findViewById<TextView>(R.id.empty).text ==
                        activity.getString(R.string.shield_apps_no_match)
                }
                capture(reopened, "shield-picker-no-match")
            }
        }
    }

    private fun openPicker(activity: MainActivity, host: NavHostFragment): View {
        val settings = host.childFragmentManager.primaryNavigationFragment!!.requireView()
        assertTrue(settings.findViewWithTag<View>(PrefKey.ShieldedPackages).performClick())
        host.childFragmentManager.executePendingTransactions()
        assertEquals(R.id.shieldAppsFragment, host.navController.currentDestination?.id)
        sizeScreen(activity)
        return (host.childFragmentManager.primaryNavigationFragment as ShieldAppsFragment).requireView()
    }

    private fun checkbox(list: ListView, label: String): MaterialCheckBox? =
        (0 until list.childCount).asSequence().map(list::getChildAt).filterIsInstance<MaterialCheckBox>()
            .firstOrNull { it.text.toString() == label }

    private fun waitFor(activity: MainActivity, stage: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            sizeScreen(activity)
            if (ready()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        org.junit.Assert.fail(stage)
    }

    private fun sizeScreen(activity: MainActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val width = (activity.resources.configuration.screenWidthDp * activity.resources.displayMetrics.density).toInt()
        val height = (activity.resources.configuration.screenHeightDp * activity.resources.displayMetrics.density).toInt()
        content.findViewById<ListView>(R.id.apps)?.forceLayout()
        content.forceLayout()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, width, height)
    }

    private fun capture(view: View, name: String) {
        val folder = File("build/reports/ui-shield-apps").apply { check(isDirectory || mkdirs()) }
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            File(folder, "$name.png").outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }
}
