package com.projectfocus.app.booking

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
import com.projectfocus.core.network.plan.FlightPlanRejectedException
import java.io.IOException
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanCompletionDispatchTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private lateinit var prefs: SharedPreferences
    private lateinit var receipts: SharedPreferences
    private val clock = Clock()
    @Before fun setup() {
        TokenStore.set("completion-owner-a")
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("completion_pending_test", Context.MODE_PRIVATE)
        receipts = context.getSharedPreferences("completion_receipts_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit(); receipts.edit().clear().commit()
    }
    @After fun teardown() { TokenStore.clear() }

    @Test fun pendingAndReceiptPrecedeNetworkAndMatchingAckAlonePublishesTheOwnedDay() = runTest {
        val gateway = Gateway()
        val selection = selection()
        val now = clock.now().toInstant().toEpochMilli()
        val wrongIndex = leg(2, expected = PlanCompletionParameters.actualArrival(now - 600_000))
        val firstMatch = leg(1, expected = PlanCompletionParameters.actualArrival(now - 120_001))
        val duplicateIndex = leg(1, expected = PlanCompletionParameters.actualArrival(now + 600_000))
        val cleared = mutableListOf<ActivePlanSelection>()
        val dispatch = create(backgroundScope, gateway, backing = { listOf(wrongIndex, firstMatch, duplicateIndex) }, cleared = cleared)
        val updates = mutableListOf<DailyFlightPlan>()
        backgroundScope.launch { dispatch.updates.collect { updates.add(it) } }
        runCurrent()
        dispatch.complete(selection)
        assertThat(dispatch.recoverySelection()).isEqualTo(selection)
        assertThat(dispatch.receiptMillis(selection.candidate)).isEqualTo(now)
        assertThat(gateway.requests).isEmpty()
        assertThat(selection.candidate.snapshot.legStatus).isEqualTo("in_progress")
        assertThat(updates).isEmpty()
        assertThat(prefs.all.values.joinToString()).doesNotContain("completion-owner-a")
        runCurrent()
        val request = gateway.requests.single()
        assertThat(request.targetStatus).isEqualTo("completed")
        assertThat(request.requestId).isEqualTo("req_leg_2026-10-31_1_completed")
        assertThat(request.actualArrivalAt).isEqualTo("2026-10-31T04:34:56.789Z")
        assertThat(request.arrivalDiffMinutes).isEqualTo(2)
        assertThat(request.timezoneOffsetMinutes).isEqualTo(480)
        assertThat(request.flightNo).isEqualTo("PF8")
        // Explicit empty G seat is not the old candidate seat or inherited seat.
        assertThat(request.seatNo).isNull()
        assertThat(cleared).isEmpty()
        gateway.replies[0].complete(response(selection))
        runCurrent()
        assertThat(dispatch.recoverySelection()).isNull()
        assertThat(cleared).containsExactly(selection)
        assertThat(updates).hasSize(1)
        assertThat(updates.single().ownerScope).isEqualTo(FlightPlanOwnership.scope())
        assertThat(updates.single().legs.single().snapshot.legStatus).isEqualTo("completed")
        assertThat(dispatch.receiptMillis(selection.candidate)).isEqualTo(now)
    }

    @Test fun nullableAckAndTerminalConflictClearTheirExactPendingWithoutInventingAList() = runTest {
        val gateway = Gateway()
        val cleared = mutableListOf<ActivePlanSelection>()
        val dispatch = create(backgroundScope, gateway, cleared = cleared)
        val updates = mutableListOf<DailyFlightPlan>()
        backgroundScope.launch { dispatch.updates.collect { updates.add(it) } }
        runCurrent()
        val first = selection()
        dispatch.complete(first); runCurrent()
        assertThat(gateway.requests[0].arrivalDiffMinutes).isNull() // empty backing, not old active expected.
        gateway.replies[0].complete(null); runCurrent()
        assertThat(dispatch.recoverySelection()).isNull()
        assertThat(cleared).containsExactly(first)
        assertThat(updates).isEmpty()
        val second = selection(2).copy(seatNo = "02C")
        dispatch.complete(second); runCurrent()
        assertThat(gateway.requests[1].seatNo).isEqualTo("02C")
        gateway.replies[1].completeExceptionally(FlightPlanRejectedException("terminal", "LEG_TERMINAL_CONFLICT"))
        runCurrent()
        assertThat(dispatch.recoverySelection()).isNull()
        assertThat(dispatch.diagnostic.value?.kind).isEqualTo(PlanCompletionFailure.TERMINAL_CONFLICT)
        assertThat(cleared).containsExactly(first, second).inOrder()
        assertThat(updates).isEmpty()
    }

    @Test fun networkFailureReconstructsFromRealPrefsAndRetryKeepsReceiptAndRequestIdentity() = runTest {
        val gateway = Gateway()
        val first = create(backgroundScope, gateway)
        val selection = selection()
        first.complete(selection); runCurrent()
        val request = gateway.requests.single()
        gateway.replies.single().completeExceptionally(IOException("fixture connection failure")); runCurrent()
        assertThat(first.recoverySelection()).isEqualTo(selection)
        assertThat(first.diagnostic.value?.kind).isEqualTo(PlanCompletionFailure.FAILED)
        clock.current = clock.current.plusHours(2)
        val recreated = create(backgroundScope, gateway)
        val restored = recreated.recoverySelection()!!
        assertThat(recreated.diagnostic.value?.kind).isEqualTo(PlanCompletionFailure.FAILED)
        recreated.complete(restored); runCurrent()
        assertThat(gateway.requests[1].actualArrivalAt).isEqualTo(request.actualArrivalAt)
        assertThat(gateway.requests[1].requestId).isEqualTo(request.requestId)
        gateway.replies[1].complete(null); runCurrent()
        assertThat(recreated.recoverySelection()).isNull()
        assertThat(recreated.diagnostic.value).isNull()
    }

    @Test fun failedPendingAndSecondReceiptCommitDoNotPretendBothFilesRolledBackAtomically() = runTest {
        val gateway = Gateway()
        val pendingFailure = create(backgroundScope, gateway, pending = failCommit(prefs, 1))
        var failed = false
        try { pendingFailure.complete(selection()) } catch (_: IOException) { failed = true }
        assertThat(failed).isTrue()
        assertThat(pendingFailure.recoverySelection()).isNull()
        assertThat(gateway.requests).isEmpty()

        val receiptFailure = create(backgroundScope, gateway, receipt = failCommit(receipts, 1))
        failed = false
        try { receiptFailure.complete(selection()) } catch (_: IOException) { failed = true }
        assertThat(failed).isTrue()
        assertThat(receiptFailure.recoverySelection()).isEqualTo(selection())
        assertThat(receiptFailure.receiptMillis(selection().candidate)).isNull()
        assertThat(receiptFailure.diagnostic.value?.kind).isEqualTo(PlanCompletionFailure.STORAGE)
        assertThat(gateway.requests).isEmpty()
        clock.current = clock.current.plusMinutes(1)
        val repaired = create(backgroundScope, gateway)
        repaired.complete(repaired.recoverySelection()!!); runCurrent()
        assertThat(gateway.requests).hasSize(1)
        gateway.replies.single().complete(null); runCurrent()
        assertThat(repaired.recoverySelection()).isNull()
    }

    @Test fun failedAckCleanupKeepsTheExactPendingAndReceiptForExplicitRetry() = runTest {
        val gateway = Gateway()
        val dispatch = create(backgroundScope, gateway, pending = failCommit(prefs, 2))
        dispatch.complete(selection()); runCurrent()
        val receipt = dispatch.receiptMillis(selection().candidate)
        gateway.replies[0].complete(null); runCurrent()
        assertThat(dispatch.recoverySelection()).isEqualTo(selection())
        assertThat(dispatch.diagnostic.value?.kind).isEqualTo(PlanCompletionFailure.STORAGE)
        assertThat(dispatch.receiptMillis(selection().candidate)).isEqualTo(receipt)
        dispatch.complete(selection()); runCurrent()
        gateway.replies[1].complete(null); runCurrent()
        assertThat(dispatch.recoverySelection()).isNull()
    }

    @Test fun oldEntryAndOldAccountRepliesCannotDeleteTheNewPendingOrPublishTheirDays() = runTest {
        val gateway = Gateway(ignoreCancellation = true)
        val cleared = mutableListOf<ActivePlanSelection>()
        val dispatch = create(backgroundScope, gateway, cleared = cleared)
        val updates = mutableListOf<DailyFlightPlan>()
        backgroundScope.launch { dispatch.updates.collect { updates.add(it) } }; runCurrent()
        val first = selection(1); val second = selection(2)
        dispatch.complete(first); runCurrent()
        dispatch.complete(second); runCurrent()
        gateway.replies[0].complete(response(first)); runCurrent()
        assertThat(dispatch.recoverySelection()).isEqualTo(second)
        assertThat(updates).isEmpty()
        assertThat(cleared).isEmpty()
        TokenStore.set("completion-owner-b")
        gateway.replies[1].complete(response(second)); runCurrent()
        assertThat(dispatch.recoverySelection()).isNull()
        assertThat(updates).isEmpty()
        TokenStore.set("completion-owner-a") // same token hash, new generation permits explicit recovery.
        assertThat(dispatch.recoverySelection()).isEqualTo(second)
        dispatch.complete(second); runCurrent()
        gateway.replies[2].complete(response(second)); runCurrent()
        assertThat(dispatch.recoverySelection()).isNull()
        assertThat(cleared).containsExactly(second)
    }

    @Test fun wrongDayAckStaysPendingAndReceiptFallbackIsOrderedAndNeverUnscoped() = runTest {
        val gateway = Gateway()
        val dispatch = create(backgroundScope, gateway)
        val fallback = selection().copy(localDate = "2026-10-30")
        val oldReceipt = clock.now().toInstant().toEpochMilli()
        dispatch.complete(fallback); runCurrent()
        assertThat(dispatch.receiptMillis(fallback.candidate)).isEqualTo(oldReceipt)
        gateway.replies[0].complete(response(fallback).copy(localPlanDate = "2026-10-31")); runCurrent()
        assertThat(dispatch.recoverySelection()).isEqualTo(fallback)
        assertThat(dispatch.diagnostic.value?.kind).isEqualTo(PlanCompletionFailure.INVALID_ACK)
        receipts.edit().putLong("2026-10-31_1", 123L).commit()
        TokenStore.set("completion-owner-b")
        assertThat(dispatch.receiptMillis(fallback.candidate)).isNull()
        TokenStore.set("completion-owner-a")
        clock.current = clock.current.plusSeconds(1)
        val firstDate = selection()
        dispatch.complete(firstDate); runCurrent()
        assertThat(dispatch.receiptMillis(firstDate.candidate)).isEqualTo(oldReceipt + 1_000)
        assertThat(dispatch.receiptMillis(firstDate.candidate.copy(snapshot = firstDate.candidate.snapshot.copy(scheduledDepartureAt = "invalid")))).isNull()
        gateway.replies[1].complete(null); runCurrent()
    }

    private fun create(scope: CoroutineScope, gateway: Gateway, pending: SharedPreferences = prefs,
                       receipt: SharedPreferences = receipts, backing: () -> List<DailyFlightLeg> = { emptyList() },
                       cleared: MutableList<ActivePlanSelection> = mutableListOf()) =
        SharedPreferencesPlanCompletionDispatch(pending, receipt, clock, gateway, scope,
            currentBacking = backing, deviceTimezone = { TimeZone.getTimeZone(ZONE) },
            clearActiveIfMatches = { cleared.add(it) })

    private fun failCommit(storage: SharedPreferences, at: Int): SharedPreferences {
        var commits = 0
        return object : SharedPreferences by storage {
            override fun edit(): SharedPreferences.Editor {
                val real = storage.edit()
                return object : SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { real.putString(key, value); return this }
                    override fun putLong(key: String?, value: Long): SharedPreferences.Editor { real.putLong(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { real.remove(key); return this }
                    override fun commit(): Boolean { real.commit(); return ++commits != at }
                }
            }
        }
    }
    private fun selection(index: Int = 1) = ActivePlanSelection(DATE, ZONE, leg(index))
    private fun leg(index: Int, expected: String = "2026-10-31T05:00:00Z") = DailyFlightLeg(
        FlightPlanLegSnapshot(index, null, null, "2026-10-31T04:00:00Z", "in_progress", 100.0,
            " PF8 ", "old-candidate-seat", 1, "学习", expectedArrivalAt = expected), null)
    private fun response(selection: ActivePlanSelection) = DailyFlightPlanSnapshot(selection.localDate, selection.timezone,
        listOf(selection.candidate.snapshot.copy(legStatus = "completed")), planDate = "2026-10-30")

    private class Clock : PreparationClock {
        var current = ZonedDateTime.parse("2026-10-31T12:34:56.789+08:00[Asia/Shanghai]")
        override fun now() = current
    }
    private class Gateway(private val ignoreCancellation: Boolean = false) : FlightPlanGateway {
        val requests = mutableListOf<UpdateFlightPlanLegStatusRequest>()
        val replies = mutableListOf<CompletableDeferred<DailyFlightPlanSnapshot?>>()
        override suspend fun updateLegStatus(request: UpdateFlightPlanLegStatusRequest): DailyFlightPlanSnapshot? {
            requests.add(request)
            val reply = CompletableDeferred<DailyFlightPlanSnapshot?>().also { replies.add(it) }
            return if (ignoreCancellation) withContext(NonCancellable) { reply.await() } else reply.await()
        }
        override suspend fun get(request: GetFlightPlanRequest): DailyFlightPlanSnapshot? = error("No hidden GET in C dispatch")
        override suspend fun upsert(request: UpsertFlightPlanRequest): DailyFlightPlanSnapshot? = error("No hidden full upsert in C dispatch")
        override suspend fun history(request: FlightPlanHistoryRequest): List<DailyFlightPlanSnapshot> = error("No hidden history in C dispatch")
    }
    private companion object { const val DATE = "2026-10-31"; const val ZONE = "Asia/Shanghai" }
}
