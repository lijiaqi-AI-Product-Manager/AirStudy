package com.projectfocus.app.freeflight

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.booking.FreeFlightHostOwnerFactory
import com.projectfocus.app.booking.SourceFlightNumber
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.app.home.HomeOriginSelection
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.freeflight.FreeFlightScene
import com.projectfocus.core.model.flight.freeflight.FreeFlightStartRequest
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.freeflight.*
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimePending
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Real Application/bootstrap/airport DAO/DataStore/Room/Core ownership. Eligibility/formal replies
 * and dispatch failure are explicit component fixtures; no claim of a real grant or normal UI start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [28])
class RetainedFreeFlightPreparationsTest {
    private lateinit var app: ProjectFocusApp
    private lateinit var graph: FreeFlightRuntimeEntryPoint
    private lateinit var settings: SettingsRepository
    private lateinit var store: FreeFlightArchiveStore
    private lateinit var hosts: FreeFlightHostOwnerFactory
    private val formal = ControlledFormal()
    private val effects = AtomicInteger()

    @Before fun open() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) { withTimeout(15_000L) { app.awaitInitialAccountRestore();app.awaitInitialAirportSeed() } }
        graph = EntryPointAccessors.fromApplication(app, FreeFlightRuntimeEntryPoint::class.java)
        store = ReflectionHelpers.getField(graph.repository(), "store")
        settings = ReflectionHelpers.getField(graph.operations(), "settings")
        hosts = FreeFlightHostOwnerFactory(app, graph.owners())
        runBlocking(Dispatchers.IO) { TokenStore.setCancellable("retained-preparation-fixture-account") }
        HomeOriginSelection.selectManually(app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE), "PEK")
    }
    @After fun close() {
        formal.finishPending()
        TokenStore.clear()
        shutdownRealApplicationAfterTest(app)
    }

    private fun retainer(eligibility: FreeFlightPreparationAdmission): RetainedFreeFlightPreparations {
        val actual = graph.repository()
        val repository = FreeFlightSessionRepository(store, ReflectionHelpers.getField(actual, "time"), formal, graph.owners(),
            ReflectionHelpers.getField(actual, "distance"), FreeFlightRuntimeEffects {
                effects.incrementAndGet();throw FreeFlightRuntimePending("Explicit test dispatch awaiting platform confirmation")
            }, graph.presence(), app.admissionApplicationScope)
        return RetainedFreeFlightPreparations(app, hosts, app.database.airportDao(), settings, store, eligibility,
            repository, app.admissionApplicationScope, graph.preparationOperations(), graph.registry())
    }
    private class Allowed : FreeFlightPreparationAdmission {
        val calls = AtomicInteger()
        override suspend fun check(owner: FreeFlightOwnerToken): FreeFlightPreparationResult {
            calls.incrementAndGet();return FreeFlightPreparationResult.Allowed
        }
    }
    private class ControlledEligibility : FreeFlightPreparationAdmission {
        val calls = Channel<CompletableDeferred<FreeFlightPreparationResult>>(Channel.UNLIMITED)
        override suspend fun check(owner: FreeFlightOwnerToken): FreeFlightPreparationResult {
            val result = CompletableDeferred<FreeFlightPreparationResult>()
            calls.send(result);return result.await()
        }
    }
    private class ControlledFormal : FreeFlightAdmission {
        val calls = AtomicInteger();val acknowledgements = AtomicInteger()
        var failAcknowledgement = false
        val callback = CompletableDeferred<(FreeFlightAdmissionEvent) -> Unit>()
        override fun request(owner: FreeFlightOwnerToken, request: FreeFlightStartRequest, emit: (FreeFlightAdmissionEvent) -> Unit) {
            calls.incrementAndGet();callback.complete(emit)
        }
        override suspend fun localStartFailed(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted) = error("Unexpected local archive failure")
        override suspend fun localStartCommitted(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted) {
            acknowledgements.incrementAndGet()
            check(!failAcknowledgement) { "Explicit local acknowledgement write failure" }
        }
        fun finishPending() {
            if (callback.isCompleted) runBlocking { callback.await()(FreeFlightAdmissionEvent.Decided(FreeFlightAdmissionResult.Rejected("Test cleanup"))) }
        }
    }
    private fun request(prepared: FreeFlightPrepared) = FreeFlightStartRequest(prepared.sessionId, prepared.owner.ownerScope,
        prepared.departure, 90.0, "1A", FreeFlightScene(1, "Reading", "study"), prepared.flightNumber, prepared.shieldingCategoryId)
    private suspend fun prepared(retainer: RetainedFreeFlightPreparations, handle: FreeFlightPreparationHandle): FreeFlightPrepared =
        (withTimeout(5_000L) { retainer.prepare(handle) } as FreeFlightPreparationDecision.Prepared).value

    @Test fun productionGraphBindsTheActualRetainerAndExistingAdmissionRepositoryAndScope() {
        val retained = graph.preparations()
        assertSame(retained, graph.preparations())
        assertSame(graph.repository(), ReflectionHelpers.getField<Any>(retained, "repository"))
        assertSame(app.admissionApplicationScope, ReflectionHelpers.getField<Any>(retained, "scope"))
        assertSame(store, ReflectionHelpers.getField<Any>(retained, "archives"))
        assertSame(settings, ReflectionHelpers.getField<Any>(retained, "settings"))
    }

    @Test fun missingActualOriginRejectsBeforeEligibilityAndNeverSubstitutesAnAirport() = runBlocking(Dispatchers.IO) {
        app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE).edit().remove(HomeOriginSelection.CODE).commit()
        val allowed = Allowed();val retained = retainer(allowed);val handle = retained.begin()
        assertTrue(retained.prepare(handle) is FreeFlightPreparationDecision.Rejected)
        assertEquals(0, allowed.calls.get());assertNull(store.active())
        assertNull(graph.owners().captureHostAccount())
    }

    @Test fun detachedWaitKeepsOneRealPreparationAndRestorationNeverRepeatsEligibility() = runBlocking(Dispatchers.IO) {
        val eligibility = ControlledEligibility();val retained = retainer(eligibility);val handle = retained.begin()
        val waiting = async { retained.prepare(handle) }
        val decision = withTimeout(5_000L) { eligibility.calls.receive() }
        waiting.cancelAndJoin()
        assertSame(handle, retained.begin());assertSame(handle, retained.restore(handle.preparationId, null))
        decision.complete(FreeFlightPreparationResult.Allowed)
        val prepared = prepared(retained, handle)
        assertSame(prepared, (retained.prepare(handle) as FreeFlightPreparationDecision.Prepared).value)
        assertSame(handle, retained.restore(handle.preparationId, prepared.sessionId))
        assertEquals("PEK", prepared.departure.code)
        assertEquals(SourceFlightNumber.generate("PEK", "FREE", true), prepared.flightNumber)
        assertTrue(retained.isCurrent(handle, prepared));assertNull(store.active())
        assertTrue(eligibility.calls.tryReceive().isFailure)
    }

    @Test fun savedIdsAndEqualCopiedContextCannotMintAnOwner() = runBlocking(Dispatchers.IO) {
        val retained = retainer(Allowed());val handle = retained.begin();val prepared = prepared(retained, handle)
        assertNull(retained.restore("invented", prepared.sessionId));assertNull(retained.restore(handle.preparationId, "invented"))
        assertTrue(retained.prepare(FreeFlightPreparationHandle(handle.preparationId)) is FreeFlightPreparationDecision.Unavailable)
        assertFalse(retained.isCurrent(handle, prepared.copy()))
        assertTrue(retained.start(handle, prepared.copy(), request(prepared)) is FreeFlightCommandResult.Rejected)
        assertEquals(0, formal.calls.get());assertNull(store.active())
    }

    @Test fun cancelDuringEligibilityDiscardsLateReplyWithoutInvalidatingTheNewRealHost() = runBlocking(Dispatchers.IO) {
        val eligibility = ControlledEligibility();val retained = retainer(eligibility);val first = retained.begin()
        val oldWaiting = async { retained.prepare(first) }
        val oldDecision = withTimeout(5_000L) { eligibility.calls.receive() }
        assertTrue(retained.cancel(first))
        val second = retained.begin();val newWaiting = async { retained.prepare(second) }
        val newDecision = withTimeout(5_000L) { eligibility.calls.receive() }
        newDecision.complete(FreeFlightPreparationResult.Allowed)
        val actual = (withTimeout(5_000L) { newWaiting.await() } as FreeFlightPreparationDecision.Prepared).value
        oldDecision.complete(FreeFlightPreparationResult.Allowed)
        assertTrue(withTimeout(5_000L) { oldWaiting.await() } is FreeFlightPreparationDecision.Unavailable)
        assertTrue(retained.isCurrent(second, actual));assertTrue(hosts.isCurrent(actual.owner))
        assertNull(retained.restore(first.preparationId, null))
    }

    @Test fun submittedStartSurvivesCancelledWaitAndDuplicateCallCannotReconsumeOrChangeTheRequest() = runBlocking(Dispatchers.IO) {
        val eligibility = Allowed();val retained = retainer(eligibility);val handle = retained.begin();val prepared = prepared(retained, handle)
        val request = request(prepared)
        val first = async { retained.start(handle, prepared, request) }
        val reply = withTimeout(5_000L) { formal.callback.await() }
        first.cancelAndJoin()
        assertFalse(retained.cancel(handle));assertSame(handle, retained.begin())
        assertTrue(retained.start(handle, prepared, request.copy(headingDegrees = 180.0)) is FreeFlightCommandResult.Rejected)
        val second = async { retained.start(handle, prepared, request) }
        reply(FreeFlightAdmissionEvent.Decided(FreeFlightAdmissionResult.Accepted(null)))
        val committed = withTimeout(5_000L) { second.await() } as FreeFlightCommandResult.Committed
        assertFalse(committed.runtimeSynchronized)
        assertEquals(prepared.sessionId, store.active()?.sessionId)
        assertEquals(prepared.owner, graph.owners().retainedOwner(prepared.sessionId))
        assertEquals(1, eligibility.calls.get());assertEquals(1, formal.calls.get());assertEquals(1, formal.acknowledgements.get())
        assertEquals(committed, retained.start(handle, prepared, request))
        val repository: FreeFlightSessionRepository = ReflectionHelpers.getField(retained, "repository")
        assertTrue(repository.synchronizeRuntime(prepared.owner, "another-session") is FreeFlightCommandResult.Rejected)
        assertEquals(1, effects.get());assertEquals(committed.archive, store.active())
        assertTrue(retained.retryRuntime(handle, prepared) is FreeFlightCommandResult.Committed)
        assertEquals(1, formal.calls.get());assertEquals(2, effects.get())
    }

    @Test fun accountPublicationAfterPreparationRejectsBeforeFormalAdmission() = runBlocking(Dispatchers.IO) {
        val retained = retainer(Allowed());val handle = retained.begin();val prepared = prepared(retained, handle)
        TokenStore.setCancellable("retained-preparation-fixture-account") // Same value, actual new publication generation.
        assertFalse(retained.isCurrent(handle, prepared))
        assertTrue(retained.start(handle, prepared, request(prepared)) is FreeFlightCommandResult.Rejected)
        assertEquals(0, formal.calls.get());assertNull(store.active())
        val next = retained.begin()
        assertNotSame(handle, next)
        val actual = prepared(retained, next)
        assertTrue(retained.isCurrent(next, actual));assertTrue(hosts.isCurrent(actual.owner))
    }

    @Test fun actualRejectedStartRemainsCurrentForItsUiThenAnExplicitNewPreparationReplacesIt() = runBlocking(Dispatchers.IO) {
        val eligibility = Allowed();val retained = retainer(eligibility);val handle = retained.begin();val prepared = prepared(retained, handle)
        val waiting = async { retained.start(handle, prepared, request(prepared)) }
        val reply = withTimeout(5_000L) { formal.callback.await() }
        reply(FreeFlightAdmissionEvent.Decided(FreeFlightAdmissionResult.Rejected("Explicit fixture refusal")))
        val rejected = withTimeout(5_000L) { waiting.await() }
        assertTrue(rejected is FreeFlightCommandResult.Rejected)
        assertTrue(retained.isCurrent(handle, prepared))
        assertSame(handle, retained.restore(handle.preparationId, prepared.sessionId))
        assertEquals(rejected, retained.start(handle, prepared, request(prepared)))
        val next = retained.begin();assertNotSame(handle, next)
        val actual = prepared(retained, next)
        assertTrue(retained.isCurrent(next, actual));assertFalse(retained.isCurrent(handle, prepared))
        assertEquals(2, eligibility.calls.get());assertEquals(1, formal.calls.get());assertNull(store.active())
    }

    @Test fun cancelledUnstartedPreparationCannotOpenAfterANewerRealHost() = runBlocking(Dispatchers.IO) {
        val eligibility = Allowed();val retained = retainer(eligibility);val old = retained.begin()
        assertTrue(retained.cancel(old))
        val current = retained.begin();val actual = prepared(retained, current)
        assertTrue(retained.prepare(old) is FreeFlightPreparationDecision.Unavailable)
        assertTrue(retained.isCurrent(current, actual));assertEquals(1, eligibility.calls.get())
        assertTrue(hosts.isCurrent(actual.owner));assertNull(store.active())
    }

    @Test fun anotherPreparationWaitsForTheActualDriverLifetimeAndReleasedReservation() = runBlocking(Dispatchers.IO) {
        val eligibility = Allowed();val retained = retainer(eligibility)
        val handle = retained.begin();val first = prepared(retained,handle)
        val starting = async { retained.start(handle,first,request(first)) }
        formal.callback.await()(FreeFlightAdmissionEvent.Decided(FreeFlightAdmissionResult.Accepted(null)))
        val started = starting.await() as FreeFlightCommandResult.Committed
        val repository:FreeFlightSessionRepository = ReflectionHelpers.getField(retained,"repository")
        val registry = graph.registry()
        val target = registry.stage(started.archive,first.owner,repository,graph.arrival())
        val lifetime = SupervisorJob()
        assertTrue(registry.attachDriver(target.key,lifetime))
        registry.driverReady(target.key)
        try {
            val ended = repository.cancel(first.owner) as FreeFlightCommandResult.Committed
            registry.stage(ended.archive,first.owner,repository,graph.arrival())
            assertNull(store.active())
            assertSame(handle,retained.begin())
            graph.owners().release(first.sessionId,first.owner)
            assertSame(handle,retained.begin())
        } finally {
            lifetime.cancel();registry.driverDestroyed(target.key,lifetime,"Actual controlled lifetime completed")
        }
        withTimeout(5_000) { while (!registry.isStopped(target.key)) delay(10) }
        val next = retained.begin()
        assertNotSame(handle,next)
        assertNull(retained.restore(handle.preparationId,first.sessionId))
        val second = prepared(retained,next)
        assertNotEquals(first.sessionId,second.sessionId)
        assertTrue(hosts.isCurrent(second.owner))
        assertEquals(2,eligibility.calls.get());assertEquals(1,formal.calls.get())
    }

    @Test fun terminalArchiveDoesNotDiscardAnUnrecordedFormalStartAcknowledgement() = runBlocking(Dispatchers.IO) {
        val eligibility = Allowed();val retained = retainer(eligibility)
        val handle = retained.begin();val first = prepared(retained,handle)
        formal.failAcknowledgement = true
        val starting = async { retained.start(handle,first,request(first)) }
        formal.callback.await()(FreeFlightAdmissionEvent.Decided(FreeFlightAdmissionResult.Accepted(null)))
        val started = starting.await() as FreeFlightCommandResult.Committed
        val repository:FreeFlightSessionRepository = ReflectionHelpers.getField(retained,"repository")
        val registry = graph.registry()
        val target = registry.stage(started.archive,first.owner,repository,graph.arrival())
        val lifetime = SupervisorJob()
        assertTrue(registry.attachDriver(target.key,lifetime))
        try {
            val ended = repository.cancel(first.owner) as FreeFlightCommandResult.Committed
            registry.stage(ended.archive,first.owner,repository,graph.arrival())
            lifetime.cancel();registry.driverDestroyed(target.key,lifetime,"Actual controlled lifetime completed")
            withTimeout(5_000) { while (!registry.isStopped(target.key)) delay(10) }
            assertSame(handle,retained.begin())
            graph.owners().release(first.sessionId,first.owner)
            assertSame(handle,retained.begin())
            formal.failAcknowledgement = false
            assertTrue(repository.retryStartAcknowledgement(first.owner,first.sessionId))
            val next = retained.begin()
            assertNotSame(handle,next)
            assertNotEquals(first.sessionId,prepared(retained,next).sessionId)
            assertEquals(2,eligibility.calls.get());assertEquals(1,formal.calls.get())
        } finally { lifetime.cancel() }
    }

    @Test fun arrivalOutsideTheOfflineCatalogueRemainsTheRealDepartureSnapshotForTheNextPreparation() = runBlocking(Dispatchers.IO) {
        val airport=PlannedAirport(43341,"FR-0001","Emile Muller Hospital Heliport","Mulhouse","FR",47.725328,7.344995)
        assertNull(app.database.airportDao().findByCode(airport.code))
        val preferences=app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME,Context.MODE_PRIVATE)
        val account=TokenStore.snapshot()
        HomeOriginSelection.advance(preferences,airport,account)
        val eligibility=Allowed();val retained=retainer(eligibility)
        val actual=prepared(retained,retained.begin())
        assertEquals(airport,actual.departure)
        assertEquals(TokenStore.storageScope(account),actual.owner.ownerScope)
        assertEquals(1,eligibility.calls.get());assertEquals(0,formal.calls.get())
        assertNull(app.database.airportDao().findByCode(airport.code))
        TokenStore.setCancellable("another-snapshot-account")
        assertNull(HomeOriginSelection.selectedSnapshot(preferences,airport.code))
    }

    @Test fun replacingTheArrivalSnapshotDuringEligibilityDiscardsTheOlderPreparationEvenForTheSameCode() = runBlocking(Dispatchers.IO) {
        val airport=PlannedAirport(43341,"FR-0001","Emile Muller Hospital Heliport","Mulhouse","FR",47.725328,7.344995)
        val preferences=app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME,Context.MODE_PRIVATE)
        val account=TokenStore.snapshot()
        HomeOriginSelection.advance(preferences,airport,account)
        val eligibility=ControlledEligibility();val retained=retainer(eligibility)
        val handle=retained.begin();val waiting=async { retained.prepare(handle) }
        val decision=withTimeout(5_000) { eligibility.calls.receive() }
        HomeOriginSelection.advance(preferences,airport,account)
        decision.complete(FreeFlightPreparationResult.Allowed)
        assertTrue(waiting.await() is FreeFlightPreparationDecision.Unavailable)
        assertFalse(retained.isSubmitted(handle));assertEquals(0,formal.calls.get())
    }
}
