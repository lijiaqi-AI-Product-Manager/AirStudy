package com.projectfocus.service.focus.freeflight

import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Test-only ports inject failure/busy conditions; no accepting production bindings exist. */
@OptIn(ExperimentalCoroutinesApi::class)
class FreeFlightSessionRepositoryTest {
    private class Store : FreeFlightArchiveStore {
        var saved: FreeFlightArchive? = null
            private set
        private val archives = linkedMapOf<Pair<String, String>, FreeFlightArchive>()
        var fail = false
        override suspend fun active() = archives.values.singleOrNull { it.status == FreeFlightStatus.ONGOING }
        override suspend fun load(id: String, owner: String) = archives[id to owner]
        override suspend fun commit(expectedRevision: Long?, archive: FreeFlightArchive, sample: FreeFlightPositionSample?): Boolean {
            if (fail) throw IOException("injected persistence failure")
            val key = archive.sessionId to archive.ownerScope
            if (expectedRevision != archives[key]?.revision) return false
            if (archive.status == FreeFlightStatus.ONGOING && archives.values.any {
                    it.status == FreeFlightStatus.ONGOING && it.sessionId != archive.sessionId
                }) return false
            val decoded = FreeFlightArchiveCodec.decode(FreeFlightArchiveCodec.encode(archive))
            archives[key] = decoded
            saved = decoded
            return true
        }
        override suspend fun positions(id: String, owner: String) = emptyList<FreeFlightPositionSample>()
        override fun terminal(owner: String): Flow<List<FreeFlightArchive>> = flowOf(archives.values.filter {
            it.ownerScope == owner && it.status != FreeFlightStatus.ONGOING
        })
    }
    private class Gate : FlightSessionOwnership {
        var held: String? = null
        var current = true
        var inLease = false
        override suspend fun acquire(sessionId: String, owner: FreeFlightOwnerToken): Boolean {
            if (held != null && held != sessionId) return false
            held = sessionId
            return true
        }
        override suspend fun release(sessionId: String, owner: FreeFlightOwnerToken) {
            check(!inLease) { "release cannot recursively enter the held Core lease" }
            if (held == sessionId) held = null
        }
        override suspend fun <T : Any> withCurrentOwner(sessionId: String, owner: FreeFlightOwnerToken, action: suspend () -> T): T? {
            if (!current || held != sessionId) return null
            check(!inLease)
            inLease = true
            return try { action() } finally { inLease = false }
        }
    }
    private class Admission : FreeFlightAdmission {
        var requested = 0
        var reconcile = 0
        var acknowledgements = 0
        var failAcknowledgement = false
        var onAcknowledgement: (() -> Unit)? = null
        var hold = false
        var throwAfterDispatch = false
        var failReconciliation = false
        var callback: ((FreeFlightAdmissionEvent) -> Unit)? = null
        override fun request(owner: FreeFlightOwnerToken, request: FreeFlightStartRequest, emit: (FreeFlightAdmissionEvent) -> Unit) {
            requested++
            callback = emit
            if (throwAfterDispatch) throw IOException("injected uncertainty after dispatch")
            if (!hold) decide(FreeFlightAdmissionResult.Accepted(null))
        }
        fun pending() { requireNotNull(callback)(FreeFlightAdmissionEvent.Pending("actual-test-receipt")) }
        fun decide(result: FreeFlightAdmissionResult) { requireNotNull(callback)(FreeFlightAdmissionEvent.Decided(result)) }
        override suspend fun localStartFailed(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted) {
            reconcile++
            if (failReconciliation) throw IOException("injected failure to record reconciliation")
        }
        override suspend fun localStartCommitted(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted) {
            acknowledgements++
            onAcknowledgement?.invoke()
            if (failAcknowledgement) throw IOException("injected durable acknowledgement failure")
        }
    }
    private val owner = FreeFlightOwnerToken("owner", 3L)
    private fun request(sessionId: String = "free-1") = FreeFlightStartRequest(
        sessionId, owner.ownerScope, PlannedAirport(1L, "AAA", "Airport", "City", "Country", 0.0, 0.0),
        90.0, "12A", FreeFlightScene(2, "Reading", "book"),
    )
    private fun repository(
        store: Store, gate: Gate, admission: Admission, effects: FreeFlightRuntimeEffects, scope: CoroutineScope,
        mapAvailability: FreeFlightMapAvailability = FreeFlightMapAvailability.UNKNOWN,
        distanceKm: Double? = null,
        clock: () -> FreeFlightClockReading = { FreeFlightClockReading(100_000L, 100_000L, 1_000_000L, "boot") },
    ) =
        FreeFlightSessionRepository(
            store, FreeFlightTimeSource { clock() },
            admission, gate, FreeFlightLandingDistance { _, _ -> requireNotNull(distanceKm) { "No landing measurement requested" } }, effects,
            FreeFlightMapPresence { _, _ -> mapAvailability }, scope,
        )

    @Test fun persistenceFailurePublishesNoStartedFlightAndReconcilesAdmission() = runTest {
        val store = Store().apply { fail = true }
        val gate = Gate()
        val admission = Admission()
        var runtimeCalls = 0
        val repository = repository(store, gate, admission, FreeFlightRuntimeEffects { runtimeCalls++ }, this)
        assertTrue(repository.start(owner, request()) is FreeFlightCommandResult.Failed)
        assertNull(repository.state.value)
        assertNull(store.saved)
        assertNull(gate.held)
        assertEquals(1, admission.reconcile)
        assertEquals(0, runtimeCalls)
    }

    @Test fun sharedRuntimeOwnerAndOwnerGenerationCannotBeBypassed() = runTest {
        val store = Store()
        val gate = Gate().apply { held = "countdown-session" }
        val admission = Admission()
        val repository = repository(store, gate, admission, FreeFlightRuntimeEffects {}, this)
        assertTrue(repository.start(owner, request()) is FreeFlightCommandResult.Rejected)
        assertEquals(0, admission.requested)
        gate.held = null
        assertTrue(repository.start(owner, request()) is FreeFlightCommandResult.Committed)
        val committed = store.saved
        val stale = repository.bindRemoteRecord(owner.copy(generation = 2L), "free-1", "wrong-record")
        assertTrue(stale is FreeFlightCommandResult.Rejected)
        assertEquals(committed, store.saved)
        assertNull(repository.state.value?.remoteRecordId)
    }

    @Test fun cancelledScreenWaitKeepsPendingReservationAndLateSuccessStartsAtAcceptanceTime() = runTest {
        val store = Store()
        val gate = Gate()
        val admission = Admission().apply { hold = true }
        var clock = FreeFlightClockReading(100_000L, 100_000L, 1_000_000L, "boot")
        val repository = repository(store, gate, admission, FreeFlightRuntimeEffects {}, this) { clock }
        val screen = launch { repository.start(owner, request()) }
        runCurrent()
        admission.pending()
        runCurrent()
        screen.cancelAndJoin()
        assertTrue(repository.startState.value is FreeFlightStartState.Pending)
        assertEquals("free-1", gate.held)
        assertNull(store.saved)
        assertTrue(repository.start(owner, request("free-2")) is FreeFlightCommandResult.Rejected)
        assertEquals(1, admission.requested)
        clock = FreeFlightClockReading(180_000L, 180_000L, 1_080_000L, "boot")
        admission.decide(FreeFlightAdmissionResult.Accepted(null))
        runCurrent()
        assertEquals(FreeFlightStatus.ONGOING, store.saved?.status)
        assertEquals(1_080_000L, store.saved?.startedAtWallMs)
        assertEquals(0L, store.saved?.clock?.activeElapsedMs)
        assertTrue((repository.startState.value as FreeFlightStartState.Finished).result is FreeFlightCommandResult.Committed)
    }

    @Test fun obsoleteOwnerLateAcceptanceIsRecordedWithoutPublishingOrStartingRuntime() = runTest {
        val store = Store()
        val gate = Gate()
        val admission = Admission().apply { hold = true }
        var runtimeCalls = 0
        val repository = repository(store, gate, admission, FreeFlightRuntimeEffects { runtimeCalls++ }, this)
        val result = async { repository.start(owner, request()) }
        runCurrent()
        admission.pending()
        runCurrent()
        gate.current = false
        admission.decide(FreeFlightAdmissionResult.Accepted(null))
        assertTrue(result.await() is FreeFlightCommandResult.Failed)
        assertNull(store.saved)
        assertNull(repository.state.value)
        assertNull(gate.held)
        assertEquals(1, admission.reconcile)
        assertEquals(0, runtimeCalls)
    }

    @Test fun thrownTransportUncertaintyRetainsCallbackAndRealLateAcceptance() = runTest {
        val store = Store()
        val gate = Gate()
        val admission = Admission().apply { throwAfterDispatch = true }
        val repository = repository(store, gate, admission, FreeFlightRuntimeEffects {}, this)
        val result = async { repository.start(owner, request()) }
        runCurrent()
        assertTrue(repository.startState.value is FreeFlightStartState.Uncertain)
        assertFalse(result.isCompleted)
        assertEquals("free-1", gate.held)
        admission.decide(FreeFlightAdmissionResult.Accepted(null))
        assertTrue(result.await() is FreeFlightCommandResult.Committed)
        assertEquals(1, admission.requested)
        assertEquals(FreeFlightStatus.ONGOING, store.saved?.status)
    }

    @Test fun failedReconciliationKeepsReservationAndRetryNeverConsumesAgain() = runTest {
        val store = Store().apply { fail = true }
        val gate = Gate()
        val admission = Admission().apply { failReconciliation = true }
        val repository = repository(store, gate, admission, FreeFlightRuntimeEffects {}, this)
        assertTrue(repository.start(owner, request()) is FreeFlightCommandResult.Failed)
        assertTrue(repository.startState.value is FreeFlightStartState.ReconciliationRequired)
        assertEquals("free-1", gate.held)
        assertTrue(repository.start(owner, request("free-2")) is FreeFlightCommandResult.Rejected)
        assertEquals(1, admission.requested)
        admission.failReconciliation = false
        assertTrue(repository.retryFailedStartReconciliation(owner, "free-1"))
        assertNull(gate.held)
        assertEquals(2, admission.reconcile)
        assertEquals(1, admission.requested)
        assertTrue((repository.startState.value as FreeFlightStartState.Finished).reconciliationRecorded)
        assertNull(store.saved)
    }

    @Test fun definitiveRejectionReleasesWithoutArchiveOrReconciliation() = runTest {
        val store = Store()
        val gate = Gate()
        val admission = Admission().apply { hold = true }
        val repository = repository(store, gate, admission, FreeFlightRuntimeEffects {}, this)
        val result = async { repository.start(owner, request()) }
        runCurrent()
        admission.pending()
        admission.decide(FreeFlightAdmissionResult.Rejected("real-service-rejection"))
        assertTrue(result.await() is FreeFlightCommandResult.Rejected)
        assertNull(gate.held)
        assertNull(store.saved)
        assertEquals(0, admission.reconcile)
        assertEquals(1, admission.requested)
    }

    @Test fun actualOwnerInvalidationBlocksAllLaterCommandsAndPaintProjections() = runTest {
        val store = Store()
        val gate = Gate()
        var effects = 0
        val repository = repository(store, gate, Admission(), FreeFlightRuntimeEffects { effects++ }, this)
        assertTrue(repository.start(owner, request()) is FreeFlightCommandResult.Committed)
        val saved = store.saved
        gate.current = false
        assertTrue(repository.tick(owner) is FreeFlightCommandResult.Rejected)
        assertTrue(repository.pause(owner) is FreeFlightCommandResult.Rejected)
        assertTrue(repository.bindRemoteRecord(owner, "free-1", "late-record") is FreeFlightCommandResult.Rejected)
        assertTrue(repository.synchronizeRuntime(owner) is FreeFlightCommandResult.Rejected)
        assertTrue(runCatching { repository.preview(owner) }.isFailure)
        assertTrue(runCatching { repository.landingSearch(owner) }.isFailure)
        assertEquals(saved, store.saved)
        assertEquals(1, effects)
    }

    @Test fun restoreChecksActualLeaseAfterAcquisitionBeforePublishingEffects() = runTest {
        val store = Store()
        val firstGate = Gate()
        val first = repository(store, firstGate, Admission(), FreeFlightRuntimeEffects {}, this)
        assertTrue(first.start(owner, request()) is FreeFlightCommandResult.Committed)
        val saved = store.saved
        val gate = Gate().apply { current = false }
        var effects = 0
        val restored = repository(store, gate, Admission(), FreeFlightRuntimeEffects { effects++ }, this)
        assertTrue(restored.restoreActive(owner) is FreeFlightCommandResult.Rejected)
        assertNull(restored.state.value)
        assertNull(gate.held)
        assertEquals(0, effects)
        assertEquals(saved, store.saved)
    }

    @Test fun terminalEffectsFinishInsideLeaseButReleaseOccursAfterLeaseExit() = runTest {
        val store = Store()
        val gate = Gate()
        val repository = repository(store, gate, Admission(), FreeFlightRuntimeEffects { assertTrue(gate.inLease) }, this)
        assertTrue(repository.start(owner, request()) is FreeFlightCommandResult.Committed)
        val cancelled = repository.cancel(owner) as FreeFlightCommandResult.Committed
        assertEquals(FreeFlightStatus.CANCELLED, cancelled.archive.status)
        assertTrue(cancelled.runtimeSynchronized)
        assertNull(gate.held)
        assertFalse(gate.inLease)
    }

    @Test fun localSuccessAckRunsAfterRealPersistenceAndAfterCoreLeaseExit() = runTest {
        val store = Store(); val gate = Gate(); val admission = Admission()
        admission.onAcknowledgement = { assertNotNull(store.saved); assertFalse(gate.inLease) }
        val repository = repository(store, gate, admission, FreeFlightRuntimeEffects {}, this)
        assertTrue(repository.start(owner, request()) is FreeFlightCommandResult.Committed)
        assertEquals(1, admission.acknowledgements)
        assertEquals(0, admission.reconcile)
        assertTrue(repository.startAcknowledgements.value[FreeFlightStartIdentity(owner, "free-1")] is FreeFlightStartAcknowledgement.Recorded)
        assertEquals("free-1", gate.held)
    }

    @Test fun failedSuccessAckPreservesStartedArchiveAndCanRetryOnlyTheExactAcknowledgement() = runTest {
        val store = Store(); val gate = Gate(); val admission = Admission().apply { failAcknowledgement = true }
        val repository = repository(store, gate, admission, FreeFlightRuntimeEffects {}, this)
        assertTrue(repository.start(owner, request()) is FreeFlightCommandResult.Committed)
        val saved = store.saved
        assertEquals(1, admission.requested)
        assertEquals(0, admission.reconcile)
        assertEquals("free-1", gate.held)
        assertTrue(repository.startAcknowledgements.value[FreeFlightStartIdentity(owner, "free-1")] is FreeFlightStartAcknowledgement.Required)
        assertFalse(repository.retryStartAcknowledgement(owner.copy(generation = 2), "free-1"))
        admission.failAcknowledgement = false
        assertTrue(repository.retryStartAcknowledgement(owner, "free-1"))
        assertEquals(2, admission.acknowledgements)
        assertEquals(1, admission.requested)
        assertEquals(saved, store.saved)
        assertTrue(repository.startAcknowledgements.value[FreeFlightStartIdentity(owner, "free-1")] is FreeFlightStartAcknowledgement.Recorded)
    }

    @Test fun failureOrObsoleteOwnerNeverAcknowledgesALocalSuccessfulStart() = runTest {
        val store = Store().apply { fail = true }; val gate = Gate(); val admission = Admission()
        val repository = repository(store, gate, admission, FreeFlightRuntimeEffects {}, this)
        assertTrue(repository.start(owner, request()) is FreeFlightCommandResult.Failed)
        assertEquals(0, admission.acknowledgements)
        assertEquals(1, admission.reconcile)
        assertTrue(repository.startAcknowledgements.value.isEmpty())
    }

    private val landingAirport = PlannedAirport(2L, "BBB", "Landing", "City", "Country", 1.0, 1.0)
    private fun at(value: Long) = FreeFlightClockReading(value, value, value + 900_000L, "boot")

    @Test fun actualNoMapCallbackFreezesTimeBeforeDelayedPersistenceWithoutGrantingAnUnflownLeg() = runTest {
        val store = Store();val gate = Gate();var clock = at(100_000L)
        val repository = repository(store, gate, Admission(), FreeFlightRuntimeEffects {}, this,
            mapAvailability = FreeFlightMapAvailability.ABSENT) { clock }
        assertTrue(repository.start(owner, request()) is FreeFlightCommandResult.Committed)
        clock = at(190_000L);repository.tick(owner)
        val flown = requireNotNull(store.saved)
        repository.prepareLanding(owner)
        val generation = requireNotNull(store.saved).landingSelectionGeneration
        repository.selectLanding(owner, "free-1", generation, landingAirport)
        clock = at(220_000L)
        assertTrue(repository.confirmLanding(owner, generation) is FreeFlightCommandResult.Committed)
        assertEquals(FreeFlightPhase.NO_MAP_ARRIVAL, store.saved?.phase)
        val permit = requireNotNull(repository.prepareArrivalCallback(owner))
        assertTrue(repository.completeLandingWithoutMap(owner, permit) is FreeFlightCommandResult.Rejected)
        clock = at(221_000L)
        assertTrue(repository.captureArrivalCallback(permit))
        clock = at(250_000L)
        assertTrue(repository.captureArrivalCallback(permit)) // Duplicate does not recapture a later time.
        repository.tick(owner)
        assertEquals(91_000L, store.saved?.clock?.activeElapsedMs)
        assertEquals(flown.position, store.saved?.position)
        assertEquals(flown.cumulativeDistanceKm, requireNotNull(store.saved).cumulativeDistanceKm, 0.0)
        assertTrue(repository.cancel(owner) is FreeFlightCommandResult.Rejected)
        val result = repository.completeLandingWithoutMap(owner, permit) as FreeFlightCommandResult.Committed
        assertEquals(FreeFlightStatus.COMPLETED, result.archive.status)
        assertEquals(91_000L, result.archive.clock.activeElapsedMs)
        assertEquals(at(221_000L).wallMs, result.archive.endedAtWallMs)
        assertEquals(flown.position, result.archive.position)
        assertEquals(flown.cumulativeDistanceKm, result.archive.cumulativeDistanceKm, 0.0)
        assertNull(gate.held)
    }

    @Test fun failedArrivalSaveRetainsTheActualCallbackClockForAnExactRetry() = runTest {
        val store = Store();val gate = Gate();var clock = at(100_000L)
        val repository = repository(store, gate, Admission(), FreeFlightRuntimeEffects {}, this,
            mapAvailability = FreeFlightMapAvailability.ABSENT) { clock }
        repository.start(owner, request());clock = at(110_000L);repository.prepareLanding(owner)
        val generation = requireNotNull(store.saved).landingSelectionGeneration
        repository.selectLanding(owner, "free-1", generation, landingAirport)
        repository.confirmLanding(owner, generation)
        val permit = requireNotNull(repository.prepareArrivalCallback(owner))
        clock = at(110_420L);assertTrue(repository.captureArrivalCallback(permit))
        store.fail = true
        assertTrue(repository.completeLandingWithoutMap(owner, permit) is FreeFlightCommandResult.Failed)
        assertEquals(FreeFlightStatus.ONGOING, store.saved?.status)
        assertTrue(repository.hasArrivalCallback(permit))
        clock = at(170_000L);store.fail = false
        val samePermit = repository.prepareArrivalCallback(owner)
        assertSame(permit, samePermit)
        val result = repository.completeLandingWithoutMap(owner, permit) as FreeFlightCommandResult.Committed
        assertEquals(10_420L, result.archive.clock.activeElapsedMs)
        assertEquals(at(110_420L).wallMs, result.archive.endedAtWallMs)
    }

    @Test fun unknownMapNeverBecomesAFabricatedNoMapArrivalOrZeroDistance() = runTest {
        val store = Store();val gate = Gate()
        val repository = repository(store, gate, Admission(), FreeFlightRuntimeEffects {}, this)
        repository.start(owner, request());repository.prepareLanding(owner)
        val generation = requireNotNull(store.saved).landingSelectionGeneration
        repository.selectLanding(owner, "free-1", generation, landingAirport)
        val before = store.saved
        assertTrue(repository.confirmLanding(owner, generation) is FreeFlightCommandResult.Rejected)
        assertEquals(before, store.saved)
        assertNull(repository.prepareArrivalCallback(owner))
    }

    @Test fun aCancelledOrPriorOwnerCallbackCannotFreezeOrCompleteAnotherFlight() = runTest {
        val store = Store();val gate = Gate();var clock = at(100_000L)
        val repository = repository(store, gate, Admission(), FreeFlightRuntimeEffects {}, this,
            mapAvailability = FreeFlightMapAvailability.ABSENT) { clock }
        repository.start(owner, request());repository.prepareLanding(owner)
        val generation = requireNotNull(store.saved).landingSelectionGeneration
        repository.selectLanding(owner, "free-1", generation, landingAirport)
        repository.confirmLanding(owner, generation)
        val old = requireNotNull(repository.prepareArrivalCallback(owner))
        assertTrue(repository.cancel(owner) is FreeFlightCommandResult.Committed)
        val nextOwner = owner.copy(generation = 4L)
        assertTrue(repository.start(nextOwner, request("free-2")) is FreeFlightCommandResult.Committed)
        assertFalse(repository.captureArrivalCallback(old))
        clock = at(101_000L);repository.tick(nextOwner)
        assertEquals(1_000L, store.saved?.clock?.activeElapsedMs)
        assertEquals("free-2", store.saved?.sessionId)
        assertEquals(FreeFlightStatus.CANCELLED, store.load("free-1", owner.ownerScope)?.status)
        assertTrue(repository.completeLandingWithoutMap(nextOwner, old) is FreeFlightCommandResult.Rejected)
        assertEquals(FreeFlightStatus.ONGOING, store.saved?.status)
    }

    @Test fun mappedArrivalAlsoUsesItsActualCallbackInsteadOfTheLaterCommandTime() = runTest {
        val store = Store();val gate = Gate();var clock = at(100_000L)
        val repository = repository(store, gate, Admission(), FreeFlightRuntimeEffects {}, this,
            mapAvailability = FreeFlightMapAvailability.PRESENT, distanceKm = 0.0) { clock }
        repository.start(owner, request());repository.prepareLanding(owner)
        val generation = requireNotNull(store.saved).landingSelectionGeneration
        // This fixture's distance is genuinely zero: the selected airport is at the actual plane point.
        val point = requireNotNull(store.saved).position
        val samePoint = landingAirport.copy(latitude = point.latitude, longitude = point.longitude)
        repository.selectLanding(owner, "free-1", generation, samePoint)
        repository.confirmLanding(owner, generation)
        val permit = requireNotNull(repository.prepareArrivalCallback(owner))
        clock = at(100_420L);assertTrue(repository.captureArrivalCallback(permit))
        clock = at(105_000L);repository.tick(owner)
        val result = repository.completeLanding(owner, permit) as FreeFlightCommandResult.Committed
        assertEquals(420L, result.archive.clock.activeElapsedMs)
        assertEquals(0.0, result.archive.cumulativeDistanceKm, 0.0)
        assertEquals(at(100_420L).wallMs, result.archive.endedAtWallMs)
    }
}
