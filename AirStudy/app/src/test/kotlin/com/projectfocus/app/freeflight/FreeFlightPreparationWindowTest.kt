package com.projectfocus.app.freeflight

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.MainActivity
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.ui.scene.AnchoredScenePickerDialog
import com.projectfocus.feature.booking.seat.SeatSceneChoices
import com.projectfocus.feature.booking.seat.SeatSelectionMapView
import com.projectfocus.feature.home.map.MapPhase
import com.projectfocus.feature.home.map.SourceMapHostView
import com.projectfocus.service.focus.freeflight.FreeFlightStartState
import com.projectfocus.service.focus.session.SessionState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Production controller/views draw in the ACTUAL Hilt Main window/native Canvas. Required backend
 * actions are explicitly fixtures: these are component UI tests, not real admission/HMS/flight E2E.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [34], qualifiers = "zh-rCN-w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FreeFlightPreparationWindowTest {
    private lateinit var activityController: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private var controller: FreeFlightPreparationController? = null
    private lateinit var actions: FreeFlightPreparationCoordinatorTest.Fixture

    @Before fun setUp() {
        // This is the real Application, including its native Room import. Do not destroy its
        // process while that actual startup transaction is still constructing the first window.
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.withTimeout(15_000L) {
                val app = ApplicationProvider.getApplicationContext<ProjectFocusApp>()
                app.awaitInitialAccountRestore();app.awaitInitialAirportSeed()
            }
        }
        activityController = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        activity = activityController.get()
        activity.supportFragmentManager.executePendingTransactions()
        val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        host.navController.navigate(R.id.action_permission_to_home)
        host.childFragmentManager.executePendingTransactions();idle()
        actions = FreeFlightPreparationCoordinatorTest.Fixture()
    }

    @After fun tearDown() {
        try { controller?.detach();activityController.pause().stop().destroy() }
        finally { shutdownRealApplicationAfterTest(ApplicationProvider.getApplicationContext<ProjectFocusApp>()) }
    }

    @Test fun actualMainWindowDoesNotCreateSurfaceOrCallPreparationWithUnavailableDependencies() {
        actions.unavailableReason = "Actual runtime dependencies unavailable"
        controller = FreeFlightPreparationController(activity, actions).also { it.begin() }
        await { controller!!.coordinator.state.value.step == FreeFlightPreparationStep.UNAVAILABLE }
        assertThat(activity.findViewById<View>(R.id.pf_ff_root)).isNull()
        assertThat(actions.prepareCalls).isEqualTo(0);assertThat(actions.requests).isEmpty()
        assertRealCountdownUntouched()
    }

    @Test fun headingUsesActualNativeCanvasAndSourcePixelsWhileCancelRemovesOnlyOwnSurface() {
        HomeMapStyleSelection.select(activity, 2)
        controller = FreeFlightPreparationController(activity, actions).also { it.begin() }
        await { controller!!.coordinator.state.value.step == FreeFlightPreparationStep.HEADING }
        advance(500)
        val root = activity.findViewById<View>(R.id.pf_ff_root)
        assertThat(root.parent).isSameInstanceAs(activity.findViewById<ViewGroup>(android.R.id.content))
        assertThat(root.paddingTop).isEqualTo(0);assertThat(root.paddingBottom).isEqualTo(0)
        val ruler = activity.findViewById<FreeFlightHeadingRulerView>(R.id.pf_ff_heading_ruler)
        assertThat(ruler.height).isEqualTo(300);assertThat(ruler.uiScale).isEqualTo(1f)
        assertThat(activity.findViewById<TextView>(R.id.pf_ff_heading_title).text.toString()).isEqualTo(activity.getString(R.string.pf_ff_free_flight_adjust_heading))
        val image = Bitmap.createBitmap(ruler.width, ruler.height, Bitmap.Config.ARGB_8888)
        ruler.draw(Canvas(image))
        assertThat(image.getPixel(ruler.width / 2, ruler.height - 150 + 25)).isEqualTo(0xFFFFD700.toInt())
        val now = SystemClock.uptimeMillis()
        fun event(action: Int, time: Long, x: Float) = MotionEvent.obtain(now, time, action, x, 80f, 0)
        event(MotionEvent.ACTION_DOWN, now, 250f).also { ruler.dispatchTouchEvent(it);it.recycle() }
        event(MotionEvent.ACTION_MOVE, now + 100, 150f).also { ruler.dispatchTouchEvent(it);it.recycle() }
        event(MotionEvent.ACTION_CANCEL, now + 200, 150f).also { ruler.dispatchTouchEvent(it);it.recycle() }
        await { actions.headings.lastOrNull()?.let { it > 0 } == true }
        capture("heading-actual-main")
        activity.findViewById<View>(R.id.pf_ff_heading_cancel).performClick();advance(350)
        assertThat(activity.findViewById<View>(R.id.pf_ff_root)).isNull()
        assertThat(actions.dismissals).containsExactly(false);assertThat(actions.requests).isEmpty()
        assertRealCountdownUntouched()
    }

    @Test fun actualSeatSceneControlsReachSourceDayAndNightReadyInMainWindow() {
        for ((night, style) in listOf(false to 4, true to 2)) {
            controller?.detach();HomeMapStyleSelection.select(activity, style)
            actions = FreeFlightPreparationCoordinatorTest.Fixture()
            controller = FreeFlightPreparationController(activity, actions).also { it.begin() }
            await { controller!!.coordinator.state.value.step == FreeFlightPreparationStep.HEADING };advance(500)
            activity.findViewById<View>(R.id.pf_ff_heading_continue).performClick()
            await { activity.findViewById<SeatSelectionMapView>(R.id.pf_ff_seat_map) != null };advance(700)
            val catalog = activity.seatSceneChoices(Bundle()) as SeatSceneChoices.Ready
            assertThat(catalog.values).isNotEmpty();val chosen = catalog.values.first()
            val map = activity.findViewById<SeatSelectionMapView>(R.id.pf_ff_seat_map)
            val chair = walk(map).filterIsInstance<TextView>().single { it.tag == "01A" }
            assertThat(chair.text.toString()).isEmpty();chair.performClick();idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertThat(dialog).isInstanceOf(AnchoredScenePickerDialog::class.java)
            val label = walk(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == chosen.title }
            var target: View = label
            while (!target.isClickable) target = target.parent as View
            target.performClick();idle()
            assertThat(controller!!.coordinator.state.value.selection?.scene?.optionId).isEqualTo(chosen.optionId)
            assertThat(controller!!.coordinator.state.value.selection?.seat).isEqualTo("1A")
            val confirm = activity.findViewById<View>(R.id.pf_ff_seat_confirm)
            assertThat(confirm.isShown).isTrue();confirm.performClick();confirm.performClick()
            await { controller!!.coordinator.state.value.step == FreeFlightPreparationStep.READY };advance(600)
            val ready = activity.findViewById<View>(R.id.pf_ff_ready)
            assertThat((ready.background as ColorDrawable).color).isEqualTo(if (night) 0xCC000000.toInt() else 0xCCFFFFFF.toInt())
            val status = activity.findViewById<TextView>(R.id.pf_ff_ready_status)
            assertThat(status.currentTextColor).isEqualTo(if (night) Color.WHITE else Color.BLACK)
            assertThat(status.text.toString()).isEqualTo(activity.getString(R.string.pf_ff_ready_to_fly_status))
            val button = activity.findViewById<FreeFlightDepartureButton>(R.id.pf_ff_depart)
            assertThat(button.width).isEqualTo(120);assertThat(button.height).isEqualTo(40)
            val paint = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888);button.draw(Canvas(paint))
            assertThat(paint.getPixel(button.width / 4, (button.height * .85f).toInt())).isEqualTo(if (night) Color.WHITE else Color.BLACK)
            capture(if (night) "ready-night-actual-main" else "ready-day-actual-main")
            assertThat(actions.requests).isEmpty();assertRealCountdownUntouched()
        }
    }

    @Test fun actualCustomButtonDoubleClickIsOneFormalWaitAndRotationAdoptsItsRetainedPendingFixture() {
        val first = FreeFlightPreparationController(activity, actions);controller = first
        first.begin();await { first.coordinator.state.value.step == FreeFlightPreparationStep.HEADING }
        first.coordinator.continueToSeat();await { first.coordinator.state.value.step == FreeFlightPreparationStep.SEAT }
        val scene = (activity.seatSceneChoices(Bundle()) as SeatSceneChoices.Ready).values.first()
        first.coordinator.confirmSeat(FreeFlightSeatChoice("1A", scene))
        await { first.coordinator.state.value.step == FreeFlightPreparationStep.READY };advance(600)
        val button = activity.findViewById<FreeFlightDepartureButton>(R.id.pf_ff_depart)
        val time = SystemClock.uptimeMillis()
        for (index in 0..1) {
            MotionEvent.obtain(time, time + index * 20, MotionEvent.ACTION_DOWN, 60f, 20f, 0).also { button.dispatchTouchEvent(it);it.recycle() }
            MotionEvent.obtain(time, time + index * 20 + 10, MotionEvent.ACTION_UP, 60f, 20f, 0).also { button.dispatchTouchEvent(it);it.recycle() }
        }
        await { actions.requests.size == 1 }
        actions.startState.value = FreeFlightStartState.Pending(actions.identity, "fixture-actual-pending")
        await { first.coordinator.state.value.step == FreeFlightPreparationStep.PENDING }
        val saved = checkNotNull(first.saved());first.detach()
        controller = FreeFlightPreparationController(activity, actions).also { it.restore(saved) }
        await { controller!!.coordinator.state.value.step == FreeFlightPreparationStep.PENDING }
        assertThat(actions.requests).hasSize(1);assertThat(actions.prepareCalls).isEqualTo(1);assertThat(actions.restoreCalls).isEqualTo(1)
        assertThat(activity.findViewById<View>(R.id.pf_ff_depart)).isNull()
        actions.startState.value = FreeFlightStartState.Finished(actions.identity, actions.commit(), false)
        await { controller!!.coordinator.state.value.step == FreeFlightPreparationStep.STARTED }
        assertThat(actions.outcomes).hasSize(1);assertThat(actions.dismissals).isEmpty()
        assertRealCountdownUntouched()
    }

    @Test fun savedFlightShowsAnActualRuntimeRetryButtonWithoutRepeatingFormalAdmission() {
        val actual = FreeFlightPreparationController(activity, actions).also { controller = it; it.begin() }
        await { actual.coordinator.state.value.step == FreeFlightPreparationStep.HEADING }
        actual.coordinator.continueToSeat(); await { actual.coordinator.state.value.step == FreeFlightPreparationStep.SEAT }
        val scene = (activity.seatSceneChoices(Bundle()) as SeatSceneChoices.Ready).values.first()
        actual.coordinator.confirmSeat(FreeFlightSeatChoice("1A", scene))
        await { actual.coordinator.state.value.step == FreeFlightPreparationStep.READY }; advance(600)
        activity.findViewById<View>(R.id.pf_ff_depart).performClick()
        await { actions.requests.size == 1 }
        actions.startState.value = FreeFlightStartState.Finished(actions.identity, actions.commit(confirmRuntime = false), false)
        await { actual.coordinator.state.value.step == FreeFlightPreparationStep.RUNTIME_WAITING }; advance(600)
        val retry = activity.findViewById<View>(R.id.pf_ff_retry_runtime)
        assertThat(retry.isShown).isTrue(); retry.performClick()
        await { actions.runtimeRetries == 1 }
        assertThat(actions.requests).hasSize(1); assertThat(actions.outcomes).isEmpty()
        actions.runtimeState.value = actions.running()
        await { actual.coordinator.state.value.step == FreeFlightPreparationStep.STARTED }
        assertThat(activity.findViewById<View>(R.id.pf_ff_retry_runtime)).isNull()
        assertThat(actions.requests).hasSize(1); assertThat(actions.outcomes).hasSize(1)
        assertRealCountdownUntouched()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w400dp-h870dp-xxhdpi")
    fun actualHighDensityPhoneKeepsSourcePixelsWithoutWrappingAndRestoresRealHomeAfterCancel() {
        activity.findViewById<View>(R.id.menuMap).performClick();idle()
        assertThat(activity.findViewById<View>(R.id.mapStyleSelectorContainer).visibility).isEqualTo(View.VISIBLE)
        val map = activity.findViewById<SourceMapHostView>(R.id.sourceMap)
        assertThat(map.state.value.phase).isEqualTo(MapPhase.MISSING_CONFIGURATION)
        controller = FreeFlightPreparationController(activity, actions).also { it.begin() }
        await { controller!!.coordinator.state.value.step == FreeFlightPreparationStep.HEADING };advance(600)
        assertThat(activity.window.decorView.width).isEqualTo(1200)
        assertThat(activity.resources.displayMetrics.density).isEqualTo(3f)
        val ruler = activity.findViewById<FreeFlightHeadingRulerView>(R.id.pf_ff_heading_ruler)
        assertThat(ruler.height).isEqualTo(300)
        val next = activity.findViewById<TextView>(R.id.pf_ff_heading_continue)
        assertThat(next.lineCount).isEqualTo(1)
        assertThat(next.paddingLeft).isEqualTo(80);assertThat(next.paddingRight).isEqualTo(80)
        assertThat(activity.findViewById<View>(R.id.right_menu_container).visibility).isEqualTo(View.INVISIBLE)
        assertThat(activity.findViewById<View>(R.id.btnStartFlight).visibility).isEqualTo(View.GONE)
        assertThat(activity.findViewById<View>(R.id.mapStyleSelectorContainer).visibility).isEqualTo(View.GONE)
        assertThat(activity.findViewById<View>(com.projectfocus.feature.home.R.id.pf_map_status_panel).visibility).isEqualTo(View.GONE)
        assertThat(map.isShown).isTrue();assertThat(map.state.value.phase).isEqualTo(MapPhase.MISSING_CONFIGURATION)
        capture("heading-phone-density-actual-main")
        activity.findViewById<View>(R.id.pf_ff_heading_cancel).performClick();advance(400)
        val menu = activity.findViewById<View>(R.id.right_menu_container)
        val button = activity.findViewById<View>(R.id.btnStartFlight)
        assertThat(menu.visibility).isEqualTo(View.VISIBLE);assertThat(menu.alpha).isEqualTo(1f)
        assertThat(button.visibility).isEqualTo(View.VISIBLE);assertThat(button.isEnabled).isTrue()
        assertThat(button.alpha).isEqualTo(1f);assertThat(button.scaleX).isEqualTo(1f)
        assertThat(activity.findViewById<View>(R.id.groundHomeContent).visibility).isEqualTo(View.VISIBLE)
        assertThat(activity.findViewById<View>(com.projectfocus.feature.home.R.id.pf_map_status_panel).visibility).isEqualTo(View.GONE)
        activity.findViewById<View>(R.id.menuMap).performClick();advance(100)
        assertThat(activity.findViewById<View>(com.projectfocus.feature.home.R.id.pf_map_status_panel).visibility).isEqualTo(View.VISIBLE)
        activity.findViewById<View>(R.id.homeMapClose).performClick();advance(100)
        assertThat(activity.findViewById<View>(R.id.groundHomeContent).visibility).isEqualTo(View.VISIBLE)
        assertThat(map.state.value.phase).isEqualTo(MapPhase.MISSING_CONFIGURATION)
        assertThat(actions.requests).isEmpty();assertRealCountdownUntouched()
    }

    @Test fun delayedOldPresentationReleaseCannotRevealControlsOfTheNewAttachedLease() {
        val first = checkNotNull(activity.beginFreeFlightHomePresentation());advance(100)
        val second = checkNotNull(activity.beginFreeFlightHomePresentation());advance(600)
        first.close();advance(600)
        assertThat(activity.findViewById<View>(R.id.right_menu_container).visibility).isEqualTo(View.INVISIBLE)
        assertThat(activity.findViewById<View>(R.id.btnStartFlight).visibility).isEqualTo(View.GONE)
        assertThat(activity.findViewById<View>(com.projectfocus.feature.home.R.id.pf_map_status_panel).visibility).isEqualTo(View.GONE)
        second.close();advance(600)
        assertThat(activity.findViewById<View>(R.id.right_menu_container).visibility).isEqualTo(View.VISIBLE)
        assertThat(activity.findViewById<View>(R.id.btnStartFlight).isEnabled).isTrue()
        assertRealCountdownUntouched()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h1280dp-mdpi")
    fun tabletMainWindowKeepsActualSource420DpWidthAndPoint52Scale() {
        controller = FreeFlightPreparationController(activity, actions).also { it.begin() }
        await { controller!!.coordinator.state.value.step == FreeFlightPreparationStep.HEADING };advance(500)
        val ruler = activity.findViewById<FreeFlightHeadingRulerView>(R.id.pf_ff_heading_ruler)
        assertThat(ruler.width).isEqualTo(420);assertThat(ruler.height).isEqualTo(105);assertThat(ruler.uiScale).isEqualTo(.52f)
        val body = activity.findViewById<ViewGroup>(R.id.pf_ff_heading_body).getChildAt(0)
        assertThat(body.paddingTop).isEqualTo(12);assertThat(body.paddingBottom).isEqualTo(56)
        capture("heading-tablet-actual-main");assertThat(actions.requests).isEmpty()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w720dp-h432dp-land-mdpi")
    fun compactMainWindowUsesSourceReady30TenTwentyDpMargins() {
        controller = FreeFlightPreparationController(activity, actions).also { it.begin() }
        await { controller!!.coordinator.state.value.step == FreeFlightPreparationStep.HEADING }
        controller!!.coordinator.continueToSeat();await { controller!!.coordinator.state.value.step == FreeFlightPreparationStep.SEAT }
        val scene = (activity.seatSceneChoices(Bundle()) as SeatSceneChoices.Ready).values.first()
        controller!!.coordinator.confirmSeat(FreeFlightSeatChoice("1A", scene))
        await { controller!!.coordinator.state.value.step == FreeFlightPreparationStep.READY };advance(600)
        val icon = activity.findViewById<View>(R.id.pf_ff_ready_plane)
        val status = activity.findViewById<View>(R.id.pf_ff_ready_status)
        val button = activity.findViewById<View>(R.id.pf_ff_depart)
        assertThat((icon.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin).isEqualTo(30)
        assertThat((status.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin).isEqualTo(100)
        assertThat((button.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin).isEqualTo(20)
        capture("ready-compact-actual-main");assertThat(actions.requests).isEmpty()
    }

    private fun assertRealCountdownUntouched() {
        assertThat(activity.sessionRepository.state.value).isEqualTo(SessionState.Idle)
        val app = activity.application as ProjectFocusApp
        assertThat(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().ongoing() }).isNull()
    }

    private fun capture(name: String) {
        val decor = activity.window.decorView
        assertThat(decor.width).isGreaterThan(0);assertThat(decor.height).isGreaterThan(0)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888);decor.draw(Canvas(bitmap))
        val folder = File(System.getProperty("pf.r170.ui.captureDir", "build/r170-freeflight-ui"));folder.mkdirs()
        File(folder, "$name.png").outputStream().use { assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue() }
    }

    private fun walk(view: View): Sequence<View> = sequence {
        yield(view);if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(walk(view.getChildAt(i)))
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS) }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do { advance(16);if (condition()) return;Thread.sleep(5) } while (System.nanoTime() < deadline)
        throw AssertionError("Production Main UI did not reach the requested state; no alternate renderer was used")
    }
}
