package com.projectfocus.app.admission.freeflight

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.FreeFlightScene
import com.projectfocus.core.model.flight.freeflight.FreeFlightStartRequest
import com.projectfocus.core.network.dto.*
import com.projectfocus.core.network.freeflight.FreeFlightAdmissionGateway
import com.projectfocus.service.focus.freeflight.*
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Candidate only. Real SharedPreferences plus a controllable transport, not a delayed fake start. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class OwnFreeFlightAdmissionTest {
    @Test fun eightSecondsPersistsPendingAndTheSameActualLateCallbackStillAcceptsAtZero() = runTest {
        val f = Fixture(this); val events = mutableListOf<FreeFlightAdmissionEvent>()
        f.adapter.request(owner, request(), events::add); runCurrent()
        assertThat(f.gateway.submissions).hasSize(1)
        advanceTimeBy(7_999); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Pending>()).isEmpty()
        advanceTimeBy(1); runCurrent()
        val pending = events.filterIsInstance<FreeFlightAdmissionEvent.Pending>().single()
        assertThat(pending.receiptId).isNotEmpty()
        val entry = f.journal.read(f.gateway.submissions.single().operationId)
        assertThat(entry.pendingReceiptId).isEqualTo(pending.receiptId)
        assertThat(entry.pendingActive).isTrue()
        f.gateway.finish(approved(f.gateway.submissions.single(), remaining = 0)); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Decided>().single().result).isEqualTo(FreeFlightAdmissionResult.Accepted(null))
        assertThat(f.journal.read(entry.operationId).pendingActive).isFalse()
        assertThat(f.journal.read(entry.operationId).pendingReceiptId).isEqualTo(pending.receiptId)
        assertThat(f.gateway.submissions).hasSize(1)
    }

    @Test fun fastMemberApprovalNeverEmitsPendingAndNeverUsesAdmissionIdAsCloudFlightId() = runTest {
        val f = Fixture(this); val events = mutableListOf<FreeFlightAdmissionEvent>()
        f.gateway.memberMode = true
        f.adapter.request(owner, request(), events::add); runCurrent()
        f.gateway.finish(approved(f.gateway.submissions.single(), member = true)); runCurrent()
        advanceTimeBy(8_000); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Pending>()).isEmpty()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Decided>().single().result).isEqualTo(FreeFlightAdmissionResult.Accepted(null))
    }

    @Test fun duplicateFormalAttachmentDoesNotRepeatConsumeOrTerminalDelivery() = runTest {
        val f = Fixture(this); val first = mutableListOf<FreeFlightAdmissionEvent>(); val second = mutableListOf<FreeFlightAdmissionEvent>()
        val frozen = request()
        f.adapter.request(owner, frozen, first::add); f.adapter.request(owner, frozen, second::add); runCurrent()
        assertThat(f.gateway.submissions).hasSize(1)
        val reply = approved(f.gateway.submissions.single())
        f.gateway.finish(reply); f.gateway.finish(reply); runCurrent()
        assertThat(first.filterIsInstance<FreeFlightAdmissionEvent.Decided>()).hasSize(1)
        assertThat(second.filterIsInstance<FreeFlightAdmissionEvent.Decided>()).hasSize(1)
    }

    @Test fun networkFailureAndMissingStatusRemainUncertainUntilRealApprovalArrives() = runTest {
        val f = Fixture(this); val events = mutableListOf<FreeFlightAdmissionEvent>()
        f.adapter.request(owner, request(), events::add); runCurrent()
        f.gateway.fail(IOException("fixture transport interruption")); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Decided>()).isEmpty()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Uncertain>()).isNotEmpty()
        assertThat(f.gateway.queries).isEqualTo(1)
        advanceTimeBy(8_000); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Pending>()).hasSize(1)
        f.gateway.finish(approved(f.gateway.submissions.single())); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Decided>()).hasSize(1)
        assertThat(f.gateway.submissions).hasSize(1)
    }

    @Test fun restartReusesActualReceiptAndQueriesWithoutReplayingFormalPost() = runTest {
        val f = Fixture(this); val frozen = request(); val oldEvents = mutableListOf<FreeFlightAdmissionEvent>()
        f.adapter.request(owner, frozen, oldEvents::add); runCurrent(); advanceTimeBy(8_000); runCurrent()
        val receipt = oldEvents.filterIsInstance<FreeFlightAdmissionEvent.Pending>().single().receiptId
        val oldRequest = f.gateway.submissions.single()
        val restoredGateway = Gateway().apply { lookup = FreeFlightOperationReply(true, approved(oldRequest)) }
        val restored = f.newAdapter(restoredGateway)
        val replay = mutableListOf<FreeFlightAdmissionEvent>()
        restored.request(owner, frozen, replay::add); runCurrent()
        assertThat(restoredGateway.submissions).isEmpty()
        assertThat(restoredGateway.queries).isEqualTo(1)
        assertThat(replay.filterIsInstance<FreeFlightAdmissionEvent.Pending>().single().receiptId).isEqualTo(receipt)
        assertThat(replay.filterIsInstance<FreeFlightAdmissionEvent.Decided>().single().result).isEqualTo(FreeFlightAdmissionResult.Accepted(null))
    }

    @Test fun changedAccountGenerationOrFrozenRequestCannotReuseOrQueryTheOldOperation() = runTest {
        val f = Fixture(this); val frozen = request()
        f.adapter.request(owner, frozen) {}; runCurrent()
        val other = owner.copy(generation = owner.generation + 1)
        f.identities.value = CapturedFreeFlightAdmissionIdentity(other, "fixture-account-two")
        val events = mutableListOf<FreeFlightAdmissionEvent>()
        f.adapter.request(other, frozen, events::add); runCurrent()
        f.adapter.reconcile(other); runCurrent()
        assertThat(f.gateway.submissions).hasSize(1)
        assertThat(f.gateway.queries).isEqualTo(0)
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Uncertain>()).hasSize(1)
        f.identities.value = CapturedFreeFlightAdmissionIdentity(owner, "fixture-account-one")
        f.adapter.request(owner, frozen.copy(seat = "02A"), events::add); runCurrent()
        assertThat(f.gateway.submissions).hasSize(1)
    }

    @Test fun approvalBindingMismatchCannotStartOrClearItsRealPendingReceipt() = runTest {
        val f = Fixture(this); val events = mutableListOf<FreeFlightAdmissionEvent>()
        f.adapter.request(owner, request(), events::add); runCurrent(); advanceTimeBy(8_000); runCurrent()
        val submitted = f.gateway.submissions.single()
        f.gateway.finish(approved(submitted).copy(sessionId = "unrelated-session")); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Decided>()).isEmpty()
        assertThat(f.journal.read(submitted.operationId).pendingActive).isTrue()
        f.gateway.finish(approved(submitted)); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Decided>()).hasSize(1)
    }

    @Test fun journalFailureCannotManufacturePendingOrLoseAnApprovedFailureQueue() = runTest {
        val f = Fixture(this); val events = mutableListOf<FreeFlightAdmissionEvent>()
        val frozen = request(); f.adapter.request(owner, frozen, events::add); runCurrent()
        f.preferences.failCommit = true
        advanceTimeBy(8_000); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Pending>()).isEmpty()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Uncertain>()).isNotEmpty()
        f.preferences.failCommit = false
        f.gateway.finish(approved(f.gateway.submissions.single())); runCurrent()
        f.gateway.failureRouteOffline = true
        f.adapter.localStartFailed(frozen, FreeFlightAdmissionResult.Accepted(null)); runCurrent()
        val stored = f.journal.find(frozen)
        assertThat(stored.localFailureQueued).isTrue()
        assertThat(stored.failureAcknowledged).isNull()
        f.gateway.failureRouteOffline = false
        f.adapter.reconcile(owner); runCurrent()
        val acknowledged = f.journal.find(frozen)
        assertThat(acknowledged.failureAcknowledged?.refunded).isFalse()
        assertThat(f.gateway.submissions).hasSize(1)
        assertThat(f.gateway.failures).isEqualTo(2)
    }

    @Test fun failureQueueWriteMustSucceedBeforeEngineCanReleaseItsOperation() = runTest {
        val f = Fixture(this); val frozen = request()
        f.adapter.request(owner, frozen) {}; runCurrent()
        f.gateway.finish(approved(f.gateway.submissions.single())); runCurrent()
        f.preferences.failCommit = true
        var thrown: Throwable? = null
        try { f.adapter.localStartFailed(frozen, FreeFlightAdmissionResult.Accepted(null)) } catch (error: Exception) { thrown = error }
        assertThat(thrown).isNotNull()
        assertThat(f.gateway.failures).isEqualTo(0)
        assertThat(f.journal.find(frozen).localFailureQueued).isFalse()
    }

    @Test fun preparationDoesNotConsumeAndItsCapturedIdentityMustStillBeCurrent() = runTest {
        val f = Fixture(this)
        var result: FreeFlightPreparationResult? = null
        launch { result = f.adapter.check(owner) }; runCurrent()
        assertThat(result).isEqualTo(FreeFlightPreparationResult.Allowed)
        assertThat(f.gateway.preparations).isEqualTo(2) // Explicit member query then real device preparation; neither consumes.
        assertThat(f.gateway.submissions).isEmpty()
        f.gateway.beforePrepareReturn = { f.identities.value = CapturedFreeFlightAdmissionIdentity(owner.copy(generation = 2), "fixture-account-two") }
        launch { result = f.adapter.check(owner) }; runCurrent()
        assertThat(result).isInstanceOf(FreeFlightPreparationResult.Rejected::class.java)
        assertThat(f.gateway.submissions).isEmpty()
    }

    @Test fun formalWaitsForRealSuspendingCaptureInsideApplicationOwnerBeforeSending() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.identities.beforeCapture = { entered.complete(Unit); release.await() }
        val events = mutableListOf<FreeFlightAdmissionEvent>()
        f.adapter.request(owner, request(), events::add)
        runCurrent(); entered.await()
        assertThat(f.gateway.submissions).isEmpty()
        assertThat(events).isEmpty()
        release.complete(Unit); runCurrent()
        assertThat(f.gateway.submissions).hasSize(1)
        f.gateway.finish(approved(f.gateway.submissions.single())); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Decided>()).hasSize(1)
    }

    @Test fun realSuccessAcknowledgementIsDurableBeforeCredentialsAreClearedAndLateReplyCannotResurrectThem() = runTest {
        val f = Fixture(this); val frozen = request(); val events = mutableListOf<FreeFlightAdmissionEvent>()
        f.adapter.request(owner, frozen, events::add); runCurrent()
        val reply = approved(f.gateway.submissions.single())
        f.gateway.finish(reply); runCurrent()
        assertThat(f.adapter.retainedCredentialCount()).isEqualTo(1)
        f.adapter.localStartCommitted(frozen, FreeFlightAdmissionResult.Accepted(null)); runCurrent()
        val recorded = f.journal.find(frozen)
        assertThat(recorded.localStartCommittedAtMillis).isNotNull()
        assertThat(recorded.localFailureQueued).isFalse()
        assertThat(f.adapter.retainedCredentialCount()).isEqualTo(0)
        f.gateway.finish(reply); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Decided>()).hasSize(1)
        assertThat(f.adapter.retainedCredentialCount()).isEqualTo(0)
        assertThat(f.gateway.failures).isEqualTo(0)
        assertThat(f.gateway.submissions).hasSize(1)
    }

    @Test fun failedSuccessAckRetainsAssociationAndCredentialsUntilTheActualDurableRetry() = runTest {
        val f = Fixture(this); val frozen = request()
        f.adapter.request(owner, frozen) {}; runCurrent()
        f.gateway.finish(approved(f.gateway.submissions.single())); runCurrent()
        f.preferences.failCommit = true
        var failure: Throwable? = null
        try { f.adapter.localStartCommitted(frozen, FreeFlightAdmissionResult.Accepted(null)) } catch (error: Exception) { failure = error }
        assertThat(failure).isNotNull()
        assertThat(f.journal.find(frozen).localStartCommittedAtMillis).isNull()
        assertThat(f.adapter.retainedCredentialCount()).isEqualTo(1)
        assertThat(f.gateway.failures).isEqualTo(0)
        f.preferences.failCommit = false
        f.adapter.localStartCommitted(frozen, FreeFlightAdmissionResult.Accepted(null)); runCurrent()
        assertThat(f.journal.find(frozen).localStartCommittedAtMillis).isNotNull()
        assertThat(f.adapter.retainedCredentialCount()).isEqualTo(0)
        assertThat(f.gateway.submissions).hasSize(1)
    }

    @Test fun committedJournalRecoveryDoesNotRetainCredentialsOrResubmitConsumption() = runTest {
        val f = Fixture(this); val frozen = request()
        f.adapter.request(owner, frozen) {}; runCurrent()
        f.gateway.finish(approved(f.gateway.submissions.single())); runCurrent()
        f.adapter.localStartCommitted(frozen, FreeFlightAdmissionResult.Accepted(null)); runCurrent()
        val recoveredGateway = Gateway(); val recovered = f.newAdapter(recoveredGateway)
        val events = mutableListOf<FreeFlightAdmissionEvent>()
        recovered.reconcile(owner); runCurrent()
        assertThat(recovered.retainedCredentialCount()).isEqualTo(0)
        recovered.request(owner, frozen, events::add); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Decided>()).hasSize(1)
        assertThat(recovered.retainedCredentialCount()).isEqualTo(0)
        assertThat(recoveredGateway.submissions).isEmpty()
        assertThat(recoveredGateway.queries).isEqualTo(0)
    }

    @Test fun obsoleteOwnerLateSuccessStillReconcilesWithCapturedAccountUntilRealFailureAck() = runTest {
        val f = Fixture(this); val frozen = request(); val events = mutableListOf<FreeFlightAdmissionEvent>()
        f.adapter.request(owner, frozen, events::add); runCurrent()
        f.identities.value = CapturedFreeFlightAdmissionIdentity(owner.copy(generation = 2), "fixture-account-two")
        f.gateway.finish(approved(f.gateway.submissions.single())); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Decided>().single().result).isEqualTo(FreeFlightAdmissionResult.Accepted(null))
        f.gateway.failureRouteOffline = true
        f.adapter.localStartFailed(frozen, FreeFlightAdmissionResult.Accepted(null)); runCurrent()
        assertThat(f.adapter.retainedCredentialCount()).isEqualTo(1)
        assertThat(f.gateway.failureBearers.single()).isEqualTo("fixture-account-one")
        f.gateway.failureRouteOffline = false
        f.identities.value = CapturedFreeFlightAdmissionIdentity(owner, "fixture-account-one")
        f.adapter.reconcile(owner); runCurrent()
        assertThat(f.journal.find(frozen).failureAcknowledged).isNotNull()
        assertThat(f.adapter.retainedCredentialCount()).isEqualTo(0)
        assertThat(f.gateway.submissions).hasSize(1)
    }

    @Test fun alreadyRecordedLocalFailureCannotStartAgainFromTheSameOldApproval() = runTest {
        val f = Fixture(this); val frozen = request()
        f.adapter.request(owner, frozen) {}; runCurrent()
        f.gateway.finish(approved(f.gateway.submissions.single())); runCurrent()
        f.adapter.localStartFailed(frozen, FreeFlightAdmissionResult.Accepted(null)); runCurrent()
        val original = f.journal.find(frozen)
        assertThat(original.failureAcknowledged).isNotNull()
        val replay = mutableListOf<FreeFlightAdmissionEvent>()
        f.adapter.request(owner, frozen, replay::add); runCurrent()
        assertThat(replay.filterIsInstance<FreeFlightAdmissionEvent.Decided>().single().result).isInstanceOf(FreeFlightAdmissionResult.Rejected::class.java)
        assertThat(f.journal.find(frozen)).isEqualTo(original)
        assertThat(f.journal.find(frozen).decision?.approved).isTrue()
        assertThat(f.gateway.submissions).hasSize(1)
        assertThat(f.adapter.retainedCredentialCount()).isEqualTo(0)
    }

    @Test fun realMemberPreparationFormalAndFailureReconciliationNeverRequireADeviceIdentity() = runTest {
        val f = Fixture(this); f.gateway.memberMode = true; f.deviceUnavailable = true
        var prepared: FreeFlightPreparationResult? = null
        launch { prepared = f.adapter.check(owner) }; runCurrent()
        assertThat(prepared).isEqualTo(FreeFlightPreparationResult.Allowed)
        val frozen = request(); val events = mutableListOf<FreeFlightAdmissionEvent>()
        f.adapter.request(owner, frozen, events::add); runCurrent()
        assertThat(f.gateway.formalRequests).hasSize(1)
        assertThat(f.gateway.formalRequests.single().hwid).isNull()
        assertThat(f.gateway.formalRequests.single().credential).isNull()
        f.gateway.finish(approved(f.gateway.submissions.single(), member = true)); runCurrent()
        assertThat(events.filterIsInstance<FreeFlightAdmissionEvent.Decided>().single().result).isEqualTo(FreeFlightAdmissionResult.Accepted(null))
        assertThat(f.journal.find(frozen).hwid).isNull()
        f.adapter.localStartFailed(frozen, FreeFlightAdmissionResult.Accepted(null)); runCurrent()
        assertThat(f.gateway.failureRequests.single().hwid).isNull()
        assertThat(f.journal.find(frozen).failureAcknowledged).isNotNull()
        assertThat(f.deviceCaptures).isEqualTo(0)
    }

    @Test fun nonMemberDeviceNegotiationKeepsOneAssociationAndOnlyOneConsumptionCapableRequest() = runTest {
        val f = Fixture(this); val frozen = request()
        f.adapter.request(owner, frozen) {}; f.adapter.request(owner, frozen) {}; runCurrent()
        assertThat(f.gateway.formalRequests).hasSize(2)
        assertThat(f.gateway.formalRequests.first().hwid).isNull()
        assertThat(f.gateway.formalRequests.last().hwid).isEqualTo(device.hwid)
        assertThat(f.gateway.formalRequests.map { it.operationId }.distinct()).hasSize(1)
        assertThat(f.gateway.submissions).hasSize(1)
        assertThat(f.deviceCaptures).isEqualTo(1)
        f.gateway.finish(approved(f.gateway.submissions.single())); runCurrent()
    }

    private class Fixture(private val scope: TestScope) {
        val preferences = FailingPreferences(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("admission-test-" + UUID.randomUUID(), Context.MODE_PRIVATE))
        val journal = FreeFlightAdmissionJournal(preferences)
        val identities = Identities()
        val gateway = Gateway()
        var deviceUnavailable = false
        var deviceCaptures = 0
        val adapter = newAdapter(gateway)
        fun newAdapter(transport: Gateway) = OwnFreeFlightAdmission(transport, identities, FreeFlightDeviceIdentityProvider {
            deviceCaptures++; if (deviceUnavailable) throw IOException("actual device unavailable fixture"); device
        }, journal,
            scope.backgroundScope, StandardTestDispatcher(scope.testScheduler), nowMillis = { 1_000 + scope.testScheduler.currentTime })
    }
    private class Identities : FreeFlightAdmissionIdentityProvider {
        var value = CapturedFreeFlightAdmissionIdentity(owner, "fixture-account-one")
        var beforeCapture: (suspend () -> Unit)? = null
        override suspend fun capture(owner: FreeFlightOwnerToken): CapturedFreeFlightAdmissionIdentity? { beforeCapture?.invoke(); return value.takeIf { it.owner == owner } }
        override suspend fun current() = value
        override suspend fun isCurrent(identity: CapturedFreeFlightAdmissionIdentity) = value == identity
    }
    private class Gateway : FreeFlightAdmissionGateway {
        /** All actual formal calls, including the non-consuming member/device-proof handshake. */
        val formalRequests = mutableListOf<FreeFlightFormalRequest>()
        /** Calls which can yield a definitive member grant/device consume. */
        val submissions = mutableListOf<FreeFlightFormalRequest>()
        private var callback: ((Result<FreeFlightFormalReply>) -> Unit)? = null
        var queries = 0; var preparations = 0; var failures = 0; var failureRouteOffline = false
        var beforePrepareReturn: (() -> Unit)? = null
        var memberMode = false
        var lookup = FreeFlightOperationReply(false)
        val failureBearers = mutableListOf<String?>()
        val failureRequests = mutableListOf<FreeFlightLocalFailureRequest>()
        override suspend fun prepare(request: FreeFlightDeviceRequest, bearer: String?): FreeFlightPrepareReply {
            preparations++; beforePrepareReturn?.invoke()
            if (memberMode) return FreeFlightPrepareReply(true, FreeFlightGrantSource.MEMBER, FreeFlightAdmissionReason.ALLOWED)
            if (request.hwid == null) return FreeFlightPrepareReply(false, FreeFlightGrantSource.NONE, FreeFlightAdmissionReason.DEVICE_IDENTITY_REQUIRED)
            return FreeFlightPrepareReply(true, FreeFlightGrantSource.DEVICE, FreeFlightAdmissionReason.ALLOWED, 1, 1)
        }
        override fun submit(request: FreeFlightFormalRequest, bearer: String?, callback: (Result<FreeFlightFormalReply>) -> Unit) {
            formalRequests += request
            if (!memberMode && request.hwid == null) {
                callback(Result.success(FreeFlightFormalReply(request.operationId, request.sessionId, request.requestFingerprint, false,
                    FreeFlightGrantSource.NONE, FreeFlightAdmissionReason.DEVICE_IDENTITY_REQUIRED, null, 1_000, deviceRequired = true)))
                return
            }
            submissions += request; this.callback = callback
        }
        fun finish(reply: FreeFlightFormalReply) { requireNotNull(callback)(Result.success(reply)) }
        fun fail(error: Exception) { requireNotNull(callback)(Result.failure(error)) }
        override suspend fun operation(request: FreeFlightOperationRequest, bearer: String?): FreeFlightOperationReply { queries++; return lookup }
        override suspend fun localStartFailed(request: FreeFlightLocalFailureRequest, bearer: String?): FreeFlightLocalFailureReply {
            failures++; failureBearers += bearer; failureRequests += request; if (failureRouteOffline) throw IOException("fixture reconciliation unavailable")
            return FreeFlightLocalFailureReply(request.operationId, 1_000, refunded = false)
        }
    }
    private class FailingPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var failCommit = false
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                override fun commit(): Boolean { val committed = editor.commit(); return committed && !failCommit }
            }
        }
    }
    companion object {
        private val owner = FreeFlightOwnerToken("user:admission-fixture", 1)
        private val device = FreeFlightDeviceIdentity("a".repeat(64), Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }))
        private fun request() = FreeFlightStartRequest("session-" + UUID.randomUUID(), owner.ownerScope,
            PlannedAirport(1L, "AAA", "Airport", "City", "Country", 31.0, 121.0), 90.0, "01A", FreeFlightScene(1, null, null))
        private fun approved(request: FreeFlightFormalRequest, member: Boolean = false, remaining: Long = 0) = FreeFlightFormalReply(
            request.operationId, request.sessionId, request.requestFingerprint, true,
            if (member) FreeFlightGrantSource.MEMBER else FreeFlightGrantSource.DEVICE, FreeFlightAdmissionReason.ALLOWED,
            if (member) null else remaining, 1_000)
    }
}
