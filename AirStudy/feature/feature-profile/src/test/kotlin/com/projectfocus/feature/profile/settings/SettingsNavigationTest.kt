package com.projectfocus.feature.profile.settings

import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.createGraph
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.fragment
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.projectfocus.core.datastore.Orientation
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.feature.profile.R
import com.projectfocus.feature.profile.ui.PrefRowView
import com.projectfocus.feature.profile.ui.SegmentedControlView
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Uses the real SettingsFragment and FragmentNavigator, rather than a fake controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "notnight", application = HiltTestApplication::class)
@HiltAndroidTest
class SettingsNavigationTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var repository: SettingsRepository

    @Test
    @Config(qualifiers = "w320dp-h640dp-notnight")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `three choices remain equal readable and clickable at 320dp`() {
        hilt.inject()
        ActivityScenario.launch(SettingsNavigationHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val settings = showSettings(activity)
                val root = settings.requireView()
                measureAt320dp(root)
                val row = root.descendants().filterIsInstance<PrefRowView>()
                    .single { it.tag == PrefKey.ScreenOrientation }
                val control = row.descendants().filterIsInstance<SegmentedControlView>().single()
                val buttons = (0 until control.childCount).map { control.getChildAt(it) as RadioButton }

                assertThat(buttons).hasSize(3)
                assertThat(buttons.map { it.text.toString() }).containsExactly("自动", "横屏", "竖屏").inOrder()
                val widths = buttons.map { it.width }
                assertThat(widths.maxOrNull()!! - widths.minOrNull()!!).isAtMost(1)
                assertThat(widths.minOrNull()!!).isAtLeast(dp(root, 80))
                buttons.forEach { button ->
                    assertThat(button.layout.lineCount).isEqualTo(1)
                    assertThat(button.layout.getEllipsisCount(0)).isEqualTo(0)
                }
                val title = row.descendants().filterIsInstance<TextView>()
                    .single { it.text.toString() == root.context.getString(R.string.pref_screen_orientation) }
                assertThat(title.layout.lineCount).isEqualTo(1)
                assertThat(row.height).isAtMost(dp(root, 160))

                // CompoundButton can toggle successfully while View.performClick returns false
                // when no separate OnClickListener was registered. Check the actual selection.
                buttons.last().performClick()
                assertThat(control.selectedValue()).isEqualTo(Orientation.PORTRAIT)
                assertThat(buttons.last().isChecked).isTrue()
            }
            val saved = awaitRenderedPreference(scenario, PrefKey.ScreenOrientation, "segment click") {
                it == Orientation.PORTRAIT
            }
            assertThat(saved).isEqualTo(Orientation.PORTRAIT)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-notnight")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `visible slider midpoint touch saves an intermediate float through the real view model`() {
        hilt.inject()
        runBlocking(Dispatchers.IO) { repository.write(PrefKey.MaterialAlpha, 0.15f) }
        ActivityScenario.launch(SettingsNavigationHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> measureAt320dp(showSettings(activity).requireView()) }
            // The initial DataStore emission must reach the actual UI before the gesture starts.
            awaitRenderedPreference(scenario, PrefKey.MaterialAlpha, "slider initialization") { it == 0.15f }
            scenario.onActivity { activity ->
                val settings = activity.host.childFragmentManager.primaryNavigationFragment as SettingsFragment
                val root = settings.requireView()
                measureAt320dp(root)
                val row = root.descendants().filterIsInstance<PrefRowView>()
                    .single { it.tag == PrefKey.MaterialAlpha }
                val seek = row.descendants().filterIsInstance<SeekBar>().single()
                // This is measured from the actual Fragment hierarchy, not a standalone control.
                assertThat(seek.width).isAtLeast(dp(root, 240))
                assertThat(seek.height).isGreaterThan(0)
                assertThat(seek.isEnabled).isTrue()
                seek.requestRectangleOnScreen(Rect(0, 0, seek.width, seek.height), true)
                shadowOf(Looper.getMainLooper()).idle()
                val bounds = Rect(0, 0, seek.width, seek.height)
                (root as ViewGroup).offsetDescendantRectToMyCoords(seek, bounds)
                assertThat(bounds.top).isAtLeast(0)
                assertThat(bounds.bottom).isAtMost(root.height)
                val startX = bounds.left + seek.paddingLeft + dp(root, 4).toFloat()
                val midpointX = bounds.left + (seek.paddingLeft + seek.width - seek.paddingRight) / 2f
                val y = bounds.exactCenterY()
                val now = SystemClock.uptimeMillis()
                // Dispatch through the visible root so NestedScrollView and SeekBar both receive
                // the real drag. MOVE exceeds horizontal touch slop before releasing at midpoint.
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                    val x = if (action == MotionEvent.ACTION_DOWN) startX else midpointX
                    val event = MotionEvent.obtain(now, now + index * 16L, action, x, y, 0)
                    try {
                        assertThat(root.dispatchTouchEvent(event)).isTrue()
                    } finally {
                        event.recycle()
                    }
                }
                assertWithMessage("midpoint gesture: progress=${seek.progress}/${seek.max}, " +
                    "tag=${seek.tag}, bounds=$bounds, x=$midpointX, initializedRepository=0.15")
                    .that(seek.progress.toFloat() / seek.max).isWithin(0.02f).of(0.5f)
                shadowOf(Looper.getMainLooper()).idle()
            }
            // Wait for the actual write. Integer division in the old binding saved 0.15f here.
            val saved = awaitRenderedPreference(scenario, PrefKey.MaterialAlpha, "slider midpoint gesture") {
                it > 0.48f && it < 0.52f
            }
            assertThat(saved).isWithin(0.01f).of(0.50f)
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                val settings = activity.host.childFragmentManager.primaryNavigationFragment as SettingsFragment
                val viewModel = ViewModelProvider(settings)[SettingsViewModel::class.java]
                assertThat(viewModel.valueOf(PrefKey.MaterialAlpha)).isWithin(0.01f).of(0.50f)
            }
        }
    }

    @Test
    fun `toolbar back returns home and leaves a consistent navigable stack`() {
        hilt.inject()
        ActivityScenario.launch(SettingsNavigationHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = activity.host
                val nav = host.navController
                nav.graph = nav.createGraph(startDestination = HOME, id = GRAPH) {
                    fragment<SettingsNavigationHomeFragment>(HOME)
                    fragment<SettingsFragment>(SETTINGS)
                }
                host.childFragmentManager.executePendingTransactions()
                nav.navigate(SETTINGS)
                host.childFragmentManager.executePendingTransactions()
                shadowOf(Looper.getMainLooper()).idle()

                assertThat(nav.currentDestination?.id).isEqualTo(SETTINGS)
                assertThat(nav.previousBackStackEntry?.destination?.id).isEqualTo(HOME)
                val settings = host.childFragmentManager.primaryNavigationFragment as SettingsFragment
                assertThat(settings.requireView().findViewById<View>(R.id.btn_back).performClick()).isTrue()
                host.childFragmentManager.executePendingTransactions()
                shadowOf(Looper.getMainLooper()).idle()

                assertThat(nav.currentDestination?.id).isEqualTo(HOME)
                assertThat(nav.previousBackStackEntry).isNull()
                assertThat(host.childFragmentManager.primaryNavigationFragment)
                    .isInstanceOf(SettingsNavigationHomeFragment::class.java)

                // A stale NavController entry can look correct visually until the next navigation.
                nav.navigate(SETTINGS)
                host.childFragmentManager.executePendingTransactions()
                shadowOf(Looper.getMainLooper()).idle()
                assertThat(nav.currentDestination?.id).isEqualTo(SETTINGS)
                assertThat(nav.previousBackStackEntry?.destination?.id).isEqualTo(HOME)
                activity.onBackPressedDispatcher.onBackPressed()
                host.childFragmentManager.executePendingTransactions()
                shadowOf(Looper.getMainLooper()).idle()
                assertThat(nav.currentDestination?.id).isEqualTo(HOME)
                assertThat(nav.previousBackStackEntry).isNull()
            }
        }
    }

    private fun showSettings(activity: SettingsNavigationHostActivity): SettingsFragment {
        val host = activity.host
        host.navController.graph = host.navController.createGraph(startDestination = HOME, id = GRAPH) {
            fragment<SettingsNavigationHomeFragment>(HOME)
            fragment<SettingsFragment>(SETTINGS)
        }
        host.childFragmentManager.executePendingTransactions()
        host.navController.navigate(SETTINGS)
        host.childFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        return host.childFragmentManager.primaryNavigationFragment as SettingsFragment
    }

    private fun <T> awaitRenderedPreference(
        scenario: ActivityScenario<SettingsNavigationHostActivity>,
        key: PrefKey<T>,
        stage: String,
        matches: (T) -> Boolean,
    ): T {
        val lastStored = AtomicReference<T?>(null)
        var lastUi = "not sampled"
        try {
            return runBlocking {
                val saved = async(Dispatchers.IO) {
                    repository.observe(key).onEach { lastStored.set(it) }.first { matches(it) }
                }
                withTimeout(5_000) {
                    var modelMatches = false
                    while (!saved.isCompleted || !modelMatches) {
                        scenario.onActivity { activity ->
                            // DataStore transforms inherit the writer's Main context. Blocking
                            // this Looper while waiting on IO can starve a completely valid write.
                            shadowOf(Looper.getMainLooper()).idle()
                            val settings = activity.host.childFragmentManager.primaryNavigationFragment as SettingsFragment
                            val model = ViewModelProvider(settings)[SettingsViewModel::class.java]
                            val value = model.valueOf(key)
                            modelMatches = matches(value)
                            val row = settings.requireView().descendants().filterIsInstance<PrefRowView>()
                                .single { it.tag == key }
                            val seek = row.descendants().filterIsInstance<SeekBar>().singleOrNull()
                            lastUi = "model=$value, progress=${seek?.progress}/${seek?.max}, " +
                                "tag=${seek?.tag}, size=${seek?.width}x${seek?.height}, enabled=${seek?.isEnabled}"
                        }
                        if (!saved.isCompleted || !modelMatches) delay(10)
                    }
                    saved.await()
                }
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError("$stage: ${key.name}, repository=${lastStored.get()}, $lastUi", failure)
        }
    }

    private fun measureAt320dp(root: View) {
        val width = dp(root, 320)
        val height = dp(root, 640)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }

    private companion object {
        const val GRAPH = 101
        const val HOME = 102
        const val SETTINGS = 103
    }
}

@AndroidEntryPoint
class SettingsNavigationHostActivity : FragmentActivity() {
    lateinit var host: NavHostFragment
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(container)
        host = NavHostFragment()
        supportFragmentManager.beginTransaction()
            .replace(container.id, host)
            .setPrimaryNavigationFragment(host)
            .commitNow()
    }
}

class SettingsNavigationHomeFragment : Fragment(android.R.layout.simple_list_item_1)
