package com.projectfocus.app.countdown

import android.Manifest
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.app.MainActivity
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.app.route.RecordCloudHttpFixture
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.booking.seat.SeatSceneChoices
import com.projectfocus.feature.booking.seat.SeatSelectionMapView
import com.projectfocus.service.focus.session.SessionState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowChoreographer
import com.projectfocus.feature.completion.R as PassR
import com.projectfocus.feature.focus.R as ModeR

/** Real ordinary entry, native controls, retained coordinator, Core and Room; no device or map-service acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [34], qualifiers = "zh-rCN-w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrdinaryFlightWindowTest {
    private lateinit var app: ProjectFocusApp
    private lateinit var activityController: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var records: RecordCloudHttpFixture

    @Before fun open() {
        app = ApplicationProvider.getApplicationContext()
        if (app.resources.configuration.screenWidthDp <= 320) RuntimeEnvironment.setFontScale(1.6f)
        runBlocking(Dispatchers.IO) { withTimeout(20_000) { app.awaitInitialAccountRestore(); app.awaitInitialAirportSeed() } }
        records = RecordCloudHttpFixture(app.recordCloudMutations, app.recordCloudSync)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking(Dispatchers.IO) { TokenStore.setCancellable("ordinary-native-window-account") }
        app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, android.content.Context.MODE_PRIVATE).edit()
            .clear().putString("origin_code", "PEK").putString("destination_code", "TSN").commit()
        HomeMapStyleSelection.select(app, 4)
        activityController = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        activity = activityController.get()
        runBlocking(Dispatchers.IO) { activity.settingsRepository.write(PrefKey.BlacklistEnabled, false) }
        activity.supportFragmentManager.executePendingTransactions()
        navHost().navController.navigate(R.id.action_permission_to_home)
        navHost().childFragmentManager.executePendingTransactions()
        await("Home start control") { activity.findViewById<View>(R.id.btnStartFlight)?.isShown == true }
    }

    @After fun close() {
        try {
            if (::activityController.isInitialized) activityController.pause().stop().destroy()
            TokenStore.clear()
        } finally {
            try { shutdownRealApplicationAfterTest(app) }
            finally { if (::records.isInitialized) records.close() }
        }
    }

    @Test fun actualHomeSeatInspectionReadyAndDepartCreateOneFrozenFlightAndRecord() {
        activity.findViewById<View>(R.id.btnStartFlight).performClick()
        await("Ordinary seats") { coordinator().state.value.step == OrdinaryPreparationStep.SEAT }
        assertEquals(R.id.homeFragment, navHost().navController.currentDestination!!.id)
        assertIdleAndNoRecord()
        capture("ordinary-seats-day")
        chooseAndConfirmSeat(checkSystemAreas = true)
        val opened = requireNotNull(coordinator().state.value.route)
        val number = requireNotNull(opened.flightNumber)
        assertEquals("1A", opened.seat)
        assertNotNull(opened.scene)
        assertEquals(number, activity.findViewById<TextView>(PassR.id.tv_flight_code).text.toString())
        assertIdleAndNoRecord()
        assertPaperTransparency()
        capture("ordinary-boarding-day")
        activity.findViewById<View>(R.id.ordinaryCheckIn).performClick()
        await("Inspection surface") { coordinator().state.value.step == OrdinaryPreparationStep.INSPECTION }
        assertIdleAndNoRecord()
        capture("ordinary-inspection-day")
        tearActualBand()
        await("Boarding confirmation") { activity.findViewById<View>(R.id.ordinaryPrepareBoarding)?.isShown == true }
        assertIdleAndNoRecord()
        capture("ordinary-prepare-day")
        activity.findViewById<View>(R.id.ordinaryPrepareBoarding).performClick()
        await("Ready surface") { activity.findViewById<View>(R.id.ordinaryReady)?.isShown == true }
        assertIdleAndNoRecord()
        advance(550)
        capture("ordinary-ready-day")
        val depart = activity.findViewById<View>(R.id.pf_ff_depart)
        depart.performClick(); depart.performClick()
        await("Accepted ordinary Core session") {
            coordinator().state.value.step == OrdinaryPreparationStep.STARTED && activity.sessionRepository.state.value is SessionState.Running &&
                activity.findViewById<View>(R.id.ordinaryPreparation) == null
        }
        assertNull(activity.findViewById<View>(R.id.ordinaryPreparation))
        assertEquals(number, activity.sessionRepository.currentRoute().flightNumber)
        assertEquals(opened.scene, activity.sessionRepository.currentRoute().scene)
        val ongoing = runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().ongoing() }!!
        assertEquals(number, ongoing.flightNumber)
        assertEquals("1A", ongoing.seat)
        assertEquals(opened.departure.name, ongoing.departureAirportName)
        assertEquals(opened.destination.name, ongoing.destinationAirportName)
        capture("ordinary-running-day")
        val protectedInsets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(0, 36, 0, 28))
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout(), androidx.core.graphics.Insets.of(48, 0, 0, 0))
            .build()
        val container = activity.findViewById<View>(R.id.nav_host_container)
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(container, protectedInsets)
        advance(32)
        val protectedPause = android.graphics.Rect()
        assertTrue(activity.findViewById<View>(com.projectfocus.feature.flight.R.id.pf_flight_pause).getGlobalVisibleRect(protectedPause))
        assertTrue(protectedPause.left >= 48 && protectedPause.top >= 36)
        val protectedStats = android.graphics.Rect()
        assertTrue(activity.findViewById<View>(com.projectfocus.feature.flight.R.id.pf_flight_distance_container).getGlobalVisibleRect(protectedStats))
        assertTrue(protectedStats.bottom <= container.height - 28)
        capture("focus-system-areas", "ui-insets")
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(container, androidx.core.view.WindowInsetsCompat.Builder().build())
        advance(32)
        val clock = activity.findViewById<TextView>(com.projectfocus.feature.flight.R.id.pf_flight_focus_clock)
        assertTrue(activity.findViewById<View>(com.projectfocus.feature.flight.R.id.pf_flight_focus_scroll).isShown)
        assertTrue(clock.text.toString().matches(Regex("[0-9]+:[0-9]{2}")))
        activity.findViewById<View>(com.projectfocus.feature.flight.R.id.pf_flight_pause).performClick()
        await("Actual paused flight") { activity.sessionRepository.state.value is SessionState.Paused }
        await("Actual pause shown") { activity.findViewById<TextView>(com.projectfocus.feature.flight.R.id.pf_flight_focus_status).text.toString() ==
            activity.getString(com.projectfocus.feature.flight.R.string.pf_flight_paused) }
        val pausedClock = clock.text.toString()
        advance(2_000)
        assertEquals(pausedClock, clock.text.toString())
        capture("focus-actual-paused-day", "ui-focus")
        activity.findViewById<View>(com.projectfocus.feature.flight.R.id.pf_flight_pause).performClick()
        await("Actual resumed flight") { activity.sessionRepository.state.value is SessionState.Running }
        capture("focus-actual-running-day", "ui-focus")
        runBlocking(Dispatchers.IO) { assertTrue(activity.sessionRepository.abandon()); assertNotNull(activity.sessionRepository.settle()) }
        val row = runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().findById(ongoing.id) }!!
        assertEquals(number, row.flightNumber)
        assertEquals("1A", row.seat)
        assertEquals(1, runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().getAll().size })
    }

    @Test fun countdownModeSelectionReturnsToTheActualOrdinarySeatFlow() {
        activity.findViewById<View>(R.id.menuTakeOff).performClick()
        await("Flight mode page") { walk(activity.window.decorView).filterIsInstance<TextView>()
            .any { it.text.toString() == activity.getString(ModeR.string.flight_mode_select_countdown) } }
        val label = walk(activity.window.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == activity.getString(ModeR.string.flight_mode_select_countdown) }
        clickParent(label)
        await("Mode countdown seats") { coordinator().state.value.step == OrdinaryPreparationStep.SEAT }
        assertEquals(R.id.homeFragment, navHost().navController.currentDestination!!.id)
        assertTrue(coordinator().cancel())
        await("Home restored") { activity.findViewById<View>(R.id.ordinaryPreparation) == null }
        assertIdleAndNoRecord()
    }

    @Test fun theSeparateHomePlanEntryStillOpensTheActualDailyPlanEditor() {
        activity.findViewById<View>(R.id.btnFlightPlan).performClick()
        await("Independent daily plan entry") { navHost().navController.currentDestination?.id == R.id.flightPreparationFragment }
        navHost().childFragmentManager.executePendingTransactions()
        assertTrue(navHost().childFragmentManager.primaryNavigationFragment is com.projectfocus.app.booking.FlightPreparationFragment)
        assertFalse(coordinator().state.value.active)
        assertIdleAndNoRecord()
    }

    @Test fun activityRecreationRetainsBoardingFactsWithoutStartingOrChoosingAnotherFlight() {
        assertTrue(activity.openOrdinaryFlightPreparation())
        await("Seats before recreation") { coordinator().state.value.step == OrdinaryPreparationStep.SEAT }
        chooseAndConfirmSeat()
        val before = coordinator().state.value
        activityController.recreate(); activity = activityController.get()
        activity.supportFragmentManager.executePendingTransactions()
        navHost().childFragmentManager.executePendingTransactions()
        await("Retained boarding window") { activity.findViewById<View>(R.id.ordinaryCheckIn)?.let { it.isShown && it.isEnabled } == true &&
            activity.findViewById<BoardingSequenceView>(R.id.ordinaryBoardingArtwork)?.renderedFrame == 0 }
        assertEquals(before.requestId, coordinator().state.value.requestId)
        assertEquals(before.route, coordinator().state.value.route)
        assertEquals(before.boardingAt, coordinator().state.value.boardingAt)
        assertEquals(before.route!!.flightNumber, activity.findViewById<TextView>(PassR.id.tv_flight_code).text.toString())
        assertIdleAndNoRecord()
        await("Restored boarding artwork") { activity.findViewById<BoardingSequenceView>(R.id.ordinaryBoardingArtwork)?.renderedFrame == 0 }
        capture("ordinary-boarding-restored")
        assertTrue(coordinator().cancel())
        await("Restored window cancellation") { activity.findViewById<View>(R.id.ordinaryPreparation) == null }
        assertIdleAndNoRecord()
    }

    @Test fun sameTokenNewGenerationWhileReadyCannotStartTheOpenedFlight() {
        assertTrue(activity.openOrdinaryFlightPreparation())
        await("Seats before identity change") { coordinator().state.value.step == OrdinaryPreparationStep.SEAT }
        chooseAndConfirmSeat()
        activity.findViewById<View>(R.id.ordinaryCheckIn).performClick()
        await("Inspection before identity change") { coordinator().state.value.step == OrdinaryPreparationStep.INSPECTION }
        tearActualBand()
        await("Boarding before identity change") { activity.findViewById<View>(R.id.ordinaryPrepareBoarding)?.isShown == true }
        activity.findViewById<View>(R.id.ordinaryPrepareBoarding).performClick()
        await("Ready before identity change") { activity.findViewById<View>(R.id.ordinaryReady)?.isShown == true }
        runBlocking(Dispatchers.IO) { TokenStore.setCancellable("ordinary-native-window-account") }
        activity.findViewById<View>(R.id.pf_ff_depart).performClick()
        await("Stale ordinary flight rejected") { coordinator().state.value.step == OrdinaryPreparationStep.UNAVAILABLE }
        assertNull(activity.findViewById<View>(R.id.ordinaryPreparation))
        assertIdleAndNoRecord()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h400dp-land-mdpi")
    fun compactNightBoardingInspectionAndReadyKeepTheirControlsInTheActualWindow() {
        HomeMapStyleSelection.select(activity, 2)
        assertTrue(activity.openOrdinaryFlightPreparation())
        await("Compact seats") { coordinator().state.value.step == OrdinaryPreparationStep.SEAT }
        chooseAndConfirmSeat()
        assertPaperTransparency()
        val surface = activity.findViewById<View>(R.id.ordinaryBoarding)
        val background = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            surface.draw(Canvas(background))
            assertTrue("The night paper uses its matching dark ambient canvas",
                android.graphics.Color.luminance(background.getPixel(4, 4)) < .12f)
        } finally { background.recycle() }
        assertTrue("The night status remains readable on the dark canvas",
            android.graphics.Color.luminance(activity.findViewById<TextView>(R.id.ordinaryPrintStatus).currentTextColor) > .35f)
        capture("ordinary-boarding-night-landscape")
        val checkIn = activity.findViewById<View>(R.id.ordinaryCheckIn)
        assertOnScreen(checkIn)
        checkIn.performClick()
        await("Compact inspection") { coordinator().state.value.step == OrdinaryPreparationStep.INSPECTION }
        capture("ordinary-inspection-night-landscape")
        tearActualBand()
        await("Compact boarding confirmation") { activity.findViewById<View>(R.id.ordinaryPrepareBoarding)?.isShown == true }
        assertOnScreen(activity.findViewById(R.id.ordinaryPrepareBoarding))
        capture("ordinary-prepare-night-landscape")
        activity.findViewById<View>(R.id.ordinaryPrepareBoarding).performClick()
        await("Compact ready") { activity.findViewById<View>(R.id.ordinaryReady)?.isShown == true }
        advance(550)
        assertOnScreen(activity.findViewById(R.id.pf_ff_depart))
        capture("ordinary-ready-night-landscape")
        assertTrue(coordinator().cancel())
        assertIdleAndNoRecord()
    }

    @Test fun aShortOrLeftwardSwipeResetsAndAccessibleInspectionStillNeedsExplicitBoarding() {
        assertTrue(activity.openOrdinaryFlightPreparation())
        await("Accessible seats") { coordinator().state.value.step == OrdinaryPreparationStep.SEAT }
        chooseAndConfirmSeat()
        activity.findViewById<View>(R.id.ordinaryCheckIn).performClick()
        await("Accessible inspection") { coordinator().state.value.step == OrdinaryPreparationStep.INSPECTION }
        val band = activity.findViewById<View>(PassR.id.tear_touch_band)
        val artwork = activity.findViewById<BoardingSequenceView>(R.id.ordinaryBoardingArtwork)
        for (distance in listOf(-350f, 299f)) {
            val now = SystemClock.uptimeMillis()
            for ((action, x) in listOf(MotionEvent.ACTION_DOWN to 20f, MotionEvent.ACTION_MOVE to 20f + distance,
                    MotionEvent.ACTION_UP to 20f + distance)) {
                MotionEvent.obtain(now, now + 100L, action, x, band.height / 2f, 0).also { band.dispatchTouchEvent(it); it.recycle() }
            }
            advance(350)
            await("Intact reset paper") { artwork.renderedFrame == 0 }
            assertEquals(OrdinaryPreparationStep.INSPECTION, coordinator().state.value.step)
            assertIdleAndNoRecord()
        }
        assertTrue(band.performClick())
        await("Accessible boarding confirmation") { coordinator().state.value.step == OrdinaryPreparationStep.PREPARE }
        assertEquals(179, artwork.renderedFrame)
        assertNull(activity.findViewById<View>(R.id.ordinaryReady))
        assertIdleAndNoRecord()
        assertTrue(coordinator().cancel())
    }

    @Test fun cancellingTheRealNotificationPromptKeepsTheFlightUnsubmitted() {
        assertTrue(activity.openOrdinaryFlightPreparation())
        await("Permission seats") { coordinator().state.value.step == OrdinaryPreparationStep.SEAT }
        chooseAndConfirmSeat()
        activity.findViewById<View>(R.id.ordinaryCheckIn).performClick()
        tearActualBand()
        await("Permission boarding confirmation") { coordinator().state.value.step == OrdinaryPreparationStep.PREPARE }
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        activity.findViewById<View>(R.id.ordinaryPrepareBoarding).performClick()
        await("Notification permission dialog") { ShadowDialog.getLatestDialog()?.isShowing == true }
        assertEquals(OrdinaryPreparationStep.PREPARE, coordinator().state.value.step)
        assertIdleAndNoRecord()
        (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog)
            .getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        await("Boarding action restored") { activity.findViewById<View>(R.id.ordinaryPrepareBoarding)?.isEnabled == true }
        assertEquals(OrdinaryPreparationStep.PREPARE, coordinator().state.value.step)
        assertIdleAndNoRecord()
        assertTrue(coordinator().cancel())
    }

    @Test fun actualPrintingHoldsInspectionAndKeepsTheFrozenFlightUntilPaperIsReady() {
        withAnimationScale(1f) {
            assertTrue(ValueAnimator.areAnimatorsEnabled())
            activity.findViewById<View>(R.id.btnStartFlight).performClick()
            await("Ordinary seat choice") { coordinator().state.value.step == OrdinaryPreparationStep.SEAT }
            chooseAndConfirmSeat(waitForPrinting = false)
            val frozen = requireNotNull(coordinator().state.value.route)
            val action = activity.findViewById<View>(R.id.ordinaryCheckIn)
            val feed = activity.findViewById<View>(R.id.ordinaryBoardingFeed)
            assertFalse(action.isEnabled)
            assertFalse(action.isShown)
            action.performClick()
            assertEquals(OrdinaryPreparationStep.BOARDING, coordinator().state.value.step)
            assertIdleAndNoRecord()
            capture("ordinary-print-start-day")
            val feedingAt = SystemClock.uptimeMillis()
            advance(800)
            assertEquals(800L, SystemClock.uptimeMillis() - feedingAt)
            assertTrue(feed.isShown)
            assertFalse(action.isEnabled)
            capture("ordinary-print-feeding-day")
            advance(950)
            assertTrue(feed.isShown)
            capture("ordinary-print-fold-day")
            advance(2_300)
            await("Actual paper and inspection action") { action.isShown && action.isEnabled }
            assertFalse(feed.isShown)
            assertTrue(activity.findViewById<View>(PassR.id.boarding_pass_root).isShown)
            assertEquals(frozen, coordinator().state.value.route)
            assertIdleAndNoRecord()
            capture("ordinary-print-complete-day")
            action.performClick()
            await("Explicit inspection") { coordinator().state.value.step == OrdinaryPreparationStep.INSPECTION }
            assertIdleAndNoRecord()
        }
    }

    @Test fun reducedMotionShowsTheRealPaperAndActionWithoutStartingAFlight() {
        withAnimationScale(0f) {
            assertFalse(ValueAnimator.areAnimatorsEnabled())
            activity.findViewById<View>(R.id.btnStartFlight).performClick()
            await("Ordinary seats without motion") { coordinator().state.value.step == OrdinaryPreparationStep.SEAT }
            chooseAndConfirmSeat()
            assertEquals(OrdinaryPreparationStep.BOARDING, coordinator().state.value.step)
            assertFalse(activity.findViewById<View>(R.id.ordinaryBoardingFeed).isShown)
            assertTrue(activity.findViewById<View>(PassR.id.boarding_pass_root).isShown)
            val route = requireNotNull(coordinator().state.value.route)
            assertEquals(route.flightNumber, activity.findViewById<TextView>(PassR.id.tv_flight_code).text.toString())
            assertIdleAndNoRecord()
            capture("ordinary-print-reduced-motion")
            activity.findViewById<View>(R.id.ordinaryCheckIn).performClick()
            await("Reduced-motion inspection") { coordinator().state.value.step == OrdinaryPreparationStep.INSPECTION }
            assertIdleAndNoRecord()
        }
    }

    @Test fun hiddenWindowFinishesTheVisualPaperWithoutInspectingOrDeparting() {
        withAnimationScale(1f) {
            activity.findViewById<View>(R.id.btnStartFlight).performClick()
            await("Seats before hiding") { coordinator().state.value.step == OrdinaryPreparationStep.SEAT }
            chooseAndConfirmSeat(waitForPrinting = false)
            val frozen = requireNotNull(coordinator().state.value.route)
            val surface = activity.findViewById<View>(R.id.ordinaryBoarding)
            advance(400)
            surface.dispatchWindowVisibilityChanged(View.GONE)
            assertFalse(activity.findViewById<View>(R.id.ordinaryBoardingFeed).isShown)
            assertTrue(activity.findViewById<View>(R.id.ordinaryCheckIn).isEnabled)
            surface.dispatchWindowVisibilityChanged(View.VISIBLE)
            advance(4_000)
            assertEquals(OrdinaryPreparationStep.BOARDING, coordinator().state.value.step)
            assertEquals(frozen, coordinator().state.value.route)
            assertIdleAndNoRecord()
            capture("ordinary-print-after-hidden-window")
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h568dp-mdpi")
    fun printedPaperAndActionRespectRepeatedSystemAreasWithLargeText() {
        activity.findViewById<View>(R.id.btnStartFlight).performClick()
        await("Compact ordinary seats") { coordinator().state.value.step == OrdinaryPreparationStep.SEAT }
        chooseAndConfirmSeat()
        val surface = activity.findViewById<View>(R.id.ordinaryBoarding)
        val action = activity.findViewById<View>(R.id.ordinaryCheckIn)
        val paper = activity.findViewById<View>(PassR.id.boarding_pass_root)
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(bars, androidx.core.graphics.Insets.of(48, 36, 0, 28)).build()
        val first = IntArray(2)
        repeat(3) { index ->
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(surface, insets)
            advance(40)
            val point = IntArray(2);action.getLocationOnScreen(point)
            assertTrue(point[0] >= 48 && point[1] >= 36)
            assertTrue(point[0] + action.width <= surface.width)
            assertTrue(point[1] + action.height <= surface.height - 28)
            assertTrue(action.height >= 48)
            if (index == 0) point.copyInto(first) else assertArrayEquals(first, point)
            val bounds = android.graphics.Rect()
            assertTrue(paper.getGlobalVisibleRect(bounds))
            assertTrue(bounds.left >= 48 && bounds.top >= 36)
            assertTrue(bounds.right <= surface.width && bounds.bottom <= surface.height - 28)
        }
        assertIdleAndNoRecord()
        capture("ordinary-print-small-large-text-system-areas")
        val route = requireNotNull(coordinator().state.value.route)
        assertEquals(1f, paper.resources.configuration.fontScale)
        assertEquals(1.6f, action.resources.configuration.fontScale)
        for (printed in walk(paper).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }) {
            val layout = requireNotNull(printed.layout)
            assertEquals("Printed field ${printed.text}", 1, layout.lineCount)
            assertEquals(0, layout.getEllipsisCount(0))
            assertTrue("Printed field width ${printed.text}", layout.getLineWidth(0) <= printed.width + 1f)
            assertTrue("Printed field height ${printed.text}", layout.height <= printed.height)
        }
        assertTrue(paper.contentDescription.contains(requireNotNull(route.flightNumber)))
        assertTrue(paper.contentDescription.contains(requireNotNull(route.seat)))
        assertTrue(paper.contentDescription.contains(route.departure.code))
        assertTrue(paper.contentDescription.contains(route.destination.code))
        action.performClick()
        await("Compact explicit inspection") { coordinator().state.value.step == OrdinaryPreparationStep.INSPECTION }
        tearActualBand()
        await("Compact preparation") { coordinator().state.value.step == OrdinaryPreparationStep.PREPARE }
        (surface as OrdinaryBoardingPassSurface).updateBlocking(true, 8)
        advance(40)
        val status = activity.findViewById<TextView>(R.id.ordinaryBlockingStatus)
        assertTrue(status.text.contains("8"))
        assertTrue(requireNotNull(status.layout).height <= status.height)
        val prepare = activity.findViewById<View>(R.id.ordinaryPrepareBoarding)
        val point = IntArray(2);prepare.getLocationOnScreen(point)
        assertTrue(point[0] >= 48 && point[1] >= 36)
        assertTrue(point[0] + prepare.width <= surface.width)
        assertTrue(point[1] + prepare.height <= surface.height - 28)
        assertTrue(prepare.height >= 48)
        assertIdleAndNoRecord()
        capture("ordinary-prepare-small-large-text-system-areas")
    }

    private fun withAnimationScale(scale: Float, action: () -> Unit) {
        val original = ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java, "sDurationScale")
        val paused = ShadowChoreographer.isPaused()
        val frameDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", scale)
        try { action() } finally {
            ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", original)
            ShadowChoreographer.setFrameDelay(frameDelay)
            ShadowChoreographer.setPaused(paused)
        }
    }

    private fun chooseAndConfirmSeat(checkSystemAreas: Boolean = false, waitForPrinting: Boolean = true) {
        await("Seat map laid out") { activity.findViewById<SeatSelectionMapView>(R.id.pf_ff_seat_map)?.width?.let { it > 0 } == true }
        advance(700)
        val chosen = (activity.seatSceneChoices(Bundle()) as SeatSceneChoices.Ready).values.first()
        val map = activity.findViewById<SeatSelectionMapView>(R.id.pf_ff_seat_map)
        walk(map).filterIsInstance<TextView>().single { it.tag == "01A" }.performClick()
        advance(30)
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        val label = walk(requireNotNull(dialog.window).decorView).filterIsInstance<TextView>().first { it.text.toString() == chosen.title }
        clickParent(label)
        await("Confirmed scene available") { coordinator().state.value.selection?.scene?.optionId == chosen.optionId }
        val confirm = activity.findViewById<View>(R.id.pf_ff_seat_confirm)
        assertTrue(confirm.isShown)
        if (checkSystemAreas) {
            val surface = map.parent as View
            val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(bars, androidx.core.graphics.Insets.of(48, 36, 0, 28)).build()
            val before = IntArray(2)
            repeat(3) { index ->
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(surface, insets)
                advance(40)
                val point = IntArray(2);confirm.getLocationOnScreen(point)
                assertTrue(point[0] >= 48)
                assertTrue(point[1] >= 36)
                assertTrue(point[0] + confirm.width <= activity.window.decorView.width)
                assertTrue(point[1] + confirm.height <= activity.window.decorView.height - 28)
                if (index == 0) point.copyInto(before) else assertArrayEquals(before, point)
            }
            capture("seats-selected-system-areas", "ui-seats")
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(surface,
                androidx.core.view.WindowInsetsCompat.Builder().setInsets(bars, androidx.core.graphics.Insets.NONE).build())
            advance(40)
        }
        confirm.performClick(); confirm.performClick()
        await("Printed boarding window") { coordinator().state.value.step == OrdinaryPreparationStep.BOARDING &&
            activity.findViewById<BoardingSequenceView>(R.id.ordinaryBoardingArtwork)?.renderedFrame == 0 &&
            (if (waitForPrinting) activity.findViewById<View>(R.id.ordinaryCheckIn)?.let { it.isShown && it.isEnabled } == true
             else activity.findViewById<View>(R.id.ordinaryBoardingFeed)?.isShown == true) }
    }

    private fun assertPaperTransparency() {
        val paper = activity.findViewById<View>(PassR.id.boarding_pass_root)
        val bitmap = Bitmap.createBitmap(paper.width, paper.height, Bitmap.Config.ARGB_8888)
        try {
            paper.draw(Canvas(bitmap))
            assertEquals("The artwork margin must preserve its encoded alpha", 1,
                android.graphics.Color.alpha(bitmap.getPixel(paper.width / 2, paper.height * 95 / 100)))
            assertEquals("The printed paper must be present", 255,
                android.graphics.Color.alpha(bitmap.getPixel(paper.width / 2, paper.height * 30 / 100)))
        } finally { bitmap.recycle() }
    }

    private fun tearActualBand() {
        val band = activity.findViewById<View>(PassR.id.tear_touch_band)
        await("Tear band laid out") { band.width > 0 && band.height > 0 }
        val now = SystemClock.uptimeMillis()
        val y = band.height * .5f
        for ((action, delta, x) in listOf(Triple(MotionEvent.ACTION_DOWN, 0L, band.width * .1f),
            Triple(MotionEvent.ACTION_MOVE, 100L, band.width * .1f + 300f), Triple(MotionEvent.ACTION_UP, 200L, band.width * .1f + 300f))) {
            MotionEvent.obtain(now, now + delta, action, x, y, 0).also { band.dispatchTouchEvent(it); it.recycle() }
        }
    }

    private fun assertIdleAndNoRecord() {
        assertEquals(SessionState.Idle, activity.sessionRepository.state.value)
        assertNull(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().ongoing() })
        assertTrue(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().getAll().isEmpty() })
    }

    private fun assertOnScreen(view: View) {
        val point = IntArray(2); view.getLocationOnScreen(point)
        assertTrue(view.isShown)
        assertTrue(point[0] >= 0 && point[1] >= 0)
        assertTrue(point[0] + view.width <= activity.window.decorView.width)
        assertTrue(point[1] + view.height <= activity.window.decorView.height)
    }

    private fun coordinator() = ViewModelProvider(activity)[OrdinaryFlightPreparationViewModel::class.java].coordinator
    private fun navHost() = activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
    private fun clickParent(label: View) {
        var target = label
        while (!target.isClickable) target = target.parent as View
        target.performClick()
    }
    private fun walk(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(walk(view.getChildAt(index)))
    }
    private fun advance(millis: Long) {
        val main = shadowOf(Looper.getMainLooper())
        if (!ShadowChoreographer.isPaused()) {
            main.idleFor(millis, TimeUnit.MILLISECONDS)
            return
        }
        var remaining = millis
        while (remaining > 0L) {
            val frame = minOf(16L, remaining)
            main.idleFor(frame, TimeUnit.MILLISECONDS)
            remaining -= frame
        }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) {
            check(System.nanoTime() < until) { "Timed out: $label; ${boardingDiagnostics()}" }
            advance(16); Thread.sleep(3)
        }
        advance(16)
    }
    private fun boardingDiagnostics(): String {
        val surface = activity.findViewById<OrdinaryBoardingPassSurface>(R.id.ordinaryBoarding)
        val controller = surface?.let { ReflectionHelpers.getField<BoardingPrintController>(it, "printing") }
        val animation = controller?.let { ReflectionHelpers.getField<ValueAnimator?>(it, "printAnimation") }
        val action = activity.findViewById<View>(R.id.ordinaryCheckIn)
        val feed = activity.findViewById<View>(R.id.ordinaryBoardingFeed)
        return "step=${coordinator().state.value.step}, phase=${controller?.phase}, " +
            "attached=${surface?.isAttachedToWindow}, window=${surface?.windowVisibility}, shown=${surface?.isShown}, " +
            "frame=${activity.findViewById<BoardingSequenceView>(R.id.ordinaryBoardingArtwork)?.renderedFrame}, " +
            "feed=${feed?.visibility}/${feed?.isShown}, action=${action?.visibility}/${action?.isEnabled}, " +
            "status=${activity.findViewById<TextView>(R.id.ordinaryPrintStatus)?.text}, " +
            "motion=${ValueAnimator.areAnimatorsEnabled()}, playTime=${animation?.currentPlayTime}, " +
            "duration=${animation?.duration}, uptime=${SystemClock.uptimeMillis()}"
    }

    private fun capture(name: String, folder: String = "ordinary-countdown") {
        advance(32)
        val root = activity.window.decorView
        assertTrue(root.width > 0 && root.height > 0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("build/reports/$folder/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
