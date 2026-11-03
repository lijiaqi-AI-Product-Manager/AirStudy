package com.projectfocus.app.booking

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.FlightPlanAirportDto
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** New tests run first against frozen v1, then unchanged against the append fix. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PlanReconciliationAppendTest {
    @Before fun setup() { TokenStore.set(TOKEN) }
    @After fun cleanup() { TokenStore.clear() }

    @Test fun `source q reset really retries an active request and old lease cannot cancel its replacement`() = runTest {
        Harness(backgroundScope).use { h ->
            h.dispatch.complete(selection(7)); runCurrent()
            assertThat(h.gateway.requests).hasSize(1)
            val old = h.bridge.resendPending(); runCurrent()
            assertThat(h.gateway.requests).hasSize(2) // v1 borrowed the active attempt and sent nothing.
            val latest = h.bridge.resendPending(); runCurrent()
            assertThat(h.gateway.requests).hasSize(3)
            old.close(); runCurrent()
            h.gateway.replies[2].complete(null); runCurrent()
            assertThat(h.bridge.pendingStatus()).isNull() // Latest real ACK was not cancelled by old.close().
            h.gateway.replies[0].complete(null)
            h.gateway.replies[1].complete(null); runCurrent()
            assertThat(h.bridge.pendingStatus()).isNull()
            latest.close()
        }
    }

    @Test fun `closing the new replay lease rejects late ACK but preserves pending and receipt bytes`() = runTest {
        Harness(backgroundScope).use { h ->
            h.dispatch.complete(selection(7)); runCurrent()
            val pending = h.pending.all.toMap()
            val receipts = h.receipts.all.toMap()
            val owned = h.bridge.resendPending(); runCurrent()
            assertThat(h.gateway.requests).hasSize(2)
            owned.close(); runCurrent()
            h.gateway.replies[0].complete(null)
            h.gateway.replies[1].complete(null); runCurrent()
            assertThat(h.pending.all).isEqualTo(pending)
            assertThat(h.receipts.all).isEqualTo(receipts)
            assertThat(h.bridge.pendingStatus()?.legIndex).isEqualTo(7)
        }
    }

    @Test fun `ordinary completion of the identical active entry keeps its existing coalescing behavior`() = runTest {
        Harness(backgroundScope).use { h ->
            val request = selection(7)
            h.dispatch.complete(request); runCurrent()
            val pending = h.pending.all.toMap()
            val receipts = h.receipts.all.toMap()
            h.dispatch.complete(request); runCurrent()
            assertThat(h.gateway.requests).hasSize(1) // Force applies to source x replay, not every complete().
            assertThat(h.pending.all).isEqualTo(pending)
            assertThat(h.receipts.all).isEqualTo(receipts)
            h.gateway.replies.single().complete(null); runCurrent()
            assertThat(h.bridge.pendingStatus()).isNull()
        }
    }

    @Test fun `H commit changing same token generation restores absent or previous real pending without sending`() = runTest {
        for (previousPending in listOf(false, true)) Harness(backgroundScope).use { h ->
            val receipt = makeReceipt(h, 7)
            if (previousPending) {
                h.dispatch.complete(selection(9)); runCurrent()
                h.gateway.replies.last().completeExceptionally(IOException("persist previous completion")); runCurrent()
            }
            val pending = h.pending.all.toMap()
            val receipts = h.receipts.all.toMap()
            val callCount = h.gateway.requests.size
            val before = TokenStore.snapshot()
            h.boundary.afterNextCommit = { TokenStore.set(TOKEN) }
            val failure = runCatching { h.bridge.requeueFromReceipt(selection(7), receipt) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(TokenStore.snapshot().token).isEqualTo(before.token)
            assertThat(TokenStore.snapshot().generation).isGreaterThan(before.generation)
            assertThat(h.pending.all).isEqualTo(pending) // v1 leaves its rejected H entry at the same scope.
            assertThat(h.receipts.all).isEqualTo(receipts)
            runCurrent()
            assertThat(h.gateway.requests).hasSize(callCount)
        }
    }

    @Test fun `H identity rollback never restores over a replacement written inside the real commit`() = runTest {
        Harness(backgroundScope).use { h ->
            val receipt = makeReceipt(h, 7)
            val receipts = h.receipts.all.toMap()
            val replacement = selection(9)
            h.boundary.afterNextCommit = {
                TokenStore.set(TOKEN)
                h.dispatch.complete(replacement) // Reentrant actual new-generation writer; no fake JSON.
            }
            val failure = runCatching { h.bridge.requeueFromReceipt(selection(7), receipt) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(h.dispatch.recoverySelection()).isEqualTo(replacement)
            assertThat(h.bridge.pendingStatus()?.legIndex).isEqualTo(9)
            // The replacement legitimately captures its own date/index receipt; H's old receipt stays exact.
            val oldKeyValues = receipts.filterKeys { it.endsWith("${DATE}_7") || it.endsWith("${DATE}_7:entry") }
            assertThat(h.receipts.all.filterKeys { it in oldKeyValues }).isEqualTo(oldKeyValues)
            runCurrent()
            assertThat(h.gateway.requests.last().legIndex).isEqualTo(9)
            h.gateway.replies.last().completeExceptionally(IOException("leave replacement recoverable")); runCurrent()
            assertThat(h.dispatch.recoverySelection()).isEqualTo(replacement)
        }
    }

    @Test fun `H false commit cannot overwrite a replacement entry even when preferences already mutated`() = runTest {
        Harness(backgroundScope).use { h ->
            val receipt = makeReceipt(h, 7)
            val replacement = selection(9)
            h.boundary.returnFalseNext = true
            h.boundary.afterNextCommit = {
                TokenStore.set(TOKEN)
                h.dispatch.complete(replacement)
            }
            val failure = runCatching { h.bridge.requeueFromReceipt(selection(7), receipt) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IOException::class.java)
            assertThat(h.dispatch.recoverySelection()).isEqualTo(replacement) // v1 generic rollback removes it.
            assertThat(h.bridge.pendingStatus()?.legIndex).isEqualTo(9)
            runCurrent()
            assertThat(h.gateway.requests.last().legIndex).isEqualTo(9)
            h.gateway.replies.last().completeExceptionally(IOException("keep newer writer")); runCurrent()
            assertThat(h.dispatch.recoverySelection()).isEqualTo(replacement)
        }
    }

    private fun TestScope.makeReceipt(h: Harness, index: Int): Long {
        h.dispatch.complete(selection(index)); runCurrent()
        h.gateway.replies.last().complete(null); runCurrent()
        assertThat(h.bridge.pendingStatus()).isNull()
        return checkNotNull(h.bridge.exactReceiptMillis(DATE, index))
    }

    private inner class Harness(scope: CoroutineScope) : Closeable {
        val pending = preferences("pending")
        val receipts = preferences("receipts")
        val boundary = CommitBoundary(pending)
        val gateway = Gateway()
        val clock = PreparationClock { ZonedDateTime.parse("2026-10-31T12:34:56.789+08:00[Asia/Shanghai]") }
        val dispatch = SharedPreferencesPlanCompletionDispatch(boundary, receipts, clock, gateway, scope,
            currentBacking = { listOf(selection(7).candidate, selection(9).candidate) })
        val bridge = dispatch.originReconciliation
        override fun close() {
            // Always unblock NonCancellable fixture replies before runTest cancellation,
            // including a RED assertion on v1: no hanging test caused by an orphan fixture.
            gateway.replies.forEach { it.complete(null) }
        }
    }

    private class Gateway : FlightPlanGateway {
        val requests = mutableListOf<UpdateFlightPlanLegStatusRequest>()
        val replies = mutableListOf<CompletableDeferred<DailyFlightPlanSnapshot?>>()
        override suspend fun updateLegStatus(request: UpdateFlightPlanLegStatusRequest): DailyFlightPlanSnapshot? {
            requests += request
            val reply = CompletableDeferred<DailyFlightPlanSnapshot?>().also { replies += it }
            return withContext(NonCancellable) { reply.await() } // Emulate a late response after job cancellation.
        }
        override suspend fun get(request: GetFlightPlanRequest): DailyFlightPlanSnapshot? = error("No unrelated GET")
        override suspend fun upsert(request: UpsertFlightPlanRequest): DailyFlightPlanSnapshot? = error("No repair upsert")
        override suspend fun history(request: FlightPlanHistoryRequest): List<DailyFlightPlanSnapshot> = error("No history scan")
    }

    /** Real SP commits first; one deterministic callback is the controlled interleaving. */
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
                    val callback = afterNextCommit
                    val reportFalse = returnFalseNext
                    afterNextCommit = null
                    returnFalseNext = false
                    val committed = editor.commit()
                    callback?.invoke()
                    return committed && !reportFalse
                }
            }
        }
    }

    private fun preferences(kind: String) = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("r167_append_${kind}_" + UUID.randomUUID(), Context.MODE_PRIVATE)
    private fun selection(index: Int) = ActivePlanSelection(DATE, ZONE, DailyFlightLeg(
        FlightPlanLegSnapshot(index, airport("PEK", 1), airport("PVG", 2), "2026-10-31T05:00:00Z",
            "waiting_takeoff", 100.0, "PF$index", "01A", 1, "Study"), null), "01A")
    private fun airport(code: String, id: Int) = FlightPlanAirportDto(id, code, code, code,
        if (id == 1) "40.08" else "31.14", if (id == 1) "116.58" else "121.8", "CN", countryRegionId = 156)
    private companion object {
        const val DATE = "2026-10-31"
        const val ZONE = "Asia/Shanghai"
        const val TOKEN = "r167-append-own-synthetic-account"
    }
}
