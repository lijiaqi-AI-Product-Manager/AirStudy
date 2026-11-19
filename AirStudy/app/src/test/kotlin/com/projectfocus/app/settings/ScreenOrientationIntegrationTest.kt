package com.projectfocus.app.settings

import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.MainActivity
import com.projectfocus.app.R
import com.projectfocus.core.datastore.Orientation
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.ui.nav.FlightPreparationNavigator
import com.projectfocus.feature.profile.ui.PrefRowView
import com.projectfocus.feature.profile.ui.SegmentedControlView
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import com.projectfocus.feature.profile.R as ProfileR


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenOrientationIntegrationTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var repository: SettingsRepository

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle()
        repository = activityValue { it.settingsRepository }
        runBlocking(Dispatchers.IO) { repository.resetToDefaults() }
        awaitOrientation(Orientation.AUTO, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        activityValue { nav(it).navigate(R.id.homeFragment) }
        await("production Home ready") { activityValue { nav(it).currentDestination?.id } == R.id.homeFragment }
    }

    @After
    fun close() {
        if (::repository.isInitialized) runBlocking(Dispatchers.IO) { repository.resetToDefaults() }
        scenario?.close()
        scenario = null
    }

    @Test
    fun `real three choices apply globally across Home and preparation`() {
        openSettings()
        activityValue { activity ->
            assertThat(buttons(activity).map { it.text.toString() }).containsExactly("自动", "横屏", "竖屏").inOrder()
            assertThat(control(activity).selectedValue()).isEqualTo(Orientation.AUTO)
        }
        choose(Orientation.LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        choose(Orientation.PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        choose(Orientation.AUTO, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        choose(Orientation.LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        activityValue { nav(it).popBackStack() }
        await("Home keeps global landscape") {
            activityValue { nav(it).currentDestination?.id == R.id.homeFragment &&
                it.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        }
        // A real store update outside Settings must still reach the Activity consumer.
        runBlocking(Dispatchers.IO) { repository.write(PrefKey.ScreenOrientation, Orientation.PORTRAIT) }
        awaitOrientation(Orientation.PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        activityValue { FlightPreparationNavigator.open(nav(it), "", "") }
        await("preparation keeps global portrait") {
            activityValue { nav(it).currentDestination?.id == R.id.flightPreparationFragment &&
                it.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }

    @Test
    fun `saved portrait survives Activity recreation and a new Activity`() {
        openSettings()
        choose(Orientation.PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        scenario!!.recreate()
        awaitOrientation(Orientation.PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        await("real settings back stack restored") {
            activityValue { nav(it).currentDestination?.id } == R.id.settingsFragment
        }
        await("recreated settings renders saved portrait") { activityValue { control(it).selectedValue() } == Orientation.PORTRAIT }
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitOrientation(Orientation.PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }

    @Test
    fun `real reset cancel retains orientation and confirmation restores source default auto`() {
        openSettings()
        choose(Orientation.LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        fun resetDialog(): AlertDialog {
            activityValue { activity ->
                page(activity).findViewById<View>(ProfileR.id.btn_reset).performClick()
            }
            idle()
            return ShadowDialog.getLatestDialog() as AlertDialog
        }
        val cancelled = resetDialog()
        cancelled.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertThat(cancelled.isShowing).isFalse()
        awaitOrientation(Orientation.LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        resetDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        awaitOrientation(Orientation.AUTO, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        await("actual control resets to auto") { activityValue { control(it).selectedValue() } == Orientation.AUTO }
    }

    private fun openSettings() {
        activityValue { nav(it).navigate(R.id.action_home_to_settings) }
        await("production Settings ready") { activityValue { nav(it).currentDestination?.id } == R.id.settingsFragment }
        await("orientation row rendered") { activityValue { page(it).walk().filterIsInstance<PrefRowView>()
            .any { row -> row.tag == PrefKey.ScreenOrientation } } }
    }

    private fun choose(value: String, requested: Int) {
        val text = when (value) {
            Orientation.LANDSCAPE -> "横屏"
            Orientation.PORTRAIT -> "竖屏"
            else -> "自动"
        }
        await("real control enabled for $value") { activityValue { activity ->
            buttons(activity).single { it.text.toString() == text }.isEnabled
        } }
        activityValue { activity -> buttons(activity).single { it.text.toString() == text }.performClick() }
        awaitOrientation(value, requested)
        await("selected real control=$value") { activityValue { control(it).selectedValue() } == value }
    }

    private fun awaitOrientation(value: String, requested: Int) = await("stored=$value, Android request=$requested") {
        runBlocking(Dispatchers.IO) { repository.read(PrefKey.ScreenOrientation) } == value &&
            activityValue { it.requestedOrientation } == requested
    }

    private fun nav(activity: MainActivity) =
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment).navController

    private fun page(activity: MainActivity): View =
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment)
            .childFragmentManager.primaryNavigationFragment!!.requireView()

    private fun control(activity: MainActivity): SegmentedControlView = page(activity).walk()
        .filterIsInstance<PrefRowView>().single { it.tag == PrefKey.ScreenOrientation }
        .walk().filterIsInstance<SegmentedControlView>().single()

    private fun buttons(activity: MainActivity) = control(activity).let { control ->
        (0 until control.childCount).map { control.getChildAt(it) as RadioButton }
    }

    private fun View.walk(): Sequence<View> = sequence {
        yield(this@walk)
        if (this@walk is ViewGroup) for (index in 0 until this@walk.childCount) yieldAll(this@walk.getChildAt(index).walk())
    }

    private fun <T> activityValue(block: (MainActivity) -> T): T {
        var result: T? = null
        scenario!!.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        scenario?.onActivity { it.supportFragmentManager.executePendingTransactions() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(reason: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("$reason; requested=${activityValue { it.requestedOrientation }}, " +
            "stored=${runBlocking(Dispatchers.IO) { repository.read(PrefKey.ScreenOrientation) }}")
    }
}
