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
import java.io.IOException
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual production dispatcher/SP/receipts. Only server replies and disk faults are fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PlanSessionStatusCommandTest {
    @Before fun setup() { TokenStore.set(TOKEN) }
    @After fun cleanup() { TokenStore.clear() }

    @Test fun completionReplay() = runTest {
        val h = Harness(backgroundScope)
        val command = command(AcceptedPlanStatus.COMPLETED)
        h.dispatch.handoff(command, TokenStore.snapshot()); runCurrent()
        val actual = h.gateway.requests.single().actualArrivalAt
        val receipt = h.receipts.all.toMap()
        assertThat(h.dispatch.exactReceiptMillis(DATE, 7)).isEqualTo(END)
        h.gateway.replies.single().complete(null); runCurrent()
        assertThat(h.dispatch.pendingStatus()).isNull()
        h.clock.now = h.clock.now.plusDays(2)
        val restored = h.newDispatch()
        restored.handoff(command, TokenStore.snapshot()); runCurrent()
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.receipts.all).isEqualTo(receipt)
        assertThat(actual).isEqualTo(PlanCompletionParameters.actualArrival(END))
        assertThat(runCatching {
            restored.handoff(command.copy(completedAtMillis = END + 1), TokenStore.snapshot())
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun acknowledgedStartReplay() = runTest {
        val h = Harness(backgroundScope)
        h.dispatch.handoff(command(AcceptedPlanStatus.STARTED), TokenStore.snapshot()); runCurrent()
        val entry = h.dispatch.snapshot()!!.entryId
        h.gateway.replies.single().complete(null); runCurrent()
        h.newDispatch().handoff(command(AcceptedPlanStatus.STARTED), TokenStore.snapshot()); runCurrent()
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.dispatch.snapshot()!!.entryId).isEqualTo(entry)
        assertThat(h.dispatch.snapshot()!!.inProgressSent).isTrue()
        assertThat(h.receipts.all).isEmpty()
    }

    @Test fun terminalSupersedesStart() = runTest {
        val h = Harness(backgroundScope)
        h.dispatch.handoff(command(AcceptedPlanStatus.STARTED), TokenStore.snapshot()); runCurrent()
        h.dispatch.handoff(command(AcceptedPlanStatus.COMPLETED), TokenStore.snapshot()); runCurrent()
        val terminal = h.dispatch.snapshot()!!.entryId
        assertThat(h.gateway.requests.map { it.targetStatus }).containsExactly("in_progress", "completed").inOrder()
        h.gateway.replies[0].complete(null); runCurrent()
        assertThat(h.dispatch.snapshot()!!.entryId).isEqualTo(terminal)
        h.gateway.replies[1].complete(null); runCurrent()
        assertThat(h.dispatch.snapshot()).isNull()
        assertThat(h.dispatch.exactReceiptMillis(DATE, 7)).isEqualTo(END)
    }

    @Test fun abortProtectsCompletion() = runTest {
        val h = Harness(backgroundScope)
        h.dispatch.handoff(command(AcceptedPlanStatus.COMPLETED), TokenStore.snapshot()); runCurrent()
        val before = h.dispatch.snapshot()
        val receipt = h.receipts.all.toMap()
        h.dispatch.handoff(command(AcceptedPlanStatus.ABORTED), TokenStore.snapshot()); runCurrent()
        assertThat(h.dispatch.snapshot()).isEqualTo(before)
        assertThat(h.receipts.all).isEqualTo(receipt)
        assertThat(h.gateway.requests.map { it.targetStatus }).containsExactly("completed")
    }

    @Test fun failedAtomicJournal() = runTest {
        val h = Harness(backgroundScope)
        h.storage.failNext = true
        assertThat(runCatching {
            h.dispatch.handoff(command(AcceptedPlanStatus.STARTED), TokenStore.snapshot())
        }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(h.realStorage.all).isEmpty()
        assertThat(h.receipts.all).isEmpty()
        assertThat(h.gateway.requests).isEmpty()
        h.dispatch.handoff(command(AcceptedPlanStatus.STARTED), TokenStore.snapshot()); runCurrent()
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.realStorage.all.keys.count { ":accepted-command:" in it }).isEqualTo(1)
    }

    @Test fun exactGenerationCompensation() = runTest {
        val h = Harness(backgroundScope)
        val stale = TokenStore.snapshot()
        h.storage.afterNext = { TokenStore.set(TOKEN) }
        assertThat(runCatching {
            h.dispatch.handoff(command(AcceptedPlanStatus.STARTED), stale)
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(h.realStorage.all).isEmpty()
        assertThat(h.gateway.requests).isEmpty()
        h.dispatch.handoff(command(AcceptedPlanStatus.STARTED), TokenStore.snapshot()); runCurrent()
        assertThat(h.gateway.requests).hasSize(1)
    }

    @Test fun receiptFailureRecreation() = runTest {
        val h = Harness(backgroundScope)
        h.receiptStorage.failNext = true
        assertThat(runCatching {
            h.dispatch.handoff(command(AcceptedPlanStatus.COMPLETED), TokenStore.snapshot())
        }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(h.dispatch.pendingStatus()?.targetStatus).isEqualTo("completed")
        assertThat(h.gateway.requests).isEmpty()
        h.clock.now = h.clock.now.plusHours(3)
        h.newDispatch().handoff(command(AcceptedPlanStatus.COMPLETED), TokenStore.snapshot()); runCurrent()
        assertThat(h.gateway.requests.single().actualArrivalAt).isEqualTo(PlanCompletionParameters.actualArrival(END))
        assertThat(h.dispatch.exactReceiptMillis(DATE, 7)).isEqualTo(END)
    }

    @Test fun receiptGenerationCompensation() = runTest {
        val h = Harness(backgroundScope)
        val old = TokenStore.snapshot()
        h.receiptStorage.afterNext = { TokenStore.set(TOKEN) }
        assertThat(runCatching {
            h.dispatch.handoff(command(AcceptedPlanStatus.COMPLETED), old)
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(h.receipts.all).isEmpty()
        assertThat(h.gateway.requests).isEmpty()
    }

    @Test fun noActiveSentCompletedFallsBack() = runTest {
        val h = Harness(backgroundScope)
        h.dispatch.handoff(command(AcceptedPlanStatus.STARTED), TokenStore.snapshot()); runCurrent()
        h.gateway.replies.single().complete(null); runCurrent()
        h.dispatch.handoff(command(AcceptedPlanStatus.COMPLETED), TokenStore.snapshot()); runCurrent()
        val receipt = h.receipts.all.toMap()
        assertThat(h.dispatch.snapshot()!!.inProgressSent).isTrue()
        h.active = null
        h.dispatch.handoff(command(AcceptedPlanStatus.ABORTED), TokenStore.snapshot()); runCurrent()
        assertThat(h.gateway.requests.last().targetStatus).isEqualTo("waiting_takeoff")
        assertThat(h.gateway.requests.last().actualArrivalAt).isNull()
        assertThat(h.dispatch.snapshot()!!.sessionTimestampMillis).isEqualTo(START)
        assertThat(h.receipts.all).isEqualTo(receipt)
    }

    @Test fun noActiveUnsentCompletedDoesNothing() = runTest {
        val h = Harness(backgroundScope)
        h.dispatch.handoff(command(AcceptedPlanStatus.COMPLETED), TokenStore.snapshot()); runCurrent()
        val pending = h.dispatch.snapshot()
        val receipt = h.receipts.all.toMap()
        h.active = null
        h.dispatch.handoff(command(AcceptedPlanStatus.ABORTED), TokenStore.snapshot()); runCurrent()
        assertThat(h.dispatch.snapshot()).isEqualTo(pending)
        assertThat(h.gateway.requests.map { it.targetStatus }).containsExactly("completed")
        assertThat(h.receipts.all).isEqualTo(receipt)
    }

    @Test fun currentActiveSelectsProtectedLeg() = runTest {
        val h = Harness(backgroundScope)
        h.dispatch.handoff(command(AcceptedPlanStatus.COMPLETED), TokenStore.snapshot()); runCurrent()
        val original = command(AcceptedPlanStatus.STARTED).selection
        h.active = original.copy(candidate = original.candidate.copy(
            snapshot = original.candidate.snapshot.copy(legIndex = 9)))
        h.dispatch.handoff(command(AcceptedPlanStatus.ABORTED), TokenStore.snapshot()); runCurrent()
        assertThat(h.gateway.requests.last().targetStatus).isEqualTo("waiting_takeoff")
        assertThat(h.gateway.requests.last().legIndex).isEqualTo(9)
        assertThat(h.dispatch.snapshot()!!.selection).isEqualTo(h.active)
    }

    private class Clock : PreparationClock {
        var now = ZonedDateTime.parse("2026-10-31T12:00:00+08:00[Asia/Shanghai]")
        override fun now() = now
    }

    private class Harness(val scope: CoroutineScope) {
        val realStorage = prefs()
        val storage = CommitBoundary(realStorage)
        val receipts = prefs()
        val receiptStorage = CommitBoundary(receipts)
        val gateway = Gateway()
        val clock = Clock()
        var active: ActivePlanSelection? = command(AcceptedPlanStatus.STARTED).selection
        val dispatch = newDispatch()
        fun newDispatch() = SharedPreferencesPlanCompletionDispatch(storage, receiptStorage, clock, gateway, scope,
            currentActiveForAbort = { active })
    }

    private class Gateway : FlightPlanGateway {
        val requests = mutableListOf<UpdateFlightPlanLegStatusRequest>()
        val replies = mutableListOf<CompletableDeferred<DailyFlightPlanSnapshot?>>()
        override suspend fun updateLegStatus(request: UpdateFlightPlanLegStatusRequest): DailyFlightPlanSnapshot? {
            requests += request
            return CompletableDeferred<DailyFlightPlanSnapshot?>().also { replies += it }.await()
        }
        override suspend fun get(request: GetFlightPlanRequest): DailyFlightPlanSnapshot? = error("No GET")
        override suspend fun upsert(request: UpsertFlightPlanRequest): DailyFlightPlanSnapshot? = error("No upsert")
        override suspend fun history(request: FlightPlanHistoryRequest): List<DailyFlightPlanSnapshot> = error("No history scan")
    }

    private class CommitBoundary(private val real: SharedPreferences) : SharedPreferences by real {
        var failNext = false
        var afterNext: (() -> Unit)? = null
        override fun edit(): SharedPreferences.Editor {
            val editor = real.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { editor.putLong(key, value) }
                override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }
                override fun commit(): Boolean {
                    val reportFalse = failNext; val callback = afterNext
                    failNext = false; afterNext = null
                    val result = editor.commit(); callback?.invoke()
                    return result && !reportFalse
                }
            }
        }
    }

    private companion object {
        const val DATE = "2026-10-31"
        const val ZONE = "Asia/Shanghai"
        const val TOKEN = "r168-synthetic-accepted-command"
        const val START = 1793419200000L
        const val END = START + 60_000L
        fun prefs() = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("r168_owned_command_" + UUID.randomUUID(), Context.MODE_PRIVATE)
        fun command(status: AcceptedPlanStatus) = AcceptedPlanStatusCommand("owned-flight-one",
            ActivePlanSelection(DATE, ZONE, DailyFlightLeg(FlightPlanLegSnapshot(7, null, null,
                "2026-10-31T04:00:00Z", "waiting_takeoff", 100.0, "PF7", "01A", 1, "Study"), null), "02C"),
            START, status, END.takeIf { status == AcceptedPlanStatus.COMPLETED })
    }
}
