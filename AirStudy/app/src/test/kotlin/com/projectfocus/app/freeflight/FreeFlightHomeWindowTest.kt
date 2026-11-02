package com.projectfocus.app.freeflight

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.app.MainActivity
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.booking.FreeFlightHostOwnerFactory
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.NearbyAirportDto
import com.projectfocus.core.network.freeflight.HttpAirportLocationGateway
import com.projectfocus.feature.flight.hud.FlightHudView
import com.projectfocus.feature.flight.hud.SourceNumberScrollView
import com.projectfocus.service.focus.freeflight.*
import com.projectfocus.service.focus.freeflight.runtime.*
import com.projectfocus.service.focus.session.SessionState
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.robolectric.shadows.ShadowDialog
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import com.projectfocus.feature.flight.R as FlightR
import com.projectfocus.feature.completion.R as CompletionR


@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [34], qualifiers = "zh-rCN-w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FreeFlightHomeWindowTest {
    private lateinit var app: ProjectFocusApp
    private lateinit var graph: FreeFlightRuntimeEntryPoint
    private lateinit var owner: FreeFlightOwnerToken
    private lateinit var store: FreeFlightArchiveStore
    private lateinit var activity: MainActivity
    private lateinit var activityController: ActivityController<MainActivity>
    private var serviceController: ServiceController<InjectedFreeFlightRuntimeService>? = null
    private val id = "actual-freeflight-home-restoration-fixture"

    @Before fun open() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) { withTimeout(15_000) { app.awaitInitialAccountRestore(); app.awaitInitialAirportSeed() } }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        graph = EntryPointAccessors.fromApplication(app, FreeFlightRuntimeEntryPoint::class.java)
        store = ReflectionHelpers.getField(graph.repository(), "store")
        runBlocking(Dispatchers.IO) {
            TokenStore.setCancellable("actual-FF-Home-restoration-fixture-account")
            owner = FreeFlightHostOwnerFactory(app, graph.owners()).open()
            val settings: SettingsRepository = ReflectionHelpers.getField(graph.operations(), "settings")
            settings.write(PrefKey.BlacklistEnabled, false)
            val airport = requireNotNull(app.database.airportDao().findByCode("PEK"))
            val departure = PlannedAirport(airport.id, airport.iataCode, airport.name, airport.city, airport.country, airport.latitude, airport.longitude)
            val time: AndroidFreeFlightTimeSource = ReflectionHelpers.getField(graph.repository(), "time")
            val now = time.read()
            val engine = FreeFlightEngine()
            val start = engine.start(FreeFlightStartRequest(id, owner.ownerScope, departure, 90.0, "1A", FreeFlightScene(1, "Reading", "study")), now)
            val archive = engine.sample(start, now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 123_000L, uptimeMillis = now.uptimeMillis + 123_000L, wallMs = now.wallMs + 123_000L))
            assertTrue(store.commit(null, archive, null))
            assertTrue(graph.repository().restoreActive(owner) is FreeFlightCommandResult.Committed)
        }
        var dispatched: Intent? = null
        repeat(12) {
            if (dispatched == null) shadowOf(app).nextStartedService?.takeIf { it.component?.className == InjectedFreeFlightRuntimeService::class.java.name }?.let { dispatched = it }
        }
        val actual = Robolectric.buildService(InjectedFreeFlightRuntimeService::class.java).create().also { serviceController = it }.get()
        actual.onStartCommand(requireNotNull(dispatched), 0, 1)
        await("Actual free-flight foreground and driver") { graph.registry().target.value?.key?.let { graph.registry().isRunning(it) } == true }
        activityController = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        activity = activityController.get()
        activity.supportFragmentManager.executePendingTransactions()
        val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        host.navController.navigate(R.id.action_permission_to_home); host.childFragmentManager.executePendingTransactions()
        await("Actual restored free-flight HUD") { activity.findViewById<FlightHudView>(R.id.flightHudOverlay)?.visibility == View.VISIBLE }
    }

    @After fun close() {
        try {
            if (::activityController.isInitialized) activityController.pause().stop().destroy()
            TokenStore.clear()
            serviceController?.let { controller ->
                val scope: CoroutineScope = ReflectionHelpers.getField(controller.get(), "serviceScope")
                controller.destroy()
                runBlocking(Dispatchers.IO) { withTimeout(10_000) { checkNotNull(scope.coroutineContext[Job]).join() } }
            }
        } finally { shutdownRealApplicationAfterTest(app) }
    }
    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(32, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("$description; ${graph.registry().observation.value}; ${graph.repository().state.value}")
    }

    @Test fun actualElapsedCountersAndPauseResumeUseTheIndependentArchiveWithoutStartingCoreCountdown() {
        assertEquals(27, activity.findViewById<SourceNumberScrollView>(FlightR.id.pf_flight_distance_value).getValue())
        assertEquals(2, activity.findViewById<SourceNumberScrollView>(FlightR.id.pf_flight_time_value).getValue())
        assertEquals(activity.getString(FlightR.string.pf_hud_source_time_elapsed), activity.findViewById<TextView>(FlightR.id.pf_flight_time_label).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.groundHomeContent).visibility)
        assertTrue(activity.sessionRepository.state.value is SessionState.Idle)
        assertEquals(FreeFlightPause.RECOVERY_REQUIRES_RESUME, graph.repository().state.value?.clock?.pause)
        activity.findViewById<View>(FlightR.id.pf_flight_pause).performClick()
        await("Actual archive resumed") { graph.repository().state.value?.clock?.pause == null }
        assertTrue(activity.sessionRepository.state.value is SessionState.Idle)
        await("Actual HUD reflects resumed state") { activity.findViewById<View>(FlightR.id.pf_flight_pause_overlay).visibility == View.GONE }
        capture("freeflight-actual-home-running")
        activity.findViewById<View>(FlightR.id.pf_flight_pause).performClick()
        await("Actual archive paused") { graph.repository().state.value?.clock?.pause == FreeFlightPause.USER }
        assertTrue(activity.sessionRepository.state.value is SessionState.Idle)
        runBlocking(Dispatchers.IO) { assertEquals(id, store.active()?.sessionId); assertNull(store.active()?.landingAirport) }
        capture("freeflight-actual-home-paused")
    }

    @Test fun accountReplacementRemovesTheOldHudAndActualRuntimeCannotKeepPaintingItsArchive() {
        val previous = graph.repository().state.value
        assertNotNull(previous)
        runBlocking(Dispatchers.IO) { TokenStore.setCancellable("another-actual-freeflight-home-account") }
        await("Old account HUD hidden") { activity.findViewById<FlightHudView>(R.id.flightHudOverlay).visibility == View.GONE }
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.groundHomeContent).visibility)
        runBlocking(Dispatchers.IO) {
            assertEquals(FreeFlightStatus.ONGOING, store.load(id, owner.ownerScope)?.status)
            assertNull(store.load(id, "another-actual-freeflight-home-account"))
        }
        assertTrue(activity.sessionRepository.state.value is SessionState.Idle)
    }

    /** Actual Home/Room/service/controller/HTTP. Only the catalogue response and SDK measurement
     * are named test fixtures. Missing configuration genuinely selects the source no-map branch. */
    @Test fun actualLandingRangeDebounceSelectionAndNoMapResultStayInTheSameIndependentArchive() {
        MockWebServer().use { server ->
            server.start();val http=OkHttpClient()
            try {
                bindLandingTransport(HttpAirportLocationGateway(http,server.url("/").toString()))
                server.enqueue(MockResponse.Builder().body(nearbyReply()).headersDelay(1,TimeUnit.SECONDS).build())
                resumeAndOpenLanding()
                await("Actual landing panel") { activity.findViewById<FreeFlightLandingSurface>(R.id.pf_ff_land_surface)!=null }
                await("First actual range HTTP dispatched after the300ms debounce") { server.requestCount==1 }
                assertNotNull(server.takeRequest(1,TimeUnit.SECONDS))
                val panel=activity.findViewById<FreeFlightLandingSurface>(R.id.pf_ff_land_surface)
                assertFalse(panel.confirm.isEnabled)
                server.enqueue(MockResponse.Builder().body(nearbyReply()).build())
                panel.ruler.onTimeChanged?.invoke(25);panel.ruler.onTimeChanged?.invoke(30);panel.ruler.onTimeChanged?.invoke(35)
                await("Latest rapid range persisted and actual second response shown") {
                    graph.repository().state.value?.landingRangeMinutes==35 && panel.cards.adapter?.itemCount==1
                }
                val selectedPhase=checkNotNull(graph.repository().state.value)
                assertEquals(id,selectedPhase.sessionId);assertEquals(FreeFlightPause.LANDING_SELECTION,selectedPhase.clock.pause)
                assertNull(selectedPhase.landingAirport);assertFalse(panel.confirm.isEnabled)
                capture("freeflight-actual-landing-unselected")
                await("Actual card laid out") { panel.cards.findViewHolderForAdapterPosition(0)!=null }
                panel.cards.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                await("User choice durably bound") { graph.repository().state.value?.landingAirport?.code=="TSN" && panel.confirm.isEnabled }
                capture("freeflight-actual-landing-selected")
                panel.confirm.performClick()
                await("Actual posted no-map arrival committed") { graph.repository().state.value?.status==FreeFlightStatus.COMPLETED }
                val finished=checkNotNull(graph.repository().state.value)
                assertEquals(id,finished.sessionId);assertEquals(FreeFlightPhase.NO_MAP_ARRIVAL,finished.phase)
                assertEquals("TSN",finished.landingAirport?.code)
                assertTrue(activity.sessionRepository.state.value is SessionState.Idle)
                runBlocking(Dispatchers.IO) {
                    assertNull(store.active());assertEquals(finished,store.load(id,owner.ownerScope))
                    assertEquals(id,store.terminal(owner.ownerScope).first().single().sessionId)
                }
                assertEquals(2,server.requestCount)
                await("Actual completion overlay shown after the committed arrival") {
                    activity.findViewById<View>(CompletionR.id.flight_completion_overlay)?.visibility==View.VISIBLE
                }
                assertEquals("TSN",activity.findViewById<TextView>(CompletionR.id.arrival_airport_code_on_media).text.toString())
                assertEquals("",activity.findViewById<TextView>(CompletionR.id.welcome_subtitle).text.toString())
                assertEquals(View.GONE,activity.findViewById<View>(CompletionR.id.stats_container).visibility)
                assertFalse(activity.findViewById<View>(R.id.btnStartFlight).isEnabled)
                val preferences=app.getSharedPreferences(com.projectfocus.app.home.HomeMapStyleSelection.FILE_NAME,android.content.Context.MODE_PRIVATE)
                assertEquals(finished.landingAirport,com.projectfocus.app.home.HomeOriginSelection.selectedSnapshot(preferences,"TSN"))
                activity.findViewById<View>(CompletionR.id.complete_flight_button).performClick()
                await("Missing approval receipt keeps the completed result available for retry") {
                    activity.findViewById<View>(CompletionR.id.complete_flight_button).isEnabled
                }
                assertEquals(View.VISIBLE,activity.findViewById<View>(CompletionR.id.flight_completion_overlay).visibility)
                capture("freeflight-actual-completed-result")
                runBlocking(Dispatchers.IO) { TokenStore.setCancellable("replacement-after-arrival") }
                await("Account replacement removes the old completion overlay") {
                    activity.findViewById<View>(CompletionR.id.flight_completion_overlay)==null &&
                        activity.findViewById<View>(R.id.btnStartFlight).isEnabled
                }
            } finally { http.dispatcher.cancelAll();http.connectionPool.evictAll();http.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun failedAirportLookupCannotConfirmAndReturningToCruisePreservesTheActualFlight() {
        MockWebServer().use { server ->
            server.start();val http=OkHttpClient()
            try {
                bindLandingTransport(HttpAirportLocationGateway(http,server.url("/").toString()))
                server.enqueue(MockResponse.Builder().code(503).body("owned-test-service-unavailable").build())
                resumeAndOpenLanding()
                await("Lookup failure remains unavailable") { activity.findViewById<TextView>(R.id.pf_ff_land_query_status)?.text?.toString()==activity.getString(R.string.pf_ff_land_retry) }
                val paused=checkNotNull(graph.repository().state.value)
                assertEquals(FreeFlightPhase.LANDING_SELECTION,paused.phase);assertNull(paused.landingAirport)
                assertFalse(activity.findViewById<FreeFlightLandingSurface>(R.id.pf_ff_land_surface).confirm.isEnabled)
                activity.findViewById<View>(FlightR.id.pf_flight_land).performClick()
                await("Actual return callback resumed the original cruise") {
                    graph.repository().state.value?.let { it.phase==FreeFlightPhase.CRUISING && !it.cruiseReturnPending && it.clock.pause==null }==true
                }
                val resumed=checkNotNull(graph.repository().state.value)
                assertEquals(id,resumed.sessionId);assertEquals(FreeFlightStatus.ONGOING,resumed.status);assertNull(resumed.clock.pause)
                assertNull(resumed.landingAirport);assertNull(resumed.landingRangeMinutes)
                assertEquals(paused.clock.activeElapsedMs,resumed.clock.activeElapsedMs)
                assertEquals(paused.cumulativeDistanceKm,resumed.cumulativeDistanceKm,0.0)
                assertTrue(activity.sessionRepository.state.value is SessionState.Idle)
                await("Selection surface actually removed") { activity.findViewById<View>(R.id.pf_ff_land_surface)==null }
                assertEquals(1,server.requestCount)
            } finally { http.dispatcher.cancelAll();http.connectionPool.evictAll();http.dispatcher.executorService.shutdownNow() }
        }
    }

    private fun bindLandingTransport(gateway:com.projectfocus.core.network.freeflight.AirportLocationGateway) {
        val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        val home=checkNotNull(host.childFragmentManager.primaryNavigationFragment)
        val actual:FreeFlightLandingController=ReflectionHelpers.getField(home,"freeFlightLanding")
        // Deliberate test-only replacement, never an accepting/no-map production dependency.
        ReflectionHelpers.setField(actual,"candidates",FreeFlightLandingCandidates(gateway,FreeFlightLandingDistance { _,_->77.5 }))
    }
    private fun nearbyReply():String {
        val airport=runBlocking(Dispatchers.IO) { checkNotNull(app.database.airportDao().findByCode("TSN")) }
        val row=NearbyAirportDto(airport.id,airport.iataCode,airport.name,airport.city,airport.country,airport.latitude,airport.longitude)
        return "{\"success\":true,\"data\":["+Json.encodeToString(NearbyAirportDto.serializer(),row)+"]}"
    }
    private fun resumeAndOpenLanding() {
        activity.findViewById<View>(FlightR.id.pf_flight_pause).performClick()
        await("Actual flight resumed before landing") { graph.repository().state.value?.clock?.pause==null && activity.findViewById<View>(FlightR.id.pf_flight_land).visibility==View.VISIBLE }
        activity.findViewById<View>(FlightR.id.pf_flight_land).performClick()
        val confirmation=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        confirmation.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        await("Landing selection persisted") { graph.repository().state.value?.phase==FreeFlightPhase.LANDING_SELECTION }
    }

    private fun capture(name: String) {
        val view = activity.window.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 432, 960)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        val bitmap = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val file = File("build/reports/freeflight-home/$name.png").apply { parentFile.mkdirs() }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
