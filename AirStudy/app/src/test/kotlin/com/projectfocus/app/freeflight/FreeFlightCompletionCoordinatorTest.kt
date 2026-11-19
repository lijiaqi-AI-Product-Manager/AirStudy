package com.projectfocus.app.freeflight

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.booking.FreeFlightHostOwnerFactory
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.app.home.HomeOriginSelection
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.freeflight.*
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeKey
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimePending
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Room, account ownership, result presentation and driver lifetime are real components.
 * The admission decision, clock, absent map and service dispatch are controlled fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [28])
class FreeFlightCompletionCoordinatorTest {
    private lateinit var app: ProjectFocusApp
    private lateinit var graph: FreeFlightRuntimeEntryPoint
    private lateinit var store: FreeFlightArchiveStore
    private lateinit var owner: FreeFlightOwnerToken
    private lateinit var repository: FreeFlightSessionRepository
    private lateinit var completion: FreeFlightCompletionCoordinator
    private var clock = FreeFlightClockReading(0, 0, 1_000_000, "completion-clock")
    private val admission = Admission()
    private val lifetime = SupervisorJob()
    private var key: FreeFlightRuntimeKey? = null
    private val destination = PlannedAirport(43341, "FR-0001", "Emile Muller Hospital Heliport",
        "Mulhouse", "FR", 47.725328, 7.344995)

    @Before fun open() = runBlocking(Dispatchers.IO) {
        app = ApplicationProvider.getApplicationContext()
        withTimeout(15_000) { app.awaitInitialAccountRestore(); app.awaitInitialAirportSeed() }
        graph = EntryPointAccessors.fromApplication(app, FreeFlightRuntimeEntryPoint::class.java)
        store = ReflectionHelpers.getField(graph.repository(), "store")
        TokenStore.setCancellable("completion-component-account")
        owner = FreeFlightHostOwnerFactory(app, graph.owners()).open()
        repository = FreeFlightSessionRepository(store, FreeFlightTimeSource { clock }, admission, graph.owners(),
            FreeFlightLandingDistance { _, _ -> error("No distance is invented for the absent-map branch") },
            FreeFlightRuntimeEffects { throw FreeFlightRuntimePending("Controlled service dispatch") },
            FreeFlightMapPresence { _, _ -> FreeFlightMapAvailability.ABSENT }, app.admissionApplicationScope)
        completion = FreeFlightCompletionCoordinator(app, repository, graph.owners(), graph.registry(), app.admissionApplicationScope,
            com.projectfocus.service.focus.history.FlightHistoryReader(app.database.historyReadDao()))
    }

    @After fun close() {
        lifetime.cancel()
        key?.let { graph.registry().driverDestroyed(it, lifetime, "Test lifetime completed") }
        TokenStore.clear()
        shutdownRealApplicationAfterTest(app)
    }

    private suspend fun arrive(): FreeFlightCompletionNotice {
        val row = checkNotNull(app.database.airportDao().findByCode("PEK"))
        val departure = PlannedAirport(row.id, row.iataCode, row.name, row.city, row.country, row.latitude, row.longitude)
        val request = FreeFlightStartRequest("completion-component-flight", owner.ownerScope, departure, -270.0,
            "01A", FreeFlightScene(2, "Reading", "book"))
        val started = repository.start(owner, request) as FreeFlightCommandResult.Committed
        val target = graph.registry().stage(started.archive, owner, repository, graph.arrival())
        key = target.key
        assertTrue(graph.registry().attachDriver(target.key, lifetime))
        graph.registry().driverReady(target.key)
        clock = clock.copy(elapsedRealtimeMs = 90_001, uptimeMillis = 90_001, wallMs = 1_090_001)
        assertTrue(repository.prepareLanding(owner) is FreeFlightCommandResult.Committed)
        val selection = checkNotNull(repository.state.value)
        assertTrue(repository.selectLanding(owner, request.sessionId, selection.landingSelectionGeneration, destination) is FreeFlightCommandResult.Committed)
        assertTrue(repository.confirmLanding(owner, selection.landingSelectionGeneration) is FreeFlightCommandResult.Committed)
        val callback = checkNotNull(repository.prepareArrivalCallback(owner))
        assertTrue(repository.captureArrivalCallback(callback))
        val ended = repository.completeLandingWithoutMap(owner, callback) as FreeFlightCommandResult.Committed
        graph.registry().stage(ended.archive, owner, repository, graph.arrival())
        return withTimeout(5_000) { completion.notice.first { it?.originSaved == true }!! }
    }

    @Test fun completingTheResultRequiresTheActualStoppedDriverReleasedSlotAndDurableAcknowledgement() = runBlocking(Dispatchers.IO) {
        admission.failAcknowledgement = true
        val notice = arrive()
        val result = notice.result
        val totals = withTimeout(5_000) { completion.totals.first { it?.notice===notice }!! }
        assertEquals(result.distanceKm, totals.totals.distanceKm, 1e-10)
        assertEquals(result.displayFocusMinutes, totals.totals.focusMinutes)
        completion.refreshTotals()
        val refreshed = withTimeout(5_000) { completion.totals.first { it?.notice===notice }!! }
        assertEquals(totals.totals, refreshed.totals)
        assertTrue(completion.blocksPreparation())
        assertEquals(90_001L, result.elapsedMs)
        assertEquals(2, result.displayFocusMinutes)
        assertEquals(result.archive, store.load(result.sessionId, owner.ownerScope))
        assertNull(store.active())
        assertNull(app.database.airportDao().findByCode(destination.code))
        val preferences = app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE)
        assertEquals(destination, HomeOriginSelection.selectedSnapshot(preferences, destination.code))
        assertFalse(completion.finish(notice))
        graph.owners().release(result.sessionId, owner)
        assertFalse(completion.finish(notice))
        lifetime.cancel()
        graph.registry().driverDestroyed(checkNotNull(key), lifetime, "Controlled lifetime finished")
        withTimeout(5_000) { while (!graph.registry().isStopped(checkNotNull(key))) delay(10) }
        assertFalse(completion.finish(notice))
        admission.failAcknowledgement = false
        assertTrue(completion.finish(notice))
        assertNull(completion.notice.value)
        assertFalse(completion.blocksPreparation())
        assertFalse(completion.finish(notice))
        assertEquals(1, admission.requests)
        assertEquals(result.archive, store.load(result.sessionId, owner.ownerScope))
        val nextOwner = FreeFlightHostOwnerFactory(app, graph.owners()).open()
        assertNotEquals(owner, nextOwner)
        assertEquals(destination, HomeOriginSelection.selectedSnapshot(preferences, destination.code))
    }

    @Test fun accountReplacementRemovesTheOldResultAndCannotDismissOrRewriteTheNewChoice() = runBlocking(Dispatchers.IO) {
        val notice = arrive()
        val preferences = app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE)
        TokenStore.setCancellable("different-completion-account")
        withTimeout(5_000) { completion.notice.first { it == null } }
        HomeOriginSelection.selectManually(preferences, "PEK")
        val marker = preferences.getString(HomeOriginSelection.MARKER, null)
        completion.retryOrigin()
        assertFalse(completion.finish(notice))
        assertFalse(completion.blocksPreparation())
        assertNull(HomeOriginSelection.selectedSnapshot(preferences, destination.code))
        assertEquals("PEK", preferences.getString(HomeOriginSelection.CODE, null))
        assertEquals(marker, preferences.getString(HomeOriginSelection.MARKER, null))
        assertEquals(notice.result.archive, store.load(notice.result.sessionId, owner.ownerScope))
        assertEquals(1, admission.requests)
    }

    private class Admission : FreeFlightAdmission {
        var failAcknowledgement = false
        var requests = 0
        override fun request(owner: FreeFlightOwnerToken, request: FreeFlightStartRequest, emit: (FreeFlightAdmissionEvent) -> Unit) {
            requests++
            emit(FreeFlightAdmissionEvent.Decided(FreeFlightAdmissionResult.Accepted(null)))
        }
        override suspend fun localStartFailed(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted) = error("Unexpected start failure")
        override suspend fun localStartCommitted(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted) {
            check(!failAcknowledgement) { "Controlled durable acknowledgement failure" }
        }
    }
}
