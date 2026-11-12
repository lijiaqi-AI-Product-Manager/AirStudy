package com.projectfocus.service.focus.freeflight.runtime

import android.os.Looper
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.service.focus.freeflight.*
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Explicit component fixtures; Android Handler/clock capture and Job completion are real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class FreeFlightRuntimeCallbacksTest {
    private val owner = FreeFlightOwnerToken("runtime-test", 1L)
    private val airport = PlannedAirport(1L, "AAA", "Airport", "City", "Country", 0.0, 0.0)
    private fun at(value: Long) = FreeFlightClockReading(value, value, value + 900_000L, "boot")
    private class Store : FreeFlightArchiveStore {
        var saved: FreeFlightArchive? = null
        var fail = false
        override suspend fun active() = saved?.takeIf { it.status == FreeFlightStatus.ONGOING }
        override suspend fun load(id: String, owner: String) = saved?.takeIf { it.sessionId == id && it.ownerScope == owner }
        override suspend fun commit(expectedRevision: Long?, archive: FreeFlightArchive, sample: FreeFlightPositionSample?): Boolean {
            if (fail) throw IOException("Injected save failure")
            if (expectedRevision != saved?.revision) return false
            saved = archive
            return true
        }
        override suspend fun positions(id: String, owner: String) = emptyList<FreeFlightPositionSample>()
        override fun terminal(owner: String) = flowOf(emptyList<FreeFlightArchive>())
    }
    private class Owners : FlightSessionOwnership, FreeFlightRuntimeOwners {
        var current = true
        var held: String? = null
        var retired = 0
        val events = MutableSharedFlow<FreeFlightOwnerToken>(extraBufferCapacity = 1)
        override val invalidations: Flow<FreeFlightOwnerToken> = events
        override val currentHosts: Flow<FreeFlightOwnerToken?> get() = error("This runtime fixture does not implement preparation hosts")
        override suspend fun <T : Any> withPreparationOwner(sessionId: String, owner: FreeFlightOwnerToken, action: suspend () -> T): T? =
            error("This existing runtime fixture does not implement preparation leases")
        var lastProof: FreeFlightStoppedDriver? = null
        override suspend fun acquire(sessionId: String, owner: FreeFlightOwnerToken): Boolean { held = sessionId; return current }
        override suspend fun release(sessionId: String, owner: FreeFlightOwnerToken) { if (held == sessionId) held = null }
        override suspend fun <T : Any> withCurrentOwner(sessionId: String, owner: FreeFlightOwnerToken, action: suspend () -> T): T? =
            if (current && held == sessionId) action() else null
        override suspend fun retireStopped(proof: FreeFlightStoppedDriver): Boolean {
            proof.requireStopped(); retired++; lastProof = proof
            if (!current) held = null
            return !current
        }
    }
    private class Surface : FreeFlightArrivalSurface,FreeFlightCruiseReturnSurface {
        var availability = FreeFlightMapAvailability.ABSENT
        var dispatches = 0
        var callback: (() -> Unit)? = null
        var returnDispatches=0
        var returnCallback:(()->Unit)?=null
        override fun current(sessionId: String, owner: FreeFlightOwnerToken) = availability
        override fun animateFinalArrival(sessionId: String, owner: FreeFlightOwnerToken, archive: FreeFlightArchive, result: () -> Unit) {
            check(Looper.myLooper() == Looper.getMainLooper())
            dispatches++; callback = result
        }
        override suspend fun animateCruiseReturn(sessionId:String,owner:FreeFlightOwnerToken,archive:FreeFlightArchive,stillCurrent:()->Boolean,result:()->Unit) {
            check(Looper.myLooper()==Looper.getMainLooper());check(stillCurrent());returnDispatches++;returnCallback=result
        }
    }
    private class Admission : FreeFlightAdmission {
        override fun request(owner: FreeFlightOwnerToken, request: FreeFlightStartRequest, emit: (FreeFlightAdmissionEvent) -> Unit) =
            emit(FreeFlightAdmissionEvent.Decided(FreeFlightAdmissionResult.Accepted(null)))
        override suspend fun localStartFailed(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted) = Unit
        override suspend fun localStartCommitted(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted) = Unit
    }
    private inner class Fixture(scope: CoroutineScope, mapped: Boolean = false) {
        val owners = Owners()
        val store = Store()
        val surface = Surface().apply { if (mapped) availability = FreeFlightMapAvailability.PRESENT }
        var clock = at(100_000L)
        val repository = FreeFlightSessionRepository(store, FreeFlightTimeSource { clock }, Admission(), owners,
            FreeFlightLandingDistance { _, _ -> 0.0 }, FreeFlightRuntimeEffects {}, surface, scope)
        val arrival = FreeFlightArrivalCoordinator(surface)
        val key = FreeFlightRuntimeKey("runtime-flight", owner, "actual-test-driver")
        suspend fun land() {
            assertTrue(repository.start(owner, FreeFlightStartRequest(key.sessionId, owner.ownerScope, airport,
                90.0, "1A", FreeFlightScene(1, "Reading", null))) is FreeFlightCommandResult.Committed)
            repository.prepareLanding(owner)
            val generation = requireNotNull(store.saved).landingSelectionGeneration
            repository.selectLanding(owner, key.sessionId, generation, airport)
            repository.confirmLanding(owner, generation)
        }
    }

    @Test fun noMapUsesARealMainPostedResultAndDelayedSavingCannotAddTime() = runTest {
        val f = Fixture(backgroundScope); f.land()
        val result = backgroundScope.async { f.arrival.awaitActualResultCallback(f.key, requireNotNull(f.store.saved), f.repository) }
        runCurrent(); assertFalse(result.isCompleted)
        f.clock = at(100_420L)
        shadowOf(Looper.getMainLooper()).idle()
        f.clock = at(105_000L); runCurrent()
        val permit = result.await()
        val committed = f.repository.completeLandingWithoutMap(owner, permit) as FreeFlightCommandResult.Committed
        assertEquals(420L, committed.archive.clock.activeElapsedMs)
        assertEquals(at(100_420L).wallMs, committed.archive.endedAtWallMs)
        assertEquals(0, f.surface.dispatches)
    }

    @Test fun cancelledWaitRemovesItsActualPostedCallback() = runTest {
        val f = Fixture(backgroundScope); f.land()
        val result = backgroundScope.async { f.arrival.awaitActualResultCallback(f.key, requireNotNull(f.store.saved), f.repository) }
        runCurrent(); result.cancelAndJoin()
        f.clock = at(101_000L); shadowOf(Looper.getMainLooper()).idle()
        f.repository.tick(owner)
        assertEquals(1_000L, f.store.saved?.clock?.activeElapsedMs)
        val permit = requireNotNull(f.repository.prepareArrivalCallback(owner))
        assertFalse(f.repository.hasArrivalCallback(permit))
    }

    @Test fun realReturnCallbackAnchorsResumptionEvenWhenSavingFailsAndTicksContinue() = runTest {
        val f=Fixture(backgroundScope,mapped=true)
        assertTrue(f.repository.start(owner,FreeFlightStartRequest(f.key.sessionId,owner.ownerScope,airport,90.0,"1A",FreeFlightScene(1,null,null))) is FreeFlightCommandResult.Committed)
        f.clock=at(130_000L);f.repository.prepareLanding(owner)
        f.clock=at(200_000L);f.repository.returnToCruise(owner)
        val waiting=checkNotNull(f.store.saved)
        val result=backgroundScope.async { f.arrival.awaitActualReturnCallback(f.key,waiting,f.repository) }
        runCurrent();shadowOf(Looper.getMainLooper()).idle();runCurrent()
        assertEquals(1,f.surface.returnDispatches);assertFalse(result.isCompleted)
        f.clock=at(201_499L);f.repository.tick(owner)
        assertEquals(waiting.clock.activeElapsedMs,f.store.saved?.clock?.activeElapsedMs)
        f.clock=at(201_500L);checkNotNull(f.surface.returnCallback).invoke();runCurrent()
        val permit=result.await()
        f.store.fail=true;assertTrue(f.repository.completeReturnToCruise(owner,permit) is FreeFlightCommandResult.Failed)
        f.store.fail=false;f.clock=at(205_000L);f.repository.tick(owner)
        assertEquals(waiting.clock.activeElapsedMs,f.store.saved?.clock?.activeElapsedMs)
        assertSame(permit,f.arrival.awaitActualReturnCallback(f.key,checkNotNull(f.store.saved),f.repository))
        assertEquals(1,f.surface.returnDispatches)
        assertTrue(f.repository.completeReturnToCruise(owner,permit) is FreeFlightCommandResult.Committed)
        val preview=f.repository.preview(owner)
        assertFalse(preview.cruiseReturnPending);assertNull(preview.clock.pause)
        assertEquals(waiting.clock.activeElapsedMs+3_500L,preview.clock.activeElapsedMs)
    }

    @Test fun returnCallbackCannotResumeANewSelectionAndActualNoMapReturnNeedsNoFakeCamera() = runTest {
        val f=Fixture(backgroundScope,mapped=true)
        f.repository.start(owner,FreeFlightStartRequest(f.key.sessionId,owner.ownerScope,airport,90.0,"1A",FreeFlightScene(1,null,null)))
        f.repository.prepareLanding(owner);f.repository.returnToCruise(owner)
        val result=backgroundScope.async { runCatching { f.arrival.awaitActualReturnCallback(f.key,checkNotNull(f.store.saved),f.repository) } }
        runCurrent();shadowOf(Looper.getMainLooper()).idle();runCurrent()
        val old=checkNotNull(f.surface.returnCallback)
        f.repository.prepareLanding(owner);old();runCurrent()
        assertTrue(result.await().isFailure);assertEquals(FreeFlightPhase.LANDING_SELECTION,f.store.saved?.phase)
        f.repository.returnToCruise(owner);f.surface.availability=FreeFlightMapAvailability.ABSENT
        val noMap=backgroundScope.async { f.arrival.awaitActualReturnCallback(f.key,checkNotNull(f.store.saved),f.repository) }
        runCurrent();shadowOf(Looper.getMainLooper()).idle();runCurrent()
        assertTrue(f.repository.completeReturnToCruise(owner,noMap.await()) is FreeFlightCommandResult.Committed)
        assertFalse(checkNotNull(f.store.saved).cruiseReturnPending);assertEquals(1,f.surface.returnDispatches)
    }

    @Test fun sourceManualResumeKeepsTheSameActualCameraAndItsCallbackSamplesOnlyEffectiveRunningTime() = runTest {
        val f=Fixture(backgroundScope,mapped=true)
        f.repository.start(owner,FreeFlightStartRequest(f.key.sessionId,owner.ownerScope,airport,90.0,"1A",FreeFlightScene(1,null,null)))
        f.clock=at(130_000L);f.repository.prepareLanding(owner)
        f.clock=at(200_000L);f.repository.returnToCruise(owner)
        val base=checkNotNull(f.store.saved).clock.activeElapsedMs
        val result=backgroundScope.async { f.arrival.awaitActualReturnCallback(f.key,checkNotNull(f.store.saved),f.repository) }
        runCurrent();shadowOf(Looper.getMainLooper()).idle();runCurrent()
        f.clock=at(200_500L);assertTrue(f.repository.resume(owner) is FreeFlightCommandResult.Committed)
        assertTrue(checkNotNull(f.store.saved).cruiseReturnPending)
        f.clock=at(201_000L);f.repository.pause(owner)
        assertEquals(base+500L,checkNotNull(f.store.saved).clock.activeElapsedMs)
        f.clock=at(201_500L);checkNotNull(f.surface.returnCallback).invoke();runCurrent()
        assertTrue(f.repository.completeReturnToCruise(owner,result.await()) is FreeFlightCommandResult.Committed)
        assertEquals(1,f.surface.returnDispatches);assertNull(checkNotNull(f.store.saved).clock.pause)
        f.clock=at(202_500L);assertEquals(base+1500L,f.repository.preview(owner).clock.activeElapsedMs)
    }

    @Test fun mappedResultWaitsForItsActualSurfaceAndRetryDoesNotAnimateAgain() = runTest {
        val f = Fixture(backgroundScope, mapped = true); f.land()
        val result = backgroundScope.async { f.arrival.awaitActualResultCallback(f.key, requireNotNull(f.store.saved), f.repository) }
        runCurrent(); shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(1, f.surface.dispatches); assertFalse(result.isCompleted)
        advanceTimeBy(6_000L); runCurrent(); assertFalse(result.isCompleted)
        f.clock = at(100_420L); requireNotNull(f.surface.callback).invoke(); runCurrent()
        val permit = result.await()
        f.store.fail = true
        assertTrue(f.repository.completeLanding(owner, permit) is FreeFlightCommandResult.Failed)
        f.clock = at(110_000L); f.store.fail = false
        assertSame(permit, f.arrival.awaitActualResultCallback(f.key, requireNotNull(f.store.saved), f.repository))
        assertEquals(1, f.surface.dispatches)
        val committed = f.repository.completeLanding(owner, permit) as FreeFlightCommandResult.Committed
        assertEquals(420L, committed.archive.clock.activeElapsedMs)
    }

    @Test fun anUnknownMappedSurfaceFailsWithoutAnArrivalGrant() = runTest {
        val f = Fixture(backgroundScope, mapped = true); f.land()
        f.surface.availability = FreeFlightMapAvailability.UNKNOWN
        val result = backgroundScope.async { runCatching { f.arrival.awaitActualResultCallback(f.key, requireNotNull(f.store.saved), f.repository) } }
        runCurrent(); shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertTrue(result.await().isFailure)
        assertEquals(FreeFlightStatus.ONGOING, f.store.saved?.status)
        assertFalse(f.repository.hasArrivalCallback(requireNotNull(f.repository.prepareArrivalCallback(owner))))
    }

    @Test fun actualDriverDestroyCannotAcknowledgeStopBeforeANonCancellableChildFinishes() = runTest {
        val f = Fixture(backgroundScope); f.land()
        val registry = FreeFlightRuntimeRegistry(backgroundScope, f.owners)
        val target = registry.stage(requireNotNull(f.store.saved), owner, f.repository, f.arrival)
        val lifetime = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + lifetime)
        val cleanup = CompletableDeferred<Unit>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        assertTrue(registry.attachDriver(target.key, lifetime))
        registry.driverReady(target.key); assertTrue(registry.isRunning(target.key))
        lifetime.cancel(); registry.driverDestroyed(target.key, lifetime, "Actual test onDestroy")
        runCurrent()
        assertFalse(lifetime.isCompleted); assertFalse(registry.isStopped(target.key)); assertEquals(0, f.owners.retired)
        assertEquals(target.key, registry.stage(requireNotNull(f.store.saved), owner, f.repository, f.arrival).key)
        cleanup.complete(Unit); runCurrent()
        assertTrue(lifetime.isCompleted); assertTrue(registry.isStopped(target.key)); assertEquals(1, f.owners.retired)
        registry.driverDestroyed(target.key, lifetime, "Duplicate callback"); runCurrent()
        assertEquals(1, f.owners.retired)
    }

    @Test fun aStoppedDriverProofAndDelayedIntentCannotAuthorizeANewerDriver() = runTest {
        val f = Fixture(backgroundScope); f.land()
        val registry = FreeFlightRuntimeRegistry(backgroundScope, f.owners)
        val old = registry.stage(requireNotNull(f.store.saved), owner, f.repository, f.arrival)
        val lifetime = SupervisorJob()
        assertTrue(registry.attachDriver(old.key, lifetime))
        lifetime.cancel(); registry.driverDestroyed(old.key, lifetime, "First actual destroy"); runCurrent()
        val proof = requireNotNull(f.owners.lastProof)
        val delayed = SupervisorJob()
        assertFalse(registry.attachDriver(old.key, delayed)); delayed.cancel()
        val next = registry.stage(requireNotNull(f.store.saved), owner, f.repository, f.arrival)
        assertNotEquals(old.key.driverId, next.key.driverId)
        var obsolete = false
        try { proof.requireStopped() } catch (_: IllegalStateException) { obsolete = true }
        assertTrue(obsolete)
        assertEquals(f.key.sessionId, f.owners.held)
    }

    @Test fun anAccountInvalidationAfterActualStopStillRetiresItsExactQuiescentOwner() = runTest {
        val f = Fixture(backgroundScope); f.land()
        val registry = FreeFlightRuntimeRegistry(backgroundScope, f.owners)
        val target = registry.stage(requireNotNull(f.store.saved), owner, f.repository, f.arrival)
        val lifetime = SupervisorJob()
        assertTrue(registry.attachDriver(target.key, lifetime))
        lifetime.cancel(); registry.driverDestroyed(target.key, lifetime, "Unexpected current-owner stop"); runCurrent()
        assertEquals(f.key.sessionId, f.owners.held)
        f.owners.current = false
        f.owners.events.emit(owner); runCurrent()
        assertNull(f.owners.held)
        assertEquals(FreeFlightStatus.ONGOING, f.store.saved?.status)
    }

    @Test fun failedConfirmationIsNotRunningAndAnUnrelatedDestroyCannotStopTheCurrentDriver() = runTest {
        val f = Fixture(backgroundScope); f.land()
        val registry = FreeFlightRuntimeRegistry(backgroundScope, f.owners)
        val target = registry.stage(requireNotNull(f.store.saved), owner, f.repository, f.arrival)
        val lifetime = SupervisorJob()
        assertTrue(registry.attachDriver(target.key, lifetime)); registry.driverReady(target.key)
        registry.failure(target.key, IOException("actual failure")); assertFalse(registry.isRunning(target.key))
        val stranger = Job().also { it.cancel() }
        registry.driverDestroyed(target.key, stranger, "Unrelated callback"); runCurrent()
        assertFalse(registry.isStopped(target.key)); assertEquals(0, f.owners.retired)
        lifetime.cancel(); registry.driverDestroyed(target.key, lifetime, "Matching callback"); runCurrent()
        assertTrue(registry.isStopped(target.key))
    }
}
