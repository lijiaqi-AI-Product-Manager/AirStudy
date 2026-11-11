package com.projectfocus.app.booking

import android.app.Application
import com.google.android.material.button.MaterialButton
import com.projectfocus.app.scene.AppSceneCatalog
import com.projectfocus.core.ui.scene.AnchoredScenePickerDialog
import com.projectfocus.feature.booking.draft.BookingDraftStore
import com.projectfocus.feature.booking.seat.SeatSelectionFragment
import com.projectfocus.feature.booking.seat.SeatSelectionMapView
import com.projectfocus.feature.booking.seat.SeatSelectionStore
import com.projectfocus.feature.booking.seat.SeatSceneChoices
import com.projectfocus.feature.booking.seat.SeatSceneChoicesHost
import com.projectfocus.feature.scene.source.SharedPreferencesSceneCatalogStore
import com.projectfocus.feature.scene.source.SourceSceneRecord
import org.robolectric.shadows.ShadowToast
import com.projectfocus.core.ui.R as CoreUiR
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageView
import android.graphics.PorterDuffColorFilter
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.feature.booking.plan.SourceStringWheelPicker
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import androidx.fragment.app.Fragment
import androidx.core.graphics.Insets
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.MainActivity
import com.projectfocus.app.R
import com.projectfocus.app.home.HomeFragment
import com.projectfocus.app.home.HomeOriginSelection
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.app.route.AirportSearchHostFragment
import com.projectfocus.app.route.RouteArgs
import com.projectfocus.core.common.flight.FlightMath
import com.projectfocus.core.common.nav.SeatDraftRequests
import com.projectfocus.core.common.nav.SeatSelectionResult
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.database.seed.AirportCsvParser
import com.projectfocus.core.database.seed.AirportSeeder
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.ThemeMode
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.core.model.airport.AirportType
import com.projectfocus.feature.airport.ui.AirportSearchFragment
import com.projectfocus.feature.airport.ui.AirportSearchAdapter
import com.projectfocus.feature.airport.ui.AirportReferencePoint
import com.projectfocus.feature.airport.ui.AirportSearchReferenceArgs
import com.projectfocus.feature.flight.hud.FlightHudView
import com.projectfocus.service.focus.session.SessionRepository
import com.projectfocus.service.focus.session.SessionState
import com.projectfocus.service.focus.monitor.FocusMonitorService
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.projectfocus.feature.airport.search.AirportSearchViewModel
import org.junit.After
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSystemClock
import com.projectfocus.feature.booking.R as BookingR
import com.projectfocus.feature.flight.R as FlightR
import com.projectfocus.feature.home.R as HomeR
import com.projectfocus.feature.airport.R as AirportR

/**
 * Exercises the production navigation host, Hilt ViewModel, plan store and Room DAOs together.
 * The isolated airports are inserted into Robolectric's database, never a connected device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlightPreparationNavigationTest {
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var airports: AirportDao
    private lateinit var history: FlightHistoryDao
    private lateinit var sessions: SessionRepository

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle()
        onActivity { nav(it).navigate(R.id.action_permission_to_home) }
        await("production Home is ready") { currentPage() is HomeFragment }
        onActivity { activity ->
            val home = currentPage(activity) as HomeFragment
            airports = home.airportDao
            history = (activity.application as ProjectFocusApp).database.flightHistoryDao()
            sessions = activity.sessionRepository
        }
        val seed = try {
            runBlocking(Dispatchers.IO) { withTimeout(10_000) {
                ApplicationProvider.getApplicationContext<ProjectFocusApp>().awaitInitialAirportSeed()
            } }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val app = ApplicationProvider.getApplicationContext<ProjectFocusApp>()
            val diagnostic = "startup bootstrap=${app.databaseBootstrapResult}\n" +
                ShadowLog.getLogs().joinToString("\n") { "${it.tag}: ${it.msg}" }
            throw AssertionError("Existing startup airport task did not finish in 10s; no second importer was started.\n$diagnostic", error)
        }
        assertThat(seed).isNotNull()
        assertThat(seed).isNotInstanceOf(AirportSeeder.Result.Failed::class.java)
        runBlocking(Dispatchers.IO) { airports.upsertAll(listOf(DEPARTURE, ARRIVAL, OTHER_ARRIVAL)) }
        idle()
    }

    @After
    fun tearDown() {
        try {
            scenario?.close()
        } finally {
            scenario = null
            shutdownRealApplicationAfterTest(ApplicationProvider.getApplicationContext<ProjectFocusApp>())
        }
    }

    @Test
    fun `saved theme preference changes the actual Activity resources`() {
        val repository = activityValue { it.settingsRepository }
        try {
            runBlocking(Dispatchers.IO) { repository.writeAll(listOf(PrefKey.ThemeMode to ThemeMode.NIGHT)) }
            await("actual night resources applied") {
                activityValue { it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK } ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            runBlocking(Dispatchers.IO) { repository.writeAll(listOf(PrefKey.ThemeMode to ThemeMode.DAY)) }
            await("actual day resources applied") {
                activityValue { it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK } ==
                    Configuration.UI_MODE_NIGHT_NO
            }
        } finally {
            runBlocking(Dispatchers.IO) { repository.writeAll(listOf(PrefKey.ThemeMode to ThemeMode.AUTO)) }
            idle()
        }
    }

    @Test
    fun `system bars inset secondary pages once and floating plan returns from a full window`() {
        val insets = WindowInsetsCompat.Builder().setInsets(
            WindowInsetsCompat.Type.systemBars(), Insets.of(0, 28, 0, 24),
        ).build()
        fun checkPadding(top: Int, bottom: Int) = onActivity { activity ->
            val container = activity.findViewById<View>(R.id.nav_host_container)
            ViewCompat.dispatchApplyWindowInsets(container, insets)
            assertThat(container.paddingTop).isEqualTo(top)
            assertThat(container.paddingBottom).isEqualTo(bottom)
        }
        checkPadding(0, 0)
        onActivity { nav(it).navigate(R.id.action_home_to_settings) }
        idle()
        checkPadding(28, 24)
        checkPadding(28, 24)
        onActivity { nav(it).popBackStack() }
        idle()
        checkPadding(0, 0)

        openPreparation(withRoute = false)
        await("floating preparation finishes loading") { !preparationState().loading }
        checkPadding(0, 0)
        clickPreparation(BookingR.id.pf_tp_floating_back)
        awaitPreparationStage(PreparationStage.COMPACT)
        assertThat(preparationState().confirmed).isFalse()
        assertThat(persistedPlan()).isNull()

        onActivity { it.onBackPressedDispatcher.onBackPressed() }
        await("host Back closes the compact plan to Home") { currentPage() is HomeFragment }
        checkPadding(0, 0)
        assertThat(sessions.state.value).isEqualTo(SessionState.Idle)
        assertThat(runBlocking(Dispatchers.IO) { history.ongoing() }).isNull()
    }

    @Test
    fun `source Home origin selection is retained when ordinary departure asks for a destination`() {
        onActivity { currentPage(it).requireView().findViewById<View>(R.id.btnStartFlight).performClick() }
        awaitAirportSearch(RouteArgs.MODE_PICK_ORIGIN)
        pickAirport(DEPARTURE)
        await("origin returns to Home") {
            currentPage() is HomeFragment && activityValue {
                currentPage(it).requireView().findViewById<TextView>(R.id.btnStartFlight).text.toString() ==
                    it.getString(R.string.home_prepare_takeoff)
            }
        }
        assertAirportResultConsumed()
        onActivity { currentPage(it).requireView().findViewById<View>(R.id.btnStartFlight).performClick() }
        awaitAirportSearch(RouteArgs.MODE_PICK_DESTINATION)
        assertThat(activityValue { it.getSharedPreferences("projectfocus_home", android.content.Context.MODE_PRIVATE)
            .getString("origin_code", null).orEmpty() }).isEqualTo(DEPARTURE.icaoCode)
        assertThat(sessions.state.value).isEqualTo(SessionState.Idle)
        assertThat(runBlocking(Dispatchers.IO) { history.ongoing() }).isNull()
    }

    @Test
    fun homeArrivalAdvancesTheNextOrdinaryOriginWithoutRecreation() {
        chooseHomeOriginThroughSourceControls()
        onActivity { activity ->
            val preferences = activity.getSharedPreferences("projectfocus_home", android.content.Context.MODE_PRIVATE)
            HomeOriginSelection.advance(preferences, PlannedAirport(ARRIVAL.id, ARRIVAL.icaoCode,
                ARRIVAL.name, ARRIVAL.city, ARRIVAL.country, ARRIVAL.latitude, ARRIVAL.longitude), TokenStore.snapshot())
        }
        idle()
        onActivity { currentPage(it).requireView().findViewById<View>(R.id.btnStartFlight).performClick() }
        awaitAirportSearch(RouteArgs.MODE_PICK_DESTINATION)
        assertThat(activityValue { it.getSharedPreferences("projectfocus_home", android.content.Context.MODE_PRIVATE)
            .getString("origin_code", null).orEmpty() }).isEqualTo(ARRIVAL.icaoCode)
        assertThat(sessions.state.value).isEqualTo(SessionState.Idle)
    }

    @Test
    fun homeClearedOriginDropsCachedAirportBeforeNextAction() {
        chooseHomeOriginThroughSourceControls()
        onActivity { activity ->
            activity.getSharedPreferences("projectfocus_home", android.content.Context.MODE_PRIVATE)
                .edit().remove("origin_code").remove("origin_city").commit()
        }
        idle()
        onActivity { activity ->
            val button = currentPage(activity).requireView().findViewById<TextView>(R.id.btnStartFlight)
            assertThat(button.text.toString()).isEqualTo(activity.getString(R.string.home_action_search_airport))
            button.performClick()
        }
        awaitAirportSearch(RouteArgs.MODE_PICK_ORIGIN)
        assertThat(sessions.state.value).isEqualTo(SessionState.Idle)
    }

    private fun chooseHomeOriginThroughSourceControls() {
        onActivity { currentPage(it).requireView().findViewById<View>(R.id.btnStartFlight).performClick() }
        awaitAirportSearch(RouteArgs.MODE_PICK_ORIGIN)
        pickAirport(DEPARTURE)
        await("origin selection returns to Home") {
            currentPage() is HomeFragment && activityValue {
                currentPage(it).requireView().findViewById<TextView>(R.id.btnStartFlight).text.toString() ==
                    it.getString(R.string.home_prepare_takeoff)
            }
        }
        assertAirportResultConsumed()
    }

    @Test
    fun `unsaved real route displays its measured distance but cannot start`() {
        openPreparation(withRoute = true)
        await("100 km plan is resolved") { preparationState().plan != null && !preparationState().loading }

        val state = preparationState()
        val plan = checkNotNull(state.plan)
        assertThat(plan.distanceKm).isEqualTo(100.0)
        assertThat(plan.durationMinutes).isEqualTo(8)
        assertThat(state.confirmed).isFalse()
        assertThat(preparationState().stage).isEqualTo(PreparationStage.DESTINATION)
        assertThat(preparationControl(R.id.btnPreparationStart).visibility).isEqualTo(View.GONE)
        assertThat(persistedPlan()).isNull()
        assertThat(preparationView(R.id.btnPreparationStart).isEnabled).isFalse()
        assertThat(preparationControl(BookingR.id.pf_tp_floating_save).isEnabled).isTrue()

        // Exercise the real source actions: preview is visible, but it is still not a save.
        previewThroughSourceEditor()
        assertThat(preparationState().stage).isEqualTo(PreparationStage.PREVIEW)
        assertThat(preparationView(BookingR.id.pf_tp_distance).text.toString()).contains("100.0")
        assertThat(preparationState().plan!!.durationMinutes).isEqualTo(8)
        assertThat(preparationState().confirmed).isFalse()
        assertThat(persistedPlan()).isNull()
        assertThat(preparationControl(R.id.btnPreparationStart).visibility).isEqualTo(View.GONE)
        assertThat(sessions.state.value).isEqualTo(SessionState.Idle)
        assertThat(runBlocking(Dispatchers.IO) { history.ongoing() }).isNull()
    }

    @Test
    fun `airport selection returns once and confirmed plan starts one frozen 100 km flight`() {
        openPreparation(withRoute = false)
        await("empty preparation finishes loading") { !preparationState().loading }
        assertThat(preparationState().plan).isNull()
        assertThat(preparationView(R.id.btnPreparationStart).isEnabled).isFalse()

        clickPreparation(BookingR.id.pf_tp_floating_origin)
        awaitAirportSearch(RouteArgs.MODE_PICK_ORIGIN)
        assertPickerReference(null)
        // Rebuild the real search host while it is above the editor. Its callback must survive.
        scenario!!.recreate()
        awaitAirportSearch(RouteArgs.MODE_PICK_ORIGIN)
        assertPickerReference(null)
        pickAirport(DEPARTURE)
        await("departure was consumed after return") {
            currentPage() is FlightPreparationFragment &&
                preparationState().origin?.code == DEPARTURE.icaoCode && !preparationState().loading
        }
        assertAirportResultConsumed()
        assertThat(preparationState().destination).isNull()

        clickPreparation(BookingR.id.pf_tp_floating_destination)
        awaitAirportSearch(RouteArgs.MODE_PICK_DESTINATION)
        assertPickerReference(AirportReferencePoint.from(DEPARTURE.latitude, DEPARTURE.longitude))
        pickAirportThroughRenderedRow(ARRIVAL, "8m")
        await("selected airports form the measured route") { preparationState().plan != null && !preparationState().loading }
        assertAirportResultConsumed()
        val plan = preparationState().plan!!
        assertThat(plan.departure.code).isEqualTo(DEPARTURE.icaoCode)
        assertThat(plan.destination.code).isEqualTo(ARRIVAL.icaoCode)
        assertThat(plan.distanceKm).isEqualTo(100.0)
        assertThat(plan.durationMinutes).isEqualTo(8)
        assertThat(preparationView(R.id.btnPreparationStart).isEnabled).isFalse()

        val confirmedPlan = saveThroughSourceEditor()
        assertThat(confirmedPlan.departure).isEqualTo(plan.departure)
        assertThat(confirmedPlan.destination).isEqualTo(plan.destination)
        assertThat(confirmedPlan.distanceKm).isEqualTo(plan.distanceKm)
        assertThat(confirmedPlan.durationMinutes).isEqualTo(plan.durationMinutes)
        assertThat(confirmedPlan.plannedDeparture).isNotNull()
        assertThat(preparationView(R.id.btnPreparationStart).isEnabled).isTrue()
        assertThat(runBlocking(Dispatchers.IO) { history.ongoing() }).isNull()

        // Restoring the editor must keep the saved snapshot and must not replay airport selection.
        scenario!!.recreate()
        await("confirmed editor is restored") {
            currentPage() is FlightPreparationFragment && preparationState().confirmed && !preparationState().loading
        }
        assertThat(preparationState().plan).isEqualTo(confirmedPlan)
        assertThat(persistedPlan()).isEqualTo(confirmedPlan)
        assertAirportResultConsumed()
        onActivity { activity ->
            val start = currentPage(activity).requireView().findViewById<View>(R.id.btnPreparationStart)
            assertThat(start.isEnabled).isTrue()
            start.performClick()
            start.performClick()
        }
        await("takeoff returns to the actual Home HUD") {
            currentPage() is HomeFragment && sessions.state.value is SessionState.Running
        }
        val running = sessions.state.value as SessionState.Running
        assertThat(running.plannedDurationMs).isEqualTo(8 * 60_000L)
        val row = checkNotNull(runBlocking(Dispatchers.IO) { history.ongoing() }) {
            "Takeoff completed without its ONGOING row. Android logs:\n" +
                ShadowLog.getLogs().joinToString("\n") { "${it.tag}: ${it.msg}" }
        }
        assertThat(row.id).isEqualTo(running.sessionId)
        assertThat(row.departureAirportCode).isEqualTo(DEPARTURE.icaoCode)
        assertThat(row.destinationAirportCode).isEqualTo(ARRIVAL.icaoCode)
        assertThat(row.durationMinutes).isEqualTo(8)
        assertThat(row.distanceKm).isEqualTo(100.0)
        assertThat(row.categoryId).isNull()
        assertThat(runBlocking(Dispatchers.IO) { history.getAll().count { it.status == "ONGOING" } }).isEqualTo(1)

        onActivity { activity ->
            val view = currentPage(activity).requireView()
            assertThat(view.findViewById<View>(R.id.flightHudOverlay).visibility).isEqualTo(View.VISIBLE)
            assertThat(view.findViewById<View>(R.id.groundHomeContent).visibility).isEqualTo(View.GONE)
            assertThat(view.findViewById<View>(R.id.bottomStack).isShown).isFalse()
            assertThat(view.findViewById<View>(R.id.sourceMap).visibility).isEqualTo(View.VISIBLE)
            val summary = view.findViewById<FlightHudView>(R.id.flightHudOverlay).contentDescription.toString()
            assertThat(summary).contains(DEPARTURE.icaoCode)
            assertThat(summary).contains(ARRIVAL.icaoCode)
            val displayedDistances = Regex("\\d+\\.\\d+")
                .findAll(summary)
                .map { it.value.toDouble() }.toList()
            assertThat(displayedDistances).hasSize(2)
            assertThat(displayedDistances.sum()).isWithin(0.11).of(100.0)
        }
    }

    @Test
    fun `editing departure uses the actual destination reference through host recreation and row return`() {
        openPreparation(withRoute = true)
        await("route is resolved before editing departure") { preparationState().plan != null && !preparationState().loading }
        clickPreparation(BookingR.id.pf_tp_floating_origin)
        awaitAirportSearch(RouteArgs.MODE_PICK_ORIGIN)
        val expected = AirportReferencePoint.from(ARRIVAL.latitude, ARRIVAL.longitude)
        assertThat(expected).isNotNull()
        assertPickerReference(expected)
        scenario!!.recreate()
        awaitAirportSearch(RouteArgs.MODE_PICK_ORIGIN)
        assertPickerReference(expected)
        pickAirportThroughRenderedRow(DEPARTURE, "8m")
        await("real row returns the departure without changing its measured route") {
            currentPage() is FlightPreparationFragment && !preparationState().loading &&
                preparationState().plan?.durationMinutes == 8
        }
        assertThat(preparationState().plan!!.distanceKm).isEqualTo(100.0)
        assertThat(preparationState().plan!!.departure.code).isEqualTo(DEPARTURE.icaoCode)
        assertAirportResultConsumed()
    }

    @Test
    fun `seat return requires the current editor draft and route and invalidates confirmation`() {
        openPreparation(withRoute = true)
        await("plan ready for confirmation") { preparationState().plan != null && !preparationState().loading }
        val savedBeforeSeat = saveThroughSourceEditor()
        clickPreparation(BookingR.id.pf_tp_flight_seat)
        await("real seat deep link opened") { navDestination() == R.id.seatSelectionFragment }
        val draftKey = activityValue { activity ->
            val args = currentPage(activity).requireArguments()
            assertThat(args.getString("from")).isEqualTo(DEPARTURE.icaoCode)
            assertThat(args.getString("to")).isEqualTo(ARRIVAL.icaoCode)
            assertThat(args.getString("recordId")).isEmpty()
            args.getString("draftKey")!!
        }
        assertThat(draftKey).isNotEmpty()
        onActivity { nav(it).popBackStack() }
        await("editor resumed after seat picker") { currentPage() is FlightPreparationFragment }

        deliverSeat(SeatSelectionResult("12A", "another-editor", ""))
        assertThat(preparationState().plan!!.seat).isNull()
        assertThat(preparationState().confirmed).isTrue()
        deliverSeat(SeatSelectionResult("12A", draftKey, "another-record"))
        assertThat(preparationState().plan!!.seat).isNull()
        deliverSeat(SeatSelectionResult("12A", draftKey, ""))
        await("matching typed selection updates this plan") { preparationState().plan?.seat == "12A" }
        assertThat(preparationState().confirmed).isFalse()
        assertThat(preparationView(R.id.btnPreparationStart).isEnabled).isFalse()
        assertThat(preparationState().stage).isEqualTo(PreparationStage.PREVIEW)
        assertThat(persistedPlan()).isEqualTo(savedBeforeSeat)
        confirmPreviewThroughSourceControl()
        assertThat(preparationView(BookingR.id.pf_tp_flight_seat).text.toString()).contains("12A")

        clickPreparation(BookingR.id.pf_tp_flight_seat)
        await("seat picker reopened") { navDestination() == R.id.seatSelectionFragment }
        val oldRouteKey = activityValue { currentPage(it).requireArguments().getString("draftKey")!! }
        onActivity { nav(it).popBackStack() }
        await("editor returned") { currentPage() is FlightPreparationFragment }
        beginEditingThroughSourceControls()
        clickPreparation(BookingR.id.pf_tp_floating_destination)
        awaitAirportSearch(RouteArgs.MODE_PICK_DESTINATION)
        pickAirport(OTHER_ARRIVAL)
        await("route change is resolved") {
            preparationState().destination?.code == OTHER_ARRIVAL.icaoCode && !preparationState().loading
        }
        deliverSeat(SeatSelectionResult("14C", oldRouteKey, ""))
        assertThat(preparationState().plan!!.seat).isEqualTo("12A")
        assertThat(preparationState().confirmed).isFalse()
        assertThat(sessions.state.value).isEqualTo(SessionState.Idle)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-notnight-mdpi")
    fun `actual 30F touch chooses a scene tag then bottom confirmation returns to the scoped editor`() {
        seedSeatScenes(seatSceneFixtures())
        val beforePlan = openEditorSeatPicker()
        val draftKey = activityValue { currentPage(it).requireArguments().getString("draftKey")!! }
        touchSeat("30F")
        awaitScenePicker()
        assertThat(activityValue { seatMap(it).selectedSeat.orEmpty() }).isEmpty()
        assertThat(activityValue { seatConfirm(it).visibility }).isEqualTo(View.GONE)
        chooseSeatScene(SEAT_SCENE_B)
        await("scene choice selects the actual 30F chair and exposes the bottom button") {
            activityValue { seatMap(it).selectedSeat == "30F" && seatConfirm(it).isShown }
        }
        onActivity { activity ->
            measureSeatWindow(activity)
            val button = seatConfirm(activity)
            val density = button.resources.displayMetrics.density
            assertThat(button.measuredWidth).isEqualTo((300 * density).toInt())
            assertThat(button.measuredHeight).isEqualTo((56 * density).toInt())
            assertThat(button.text.toString()).isEqualTo(activity.getString(BookingR.string.pf_seat_confirm_selection))
            assertThat(button.performClick()).isTrue()
        }
        await("real typed seat result returns to the correct preparation plan") {
            currentPage() is FlightPreparationFragment && preparationState().plan?.seat == "30F"
        }
        assertThat(preparationState().confirmed).isFalse()
        assertThat(preparationState().plan).isEqualTo(beforePlan.copy(seat = "30F"))
        assertThat(preparationState().stage).isEqualTo(PreparationStage.PREVIEW)
        assertThat(persistedPlan()).isEqualTo(beforePlan)
        assertThat(sessions.state.value).isEqualTo(SessionState.Idle)
        assertThat(runBlocking(Dispatchers.IO) { history.ongoing() }).isNull()
        val saved = activityValue { checkNotNull(BookingDraftStore(it).load(draftKey)) }
        assertThat(saved.seat).isEqualTo("30F")
        assertThat(saved.fromCode).isEqualTo(DEPARTURE.icaoCode)
        assertThat(saved.toCode).isEqualTo(ARRIVAL.icaoCode)
        assertThat(saved.recordId).isEmpty()
        // Plan-pending consumes seat only, preserving the preparation scene/clock snapshot.
        assertThat(preparationState().plan!!.scene).isEqualTo(beforePlan.scene)
        assertThat(preparationState().plan!!.plannedDeparture).isEqualTo(beforePlan.plannedDeparture)
        assertThat(preparationState().plan!!.categoryId).isNull()
        confirmPreviewThroughSourceControl()
        assertThat(persistedPlan()).isEqualTo(beforePlan.copy(seat = "30F"))
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-notnight-mdpi")
    fun `scene cancellation keeps the old chair and recreation restores the second same icon custom choice`() {
        val ids = seedSeatScenes(seatSceneFixtures())
        assertThat(ids).hasSize(2)
        assertThat(ids[0]).isNotEqualTo(ids[1])
        val beforePlan = openEditorSeatPicker()
        val draftKey = activityValue { currentPage(it).requireArguments().getString("draftKey")!! }
        touchSeat("01A")
        awaitScenePicker()
        chooseSeatScene(SEAT_SCENE_B)
        assertSeatSceneFill("01A", SEAT_SCENE_B_COLOR)
        touchSeat("30F")
        awaitScenePicker()
        onActivity { currentScenePicker().cancel() }
        idle()
        assertThat(activityValue { seatMap(it).selectedSeat!! }).isEqualTo("01A")
        assertSeatSceneFill("01A", SEAT_SCENE_B_COLOR)
        assertThat(activityValue { SeatSelectionStore(it).load(draftKey)!! }).isEqualTo("01A")
        assertThat(activityValue { seatConfirm(it).isShown }).isTrue()

        scenario!!.recreate()
        await("seat page is restored with its selected chair") {
            navDestination() == R.id.seatSelectionFragment && currentPage().view != null &&
                activityValue { seatMap(it).selectedSeat == "01A" && seatConfirm(it).isShown }
        }
        assertSeatSceneFill("01A", SEAT_SCENE_B_COLOR)
        // Both fixtures share iconKey. Restoring by iconKey would select the first color and fail here.
        onActivity { assertThat(seatConfirm(it).performClick()).isTrue() }
        await("restored seat is confirmed through the real editor consumer") {
            currentPage() is FlightPreparationFragment && preparationState().plan?.seat == "01A"
        }
        assertThat(preparationState().stage).isEqualTo(PreparationStage.PREVIEW)
        assertThat(preparationState().confirmed).isFalse()
        assertThat(persistedPlan()).isEqualTo(beforePlan)
        confirmPreviewThroughSourceControl()
        assertThat(persistedPlan()).isEqualTo(beforePlan.copy(seat = "01A"))
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-notnight-mdpi")
    fun `legitimate empty scenes stay empty while an actual typed preference failure can retry`() {
        seedSeatScenes(emptyList())
        val emptyScenePlan = openEditorSeatPicker()
        assertThat(emptyScenePlan.scene).isNull()
        assertThat(emptyScenePlan.categoryId).isNull()
        val draftKey = activityValue { currentPage(it).requireArguments().getString("draftKey")!! }
        touchSeat("30F")
        awaitScenePicker()
        onActivity { activity ->
            val dialog = currentScenePicker()
            assertThat(sceneTagButtons(dialog)).isEmpty()
            val texts = descendants(dialog.findViewById(CoreUiR.id.pf_scene_card_root))
                .filterIsInstance<TextView>().map { it.text.toString() }
            assertThat(texts).contains(activity.getString(CoreUiR.string.pf_scene_picker_empty))
            assertThat(SharedPreferencesSceneCatalogStore.from(activity).readEnabled(AppSceneCatalog.SCOPE).options).isEmpty()
            assertThat(seatMap(activity).selectedSeat).isNull()
            assertThat(seatConfirm(activity).visibility).isEqualTo(View.GONE)
            assertThat(BookingDraftStore(activity).load(draftKey)).isNull()
            dialog.cancel()
            // A malformed JSON string follows the source fallback and is not an Unavailable fixture.
            // A non-String value makes the real getString throw before decoding; Main handles that.
            activity.getSharedPreferences(SharedPreferencesSceneCatalogStore.FILE_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putInt(SharedPreferencesSceneCatalogStore.enabledKey(AppSceneCatalog.SCOPE), 160).commit()
        }
        touchSeat("30F")
        await("real catalog load failure is shown without changing the chair") {
            ShadowToast.getTextOfLatestToast()?.toString() == activityValue {
                it.getString(BookingR.string.seat_selection_scenes_unavailable)
            }
        }
        assertThat(activityValue { seatMap(it).selectedSeat.orEmpty() }).isEmpty()
        assertThat(activityValue { seatConfirm(it).visibility }).isEqualTo(View.GONE)
        assertThat(navDestination()).isEqualTo(R.id.seatSelectionFragment)
        assertThat((ShadowDialog.getLatestDialog() as? AnchoredScenePickerDialog)?.isShowing).isFalse()
        onActivity { activity ->
            SharedPreferencesSceneCatalogStore.from(activity).writeEnabled(AppSceneCatalog.SCOPE, seatSceneFixtures())
        }
        touchSeat("30F")
        awaitScenePicker()
        chooseSeatScene(SEAT_SCENE_B)
        await("the same actual chair can retry after the real store is repaired") {
            activityValue { seatMap(it).selectedSeat == "30F" && seatConfirm(it).isShown }
        }
        assertSeatSceneFill("30F", SEAT_SCENE_B_COLOR)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-notnight-mdpi")
    fun `missing record confirmation retains the seat page and does not publish a confirmed draft`() {
        seedSeatScenes(seatSceneFixtures())
        val missingRecord = "seat-nav-r160-missing-record"
        val wrongDraft = "seat-nav-r160-wrong-record-key"
        assertThat(runBlocking(Dispatchers.IO) { history.findById(missingRecord) }).isNull()
        onActivity { activity ->
            nav(activity).navigate(R.id.seatSelectionFragment, Bundle().apply {
                putString(SeatSelectionFragment.ARG_RECORD_ID, missingRecord)
                putString(SeatSelectionFragment.ARG_DRAFT_KEY, wrongDraft)
                putString(SeatSelectionFragment.ARG_FROM, DEPARTURE.icaoCode)
                putString(SeatSelectionFragment.ARG_TO, ARRIVAL.icaoCode)
            })
        }
        await("actual record seat page is attached") { navDestination() == R.id.seatSelectionFragment && currentPage().view != null }
        touchSeat("30F")
        awaitScenePicker()
        chooseSeatScene(SEAT_SCENE_B)
        onActivity { assertThat(seatConfirm(it).performClick()).isTrue() }
        await("real Room miss leaves an enabled retry on the seat page") {
            navDestination() == R.id.seatSelectionFragment && activityValue { seatConfirm(it).isEnabled } &&
                ShadowToast.getTextOfLatestToast()?.toString() == activityValue {
                    it.getString(BookingR.string.seat_selection_record_update_failed)
                }
        }
        assertThat(runBlocking(Dispatchers.IO) { history.findById(missingRecord) }).isNull()
        onActivity { activity ->
            assertThat(seatMap(activity).selectedSeat).isEqualTo("30F")
            assertThat(seatConfirm(activity).isShown).isTrue()
            assertThat(BookingDraftStore(activity).load(missingRecord)).isNull()
            assertThat(BookingDraftStore(activity).load(wrongDraft)).isNull()
            assertThat(SeatSelectionStore(activity).load(missingRecord)).isEqualTo("30F")
            assertThat(SeatSelectionStore(activity).load(wrongDraft)).isNull()
        }
        assertSeatSceneFill("30F", SEAT_SCENE_B_COLOR)
    }

    @Test
    fun `actual add segment and date arrows keep the whole list through recreation`() {
        openPreparation(withRoute = true)
        awaitPreparationStage(PreparationStage.DESTINATION)
        saveThroughSourceEditor()
        val first = preparationState().dailyPlan!!.legs.single()
        clickPreparation(BookingR.id.pf_tp_add_segment)
        awaitPreparationStage(PreparationStage.DESTINATION)
        assertThat(preparationState().origin?.code).isEqualTo(ARRIVAL.icaoCode)
        assertThat(preparationState().destination).isNull()
        clickPreparation(BookingR.id.pf_tp_floating_destination)
        awaitAirportSearch(RouteArgs.MODE_PICK_DESTINATION)
        pickAirportThroughRenderedRow(OTHER_ARRIVAL, "8m")
        awaitPreparationStage(PreparationStage.DESTINATION)
        saveThroughSourceEditor()
        val day = preparationState().dailyPlan!!
        assertThat(day.legs.size).isEqualTo(2)
        assertThat(day.legs.first()).isEqualTo(first)
        assertThat(day.legs.last().route?.departure?.code).isEqualTo(ARRIVAL.icaoCode)
        onActivity { activity -> assertThat(currentPage(activity).requireView()
            .findViewById<RecyclerView>(BookingR.id.pf_tp_segment_list).adapter!!.itemCount).isEqualTo(2) }
        clickPreparation(BookingR.id.pf_tp_prev_date)
        await("previous day is a separate empty list") { !preparationState().loading && preparationState().selectedDate != day.localDate }
        assertThat(preparationState().dailyPlan!!.legs).isEmpty()
        clickPreparation(BookingR.id.pf_tp_next_date)
        await("date arrows restore both confirmed legs") { !preparationState().loading && preparationState().dailyPlan == day }
        scenario!!.recreate()
        await("whole day survives Activity recreation") { currentPage() is FlightPreparationFragment && !preparationState().loading && preparationState().dailyPlan == day }
        assertThat(sessions.state.value).isEqualTo(SessionState.Idle)
        assertThat(runBlocking(Dispatchers.IO) { history.ongoing() }).isNull()
    }

    @Test
    fun `source arrows go six five four and cancel new or edited plans without committing`() {
        seedSeatScenes(SourceSceneCatalog.initialEnabledRecords())
        openPreparation(withRoute = true)
        awaitPreparationStage(PreparationStage.DESTINATION)
        clickPreparation(BookingR.id.pf_tp_floating_save)
        awaitPreparationStage(PreparationStage.SCENE)
        clickPreparation(BookingR.id.pf_tp_floating_save)
        awaitPreparationStage(PreparationStage.DEPARTURE_TIME)
        clickPreparation(BookingR.id.pf_tp_floating_back)
        awaitPreparationStage(PreparationStage.SCENE)
        clickPreparation(BookingR.id.pf_tp_floating_back)
        awaitPreparationStage(PreparationStage.DESTINATION)
        clickPreparation(BookingR.id.pf_tp_floating_back)
        awaitPreparationStage(PreparationStage.COMPACT)
        assertThat(preparationState().confirmed).isFalse()
        assertThat(persistedPlan()).isNull()
        assertThat(preparationControl(R.id.btnPreparationStart).visibility).isEqualTo(View.GONE)

        clickPreparation(BookingR.id.pf_tp_set_destination_action)
        awaitPreparationStage(PreparationStage.DESTINATION)
        val saved = saveThroughSourceEditor()
        beginEditingThroughSourceControls()
        clickPreparation(BookingR.id.pf_tp_floating_destination)
        awaitAirportSearch(RouteArgs.MODE_PICK_DESTINATION)
        pickAirport(OTHER_ARRIVAL)
        await("an actual row edits the route draft") {
            currentPage() is FlightPreparationFragment && !preparationState().loading &&
                preparationState().destination?.code == OTHER_ARRIVAL.icaoCode
        }
        assertThat(preparationState().plan!!.distanceKm).isEqualTo(200.0)
        assertThat(preparationState().confirmed).isFalse()
        assertThat(persistedPlan()).isEqualTo(saved)
        clickPreparation(BookingR.id.pf_tp_floating_save)
        awaitPreparationStage(PreparationStage.SCENE)
        clickPreparation(BookingR.id.pf_tp_floating_back)
        awaitPreparationStage(PreparationStage.DESTINATION)
        clickPreparation(BookingR.id.pf_tp_floating_back)
        awaitPreparationStage(PreparationStage.EXPANDED)
        assertThat(preparationState().plan).isEqualTo(saved)
        assertThat(preparationState().confirmed).isTrue()
        assertThat(preparationState().editing).isFalse()
        assertThat(persistedPlan()).isEqualTo(saved)
        assertThat(sessions.state.value).isEqualTo(SessionState.Idle)
        assertThat(runBlocking(Dispatchers.IO) { history.ongoing() }).isNull()
    }

    @Test
    fun `actual scene cancellation and stale displayed choice preserve pending metadata before a real work selection`() {
        val records = SourceSceneCatalog.initialEnabledRecords()
        val ids = seedSeatScenes(records)
        openPreparation(withRoute = true)
        awaitPreparationStage(PreparationStage.DESTINATION)
        clickPreparation(BookingR.id.pf_tp_floating_save)
        awaitPreparationStage(PreparationStage.SCENE)
        val before = preparationState()
        assertThat(before.scene!!.optionId).isEqualTo(ids[0])
        assertThat(before.departureClock).isNull()
        clickPreparation(BookingR.id.pf_tp_floating_scene_selector)
        awaitScenePicker()
        onActivity { currentScenePicker().cancel() }
        idle()
        assertThat(preparationState().stage).isEqualTo(PreparationStage.SCENE)
        assertThat(preparationState().scene).isEqualTo(before.scene)
        assertThat(preparationState().departureClock).isNull()
        assertThat(persistedPlan()).isNull()
        onActivity { assertThat(SharedPreferencesSceneCatalogStore.from(it).readEnabled(AppSceneCatalog.SCOPE).records)
            .containsExactlyElementsIn(records).inOrder() }

        clickPreparation(BookingR.id.pf_tp_floating_scene_selector)
        awaitScenePicker()
        // Simulate an external directory update after the actual tag was rendered.
        // The real Fragment callback must re-read by opaque ID, not trust its old list.
        onActivity { SharedPreferencesSceneCatalogStore.from(it).writeEnabled(AppSceneCatalog.SCOPE, records.take(1)) }
        choosePreparationScene(ids[1])
        assertThat(preparationState().scene).isEqualTo(before.scene)
        assertThat(preparationState().stage).isEqualTo(PreparationStage.SCENE)
        assertThat(preparationState().departureClock).isNull()
        assertThat(persistedPlan()).isNull()
        onActivity {
            val store = SharedPreferencesSceneCatalogStore.from(it)
            assertThat(store.readEnabled(AppSceneCatalog.SCOPE).records).containsExactly(records[0])
            store.writeEnabled(AppSceneCatalog.SCOPE, records)
        }
        clickPreparation(BookingR.id.pf_tp_floating_scene_selector)
        awaitScenePicker()
        choosePreparationScene(ids[1])
        await("the actual work tag updates metadata without moving to the clock") {
            preparationState().stage == PreparationStage.SCENE && preparationState().scene?.optionId == ids[1]
        }
        onActivity { activity ->
            val root = currentPage(activity).requireView()
            val icon = root.findViewById<ImageView>(BookingR.id.pf_tp_floating_scene_icon)
            assertThat(icon.isShown).isTrue()
            assertThat(icon.drawable).isNotNull()
            assertThat(shadowOf(icon.colorFilter as PorterDuffColorFilter).color).isEqualTo(records[1].color)
            assertThat(root.findViewById<TextView>(BookingR.id.pf_tp_floating_scene_text).text.toString())
                .isEqualTo(SourceSceneCatalog.label(activity, records[1]))
        }
        assertThat(preparationState().plan!!.categoryId).isNull()
        assertThat(preparationState().departureClock).isNull()
        assertThat(persistedPlan()).isNull()
        clickPreparation(BookingR.id.pf_tp_floating_save)
        awaitPreparationStage(PreparationStage.DEPARTURE_TIME)
        assertThat(preparationState().scene!!.optionId).isEqualTo(ids[1])
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-notnight-mdpi")
    fun `real wheels reject past preview and confirmed future clock permits one durable manual start`() {
        val previousZone = TimeZone.getDefault()
        val utcHour = ZonedDateTime.now(ZoneOffset.UTC).hour
        var offset = (10 - utcHour + 24) % 24
        if (offset > 12) offset -= 24
        // A test-only device-zone fixture keeps the real clock safely away from midnight.
        // It is not evidence of the original plan-zone supplier or a fake clock/store.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.ofHours(offset)))
        try {
            seedSeatScenes(SourceSceneCatalog.initialEnabledRecords())
            openPreparation(withRoute = true)
            awaitPreparationStage(PreparationStage.DESTINATION)
            clickPreparation(BookingR.id.pf_tp_floating_save)
            awaitPreparationStage(PreparationStage.SCENE)
            clickPreparation(BookingR.id.pf_tp_floating_save)
            awaitPreparationStage(PreparationStage.DEPARTURE_TIME)
            val initial = checkNotNull(preparationState().departureClock)
            if (initial.minute > 0) moveClockThroughWheels(initial.hour, initial.minute - 1)
            else moveClockThroughWheels(initial.hour - 1, initial.minute)
            await("past clock disables the actual preview") { !preparationState().timeCanPreview }
            assertThat(preparationControl(BookingR.id.pf_tp_floating_time_save).alpha).isWithin(.001f).of(.45f)
            val earliest = preparationState().earliestDeparture
            assertThat(preparationView(BookingR.id.pf_tp_floating_time_save).text.toString()).isEqualTo(
                activityValue { it.getString(BookingR.string.pf_tp_takeoff_plan_time_earliest_fmt, earliest) },
            )
            val rejected = preparationState().plan
            touchDisabledPreparationControl(BookingR.id.pf_tp_floating_time_save)
            assertThat(preparationState().stage).isEqualTo(PreparationStage.DEPARTURE_TIME)
            assertThat(preparationState().plan).isEqualTo(rejected)
            assertThat(persistedPlan()).isNull()

            val future = ZonedDateTime.now().plusMinutes(2)
            moveClockThroughWheels(future.hour, future.minute)
            await("future clock can be previewed") { preparationState().timeCanPreview }
            previewCurrentClock()
            val preview = checkNotNull(preparationState().plan)
            assertThat(preview.plannedDeparture!!.hour).isEqualTo(future.hour)
            assertThat(preview.plannedDeparture!!.minute).isEqualTo(future.minute)
            assertThat(preview.scene).isNotNull()
            assertThat(preview.categoryId).isNull()
            assertThat(preparationState().confirmed).isFalse()
            assertThat(persistedPlan()).isNull()
            scenario!!.recreate()
            awaitPreparationStage(PreparationStage.PREVIEW)
            assertThat(preparationState().plan).isEqualTo(preview)
            assertThat(preparationState().confirmed).isFalse()
            assertThat(persistedPlan()).isNull()
            assertThat(preparationControl(R.id.btnPreparationStart).visibility).isEqualTo(View.GONE)
            confirmPreviewThroughSourceControl()
            assertThat(preparationState().plan).isEqualTo(preview)
            assertThat(preparationState().departureDue).isFalse()
            assertThat(preparationControl(R.id.btnPreparationStart).visibility).isEqualTo(View.VISIBLE)
            assertThat(preparationControl(R.id.btnPreparationStart).isEnabled).isTrue()
            val queue = shadowOf(ApplicationProvider.getApplicationContext<Application>())
            queue.clearStartedServices()
            clickPreparation(R.id.btnPreparationStart)
            await("confirmed future clock starts the real session now") {
                currentPage() is HomeFragment && sessions.state.value is SessionState.Running
            }
            val row = checkNotNull(runBlocking(Dispatchers.IO) { history.ongoing() })
            assertThat(row.plannedDepartureJson).isEqualTo(com.projectfocus.core.model.flight.PlannedFlightMetadataCodec.encodeDeparture(preview.plannedDeparture))
            assertThat(runBlocking(Dispatchers.IO) { history.getAll().count { it.status == "ONGOING" } }).isEqualTo(1)
            assertThat(queue.allStartedServices.filter { it.component?.className == FocusMonitorService::class.java.name }).hasSize(1)
            assertThat(persistedPlan()).isEqualTo(preview)
        } finally { TimeZone.setDefault(previousZone) }
    }

    private fun seedSeatScenes(records: List<SourceSceneRecord>): List<String> {
        val ids = activityValue { activity ->
            SeatSelectionStore(activity).clearAll()
            BookingDraftStore(activity).clearAll()
            val store = SharedPreferencesSceneCatalogStore.from(activity)
            store.writeEnabled(AppSceneCatalog.SCOPE, records)
            val options = store.readEnabled(AppSceneCatalog.SCOPE).options
            assertThat(options).hasSize(records.size)
            options.map { it.optionId }
        }
        idle()
        return ids
    }

    private fun seatSceneFixtures(): List<SourceSceneRecord> = listOf(
        SourceSceneRecord(SEAT_SCENE_A, "seat_nav_shared_custom", SEAT_SCENE_A_COLOR,
            isCustom = true, clientUuid = "16000000-0000-0000-0000-000000000001"),
        SourceSceneRecord(SEAT_SCENE_B, "seat_nav_shared_custom", SEAT_SCENE_B_COLOR,
            isCustom = true, clientUuid = "16000000-0000-0000-0000-000000000002"),
    )

    private fun openEditorSeatPicker(): FlightLegPlan {
        openPreparation(withRoute = true)
        await("source plan is ready for its actual seat action") { preparationState().plan != null && !preparationState().loading }
        val before = saveThroughSourceEditor()
        clickPreparation(BookingR.id.pf_tp_flight_seat)
        await("actual source footer opens the seat deep link") { navDestination() == R.id.seatSelectionFragment && currentPage().view != null }
        return before
    }

    private fun seatMap(activity: MainActivity): SeatSelectionMapView =
        currentPage(activity).requireView().findViewById(BookingR.id.seatMap)

    private fun seatConfirm(activity: MainActivity): MaterialButton =
        currentPage(activity).requireView().findViewById(BookingR.id.btnConfirmSeat)

    private fun measureSeatWindow(activity: MainActivity) {
        val metrics = activity.resources.displayMetrics
        val decor = activity.window.decorView
        repeat(2) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            idle()
        }
    }

    private fun touchSeat(label: String) {
        onActivity { activity ->
            measureSeatWindow(activity)
            val root = currentPage(activity).requireView() as ViewGroup
            val chair = checkNotNull(root.findViewWithTag<TextView>(label))
            val scroll = root.findViewById<android.widget.ScrollView>(BookingR.id.pf_seat_scroll)
            val contentBounds = Rect(0, 0, chair.width, chair.height)
            scroll.offsetDescendantRectToMyCoords(chair, contentBounds)
            // Leave the actual bottom confirmation button unobscured when another chair is chosen.
            val reserve = (128 * root.resources.displayMetrics.density).toInt()
            scroll.scrollTo(0, (contentBounds.bottom - scroll.height + reserve).coerceAtLeast(0))
            idle()
            val target = Rect(0, 0, chair.width, chair.height)
            root.offsetDescendantRectToMyCoords(chair, target)
            assertThat(target.width()).isGreaterThan(0)
            assertThat(target.height()).isGreaterThan(0)
            assertThat(target.left).isAtLeast(0)
            assertThat(target.right).isAtMost(root.width)
            assertThat(target.top).isAtLeast(0)
            assertThat(target.bottom).isAtMost(root.height - reserve)
            val now = SystemClock.uptimeMillis()
            listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event = android.view.MotionEvent.obtain(now, now + index * 20L, action,
                    target.exactCenterX(), target.exactCenterY(), 0)
                try { assertThat(root.dispatchTouchEvent(event)).isTrue() } finally { event.recycle() }
            }
        }
        idle()
    }

    private fun awaitScenePicker() {
        await("actual anchored scene card is showing") {
            val dialog = ShadowDialog.getLatestDialog()
            dialog is AnchoredScenePickerDialog && dialog.isShowing
        }
    }

    private fun currentScenePicker(): AnchoredScenePickerDialog =
        (ShadowDialog.getLatestDialog() as AnchoredScenePickerDialog).also { assertThat(it.isShowing).isTrue() }

    private fun sceneTagButtons(dialog: AnchoredScenePickerDialog): List<MaterialButton> =
        descendants(dialog.findViewById(CoreUiR.id.pf_scene_card_root)).filterIsInstance<MaterialButton>().filter { it.tag is String }

    private fun chooseSeatScene(name: String) {
        onActivity {
            val dialog = currentScenePicker()
            assertThat(sceneTagButtons(dialog).map { it.text.toString() }).containsExactly(SEAT_SCENE_A, SEAT_SCENE_B)
            val button = sceneTagButtons(dialog).single { it.text.toString() == name }
            assertThat(button.performClick()).isTrue()
        }
        idle()
        assertThat((ShadowDialog.getLatestDialog() as AnchoredScenePickerDialog).isShowing).isFalse()
    }

    private fun assertSeatSceneFill(seat: String, expected: Int) = onActivity { activity ->
        val chair = seatMap(activity).findViewWithTag<TextView>(seat)
        assertThat(chair.text.toString()).isEmpty()
        val actualLayers = chair.background as android.graphics.drawable.LayerDrawable
        val actualBackground = actualLayers.getDrawable(0) as android.graphics.drawable.GradientDrawable
        assertThat(actualBackground.color!!.defaultColor).isEqualTo(expected)
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()

    private fun prepareNativeInitialSeatScenes() = onActivity { activity ->
        SeatSelectionStore(activity).clearAll()
        BookingDraftStore(activity).clearAll()
        // Exercise the production first-read path, not a test-only list installed in Main.
        activity.getSharedPreferences(SharedPreferencesSceneCatalogStore.FILE_NAME, android.content.Context.MODE_PRIVATE)
            .edit().remove(SharedPreferencesSceneCatalogStore.enabledKey(AppSceneCatalog.SCOPE)).commit()
        SharedPreferencesSceneCatalogStore.from(activity).readEnabled(AppSceneCatalog.SCOPE)
    }

    private fun writeNativeSeatScenePreviews(output: File, night: Boolean) {
        val firstId = activityValue { activity ->
            val ready = (activity as SeatSceneChoicesHost).seatSceneChoices(currentPage(activity).arguments ?: Bundle.EMPTY)
                as SeatSceneChoices.Ready
            assertThat(ready.values.map { it.iconKey }).containsExactly("study", "work").inOrder()
            ready.values.first().optionId
        }
        touchSeat("01A")
        awaitScenePicker()
        writeNativeScenePopupPreview(output, night)
        onActivity {
            val button = sceneTagButtons(currentScenePicker()).single { it.tag == firstId }
            assertThat(button.performClick()).isTrue()
        }
        await("initial real study scene exposes the native bottom confirmation") {
            activityValue { seatMap(it).selectedSeat == "01A" && seatConfirm(it).isShown }
        }
        // Render the actual bottom CTA before navigation; functional cases above perform confirmation.
        writeNativePreview(output, "seat-confirmation", night)
    }

    private fun writeNativeScenePopupPreview(output: File, night: Boolean, screen: String = "seat-scene-popup") {
        settleNativeEntrance()
        onActivity { activity ->
            measureSeatWindow(activity)
            val dialog = currentScenePicker()
            val window = checkNotNull(dialog.window)
            val mainDecor = activity.window.decorView
            val popupDecor = window.decorView
            val density = activity.resources.displayMetrics.density
            val width = (390 * density).toInt()
            val height = (844 * density).toInt()
            popupDecor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            popupDecor.layout(0, 0, popupDecor.measuredWidth, popupDecor.measuredHeight)
            val card = dialog.findViewById<View>(CoreUiR.id.pf_scene_card_root)
            assertThat(card.measuredWidth).isEqualTo((270 * density).toInt())
            assertThat(card.measuredHeight).isEqualTo((310 * density).toInt())
            assertThat(card.alpha).isGreaterThan(0f)
            val actualLabels = sceneTagButtons(dialog).map { it.text.toString() }
            val actualChoices = ((activity as SeatSceneChoicesHost).seatSceneChoices(currentPage(activity).arguments ?: Bundle.EMPTY)
                as SeatSceneChoices.Ready).values
            assertThat(actualChoices.map { it.iconKey }).containsExactly("study", "work").inOrder()
            assertThat(actualLabels).containsExactlyElementsIn(actualChoices.map { it.title }).inOrder()
            val configuredNight = card.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            assertThat(configuredNight).isEqualTo(if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            val attributes = window.attributes
            assertThat(attributes.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(android.view.Gravity.LEFT)
            assertThat(attributes.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK).isEqualTo(android.view.Gravity.TOP)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val actualBackground = checkNotNull(mainDecor.background)
                val previousBounds = Rect(actualBackground.bounds)
                try {
                    actualBackground.setBounds(0, 0, width, height)
                    actualBackground.draw(canvas)
                } finally { actualBackground.bounds = previousBounds }
                // Isolate both actual window roots on native canvases. Drawing a second
                // View root on the first root's Canvas can retain display-list state.
                val mainSurface = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try { mainDecor.draw(Canvas(mainSurface)); canvas.drawBitmap(mainSurface, 0f, 0f, null) }
                finally { mainSurface.recycle() }
                val popupSurface = Bitmap.createBitmap(popupDecor.measuredWidth, popupDecor.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    popupDecor.draw(Canvas(popupSurface))
                    canvas.drawBitmap(popupSurface, attributes.x.toFloat(), attributes.y.toFloat(), null)
                } finally { popupSurface.recycle() }
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                assertThat(pixels.count { Color.alpha(it) != 255 }).isEqualTo(0)
                val suffix = if (night) "night" else "day"
                File(output, "$screen-$suffix.png").outputStream().use {
                    assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue()
                }
                val location = IntArray(2)
                popupDecor.getLocationOnScreen(location)
                val metadata = org.json.JSONObject()
                    .put("kind", "native_actual_main_and_dialog_view_drawing")
                    .put("viewportWidth", width).put("viewportHeight", height)
                    .put("runtimeWindowX", attributes.x).put("runtimeWindowY", attributes.y)
                    .put("runtimeWindowGravity", attributes.gravity)
                    .put("measuredDialogWidth", popupDecor.measuredWidth).put("measuredDialogHeight", popupDecor.measuredHeight)
                    .put("decorLocationOnScreenX", location[0]).put("decorLocationOnScreenY", location[1])
                    .put("labels", org.json.JSONArray(actualLabels))
                    .put("source", "Production MainActivity, SceneHost, initial Store study/work, actual anchor tap and actual Dialog decor; no mock controls or arbitrary backdrop")
                    .put("placement", "Each real window uses its own native surface; composition uses actual TOP|LEFT LayoutParams x/y. Not PixelCopy/device capture or original-APK screenshot.")
                File(output, "$screen-$suffix.json").writeText(metadata.toString(2))
            } finally { bitmap.recycle() }
        }
    }

    @Test
    fun `accepted UI ticks advance the real flight without restarting its foreground service`() {
        openPreparation(withRoute = true)
        await("route resolved") { preparationState().plan != null && !preparationState().loading }
        saveThroughSourceEditor()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val serviceQueue = shadowOf(application)
        fun monitorStarts() = serviceQueue.allStartedServices.filter {
            it.component?.className == FocusMonitorService::class.java.name
        }
        serviceQueue.clearStartedServices()
        clickPreparation(R.id.btnPreparationStart)
        await("actual takeoff is visible on Home") {
            currentPage() is HomeFragment && sessions.state.value is SessionState.Running
        }
        useActualMissingMap()
        assertDayFallbackControlsReadable()
        assertThat(monitorStarts()).hasSize(1)
        assertThat(monitorStarts().single().action).isEqualTo(FocusMonitorService.ACTION_START)
        val sessionId = (sessions.state.value as SessionState.Running).sessionId
        val initialDistance = activityValue {
            currentPage(it).requireView().findViewById<com.projectfocus.feature.flight.hud.SourceNumberScrollView>(FlightR.id.pf_flight_distance_value).getValue().toDouble()
        }
        serviceQueue.clearStartedServices()
        repeat(4) {
            val before = sessions.state.value as SessionState.Running
            val clockBefore = SystemClock.elapsedRealtime()
            ShadowSystemClock.advanceBy(1, TimeUnit.SECONDS)
            assertThat(sessions.nowMono()).isAtLeast(clockBefore + 1_000L)
            runBlocking(Dispatchers.IO) { sessions.tick() }
            await("accepted tick publishes actual progress") {
                (sessions.state.value as? SessionState.Running)?.accumulatedMs?.let { it > before.accumulatedMs } == true
            }
            val after = sessions.state.value as SessionState.Running
            assertThat(after.sessionId).isEqualTo(sessionId)
            assertThat(after.progress(sessions.nowMono())).isGreaterThan(before.progress(sessions.nowMono()))
            assertThat(monitorStarts()).isEmpty()
        }
        val updatedDistance = activityValue {
            currentPage(it).requireView().findViewById<com.projectfocus.feature.flight.hud.SourceNumberScrollView>(FlightR.id.pf_flight_distance_value).getValue().toDouble()
        }
        assertThat(updatedDistance).isLessThan(initialDistance)

        // Re-entering the foreground still requests the service once, so a killed service can recover.
        scenario!!.moveToState(Lifecycle.State.CREATED)
        idle()
        serviceQueue.clearStartedServices()
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        await("foreground re-entry requests the actual service once") { monitorStarts().isNotEmpty() }
        assertThat(monitorStarts()).hasSize(1)
        assertThat((sessions.state.value as SessionState.Running).sessionId).isEqualTo(sessionId)
        serviceQueue.clearStartedServices()
        val before = sessions.state.value as SessionState.Running
        ShadowSystemClock.advanceBy(1, TimeUnit.SECONDS)
        runBlocking(Dispatchers.IO) { sessions.tick() }
        await("progress continues after foreground re-entry") {
            (sessions.state.value as? SessionState.Running)?.accumulatedMs?.let { it > before.accumulatedMs } == true
        }
        assertThat(monitorStarts()).isEmpty()
    }

    @Test
    fun `real pause resume and arrival transitions never restore the ground console during flight`() {
        openPreparation(withRoute = true)
        await("route resolved") { preparationState().plan != null && !preparationState().loading }
        saveThroughSourceEditor()
        clickPreparation(R.id.btnPreparationStart)
        await("real flight running") { currentPage() is HomeFragment && sessions.state.value is SessionState.Running }
        useActualMissingMap()
        assertDayFallbackControlsReadable()
        onActivity { currentPage(it).requireView().findViewById<View>(FlightR.id.pf_flight_pause).performClick() }
        await("real flight paused") { sessions.state.value is SessionState.Paused }
        assertDayFallbackControlsReadable()
        val paused = sessions.state.value as SessionState.Paused
        onActivity { activity ->
            val root = currentPage(activity).requireView()
            assertThat(root.findViewById<View>(R.id.bottomStack).isShown).isFalse()
            assertThat(root.findViewById<View>(R.id.groundHomeContent).visibility).isEqualTo(View.GONE)
            assertThat(root.findViewById<View>(R.id.sourceMap).visibility).isEqualTo(View.VISIBLE)
            assertThat(root.findViewById<View>(FlightR.id.pf_flight_focus_scroll).isShown).isTrue()
            assertThat(root.findViewById<TextView>(FlightR.id.pf_flight_focus_status).text.toString())
                .isEqualTo(root.context.getString(FlightR.string.pf_flight_paused))
            assertThat(root.findViewById<View>(FlightR.id.pf_flight_right_controls).visibility).isEqualTo(View.GONE)
            assertThat(root.findViewById<View>(FlightR.id.pf_flight_cancel).visibility).isEqualTo(View.VISIBLE)
        }
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertThat((sessions.state.value as SessionState.Paused).elapsedMs()).isEqualTo(paused.elapsedMs())
        onActivity { currentPage(it).requireView().findViewById<View>(FlightR.id.pf_flight_pause).performClick() }
        await("real flight resumed") { sessions.state.value is SessionState.Running }
        assertDayFallbackControlsReadable()
        onActivity { activity ->
            val root = currentPage(activity).requireView()
            assertThat(root.findViewById<View>(FlightR.id.pf_flight_pause_overlay).visibility).isEqualTo(View.GONE)
            assertThat(root.findViewById<View>(R.id.bottomStack).isShown).isFalse()
            root.findViewById<View>(FlightR.id.pf_flight_pause).performClick()
        }
        await("flight paused for explicit cancellation") { sessions.state.value is SessionState.Paused }
        onActivity { currentPage(it).requireView().findViewById<View>(FlightR.id.pf_flight_cancel).performLongClick() }
        onActivity {
            (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog)
                .getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        await("cancel transitions to real completion navigation") {
            sessions.state.value is SessionState.Arrived && navDestination() == R.id.flightCompletionFragment
        }
        onActivity { nav(it).popBackStack(R.id.homeFragment, false) }
        await("home can display the arrived inactive state") { currentPage() is HomeFragment }
        assertInactiveHome()
        runBlocking(Dispatchers.IO) { sessions.settle() }
        await("session settles to idle") { sessions.state.value is SessionState.Idle }
        assertInactiveHome()
    }

    private fun useActualMissingMap() {
        onActivity { activity ->
            val home = currentPage(activity) as HomeFragment
            home.requireView().findViewById<com.projectfocus.feature.home.map.SourceMapHostView>(R.id.sourceMap)
                .bindLifecycle(home.viewLifecycleOwner, com.projectfocus.feature.home.map.MapConfiguration(""))
        }
        idle()
    }

    private fun assertDayFallbackControlsReadable() {
        onActivity { activity ->
            val root = currentPage(activity).requireView()
            assertThat(root.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                .isNotEqualTo(android.content.res.Configuration.UI_MODE_NIGHT_YES)
            assertThat(root.findViewById<com.projectfocus.feature.home.map.SourceMapHostView>(R.id.sourceMap).state.value.phase)
                .isEqualTo(com.projectfocus.feature.home.map.MapPhase.MISSING_CONFIGURATION)
            val expected = root.context.getColor(com.projectfocus.core.ui.R.color.sky_ink)
            assertThat(androidx.core.graphics.ColorUtils.calculateContrast(expected,
                root.context.getColor(com.projectfocus.core.ui.R.color.sky_card_solid))).isAtLeast(4.5)
            for (id in listOf(FlightR.id.pf_flight_pause, FlightR.id.pf_flight_audio,
                    FlightR.id.pf_flight_map_style, FlightR.id.pf_flight_window_toggle))
                assertThat(root.findViewById<android.widget.ImageView>(id).imageTintList?.defaultColor).isEqualTo(expected)
        }
    }

    private fun assertInactiveHome() {
        onActivity { activity ->
            val root = currentPage(activity).requireView()
            assertThat(root.findViewById<View>(R.id.flightHudOverlay).visibility).isEqualTo(View.GONE)
            assertThat(root.findViewById<View>(R.id.sourceMap).visibility).isEqualTo(View.VISIBLE)
            assertThat(root.findViewById<View>(R.id.flightWindow).visibility).isEqualTo(View.GONE)
            assertThat(root.findViewById<View>(R.id.groundHomeContent).visibility).isEqualTo(View.VISIBLE)
            assertThat(root.findViewById<View>(R.id.bottomStack).visibility).isEqualTo(View.VISIBLE)
        }
    }

    // Visual artifacts are separate cases from the route/persistence behavior checks above.
    // They render this implementation; no reference screenshot or fidelity score is implied.
    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `native day preparation and Home HUD previews are written at 390 dp`() {
        writeNativePreviews(night = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-night-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `native night preparation and Home HUD previews are written at 390 dp`() {
        writeNativePreviews(night = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `native completed day cards and real canceled batch menu persist and reload`() {
        writeNativeCompletionAndBatch(night = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-night-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `native completed night cards and real canceled batch menu persist and reload`() {
        writeNativeCompletionAndBatch(night = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `native sorted day rows and prefetched tomorrow lock use production state`() = writeNativeOrderedPlans(false)

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-night-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `native sorted night rows and prefetched tomorrow lock use production state`() = writeNativeOrderedPlans(true)

    private fun writeNativeOrderedPlans(night: Boolean) {
        com.projectfocus.core.network.di.TokenStore.clear()
        val now = ZonedDateTime.now().withSecond(0).withNano(0)
        runBlocking(Dispatchers.IO) {
            ApplicationProvider.getApplicationContext<Application>().assets.open(AirportSeeder.ASSET_PATH).bufferedReader().use { reader ->
                airports.upsertAll(AirportCsvParser.parse(reader).filter { it.iataCode in setOf("PEK", "PVG", "CAN") }.toList())
            }
        }
        fun route(from: String, to: String) = runBlocking(Dispatchers.IO) { sessions.preparePlan(from, to) }
            .copy(plannedDeparture = com.projectfocus.core.model.flight.PlannedDeparture(
                now.toLocalDate().toString(), now.hour, now.minute, now.zone.id))
        val a = DailyFlightPlans.fromRoute(route("PEK", "PVG"), now.toLocalDate().toString(), now.zone.id, 1)
        val b = DailyFlightPlans.fromRoute(route("PVG", "CAN"), now.toLocalDate().toString(), now.zone.id, 2)
        val day = DailyFlightPlan(now.toLocalDate().toString(), now.zone.id, legs = listOf(b, a))
        val store = activityValue { activity -> SharedPreferencesConfirmedFlightPlanStore(activity.getSharedPreferences(
            SharedPreferencesConfirmedFlightPlanStore.FILE_NAME, android.content.Context.MODE_PRIVATE), Dispatchers.IO) }
        runBlocking { store.saveDay(day) }
        openPreparation(withRoute = false)
        await("wire day loads without sorting its backing list") { !preparationState().loading && preparationState().dailyPlan == day }
        val output = File("build/reports/r164-plan-order-preview").apply { mkdirs() }
        writeNativePreview(output, "plan-sorted", night)
        onActivity { activity ->
            val list = currentPage(activity).requireView().findViewById<RecyclerView>(BookingR.id.pf_tp_segment_list)
            val first = checkNotNull(list.findViewHolderForAdapterPosition(0)).itemView
            val second = checkNotNull(list.findViewHolderForAdapterPosition(1)).itemView
            assertThat(first.findViewById<TextView>(BookingR.id.pf_tp_dep_code).text.toString()).isEqualTo("PEK")
            assertThat(second.findViewById<TextView>(BookingR.id.pf_tp_dep_code).text.toString()).isEqualTo("PVG")
            assertThat(preparationViewModel(currentPage(activity)).canEdit(0)).isFalse()
            assertThat(preparationViewModel(currentPage(activity)).canEdit(1)).isTrue()
        }
        val tomorrow = day.copy(localDate = now.toLocalDate().plusDays(1).toString(),
            legs = listOf(a.copy(snapshot = a.snapshot.copy(legStatus = "completed"))))
        runBlocking { store.saveDay(tomorrow) }
        clickPreparation(BookingR.id.pf_tp_prev_date)
        await("previous date is loaded") { !preparationState().loading && preparationState().selectedDate != day.localDate }
        shadowOf(Looper.getMainLooper()).idleFor(2_100, TimeUnit.MILLISECONDS)
        clickPreparation(BookingR.id.pf_tp_next_date)
        await("production tomorrow prefetch locks today") {
            !preparationState().loading && preparationState().selectedDate == day.localDate && preparationState().todayBlocked
        }
        assertThat(preparationState().canAddSegment).isFalse()
        onActivity { activity ->
            val vm = preparationViewModel(currentPage(activity))
            assertThat(vm.canEdit(1)).isFalse()
            assertThat(vm.canRemove(1)).isFalse()
        }
        writeNativePreview(output, "plan-tomorrow-locked", night)
        assertThat(runBlocking { store.loadDay(day.localDate, day.timezone, day.localDate) }).isEqualTo(day)
        assertThat(sessions.state.value).isEqualTo(SessionState.Idle)
    }

    private fun writeNativeCompletionAndBatch(night: Boolean) {
        com.projectfocus.core.network.di.TokenStore.clear()
        val now = ZonedDateTime.now().withSecond(0).withNano(0)
        runBlocking(Dispatchers.IO) {
            ApplicationProvider.getApplicationContext<Application>().assets.open(AirportSeeder.ASSET_PATH).bufferedReader().use { reader ->
                airports.upsertAll(AirportCsvParser.parse(reader).filter { it.iataCode in setOf("PEK", "PVG") }.toList())
            }
        }
        val route = runBlocking(Dispatchers.IO) { sessions.preparePlan("PEK", "PVG") }
            .copy(plannedDeparture = com.projectfocus.core.model.flight.PlannedDeparture(
                now.toLocalDate().toString(), now.hour, now.minute, now.zone.id))
        val first = DailyFlightPlans.fromRoute(route, now.toLocalDate().toString(), now.zone.id, 1)
        val expected = now.toInstant().plusSeconds(3_600L)
        val early = first.copy(snapshot = first.snapshot.copy(legStatus = "completed", expectedArrivalAt = expected.toString(),
            actualArrivalAt = expected.minusMillis(119_999L).toString()))
        val late = first.copy(snapshot = first.snapshot.copy(legIndex = 2, legStatus = "completed", expectedArrivalAt = expected.toString(),
            actualArrivalAt = expected.plusMillis(120_001L).toString()))
        val canceled = first.copy(snapshot = first.snapshot.copy(legIndex = 3, legStatus = "canceled"))
        val day = DailyFlightPlan(now.toLocalDate().toString(), now.zone.id, legs = listOf(early, late, canceled))
        val store = activityValue { activity -> SharedPreferencesConfirmedFlightPlanStore(activity.getSharedPreferences(
            SharedPreferencesConfirmedFlightPlanStore.FILE_NAME, android.content.Context.MODE_PRIVATE), Dispatchers.IO) }
        runBlocking { store.saveDay(day) }
        openPreparation(withRoute = false)
        await("real daily store loads completed and canceled rows") {
            !preparationState().loading && preparationState().dailyPlan == day
        }
        val output = File("build/reports/r163-plan-status-preview").apply { mkdirs() }
        writeNativePreview(output, "plan-completion", night)
        onActivity { activity ->
            val list = currentPage(activity).requireView().findViewById<RecyclerView>(BookingR.id.pf_tp_segment_list)
            val earlyRow = checkNotNull(list.findViewHolderForAdapterPosition(0)).itemView
            val lateRow = checkNotNull(list.findViewHolderForAdapterPosition(1)).itemView
            assertThat(earlyRow.findViewById<TextView>(BookingR.id.pf_tp_segment_status).text.toString())
                .isEqualTo(activity.getString(BookingR.string.pf_tp_plan_status_completed_early_fmt, 1))
            assertThat(earlyRow.findViewById<TextView>(BookingR.id.pf_tp_segment_status).typeface.style).isEqualTo(1)
            assertThat(lateRow.findViewById<TextView>(BookingR.id.pf_tp_segment_status).typeface.style).isEqualTo(3)
            assertThat(earlyRow.performLongClick()).isFalse()
            assertThat(lateRow.performLongClick()).isFalse()
            assertThat(checkNotNull(list.findViewHolderForAdapterPosition(2)).itemView.performLongClick()).isTrue()
        }
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        onActivity { activity ->
            val popup = checkNotNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).latestPopupWindow)
            assertThat(popup.isShowing).isTrue()
            val content = popup.contentView as android.widget.LinearLayout
            assertThat(content.childCount).isEqualTo(3)
            assertThat(content.findViewById<View>(BookingR.id.pf_tp_segment_menu_edit).isEnabled).isFalse()
            assertThat(content.findViewById<View>(BookingR.id.pf_tp_segment_menu_delete).isEnabled).isTrue()
            val batch = content.findViewById<android.widget.LinearLayout>(BookingR.id.pf_tp_segment_menu_batch_delete)
            assertThat((batch.getChildAt(0) as TextView).text.toString())
                .isEqualTo(activity.getString(BookingR.string.pf_tp_segment_context_batch_delete_canceled))
            val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
            try {
                content.draw(Canvas(bitmap))
                File(output, "plan-canceled-menu-${if (night) "night" else "day"}.png").outputStream().use {
                    assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue()
                }
            } finally { bitmap.recycle() }
            assertThat(batch.performClick()).isTrue()
            assertThat(popup.isShowing).isFalse()
        }
        await("real batch action reloads the remaining completed rows") {
            !preparationState().loading && !preparationState().busy && preparationState().dailyPlan?.legs == listOf(early, late)
        }
        assertThat(runBlocking { store.loadDay(day.localDate, day.timezone, day.localDate) }.legs).containsExactly(early, late).inOrder()
        assertThat(ShadowToast.getTextOfLatestToast()?.toString()).isEqualTo(activityValue {
            it.getString(BookingR.string.pf_tp_plan_deleted_canceled_legs_fmt, 1)
        })
        assertThat(sessions.state.value).isEqualTo(SessionState.Idle)
        assertThat(runBlocking(Dispatchers.IO) { history.ongoing() }).isNull()
        writeNativePreview(output, "plan-after-batch-delete", night)
    }

    private fun writeNativePreviews(night: Boolean) {
        val output = File("build/reports/flight-preparation-preview").apply { mkdirs() }
        val previewAirports = runBlocking(Dispatchers.IO) {
            ApplicationProvider.getApplicationContext<Application>().assets.open(AirportSeeder.ASSET_PATH).bufferedReader().use { reader ->
                AirportCsvParser.parse(reader).filter { it.iataCode in setOf("PEK", "PVG", "CAN") }.toList()
            }.also { rows ->
                assertThat(rows.map { it.iataCode }).containsExactly("PEK", "PVG", "CAN")
                airports.upsertAll(rows)
            }
        }
        writeNativePreview(output, "home-ground", night)
        onActivity { nav(it).navigate(R.id.action_home_to_airport, Bundle().apply {
            putString(RouteArgs.ARG_MODE, RouteArgs.MODE_PICK_ORIGIN)
        }) }
        awaitAirportSearch(RouteArgs.MODE_PICK_ORIGIN)
        assertPickerReference(null)
        await("airport suggestions load without typing") {
            activityValue { currentPage(it).requireView().findViewById<View>(AirportR.id.idlePanel)?.isShown == true }
        }
        writeNativePreview(output, "airport-search", night)
        onActivity { currentPage(it).requireView().findViewById<android.widget.EditText>(AirportR.id.searchInput).setText("International") }
        await("real SQL airport results are committed to the actual list") {
            activityValue {
                val list = currentPage(it).requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(AirportR.id.resultList)
                list.isShown && (list.adapter?.itemCount ?: 0) > 0
            }
        }
        writeNativePreview(output, "airport-results", night)
        assertVisibleAirportDurations(null)
        onActivity { nav(it).popBackStack() }
        await("home is restored after airport preview") { currentPage() is HomeFragment }
        onActivity { nav(it).navigate(R.id.action_home_to_settings) }
        await("settings is attached") { navDestination() == R.id.settingsFragment && currentPage().view != null }
        writeNativePreview(output, "settings", night)
        onActivity { nav(it).popBackStack() }
        await("home is restored after settings") { currentPage() is HomeFragment }
        prepareNativeInitialSeatScenes()
        onActivity { nav(it).navigate(R.id.seatSelectionFragment) }
        await("source seat map is attached") { navDestination() == R.id.seatSelectionFragment && currentPage().view != null }
        writeNativePreview(output, "seat-source", night)
        writeNativeSeatScenePreviews(output, night)
        onActivity { nav(it).popBackStack() }
        await("home is restored after seat preview") { currentPage() is HomeFragment }
        onActivity { nav(it).navigate(R.id.action_home_to_flight_preparation, Bundle().apply {
            putString(FlightPreparationFragment.ARG_ORIGIN, previewAirports.single { it.iataCode == "PEK" }.iataCode)
            putString(FlightPreparationFragment.ARG_DESTINATION, previewAirports.single { it.iataCode == "PVG" }.iataCode)
        }) }
        await("preview preparation host is attached") { currentPage() is FlightPreparationFragment }
        await("preview route is resolved") { preparationState().plan != null && !preparationState().loading }
        val previewPlan = checkNotNull(preparationState().plan)
        assertThat(previewPlan.departure.code).isEqualTo("PEK")
        assertThat(previewPlan.destination.code).isEqualTo("PVG")
        clickPreparation(BookingR.id.pf_tp_floating_destination)
        awaitAirportSearch(RouteArgs.MODE_PICK_DESTINATION)
        val reference = AirportReferencePoint.from(previewPlan.departure.latitude, previewPlan.departure.longitude)
        assertThat(reference).isNotNull()
        assertPickerReference(reference)
        onActivity { currentPage(it).requireView().findViewById<EditText>(AirportR.id.searchInput).setText("International") }
        await("destination search commits actual airports with the PEK reference") {
            shadowOf(Looper.getMainLooper()).idleFor(24, TimeUnit.MILLISECONDS)
            activityValue {
                val list = currentPage(it).requireView().findViewById<RecyclerView>(AirportR.id.resultList)
                list.isShown && (list.adapter?.itemCount ?: 0) > 0
            }
        }
        writeNativePreview(output, "airport-destinations", night)
        assertVisibleAirportDurations(reference)
        onActivity { currentPage(it).requireView().findViewById<View>(AirportR.id.btnBack).performClick() }
        await("cancelled destination preview returns to the original unsaved plan") {
            currentPage() is FlightPreparationFragment && !preparationState().loading &&
                preparationState().plan == previewPlan && !preparationState().confirmed
        }
        assertAirportResultConsumed()
        writeNativePreview(output, "preparation-step1-destination", night)
        assertThat(persistedPlan()).isNull()
        clickPreparation(BookingR.id.pf_tp_floating_save)
        awaitPreparationStage(PreparationStage.SCENE)
        clickPreparation(BookingR.id.pf_tp_floating_scene_selector)
        awaitScenePicker()
        writeNativeScenePopupPreview(output, night, "preparation-scene-popup")
        val workId = activityValue { activity ->
            SharedPreferencesSceneCatalogStore.from(activity).readEnabled(AppSceneCatalog.SCOPE)
                .options.single { it.record.iconKey == "work" }.optionId
        }
        choosePreparationScene(workId)
        await("native selected scene remains in step2") {
            preparationState().stage == PreparationStage.SCENE && preparationState().scene?.optionId == workId
        }
        assertThat(preparationControl(BookingR.id.pf_tp_floating_scene_icon).isShown).isTrue()
        writeNativePreview(output, "preparation-step2-scene", night)
        assertThat(persistedPlan()).isNull()
        clickPreparation(BookingR.id.pf_tp_floating_save)
        awaitPreparationStage(PreparationStage.DEPARTURE_TIME)
        onActivity { activity ->
            val root = currentPage(activity).requireView()
            for (id in listOf(BookingR.id.pf_tp_floating_time_hour, BookingR.id.pf_tp_floating_time_minute)) {
                val wheel = root.findViewById<SourceStringWheelPicker>(id)
                assertThat(wheel.isShown).isTrue()
                assertThat(wheel.getSelectedItem()).isNotEmpty()
            }
        }
        writeNativePreview(output, "preparation-step3-clock", night)
        previewCurrentClock()
        assertThat(preparationState().confirmed).isFalse()
        assertThat(persistedPlan()).isNull()
        onActivity { activity ->
            val scene = checkNotNull(preparationState().scene)
            val card = currentPage(activity).requireView()
                .findViewById<View>(BookingR.id.pf_tp_add_segment_compact_container)
            val icon = card.findViewById<ImageView>(BookingR.id.pf_tp_focus_icon)
            assertThat(icon.isShown).isTrue()
            assertThat(shadowOf(icon.drawable).createdFromResId).isEqualTo(SourceSceneCatalog.icon(scene.iconKey))
            assertThat(icon.imageTintList?.defaultColor).isEqualTo(scene.color)
            assertThat(card.findViewById<TextView>(BookingR.id.pf_tp_focus_scene).currentTextColor).isEqualTo(scene.color)
        }
        writeNativePreview(output, "preparation-preview", night)
        confirmPreviewThroughSourceControl()
        onActivity { activity ->
            val root = currentPage(activity).requireView()
            val backdrop = activity.getColor(CoreUiR.color.bg_base)
            val dateCard = root.findViewById<com.google.android.material.card.MaterialCardView>(BookingR.id.pf_tp_date_selector_card)
            val dateBackdrop = ColorUtils.compositeColors(dateCard.cardBackgroundColor.defaultColor, backdrop)
            for (id in listOf(BookingR.id.pf_tp_date_title, BookingR.id.pf_tp_total_distance, BookingR.id.pf_tp_flight_seat)) {
                val label = root.findViewById<TextView>(id)
                assertThat(label.isShown).isTrue()
                assertThat(ColorUtils.calculateContrast(label.currentTextColor,
                    if (id == BookingR.id.pf_tp_date_title) dateBackdrop else backdrop)).isAtLeast(4.5)
            }
        }
        writeNativePreview(output, "preparation", night)
        clickPreparation(BookingR.id.pf_tp_add_segment)
        awaitPreparationStage(PreparationStage.DESTINATION)
        assertThat(preparationState().origin?.code).isEqualTo("PVG")
        clickPreparation(BookingR.id.pf_tp_floating_destination)
        awaitAirportSearch(RouteArgs.MODE_PICK_DESTINATION)
        pickAirport(previewAirports.single { it.iataCode == "CAN" })
        awaitPreparationStage(PreparationStage.DESTINATION)
        saveThroughSourceEditor()
        assertThat(preparationState().dailyPlan!!.legs.size).isEqualTo(2)
        writeNativePreview(output, "preparation-multileg", night)
        onActivity { activity ->
            val list = currentPage(activity).requireView().findViewById<RecyclerView>(BookingR.id.pf_tp_segment_list)
            assertThat(checkNotNull(list.findViewHolderForAdapterPosition(1)).itemView.performLongClick()).isTrue()
        }
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        onActivity {
            val popup = checkNotNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).latestPopupWindow)
            assertThat(popup.isShowing).isTrue()
            val content = popup.contentView
            assertThat(content.width).isGreaterThan(0)
            assertThat(content.height).isGreaterThan(0)
            assertThat(content.findViewById<View>(BookingR.id.pf_tp_segment_menu_edit).isEnabled).isTrue()
            assertThat(content.findViewById<View>(BookingR.id.pf_tp_segment_menu_delete).isEnabled).isTrue()
            val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
            content.draw(Canvas(bitmap))
            File(output, "preparation-segment-menu-${if (night) "night" else "day"}.png").outputStream().use {
                assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue()
            }
            bitmap.recycle()
            popup.dismiss()
        }
        clickPreparation(R.id.btnPreparationStart)
        await("preview flight reached the actual Home HUD") {
            currentPage() is HomeFragment && sessions.state.value is SessionState.Running
        }
        writeNativePreview(output, "home-hud", night)
        File(output, "README.txt").writeText(
            "Native Robolectric SDK 35 renders of the production MainActivity/NavHost screens.\n" +
                "Viewport: 390 x 844 dp, mdpi, zh-rCN, day/night resource configurations; system bars excluded.\n" +
                "The actual Activity decor/window background is drawn under the Fragment viewport.\n" +
                "Home uses the source full-window composition; no custom card entrance is applied.\n" +
                "Every output pixel must be opaque; no arbitrary backdrop or image retouching is used.\n" +
                "Preview route: PEK -> PVG from the packaged airport CSV; functional 100km fixtures are separate.\n" +
                "airport-results is an origin picker with no reference (--); airport-destinations opens from the real unsaved PEK/PVG editor and uses its PEK reference.\n" +
                "Preparation step1/scene/clock are actual production stages; preview does not write the confirmed store; the compact Save persists before Expanded/footer and real takeoff.\n" +
                "Scene popups draw actual independent native window surfaces at their runtime placement.\n" +
                "These are implementation previews for visual QA, not original APK screenshots.\n" +
                "Pixel/size checks do not establish screenshot similarity, animation, or device acceptance.\n",
        )
    }

    private fun writeNativePreview(output: File, screen: String, night: Boolean) {
        settleNativeEntrance()
        onActivity { activity ->
            val target = currentPage(activity).requireView()
            val configuredNight = target.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            assertThat(configuredNight).isEqualTo(
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            )
            val density = target.resources.displayMetrics.density
            if (screen == "home-ground") {
                val background = target.findViewById<View>(HomeR.id.pf_map_status_panel).background as android.graphics.drawable.ColorDrawable
                val menu = target.findViewById<TextView>(R.id.menuRecord)
                assertThat(ColorUtils.calculateContrast(menu.currentTextColor, background.color)).isAtLeast(4.5)
            }
            val width = (390 * density).toInt()
            val height = (844 * density).toInt()
            repeat(2) {
                target.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                target.layout(0, 0, width, height)
                idle()
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Home's transparent root relies on this real window background on device.
            // Drawing the Fragment alone into an empty ARGB bitmap loses that layer.
            val background = checkNotNull(activity.window.decorView.background) {
                "The actual Activity window background must be available for the preview"
            }
            val previousBounds = Rect(background.bounds)
            try {
                background.setBounds(0, 0, width, height)
                background.draw(canvas)
            } finally {
                background.bounds = previousBounds
            }
            target.draw(canvas)
            if (screen == "preparation") {
                val start = target.findViewById<TextView>(R.id.btnPreparationStart)
                val bounds = Rect(0, 0, start.width, start.height)
                (target as ViewGroup).offsetDescendantRectToMyCoords(start, bounds)
                var labelPixels = 0
                for (y in bounds.top + 8 until bounds.bottom - 8) {
                    for (x in bounds.centerX() - 60 until bounds.centerX() + 60) {
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.red(pixel) > 220 && Color.green(pixel) > 220 && Color.blue(pixel) > 220) labelPixels++
                    }
                }
                val diagnostic = "text=${start.text}; color=${start.currentTextColor}; alpha=${start.alpha}; " +
                    "enabled=${start.isEnabled}; height=${start.height}; baseline=${start.baseline}; visibleLabelPixels=$labelPixels"
                File(output, "preparation-action-${if (night) "night" else "day"}.txt").writeText(diagnostic)
                assertThat(start.text.toString()).isEqualTo(activity.getString(R.string.flight_preparation_start))
                org.junit.Assert.assertTrue(diagnostic, labelPixels > 10)
            }
            if (screen == "home-ground") {
                val start = target.findViewById<TextView>(R.id.btnStartFlight)
                val bounds = Rect(0, 0, start.width, start.height)
                (target as ViewGroup).offsetDescendantRectToMyCoords(start, bounds)
                var labelPixels = 0
                for (y in bounds.top + 8 until bounds.bottom - 8) {
                    for (x in bounds.centerX() - 60 until bounds.centerX() + 60) {
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.red(pixel) > 220 && Color.green(pixel) > 220 && Color.blue(pixel) > 220) labelPixels++
                    }
                }
                val diagnostic = "text=${start.text}; color=${start.currentTextColor}; alpha=${start.alpha}; " +
                    "enabled=${start.isEnabled}; height=${start.height}; baseline=${start.baseline}; visibleLabelPixels=$labelPixels"
                File(output, "home-action-${if (night) "night" else "day"}.txt").writeText(diagnostic)
                assertThat(start.text.toString()).isNotEmpty()
                org.junit.Assert.assertTrue(diagnostic, labelPixels > 10)
            }
            assertThat(bitmap.width).isEqualTo(width)
            assertThat(bitmap.height).isEqualTo(height)
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            assertThat(pixels.count { Color.alpha(it) != 255 }).isEqualTo(0)
            val backdrop = bitmap.getPixel(0, 0)
            var changed = 0
            for (y in 0 until height step 3) for (x in 0 until width step 3) {
                if (bitmap.getPixel(x, y) != backdrop) changed++
            }
            assertThat(changed).isGreaterThan(50)
            File(output, "$screen-${if (night) "night" else "day"}.png").outputStream().use {
                assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue()
            }
            bitmap.recycle()
        }
    }

    private fun settleNativeEntrance() {
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        idle()
    }

    private fun openPreparation(withRoute: Boolean) {
        onActivity { activity ->
            if (withRoute) {
                nav(activity).navigate(R.id.action_home_to_flight_preparation, Bundle().apply {
                    putString(FlightPreparationFragment.ARG_ORIGIN, DEPARTURE.icaoCode)
                    putString(FlightPreparationFragment.ARG_DESTINATION, ARRIVAL.icaoCode)
                })
            } else {
                nav(activity).navigate(R.id.action_home_to_flight_preparation)
            }
        }
        await("preparation host is attached") { currentPage() is FlightPreparationFragment }
    }

    private fun awaitPreparationStage(stage: PreparationStage) {
        await("actual preparation reaches $stage") {
            currentPage() is FlightPreparationFragment && preparationState().stage == stage &&
                !preparationState().loading && !preparationState().busy
        }
    }

    private fun persistedPlan(): FlightLegPlan? {
        var value: String? = null
        onActivity { activity ->
            value = activity.getSharedPreferences(SharedPreferencesConfirmedFlightPlanStore.FILE_NAME,
                android.content.Context.MODE_PRIVATE).getString(SharedPreferencesConfirmedFlightPlanStore.PLAN_KEY, null)
        }
        return value?.let(FlightPlanJson::decode)
    }

    private fun previewThroughSourceEditor() {
        val before = persistedPlan()
        awaitPreparationStage(PreparationStage.DESTINATION)
        clickPreparation(BookingR.id.pf_tp_floating_save)
        awaitPreparationStage(PreparationStage.SCENE)
        assertThat(preparationState().confirmed).isFalse()
        assertThat(persistedPlan()).isEqualTo(before)
        val emptyCatalog = activityValue { SharedPreferencesSceneCatalogStore.from(it)
            .readEnabled(AppSceneCatalog.SCOPE).options.isEmpty() }
        if (emptyCatalog) assertThat(preparationState().scene).isNull()
        clickPreparation(BookingR.id.pf_tp_floating_save)
        awaitPreparationStage(PreparationStage.DEPARTURE_TIME)
        previewCurrentClock()
        assertThat(preparationState().confirmed).isFalse()
        assertThat(persistedPlan()).isEqualTo(before)
        assertThat(preparationControl(R.id.btnPreparationStart).visibility).isEqualTo(View.GONE)
    }

    private fun previewCurrentClock() {
        awaitPreparationStage(PreparationStage.DEPARTURE_TIME)
        // A real wall-clock minute may change while native rendering is running.
        // Repair through the actual wheels, never a ViewModel or setter shortcut.
        val now = ZonedDateTime.now()
        val clock = checkNotNull(preparationState().departureClock)
        if (preparationState().previousArrivalText == null && clock.date == now.toLocalDate().toString() && clock.minuteOfDay < now.hour * 60 + now.minute) {
            moveClockThroughWheels(now.hour, now.minute)
        }
        // Source Q wraps HH:mm without carrying a date, while J compares minutes
        // linearly. Near midnight, previous arrival+5 may be 00:00 although the
        // actual minimum (+2) is 23:57. Select that real allowed boundary through
        // the actual wheels; never change the source guard or force the ViewModel.
        val current = preparationState()
        if (!current.timeCanPreview && current.previousArrivalText != null) {
            val minimum = checkNotNull(com.projectfocus.core.common.plan.SourceTakeoffClock.parseMinutes(current.earliestDeparture))
            moveClockThroughWheels(minimum / 60, minimum % 60)
        }
        assertThat(preparationState().timeCanPreview).isTrue()
        clickPreparation(BookingR.id.pf_tp_floating_time_save)
        awaitPreparationStage(PreparationStage.PREVIEW)
    }

    private fun confirmPreviewThroughSourceControl(): FlightLegPlan {
        awaitPreparationStage(PreparationStage.PREVIEW)
        assertThat(preparationView(BookingR.id.pf_tp_set_destination_action).text.toString()).isEqualTo(
            activityValue { it.getString(BookingR.string.pf_tp_common_save) },
        )
        clickPreparation(BookingR.id.pf_tp_set_destination_action)
        awaitPreparationStage(PreparationStage.EXPANDED)
        assertThat(preparationState().confirmed).isTrue()
        val plan = checkNotNull(preparationState().plan)
        assertThat(persistedPlan()).isEqualTo(plan)
        assertThat(preparationControl(BookingR.id.pf_tp_flight_seat).isShown).isTrue()
        return plan
    }

    private fun saveThroughSourceEditor(): FlightLegPlan {
        previewThroughSourceEditor()
        return confirmPreviewThroughSourceControl()
    }

    private fun beginEditingThroughSourceControls() {
        awaitPreparationStage(PreparationStage.EXPANDED)
        clickPreparation(BookingR.id.pf_tp_header)
        awaitPreparationStage(PreparationStage.COLLAPSED)
        clickPreparation(BookingR.id.pf_tp_set_destination_route)
        awaitPreparationStage(PreparationStage.DESTINATION)
        assertThat(preparationState().editing).isTrue()
    }

    private fun moveClockThroughWheels(hour: Int, minute: Int) {
        require(hour in 0..23 && minute in 0..59)
        for ((id, target) in listOf(BookingR.id.pf_tp_floating_time_hour to hour,
            BookingR.id.pf_tp_floating_time_minute to minute)) {
            var current = activityValue { currentPage(it).requireView().findViewById<SourceStringWheelPicker>(id).getSelectedIndex() }
            while (current != target) {
                val direction = if (target > current) 1 else -1
                val expected = current + direction
                touchClockOneRow(id, direction)
                await("actual source wheel reaches $expected") {
                    val clock = preparationState().departureClock
                    if (id == BookingR.id.pf_tp_floating_time_hour) clock?.hour == expected else clock?.minute == expected
                }
                current = expected
            }
        }
    }

    private fun touchClockOneRow(id: Int, direction: Int) {
        onActivity { activity ->
            measureSeatWindow(activity)
            val root = currentPage(activity).requireView() as ViewGroup
            val wheel = root.findViewById<SourceStringWheelPicker>(id)
            assertThat(wheel.isShown).isTrue()
            assertThat(wheel.isEnabled).isTrue()
            val bounds = Rect(0, 0, wheel.width, wheel.height)
            root.offsetDescendantRectToMyCoords(wheel, bounds)
            assertThat(bounds.left).isAtLeast(0)
            assertThat(bounds.right).isAtMost(root.width)
            assertThat(bounds.top).isAtLeast(0)
            assertThat(bounds.bottom).isAtMost(root.height)
            val delta = -(36 * wheel.resources.displayMetrics.density + .5f).toInt() * direction
            val start = SystemClock.uptimeMillis()
            listOf(Triple(android.view.MotionEvent.ACTION_DOWN, 0L, bounds.exactCenterY()),
                Triple(android.view.MotionEvent.ACTION_MOVE, 400L, bounds.exactCenterY() + delta),
                Triple(android.view.MotionEvent.ACTION_UP, 800L, bounds.exactCenterY() + delta)).forEach { (action, elapsed, y) ->
                val event = android.view.MotionEvent.obtain(start, start + elapsed, action, bounds.exactCenterX(), y, 0)
                try { assertThat(root.dispatchTouchEvent(event)).isTrue() } finally { event.recycle() }
            }
        }
        idle()
    }

    private fun touchDisabledPreparationControl(id: Int) {
        onActivity { activity ->
            measureSeatWindow(activity)
            val root = currentPage(activity).requireView() as ViewGroup
            val button = root.findViewById<View>(id)
            assertThat(button.isShown).isTrue()
            assertThat(button.isEnabled).isFalse()
            val bounds = Rect(0, 0, button.width, button.height)
            root.offsetDescendantRectToMyCoords(button, bounds)
            val start = SystemClock.uptimeMillis()
            listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event = android.view.MotionEvent.obtain(start, start + index * 20L, action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                try { root.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        idle()
    }

    private fun choosePreparationScene(optionId: String) {
        onActivity {
            val button = sceneTagButtons(currentScenePicker()).single { it.tag == optionId }
            assertThat(button.performClick()).isTrue()
        }
        idle()
        assertThat((ShadowDialog.getLatestDialog() as AnchoredScenePickerDialog).isShowing).isFalse()
    }

    private fun awaitAirportSearch(mode: String) {
        await("airport search host and real child are ready") {
            activityValue { activity ->
                val page = currentPage(activity)
                page is AirportSearchHostFragment &&
                    page.arguments?.getString(RouteArgs.ARG_MODE) == mode &&
                    page.childFragmentManager.fragments.any { it is AirportSearchFragment && it.view != null }
            }
        }
    }

    private fun pickAirport(airport: AirportEntity) {
        var reference: AirportReferencePoint? = null
        onActivity { activity -> reference = AirportSearchReferenceArgs.readFrom(currentPage(activity).arguments) }
        val minutes = reference?.let { FlightMath.minutesForDistanceKm(FlightMath.airportDistanceKm(
            it.latitude, it.longitude, airport.latitude, airport.longitude,
        ).toDouble()) }
        val expected = when {
            minutes == null -> "--"
            minutes < 60 -> "${minutes}m"
            else -> "${minutes / 60}h${minutes % 60}m"
        }
        pickAirportThroughRenderedRow(airport, expected)
    }

    private fun assertPickerReference(expected: AirportReferencePoint?) {
        onActivity { activity ->
            val host = currentPage(activity) as AirportSearchHostFragment
            val search = host.childFragmentManager.fragments.filterIsInstance<AirportSearchFragment>().single()
            for (args in listOf(host.arguments, search.arguments)) {
                assertThat(AirportSearchReferenceArgs.readFrom(args)).isEqualTo(expected)
                if (expected == null) {
                    assertThat(args!!.containsKey(RouteArgs.ARG_REFERENCE_LATITUDE)).isFalse()
                    assertThat(args.containsKey(RouteArgs.ARG_REFERENCE_LONGITUDE)).isFalse()
                } else {
                    assertThat(args!!.getDouble(RouteArgs.ARG_REFERENCE_LATITUDE, Double.NaN)).isEqualTo(expected.latitude)
                    assertThat(args.getDouble(RouteArgs.ARG_REFERENCE_LONGITUDE, Double.NaN)).isEqualTo(expected.longitude)
                }
            }
        }
    }

    private fun pickAirportThroughRenderedRow(airport: AirportEntity, expectedDuration: String) {
        onActivity { activity ->
            val host = currentPage(activity) as AirportSearchHostFragment
            host.childFragmentManager.fragments.filterIsInstance<AirportSearchFragment>().single()
                .requireView().findViewById<EditText>(AirportR.id.searchInput).setText(airport.icaoCode)
        }
        try { await("actual search row shows $expectedDuration for ${airport.icaoCode}") {
            // Advance the real UI debounce; this does not bypass the production query/adapter.
            shadowOf(Looper.getMainLooper()).idleFor(24, TimeUnit.MILLISECONDS)
            renderedAirportRow(airport)?.findViewById<TextView>(AirportR.id.pf_airport_tv_airport_city)
                ?.text?.toString() == expectedDuration
        } } catch (error: AssertionError) {
            val diagnostics = activityValue { activity ->
                val host = currentPage(activity) as AirportSearchHostFragment
                val search = host.childFragmentManager.fragments.filterIsInstance<AirportSearchFragment>().single()
                val root = search.requireView()
                val list = root.findViewById<RecyclerView>(AirportR.id.resultList)
                val rows = (list.adapter as AirportSearchAdapter).currentList
                "hostRef=${AirportSearchReferenceArgs.readFrom(host.arguments)}, childRef=${AirportSearchReferenceArgs.readFrom(search.arguments)}, " +
                    "life=${search.lifecycle.currentState}/${search.viewLifecycleOwner.lifecycle.currentState}, " +
                    "input=${root.findViewById<EditText>(AirportR.id.searchInput).text}, " +
                    "state=${ViewModelProvider(search)[AirportSearchViewModel::class.java].state.value}, rows=$rows, " +
                    "visible=" + (0 until list.childCount).map { index ->
                        list.getChildAt(index).let { row -> row.findViewById<TextView>(AirportR.id.pf_airport_airport_code)?.text.toString() + ":" +
                            row.findViewById<TextView>(AirportR.id.pf_airport_tv_airport_city)?.text }
                    }
            }
            val db = runBlocking(Dispatchers.IO) {
                "airport=${airports.findByCode(airport.icaoCode)}, count=${airports.count(true)}, " +
                    "matches=${airports.search(airport.icaoCode, 26, false)}"
            }
            throw AssertionError(error.message + "\n" + diagnostics + "\n" + db, error)
        }
        onActivity {
            val row = checkNotNull(renderedAirportRow(airport))
            assertThat(row.performClick()).isTrue()
        }
        idle()
    }

    private fun renderedAirportRow(airport: AirportEntity): View? {
        var row: View? = null
        onActivity { activity ->
            val host = currentPage(activity) as AirportSearchHostFragment
            val search = host.childFragmentManager.fragments.filterIsInstance<AirportSearchFragment>().single()
            val list = search.requireView().findViewById<RecyclerView>(AirportR.id.resultList)
            list.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 320, 500)
            row = (0 until list.childCount).map(list::getChildAt).firstOrNull {
                it.findViewById<TextView>(AirportR.id.pf_airport_airport_code)?.text?.toString() == airport.iataCode.ifBlank { airport.icaoCode }
            }
        }
        return row
    }

    private fun assertVisibleAirportDurations(reference: AirportReferencePoint?) {
        onActivity { activity ->
            val list = currentPage(activity).requireView().findViewById<RecyclerView>(AirportR.id.resultList)
            val rows = (list.adapter as AirportSearchAdapter).currentList
            assertThat(rows.filterIsInstance<AirportSearchAdapter.Row.AirportRow>()).isNotEmpty()
            var checked = 0
            for (index in 0 until list.childCount) {
                val view = list.getChildAt(index)
                val row = rows.getOrNull(list.getChildAdapterPosition(view)) as? AirportSearchAdapter.Row.AirportRow ?: continue
                assertThat(view.findViewById<TextView>(AirportR.id.pf_airport_airport_code).text.toString())
                    .isEqualTo(row.hit.airport.displayCode)
                val expectedMinutes = reference?.let {
                    FlightMath.minutesForDistanceKm(FlightMath.airportDistanceKm(
                        it.latitude, it.longitude, row.hit.airport.latitude, row.hit.airport.longitude,
                    ).toDouble())
                }
                assertThat(row.flightMinutes).isEqualTo(expectedMinutes)
                val expected = when {
                    expectedMinutes == null -> "--"
                    expectedMinutes < 60 -> "${expectedMinutes}m"
                    else -> "${expectedMinutes / 60}h${expectedMinutes % 60}m"
                }
                assertThat(view.findViewById<TextView>(AirportR.id.pf_airport_tv_airport_city).text.toString()).isEqualTo(expected)
                checked++
            }
            assertThat(checked).isGreaterThan(0)
        }
    }

    private fun assertAirportResultConsumed() {
        onActivity { activity ->
            val result = nav(activity).currentBackStackEntry!!.savedStateHandle
            assertThat(result.contains(AirportSearchHostFragment.KEY_PICKED)).isFalse()
            assertThat(result.contains(AirportSearchHostFragment.KEY_AIRPORT_CODE)).isFalse()
            assertThat(result.contains(AirportSearchHostFragment.KEY_PICKED_ORIGIN)).isFalse()
        }
    }

    private fun deliverSeat(selection: SeatSelectionResult) {
        onActivity { activity ->
            currentPage(activity).parentFragmentManager.setFragmentResult(
                SeatDraftRequests.SELECTION,
                Bundle().apply { putSerializable(SeatDraftRequests.RESULT_DATA, selection) },
            )
        }
        idle()
    }

    private fun clickPreparation(id: Int) {
        onActivity { activity ->
            val control = currentPage(activity).requireView().findViewById<View>(id)
            assertThat(control.isShown).isTrue()
            assertThat(control.isEnabled).isTrue()
            assertThat(control.performClick()).isTrue()
        }
        idle()
    }

    private fun preparationView(id: Int): TextView = activityValue {
        currentPage(it).requireView().findViewById(id)
    }

    private fun preparationControl(id: Int): View = activityValue {
        currentPage(it).requireView().findViewById(id)
    }

    private fun preparationState(): PreparationUiState = activityValue {
        preparationViewModel(currentPage(it)).state.value
    }

    private fun preparationViewModel(page: Fragment): FlightPreparationViewModel =
        ViewModelProvider(page)[FlightPreparationViewModel::class.java]

    private fun navDestination(): Int = activityValue { nav(it).currentDestination!!.id }

    private fun currentPage(): Fragment = activityValue(::currentPage)

    private fun currentPage(activity: MainActivity): Fragment {
        activity.supportFragmentManager.executePendingTransactions()
        val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        host.childFragmentManager.executePendingTransactions()
        return checkNotNull(host.childFragmentManager.primaryNavigationFragment)
    }

    private fun nav(activity: MainActivity): NavController =
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment).navController

    private fun onActivity(action: (MainActivity) -> Unit) {
        scenario!!.onActivity { action(it) }
    }

    private fun <T : Any> activityValue(read: (MainActivity) -> T): T {
        var value: T? = null
        onActivity { value = read(it) }
        return checkNotNull(value)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Timed out: $message")
    }

    private fun AirportEntity.asAirport(): Airport = Airport(
        id, iataCode, icaoCode, name, city, country, latitude, longitude, elevation,
        continent, AirportType.fromRaw(type), popularity, isPremiumOnly, nameZh, cityZh,
    )

    companion object {
        private const val SEAT_SCENE_A = "SeatNav Alpha"
        private const val SEAT_SCENE_B = "SeatNav Beta"
        private val SEAT_SCENE_A_COLOR = 0xFFB33A4A.toInt()
        private val SEAT_SCENE_B_COLOR = 0xFF2277AA.toInt()
        private val DEPARTURE = airport(9_157_001L, "R157A", 0.0)
        private val ARRIVAL = airport(9_157_002L, "R157B", Math.toDegrees(100.0 / FlightMath.EARTH_RADIUS_KM))
        private val OTHER_ARRIVAL = airport(9_157_003L, "R157C", Math.toDegrees(200.0 / FlightMath.EARTH_RADIUS_KM))

        private fun airport(id: Long, code: String, longitude: Double) = AirportEntity(
            id = id, iataCode = "", icaoCode = code, name = "Integration $code", city = "City $code",
            country = "Test", latitude = 0.0, longitude = longitude, elevation = 0, continent = "AS",
            type = "large_airport", popularity = 0, isPremiumOnly = false,
        )
    }
}
