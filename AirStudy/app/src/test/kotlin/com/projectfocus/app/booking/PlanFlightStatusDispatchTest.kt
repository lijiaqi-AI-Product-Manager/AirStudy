package com.projectfocus.app.booking

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.UpdateFlightPlanLegStatusRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import com.projectfocus.core.network.plan.FlightPlanGateway
import java.io.Closeable
import java.io.IOException
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real SP/dispatcher/coroutines. RPC results and a synchronous commit interleaving are fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PlanFlightStatusDispatchTest {
    @Before fun setup() { TokenStore.set(TOKEN) }
    @After fun cleanup() { TokenStore.clear() }

    @Test fun `mode2 writes before transport and ACK retains exact pending sent flag and active selection`() = runTest {
        Harness(backgroundScope).use { h ->
            h.dispatch.markInProgress(selection(), START, TokenStore.snapshot())
            val before = h.dispatch.snapshot()!!
            assertThat(before.targetStatus).isEqualTo("in_progress")
            assertThat(before.actuallyStarted).isTrue()
            assertThat(before.inProgressSent).isFalse()
            assertThat(h.receipts.all).isEmpty()
            assertThat(h.gateway.requests).isEmpty()
            assertThat(h.dispatch.recoverySelection()).isNull() // Off/D must not complete an arbitrary mode2 record.
            runCurrent()
            val request = h.gateway.requests.single()
            assertThat(request.targetStatus).isEqualTo("in_progress")
            assertThat(request.actualArrivalAt).isNull()
            assertThat(request.arrivalDiffMinutes).isNull()
            assertThat(request.requestId).endsWith("_in_progress_r$START")
            assertThat(h.cleared).isEmpty()
            h.gateway.replies[0].complete(response(selection(), "in_progress")); runCurrent()
            val after = h.dispatch.snapshot()!!
            assertThat(after.entryId).isEqualTo(before.entryId)
            assertThat(after.inProgressSent).isTrue()
            assertThat(after.selection).isEqualTo(before.selection)
            assertThat(h.dispatch.pendingStatus()?.mappedStatus).isEqualTo(2)
            assertThat(h.cleared).isEmpty()
            assertThat(h.receipts.all).isEmpty()
            assertThat(h.updates.single().legs.single().snapshot.legStatus).isEqualTo("in_progress")
            h.dispatch.retryPendingStatus(); h.dispatch.resendPending(); runCurrent()
            assertThat(h.gateway.requests).hasSize(1) // Both L entries wait for a genuinely started, acknowledged mode2.
        }
    }

    @Test fun `unsent mode2 survives recreation and ordinary retry preserves request identity without receipt`() = runTest {
        Harness(backgroundScope).use { h ->
            h.dispatch.markInProgress(selection(), START, TokenStore.snapshot()); runCurrent()
            val first = h.gateway.requests.single()
            h.gateway.replies[0].completeExceptionally(IOException("not acknowledged")); runCurrent()
            val recreated = h.recreate(backgroundScope)
            assertThat(recreated.snapshot()?.inProgressSent).isFalse()
            assertThat(recreated.snapshot()?.actuallyStarted).isTrue()
            assertThat(recreated.recoverySelection()).isNull()
            recreated.retryPendingStatus(); runCurrent()
            assertThat(h.gateway.requests[1]).isEqualTo(first)
            h.gateway.replies[1].complete(null); runCurrent()
            assertThat(recreated.snapshot()?.inProgressSent).isTrue()
            assertThat(recreated.snapshot()?.entryId).isEqualTo(h.dispatch.snapshot()?.entryId)
            assertThat(h.receipts.all).isEmpty()
        }
    }

    @Test fun `acknowledged restored mode2 without started flag really dispatches waiting and remains retryable`() = runTest {
        Harness(backgroundScope).use { h ->
            h.seed("in_progress", sent = true, started = false)
            h.dispatch.retryPendingStatus(); runCurrent()
            val waiting = h.dispatch.snapshot()!!
            assertThat(waiting.targetStatus).isEqualTo("waiting_takeoff")
            assertThat(waiting.inProgressSent).isFalse()
            assertThat(waiting.actuallyStarted).isFalse()
            assertThat(waiting.sessionTimestampMillis).isEqualTo(START)
            assertThat(h.dispatch.pendingStatus()?.mappedStatus).isEqualTo(1)
            assertThat(h.gateway.requests.single().targetStatus).isEqualTo("waiting_takeoff")
            assertThat(h.gateway.requests.single().actualArrivalAt).isNull()
            assertThat(h.gateway.requests.single().arrivalDiffMinutes).isNull()
            assertThat(h.receipts.all).isEmpty()
            h.gateway.replies[0].completeExceptionally(IOException("keep real undo pending")); runCurrent()
            val recreated = h.recreate(backgroundScope)
            recreated.retryPendingStatus(); runCurrent()
            assertThat(h.gateway.requests[1]).isEqualTo(h.gateway.requests[0])
            h.gateway.replies[1].complete(response(selection(), "waiting_takeoff")); runCurrent()
            assertThat(recreated.snapshot()).isNull()
            assertThat(h.receipts.all).isEmpty()
            assertThat(h.cleared).contains(selection())
        }
    }

    @Test fun `abort protects same date and wire index completed pending and its original active attempt`() = runTest {
        Harness(backgroundScope).use { h ->
            h.dispatch.complete(selection()); runCurrent()
            val before = h.pending.all.toMap()
            val receipts = h.receipts.all.toMap()
            val action = h.dispatch.notifyAborted(selection(), TokenStore.snapshot())
            runCurrent()
            assertThat(action).isEqualTo(PlanAbortStatusAction.PRESERVED_COMPLETION)
            assertThat(h.pending.all).isEqualTo(before)
            assertThat(h.receipts.all).isEqualTo(receipts)
            assertThat(h.gateway.requests).hasSize(1)
            assertThat(h.gateway.requests[0].targetStatus).isEqualTo("completed")
            h.gateway.replies[0].complete(null); runCurrent()
            assertThat(h.dispatch.snapshot()).isNull()
            assertThat(h.receipts.all).isEqualTo(receipts)
        }
    }

    @Test fun `abort is waiting not canceled and an old uncancellable mode2 ACK cannot set its sent flag`() = runTest {
        Harness(backgroundScope, lateReplies = true).use { h ->
            h.dispatch.markInProgress(selection(), START, TokenStore.snapshot()); runCurrent()
            val started = h.dispatch.snapshot()!!
            val result = h.dispatch.notifyAborted(selection(), TokenStore.snapshot()); runCurrent()
            val waiting = h.dispatch.snapshot()!!
            assertThat(result).isEqualTo(PlanAbortStatusAction.QUEUED_WAITING)
            assertThat(waiting.entryId).isNotEqualTo(started.entryId)
            assertThat(waiting.targetStatus).isEqualTo("waiting_takeoff")
            assertThat(h.gateway.requests.map { it.targetStatus }).containsExactly("in_progress", "waiting_takeoff").inOrder()
            h.gateway.replies[0].complete(response(selection(), "in_progress")); runCurrent()
            assertThat(h.dispatch.snapshot()).isEqualTo(waiting)
            assertThat(h.updates).isEmpty()
            assertThat(h.receipts.all).isEmpty()
            h.gateway.replies[1].complete(response(selection(), "waiting_takeoff")); runCurrent()
            assertThat(h.dispatch.snapshot()).isNull()
            assertThat(h.updates.single().legs.single().snapshot.legStatus).isEqualTo("waiting_takeoff")
            assertThat(h.receipts.all).isEmpty()
        }
    }

    @Test fun `no-active abort uses the persisted sent gate and does not invent a missing pending candidate`() = runTest {
        Harness(backgroundScope).use { h ->
            assertThat(h.dispatch.notifyAborted(null, TokenStore.snapshot())).isEqualTo(PlanAbortStatusAction.NO_PENDING)
            h.seed("in_progress", sent = false, started = false)
            val initial = h.pending.all.toMap()
            assertThat(h.dispatch.notifyAborted(null, TokenStore.snapshot())).isEqualTo(PlanAbortStatusAction.NO_PENDING)
            assertThat(h.pending.all).isEqualTo(initial)
            assertThat(h.gateway.requests).isEmpty()
            h.seed("in_progress", sent = true, started = false)
            assertThat(h.dispatch.notifyAborted(null, TokenStore.snapshot())).isEqualTo(PlanAbortStatusAction.QUEUED_WAITING)
            runCurrent()
            assertThat(h.gateway.requests.single().targetStatus).isEqualTo("waiting_takeoff")
            assertThat(h.gateway.requests.single().legIndex).isEqualTo(7)
            assertThat(h.receipts.all).isEmpty()
            h.gateway.replies.single().complete(null); runCurrent()
            assertThat(h.dispatch.snapshot()).isNull()
        }
    }

    @Test fun `completion after mode2 alone captures receipt while preserving the original session timestamp`() = runTest {
        Harness(backgroundScope).use { h ->
            h.dispatch.markInProgress(selection(), START, TokenStore.snapshot()); runCurrent()
            h.gateway.replies[0].complete(null); runCurrent()
            assertThat(h.receipts.all).isEmpty()
            h.clock.current = h.clock.current.plusMinutes(9)
            val actual = h.clock.now().toInstant().toEpochMilli()
            h.dispatch.complete(selection()); runCurrent()
            assertThat(h.dispatch.snapshot()?.sessionTimestampMillis).isEqualTo(START)
            assertThat(h.dispatch.receiptMillis(selection().candidate)).isEqualTo(actual)
            val request = h.gateway.requests[1]
            assertThat(request.targetStatus).isEqualTo("completed")
            assertThat(request.actualArrivalAt).isEqualTo(PlanCompletionParameters.actualArrival(actual))
            assertThat(request.requestId).endsWith("_completed_r$START")
            val receiptBytes = h.receipts.all.toMap()
            h.clock.current = h.clock.current.plusMinutes(1)
            h.dispatch.complete(selection()); runCurrent()
            assertThat(h.gateway.requests).hasSize(2)
            assertThat(h.receipts.all).isEqualTo(receiptBytes)
            h.gateway.replies[1].complete(null); runCurrent()
            assertThat(h.dispatch.snapshot()).isNull()
            assertThat(h.receipts.all).isEqualTo(receiptBytes)
        }
    }

    @Test fun `failed sent flag commit restores unsent pending and explicit retry sends again without receipt`() = runTest {
        Harness(backgroundScope).use { h ->
            h.dispatch.markInProgress(selection(), START, TokenStore.snapshot()); runCurrent()
            h.boundary.returnFalseNext = true
            h.gateway.replies[0].complete(null); runCurrent()
            assertThat(h.dispatch.snapshot()?.inProgressSent).isFalse()
            assertThat(h.dispatch.diagnostic.value?.kind).isEqualTo(PlanCompletionFailure.STORAGE)
            assertThat(h.receipts.all).isEmpty()
            h.dispatch.retryPendingStatus(); runCurrent()
            assertThat(h.gateway.requests[1]).isEqualTo(h.gateway.requests[0])
            h.gateway.replies[1].complete(null); runCurrent()
            assertThat(h.dispatch.snapshot()?.inProgressSent).isTrue()
            assertThat(h.receipts.all).isEmpty()
        }
    }

    @Test fun `same-token generation during start commit rolls back only its own pending without network`() = runTest {
        Harness(backgroundScope).use { h ->
            h.seed("completed", sent = false, started = false)
            val previous = h.pending.all.toMap()
            val account = TokenStore.snapshot()
            h.boundary.afterNextCommit = { TokenStore.set(TOKEN) }
            val failure = runCatching { h.dispatch.markInProgress(selection(), START, account) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(h.pending.all).isEqualTo(previous)
            assertThat(h.receipts.all).isEmpty()
            runCurrent()
            assertThat(h.gateway.requests).isEmpty()
            assertThat(runCatching { h.dispatch.notifyAborted(selection(), account) }.exceptionOrNull())
                .isInstanceOf(IllegalStateException::class.java)
            assertThat(h.pending.all).isEqualTo(previous)
        }
    }

    @Test fun `old mode2 ACK compensation cannot erase a new-generation entry written during commit`() = runTest {
        Harness(backgroundScope).use { h ->
            h.dispatch.markInProgress(selection(), START, TokenStore.snapshot()); runCurrent()
            h.boundary.afterNextCommit = {
                TokenStore.set(TOKEN)
                h.dispatch.markInProgress(selection(9), START + 1, TokenStore.snapshot())
            }
            h.gateway.replies[0].complete(response(selection(), "in_progress")); runCurrent()
            assertThat(h.dispatch.snapshot()?.selection?.candidate?.snapshot?.legIndex).isEqualTo(9)
            assertThat(h.dispatch.snapshot()?.inProgressSent).isFalse()
            assertThat(h.gateway.requests).hasSize(2)
            assertThat(h.updates).isEmpty()
            h.gateway.replies[1].complete(null); runCurrent()
            assertThat(h.dispatch.snapshot()?.inProgressSent).isTrue()
            assertThat(h.dispatch.snapshot()?.selection?.candidate?.snapshot?.legIndex).isEqualTo(9)
            assertThat(h.receipts.all).isEmpty()
        }
    }

    @Test fun `legacy completed JSON defaults flags and expired mode2 is removed before the started wait gate`() = runTest {
        Harness(backgroundScope).use { h ->
            h.seed("completed", sent = false, started = false, omitFlags = true)
            assertThat(h.dispatch.snapshot()?.inProgressSent).isFalse()
            assertThat(h.dispatch.snapshot()?.actuallyStarted).isFalse()
            assertThat(h.dispatch.recoverySelection()).isEqualTo(selection())
            h.seed("in_progress", sent = true, started = true,
                timestamp = h.clock.now().toInstant().toEpochMilli() - SourceOriginReconciliationPolicy.MAX_AGE_MILLIS - 1)
            h.dispatch.retryPendingStatus(); runCurrent()
            assertThat(h.dispatch.snapshot()).isNull()
            assertThat(h.gateway.requests).isEmpty()
            assertThat(h.receipts.all).isEmpty()
            h.seed("canceled", sent = false, started = false)
            assertThat(runCatching { h.dispatch.snapshot() }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private inner class Harness(scope: CoroutineScope, lateReplies: Boolean = false) : Closeable {
        val pending = preferences("pending")
        val receipts = preferences("receipt")
        val boundary = CommitBoundary(pending)
        val clock = Clock()
        val gateway = Gateway(lateReplies)
        val cleared = mutableListOf<ActivePlanSelection>()
        val updates = mutableListOf<DailyFlightPlan>()
        val dispatch = recreate(scope)
        init { scope.launch { dispatch.updates.collect { updates += it } } }
        fun recreate(scope: CoroutineScope) = SharedPreferencesPlanCompletionDispatch(boundary, receipts, clock, gateway,
            scope, currentBacking = { listOf(selection().candidate, selection(9).candidate) }, clearActiveIfMatches = { cleared += it })
        fun seed(target: String, sent: Boolean, started: Boolean, timestamp: Long = START, omitFlags: Boolean = false) {
            val value = PendingPlanCompletion(FlightPlanOwnership.scope(), UUID.randomUUID().toString(), selection(),
                target, timestamp, sent, started)
            val json = JSONObject(Json { encodeDefaults = true }.encodeToString(PendingPlanCompletion.serializer(), value))
            if (omitFlags) { json.remove("inProgressSent"); json.remove("actuallyStarted") }
            check(pending.edit().putString(FlightPlanOwnership.scope() + ":pending", json.toString()).commit())
        }
        override fun close() { gateway.replies.forEach { it.complete(null) } }
    }
    private class Clock : PreparationClock {
        var current = ZonedDateTime.parse("2026-10-31T12:34:56.789+08:00[Asia/Shanghai]")
        override fun now() = current
    }
    private class Gateway(private val lateReplies: Boolean) : FlightPlanGateway {
        val requests = mutableListOf<UpdateFlightPlanLegStatusRequest>()
        val replies = mutableListOf<CompletableDeferred<DailyFlightPlanSnapshot?>>()
        override suspend fun updateLegStatus(request: UpdateFlightPlanLegStatusRequest): DailyFlightPlanSnapshot? {
            requests += request
            val reply = CompletableDeferred<DailyFlightPlanSnapshot?>().also { replies += it }
            return if (lateReplies) withContext(NonCancellable) { reply.await() } else reply.await()
        }
        override suspend fun get(request: GetFlightPlanRequest): DailyFlightPlanSnapshot? = error("No extra GET")
        override suspend fun upsert(request: UpsertFlightPlanRequest): DailyFlightPlanSnapshot? = error("No full-plan upsert")
        override suspend fun history(request: FlightPlanHistoryRequest): List<DailyFlightPlanSnapshot> = error("No history scan")
    }
    private class CommitBoundary(private val real: SharedPreferences) : SharedPreferences by real {
        var afterNextCommit: (() -> Unit)? = null
        var returnFalseNext = false
        override fun edit(): SharedPreferences.Editor {
            val editor = real.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { editor.putLong(key, value) }
                override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }
                override fun commit(): Boolean {
                    val callback = afterNextCommit; val reportFalse = returnFalseNext
                    afterNextCommit = null; returnFalseNext = false
                    val committed = editor.commit(); callback?.invoke()
                    return committed && !reportFalse
                }
            }
        }
    }
    private fun preferences(kind: String) = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("r168_status_${kind}_" + UUID.randomUUID(), Context.MODE_PRIVATE)
    private fun selection(index: Int = 7) = ActivePlanSelection(DATE, ZONE, DailyFlightLeg(
        FlightPlanLegSnapshot(index, null, null, "2026-10-31T04:00:00Z", "waiting_takeoff", 100.0,
            "PF$index", "01A", 1, "Study", expectedArrivalAt = "2026-10-31T05:00:00Z"), null), "02C")
    private fun response(selection: ActivePlanSelection, target: String) = DailyFlightPlanSnapshot(DATE, ZONE,
        listOf(selection.candidate.snapshot.copy(legStatus = target)))
    private companion object {
        const val DATE = "2026-10-31"
        const val ZONE = "Asia/Shanghai"
        const val TOKEN = "r168-own-synthetic-status-token"
        const val START = 1793420000000L
    }
}
