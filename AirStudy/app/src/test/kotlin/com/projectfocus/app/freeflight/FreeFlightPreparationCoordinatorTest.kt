package com.projectfocus.app.freeflight

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.FreeFlightClockReading
import com.projectfocus.core.model.flight.freeflight.FreeFlightEngine
import com.projectfocus.core.model.flight.freeflight.FreeFlightStartRequest
import com.projectfocus.feature.booking.seat.SeatSceneChoice
import com.projectfocus.service.focus.freeflight.FreeFlightCommandResult
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.FreeFlightStartIdentity
import com.projectfocus.service.focus.freeflight.FreeFlightStartState
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightDriverState
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeKey
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/** UI waiting/state tests use an explicit fixture, never a production accepting host implementation. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FreeFlightPreparationCoordinatorTest {
    @Test fun missingDependenciesDoNotCallPreparationOrStart() = runTest {
        val actions = Fixture().apply { unavailableReason = "Dependencies unavailable" }
        val coordinator = FreeFlightPreparationCoordinator(actions, backgroundScope)
        coordinator.begin();runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.UNAVAILABLE)
        assertThat(actions.prepareCalls).isEqualTo(0);assertThat(actions.requests).isEmpty()
    }

    @Test fun preparationRejectedDoesNotShowHeadingOrUseFormalStart() = runTest {
        val actions = Fixture().apply { preparation = FreeFlightPreparationDecision.Rejected("Not admitted") }
        val coordinator = FreeFlightPreparationCoordinator(actions, backgroundScope)
        coordinator.begin();runCurrent();coordinator.continueToSeat();coordinator.submit();runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.REJECTED)
        assertThat(actions.requests).isEmpty();assertThat(actions.permissionCalls).isEqualTo(0)
    }

    @Test fun actualPermissionNeedsActionRestoresHeadingAndNeverMeansGranted() = runTest {
        val actions = Fixture().apply { permission = FreeFlightPermissionDecision.NeedsAction("Allow notifications") }
        val coordinator = FreeFlightPreparationCoordinator(actions, backgroundScope)
        coordinator.begin();runCurrent();coordinator.continueToSeat();coordinator.continueToSeat();runCurrent()
        assertThat(actions.permissionCalls).isEqualTo(1)
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.HEADING)
        assertThat(actions.requests).isEmpty()
        actions.permission = FreeFlightPermissionDecision.Ready
        coordinator.continueToSeat();runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.SEAT)
    }

    @Test fun sourceHeadingAndIndependentSceneBuildOneFrozenRequestWithoutCategorySubstitution() = runTest {
        val actions = Fixture();val coordinator = FreeFlightPreparationCoordinator(actions, backgroundScope)
        coordinator.begin();runCurrent();coordinator.headingChanged(-2.8f);runCurrent()
        coordinator.continueToSeat();runCurrent()
        coordinator.confirmSeat(FreeFlightSeatChoice("1A", SCENE.copy(type = -7, title = "  Study  ")))
        runCurrent();coordinator.submit();coordinator.submit();runCurrent()
        val request = actions.requests.single()
        assertThat(request.departure).isEqualTo(actions.prepared.departure)
        assertThat(request.sessionId).isEqualTo(actions.prepared.sessionId)
        assertThat(request.headingDegrees).isEqualTo(358.0)
        assertThat(request.seat).isEqualTo("1A")
        assertThat(request.scene.type).isEqualTo(0);assertThat(request.scene.title).isEqualTo("Study")
        assertThat(request.scene.iconKey).isEqualTo(SCENE.iconKey)
        assertThat(request.shieldingCategoryId).isNull()
        assertThat(actions.headings.last()).isEqualTo(358)
    }

    @Test fun pendingAndUncertainKeepSameAttemptAndNeverExposeAnotherStart() = runTest {
        val actions = Fixture();val coordinator = ready(actions)
        coordinator.submit();runCurrent()
        actions.startState.value = FreeFlightStartState.Pending(actions.identity, "actual-fixture-receipt")
        runCurrent();assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.PENDING)
        coordinator.submit();coordinator.begin();coordinator.continueToSeat();runCurrent()
        actions.startState.value = FreeFlightStartState.Uncertain(actions.identity, "Waiting for actual result")
        runCurrent();coordinator.submit();runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.UNCERTAIN)
        assertThat(actions.requests).hasSize(1);assertThat(actions.prepareCalls).isEqualTo(1)
        assertThat(actions.dismissals).isEmpty();assertThat(actions.outcomes).isEmpty()
    }

    @Test fun rotationCancelsOnlyUiWaitAndRestoresActualPendingBeforeLateCommit() = runTest {
        val actions = Fixture();val first = ready(actions)
        first.submit();runCurrent();actions.startState.value = FreeFlightStartState.Pending(actions.identity, "receipt")
        runCurrent();val saved = checkNotNull(first.saved());first.detach();runCurrent()
        assertThat(actions.dismissals).isEmpty()
        val second = FreeFlightPreparationCoordinator(actions, backgroundScope);second.restore(saved);runCurrent()
        assertThat(actions.restoreCalls).isEqualTo(1);assertThat(actions.prepareCalls).isEqualTo(1)
        assertThat(second.state.value.step).isEqualTo(FreeFlightPreparationStep.PENDING)
        val committed = actions.commit()
        actions.startState.value = FreeFlightStartState.Finished(actions.identity, committed, false);runCurrent()
        assertThat(second.state.value.step).isEqualTo(FreeFlightPreparationStep.STARTED)
        assertThat(actions.requests).hasSize(1);assertThat(actions.outcomes).containsExactly(committed)
    }

    @Test fun actualPendingOverridesStaleSavedSubmittedFalseWithoutResubmitting() = runTest {
        val actions = Fixture();val first = ready(actions);val saved = checkNotNull(first.saved());first.detach()
        actions.startState.value = FreeFlightStartState.Pending(actions.identity, "retained-receipt")
        val second = FreeFlightPreparationCoordinator(actions, backgroundScope);second.restore(saved);runCurrent();second.submit();runCurrent()
        assertThat(second.state.value.submitted).isTrue();assertThat(second.state.value.step).isEqualTo(FreeFlightPreparationStep.PENDING)
        assertThat(actions.requests).isEmpty();assertThat(actions.restoreCalls).isEqualTo(1)
    }

    @Test fun explicitDismissAfterSubmissionDoesNotReleaseOrCallAnotherFormalStart() = runTest {
        val actions = Fixture();val coordinator = ready(actions);coordinator.submit();runCurrent()
        actions.startState.value = FreeFlightStartState.Pending(actions.identity, "receipt");runCurrent()
        coordinator.dismiss(FreeFlightPreparationDismissal.BACK_EXIT);runCurrent()
        assertThat(actions.dismissals).containsExactly(true)
        actions.startState.value = FreeFlightStartState.Finished(actions.identity, actions.commit(), false);runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.CLOSED)
        assertThat(actions.requests).hasSize(1);assertThat(actions.outcomes).isEmpty()
    }

    @Test fun latePreparationAfterExplicitCancelIsNotAdopted() = runTest {
        val actions = Fixture().apply { delayedPreparation = CompletableDeferred() }
        val coordinator = FreeFlightPreparationCoordinator(actions, backgroundScope)
        coordinator.begin();runCurrent();val saved = checkNotNull(coordinator.saved())
        assertThat(saved.preparationId).isEqualTo(actions.preparationId);assertThat(saved.sessionId).isNull()
        coordinator.dismiss(FreeFlightPreparationDismissal.HEADING_CANCEL)
        actions.delayedPreparation!!.complete(FreeFlightPreparationDecision.Prepared(actions.prepared));runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.CLOSED)
        assertThat(actions.shown).isEqualTo(0);assertThat(actions.dismissals).containsExactly(false)
        assertThat(actions.requests).isEmpty()
    }

    @Test fun exactOwnerInvalidationRejectsOldUiButUnrelatedTokenDoesNot() = runTest {
        val actions = Fixture();val coordinator = ready(actions)
        actions.ownerInvalidations.emit(actions.prepared.owner.copy(generation = 99));runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.READY)
        actions.current = false;actions.ownerInvalidations.emit(actions.prepared.owner);runCurrent();coordinator.submit();runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.UNAVAILABLE)
        assertThat(actions.requests).isEmpty();assertThat(actions.dismissals).isEmpty()
    }

    @Test fun ownerCheckCompletingAfterExplicitCancelCannotReopenHeading() = runTest {
        val actions = Fixture().apply { currentBarrier = CompletableDeferred() }
        val coordinator = FreeFlightPreparationCoordinator(actions, backgroundScope)
        coordinator.begin();runCurrent();coordinator.dismiss(FreeFlightPreparationDismissal.HEADING_CANCEL)
        actions.currentBarrier!!.complete(true);runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.CLOSED)
        assertThat(actions.shown).isEqualTo(0);assertThat(actions.requests).isEmpty()
    }

    @Test fun unknownOrWrongOwnerSessionCannotPaintCommitted() = runTest {
        val actions = Fixture();val coordinator = ready(actions);coordinator.submit();runCurrent()
        actions.startState.value = FreeFlightStartState.Pending(FreeFlightStartIdentity(actions.prepared.owner.copy(generation = 3), actions.prepared.sessionId), "wrong")
        runCurrent();assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.STARTING)
        val wrong = actions.commit().let { it.copy(archive = it.archive.copy(sessionId = "wrong-session")) }
        actions.startState.value = FreeFlightStartState.Finished(actions.identity, wrong, false);runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.STARTING)
        assertThat(actions.outcomes).isEmpty()
    }

    @Test fun localArchiveCommitWaitsForActualForegroundAndDispatcherWithoutAnotherAdmission() = runTest {
        val actions = Fixture();val coordinator = ready(actions);coordinator.submit();runCurrent()
        val committed = actions.commit(confirmRuntime = false).copy(runtimeSynchronized = false)
        actions.startState.value = FreeFlightStartState.Finished(actions.identity, committed, false);runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.RUNTIME_WAITING)
        assertThat(actions.outcomes).isEmpty()
        actions.runtimeState.value = actions.running().copy(foregroundConfirmed = false);runCurrent()
        assertThat(actions.outcomes).isEmpty()
        actions.runtimeState.value = actions.running().copy(dispatching = false);runCurrent()
        assertThat(actions.outcomes).isEmpty()
        actions.runtimeState.value = actions.running();runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.STARTED)
        assertThat(actions.outcomes).containsExactly(committed)
        coordinator.submit();coordinator.retryRuntime();runCurrent()
        assertThat(actions.requests).hasSize(1);assertThat(actions.runtimeRetries).isEqualTo(0)
    }

    @Test fun runtimeReadyForAnotherOwnerOrSessionCannotCompleteThisUi() = runTest {
        val actions = Fixture();val coordinator = ready(actions);coordinator.submit();runCurrent()
        val committed = actions.commit(confirmRuntime = false)
        actions.startState.value = FreeFlightStartState.Finished(actions.identity, committed, false);runCurrent()
        for (key in listOf(actions.running().key!!.copy(sessionId = "another"),
                actions.running().key!!.copy(owner = actions.prepared.owner.copy(generation = 99)))) {
            actions.runtimeState.value = actions.running().copy(key = key);runCurrent()
            assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.RUNTIME_WAITING)
        }
        actions.runtimeState.value = actions.running().copy(state = FreeFlightDriverState.FAILED);runCurrent()
        assertThat(actions.outcomes).isEmpty()
    }

    @Test fun restoreLocalCommitAndRetryOnlyItsRuntimeThenDeliverExactlyOnce() = runTest {
        val actions = Fixture();val first = ready(actions);first.submit();runCurrent()
        val committed = actions.commit(confirmRuntime = false).copy(runtimeSynchronized = false)
        actions.startState.value = FreeFlightStartState.Finished(actions.identity, committed, false);runCurrent()
        val saved = checkNotNull(first.saved());first.detach();runCurrent()
        val second = FreeFlightPreparationCoordinator(actions, backgroundScope);second.restore(saved);runCurrent()
        second.submit();second.retryRuntime();second.retryRuntime();runCurrent()
        assertThat(actions.requests).hasSize(1);assertThat(actions.runtimeRetries).isEqualTo(1)
        assertThat(second.state.value.step).isEqualTo(FreeFlightPreparationStep.RUNTIME_WAITING)
        actions.runtimeState.value = actions.running();runCurrent()
        assertThat(second.state.value.step).isEqualTo(FreeFlightPreparationStep.STARTED)
        actions.runtimeState.value = actions.running().copy(detail = "Repeated driver publication");runCurrent()
        assertThat(actions.outcomes).hasSize(1)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.ready(actions: Fixture): FreeFlightPreparationCoordinator {
        val coordinator = FreeFlightPreparationCoordinator(actions, backgroundScope)
        coordinator.begin();runCurrent();coordinator.continueToSeat();runCurrent()
        coordinator.confirmSeat(FreeFlightSeatChoice("1A", SCENE));runCurrent()
        assertThat(coordinator.state.value.step).isEqualTo(FreeFlightPreparationStep.READY)
        return coordinator
    }

    internal class Fixture : FreeFlightPreparationActions {
        override val preparationId = "fixture-preparation"
        val prepared = FreeFlightPrepared(preparationId, FreeFlightOwnerToken("fixture-owner", 1), "fixture-session",
            PlannedAirport(1L, "TEST", "Departure", "City", "Country", 1.0, 2.0), null, null)
        val identity get() = FreeFlightStartIdentity(prepared.owner, prepared.sessionId)
        override var unavailableReason: String? = null
        override val rulerFeedback = object : FreeFlightRulerFeedback { override fun tickVolume() = 0f;override fun hapticIntensity() = 0 }
        override val startState = MutableStateFlow<FreeFlightStartState>(FreeFlightStartState.Idle)
        override val runtimeState = MutableStateFlow(FreeFlightRuntimeObservation())
        override val ownerInvalidations = MutableSharedFlow<FreeFlightOwnerToken>(extraBufferCapacity = 4)
        var current = true
        var preparation: FreeFlightPreparationDecision = FreeFlightPreparationDecision.Prepared(prepared)
        var permission: FreeFlightPermissionDecision = FreeFlightPermissionDecision.Ready
        var delayedPreparation: CompletableDeferred<FreeFlightPreparationDecision>? = null
        var currentBarrier: CompletableDeferred<Boolean>? = null
        var prepareCalls = 0;var restoreCalls = 0;var permissionCalls = 0;var shown = 0;var runtimeRetries = 0
        val headings = mutableListOf<Int>();val requests = mutableListOf<FreeFlightStartRequest>()
        val outcomes = mutableListOf<FreeFlightCommandResult>();val dismissals = mutableListOf<Boolean>()
        private val completion = CompletableDeferred<FreeFlightCommandResult>()
        override suspend fun prepare(): FreeFlightPreparationDecision {
            prepareCalls++
            return delayedPreparation?.let { withContext(NonCancellable) { it.await() } } ?: preparation
        }
        override suspend fun restore(preparationId: String, sessionId: String?): FreeFlightPreparationDecision {
            restoreCalls++;check(preparationId == this.preparationId && (sessionId == null || sessionId == prepared.sessionId));return preparation
        }
        override suspend fun isCurrent(prepared: FreeFlightPrepared): Boolean =
            currentBarrier?.let { withContext(NonCancellable) { it.await() } } ?: (current && prepared == this.prepared)
        override suspend fun ensureKeepAlivePermissions(prepared: FreeFlightPrepared): FreeFlightPermissionDecision { permissionCalls++;return permission }
        override fun preparationShown(prepared: FreeFlightPrepared) { shown++ }
        override fun preparationHidden(prepared: FreeFlightPrepared?, submitted: Boolean) = Unit
        override fun headingChanged(prepared: FreeFlightPrepared, headingDegrees: Int) { headings += headingDegrees }
        override suspend fun start(prepared: FreeFlightPrepared, request: FreeFlightStartRequest): FreeFlightCommandResult {
            requests += request;startState.value = FreeFlightStartState.Checking(identity);return completion.await()
        }
        override suspend fun retryRuntime(prepared: FreeFlightPrepared): FreeFlightCommandResult {
            runtimeRetries++;return commit(confirmRuntime = false).copy(runtimeSynchronized = false)
        }
        override fun dismissed(prepared: FreeFlightPrepared?, submitted: Boolean, reason: FreeFlightPreparationDismissal) { dismissals += submitted }
        override fun startOutcome(prepared: FreeFlightPrepared, result: FreeFlightCommandResult) { outcomes += result }
        fun running() = FreeFlightRuntimeObservation(FreeFlightRuntimeKey(prepared.sessionId, prepared.owner, "fixture-driver"),
            FreeFlightDriverState.RUNNING, foregroundConfirmed = true, dispatching = true)
        fun commit(confirmRuntime: Boolean = true): FreeFlightCommandResult.Committed {
            val request = requests.singleOrNull() ?: FreeFlightStartRequest(prepared.sessionId, prepared.owner.ownerScope, prepared.departure, 0.0, "1A",
                com.projectfocus.core.model.flight.freeflight.FreeFlightScene(1, "Study", "study"))
            if (confirmRuntime) runtimeState.value = running()
            return FreeFlightCommandResult.Committed(FreeFlightEngine().start(request, FreeFlightClockReading(1000, 900, 1_800_000_000_000, "fixture-epoch")), false, true)
        }
    }

    companion object { internal val SCENE = SeatSceneChoice("fixture-scene", "study", "Study", 1) }
}
