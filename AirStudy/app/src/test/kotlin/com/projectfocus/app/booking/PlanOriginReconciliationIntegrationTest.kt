package com.projectfocus.app.booking

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.common.plan.SourceFlightPlanDates
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.FlightPlanAirportDto
import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.UpdateFlightPlanLegStatusRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import com.projectfocus.core.network.plan.FlightPlanGateway
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual controller/dispatch/runtime/three SPs; entitlement, RPC and external origin are fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PlanOriginReconciliationIntegrationTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<FlightPreparationViewModel>()
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }
    @Before fun setup() { TokenStore.set(TOKEN) }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; TokenStore.clear() }

    @Test fun `current and previous wire indices resend real pending and both reload outcomes reenter admission`() = runTest(dispatcher) {
        for (pendingIndex in listOf(7, 6)) for (failPostReload in listOf(false, true)) {
            val h = Harness(backgroundScope)
            runCurrent()
            val pendingLeg = if (pendingIndex == 7) h.day.legs.first { it.snapshot.legIndex == 7 } else leg(6, "in_progress")
            h.dispatch.complete(ActivePlanSelection(DATE, ZONE, pendingLeg, "02C")); runCurrent()
            h.gateway.replies[0].completeExceptionally(IOException("saved completion needs retry")); runCurrent()
            if (failPostReload) h.gateway.failGets += 2
            h.controller.toggle(true); runCurrent()
            assertThat(h.gateway.getCalls).isEqualTo(1)
            assertThat(h.gateway.requests).hasSize(2)
            assertThat(h.gateway.requests[1].legIndex).isEqualTo(pendingIndex) // never sorted UI position0/1.
            assertThat(h.global.current().selectionPending).isFalse()
            assertThat(h.controller.state.value.busy).isTrue()
            val ack = if (pendingIndex == 7) h.gateway.server.copy(legs = h.gateway.server.legs.map {
                if (it.legIndex == 7) it.copy(legStatus = "completed") else it
            }) else h.gateway.server
            h.gateway.ack(1, ack); runCurrent()
            assertThat(h.bridge.pendingStatus()).isNull() // real ACK removed actual dispatch SP entry.
            advanceTimeBy(499); runCurrent()
            assertThat(h.gateway.getCalls).isEqualTo(1)
            advanceTimeBy(1); runCurrent()
            assertThat(h.gateway.getCalls).isEqualTo(2)
            assertThat(h.controller.state.value.busy).isFalse()
            if (pendingIndex == 7) {
                assertThat(h.global.current().pendingSelection?.candidate?.snapshot?.legIndex).isEqualTo(9)
                assertThat(h.controller.state.value.pendingNavigation?.candidate?.snapshot?.legIndex).isEqualTo(9)
            } else {
                // The current-origin provider was NOT silently moved to the required code.
                assertThat(h.controller.state.value.pendingNavigation).isNull()
                assertThat(h.notices.last()).isEqualTo(PlanEnableMessage(PlanEnableNotice.FLY_BACK_FIRST, "PVG"))
            }
            assertThat(h.origin).isEqualTo("PEK")
        }
    }

    @Test fun `H requeues exact today receipt before network and never replaces it with current now`() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        runCurrent()
        val selection = ActivePlanSelection(DATE, ZONE, h.day.legs.first { it.snapshot.legIndex == 7 }, "02C")
        h.dispatch.complete(selection); runCurrent()
        h.gateway.replies[0].complete(null); runCurrent()
        val firstReceipt = h.bridge.exactReceiptMillis(DATE, 7)!!
        val receiptBytes = h.receiptPrefs.all.toMap()
        assertThat(h.bridge.pendingStatus()).isNull()
        h.clock.current = h.clock.current.plusHours(1)
        h.controller.toggle(true); runCurrent()
        assertThat(h.gateway.requests).hasSize(2)
        assertThat(h.bridge.pendingStatus()?.timestampMillis).isEqualTo(firstReceipt)
        assertThat(h.bridge.pendingStatus()?.mappedStatus).isEqualTo(3)
        assertThat(h.bridge.pendingStatus()?.targetStatus).isEqualTo("completed")
        assertThat(h.gateway.requests[1].actualArrivalAt).isEqualTo(PlanCompletionParameters.actualArrival(firstReceipt))
        assertThat(h.gateway.requests[1].legIndex).isEqualTo(7)
        assertThat(h.gateway.requests[1].requestId).endsWith("_r$firstReceipt")
        assertThat(h.receiptPrefs.all).isEqualTo(receiptBytes)
        h.gateway.ack(1, h.gateway.server.copy(legs = h.gateway.server.legs.map {
            if (it.legIndex == 7) it.copy(legStatus = "completed") else it
        })); runCurrent(); advanceTimeBy(500); runCurrent()
        assertThat(h.global.current().pendingSelection?.candidate?.snapshot?.legIndex).isEqualTo(9)
        assertThat(h.receiptPrefs.all).isEqualTo(receiptBytes)
    }

    @Test fun `yesterday receipt and seat navigation pending are not completion evidence`() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        runCurrent()
        val candidate = h.day.legs.first { it.snapshot.legIndex == 7 }
        h.dispatch.complete(ActivePlanSelection("2026-10-30", ZONE, candidate)); runCurrent()
        h.gateway.replies[0].complete(null); runCurrent()
        assertThat(h.bridge.exactReceiptMillis("2026-10-30", 7)).isNotNull()
        assertThat(h.bridge.exactReceiptMillis(DATE, 7)).isNull()
        h.global.beginPending(h.day, candidate)
        val navigationBytes = h.globalPrefs.all.toMap()
        h.controller.toggle(true); runCurrent()
        assertThat(h.gateway.getCalls).isEqualTo(1)
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.controller.state.value.pendingNavigation).isNull()
        assertThat(h.notices).containsExactly(PlanEnableMessage(PlanEnableNotice.FLY_BACK_FIRST, "PVG"))
        assertThat(h.globalPrefs.all).isEqualTo(navigationBytes)
        assertThat(h.global.current().selectionPending).isTrue()
    }

    @Test fun `forty actual pending polls time out only the UI switch and preserve all three durable stores`() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        runCurrent()
        val candidate = h.day.legs.first { it.snapshot.legIndex == 7 }
        h.dispatch.complete(ActivePlanSelection(DATE, ZONE, candidate)); runCurrent()
        h.gateway.replies[0].completeExceptionally(IOException("retry later")); runCurrent()
        h.global.beginPending(h.day, candidate)
        val globalBefore = h.globalPrefs.all.toMap()
        val pendingBefore = h.pendingPrefs.all.toMap()
        val receiptBefore = h.receiptPrefs.all.toMap()
        h.controller.toggle(true); runCurrent()
        assertThat(h.gateway.requests).hasSize(2)
        advanceTimeBy(19_999); runCurrent()
        assertThat(h.controller.state.value.busy).isTrue()
        assertThat(h.gateway.getCalls).isEqualTo(1)
        advanceTimeBy(1); runCurrent()
        assertThat(h.controller.state.value.busy).isFalse()
        assertThat(h.controller.state.value.switchOverride).isFalse()
        assertThat(h.notices).containsExactly(PlanEnableMessage(PlanEnableNotice.SYNCING_STATUS),
            PlanEnableMessage(PlanEnableNotice.SYNCING_STATUS)).inOrder()
        assertThat(h.globalPrefs.all).isEqualTo(globalBefore)
        assertThat(h.pendingPrefs.all).isEqualTo(pendingBefore)
        assertThat(h.receiptPrefs.all).isEqualTo(receiptBefore)
        assertThat(h.dispatch.recoverySelection()).isNotNull()
        assertThat(h.global.current().enabledDate).isEqualTo(DATE)
        assertThat(h.global.current().selectionPending).isTrue()
    }

    @Test fun `same token generation cross day and revision cancel owned replay and reject its late ACK`() = runTest(dispatcher) {
        for (change in Change.values()) {
            TokenStore.set(TOKEN)
            val h = Harness(backgroundScope, ignoreCancellation = true)
            runCurrent()
            val candidate = h.day.legs.first { it.snapshot.legIndex == 7 }
            h.dispatch.complete(ActivePlanSelection(DATE, ZONE, candidate)); runCurrent()
            h.gateway.replies[0].completeExceptionally(IOException("retry")); runCurrent()
            val pendingBefore = h.pendingPrefs.all.toMap()
            val receiptBefore = h.receiptPrefs.all.toMap()
            h.controller.toggle(true); runCurrent()
            assertThat(h.gateway.requests).hasSize(2)
            when (change) {
                Change.GENERATION -> TokenStore.set(TOKEN)
                Change.DAY -> h.clock.current = h.clock.current.plusDays(1)
                Change.REVISION -> h.revision++
            }
            advanceTimeBy(500); runCurrent()
            assertThat(h.controller.state.value.busy).isFalse()
            assertThat(h.controller.state.value.pendingNavigation).isNull()
            h.gateway.ack(1, h.gateway.server.copy(legs = h.gateway.server.legs.map { it.copy(legStatus = "completed") }))
            runCurrent()
            assertThat(h.pendingPrefs.all).isEqualTo(pendingBefore)
            assertThat(h.receiptPrefs.all).isEqualTo(receiptBefore)
            assertThat(h.emitted).isEmpty()
            assertThat(h.gateway.getCalls).isEqualTo(1)
            assertThat(h.global.current().selectionPending).isFalse()
        }
    }

    @Test fun `an old replay lease cannot cancel or remove a newer real pending entry`() = runTest(dispatcher) {
        val h = Harness(backgroundScope, ignoreCancellation = true)
        runCurrent()
        val first = ActivePlanSelection(DATE, ZONE, h.day.legs.first { it.snapshot.legIndex == 7 })
        h.dispatch.complete(first); runCurrent()
        h.gateway.replies[0].completeExceptionally(IOException("retry")); runCurrent()
        val lease = h.bridge.resendPending(); runCurrent()
        val next = ActivePlanSelection(DATE, ZONE, h.day.legs.first { it.snapshot.legIndex == 9 })
        h.dispatch.complete(next); runCurrent()
        lease.close()
        h.gateway.ack(1, h.gateway.server); runCurrent()
        assertThat(h.dispatch.recoverySelection()).isEqualTo(next)
        h.gateway.ack(2, h.gateway.server); runCurrent()
        assertThat(h.dispatch.recoverySelection()).isNull()
        assertThat(h.emitted).hasSize(1)
    }

    @Test fun `failed H pending commit restores its real bytes without sending or rewriting receipt`() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        runCurrent()
        h.dispatch.complete(ActivePlanSelection(DATE, ZONE, h.day.legs.first { it.snapshot.legIndex == 7 })); runCurrent()
        h.gateway.replies[0].complete(null); runCurrent()
        val before = h.pendingPrefs.all.toMap()
        val receipts = h.receiptPrefs.all.toMap()
        h.pendingBoundary.failNext = true
        h.controller.toggle(true); runCurrent()
        assertThat(h.notices).containsExactly(PlanEnableMessage(PlanEnableNotice.STORAGE_FAILED))
        assertThat(h.controller.state.value.busy).isFalse()
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.pendingPrefs.all).isEqualTo(before)
        assertThat(h.receiptPrefs.all).isEqualTo(receipts)
        assertThat(h.controller.state.value.pendingNavigation).isNull()
    }

    @Test fun `actual VM passes the production completion bridge and opens only the post sync candidate`() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        runCurrent()
        h.dispatch.complete(ActivePlanSelection(DATE, ZONE, h.day.legs.first { it.snapshot.legIndex == 7 })); runCurrent()
        h.gateway.replies[0].completeExceptionally(IOException("persisted retry")); runCurrent()
        val actions = object : FlightPreparationActions {
            var starts = 0
            override suspend fun findAirport(code: String) = null
            override suspend fun startFlight(plan: FlightLegPlan): Boolean { starts++; return false }
            override fun hasActiveSession() = false
            override fun currentOriginCode() = "PEK"
        }
        val store = object : ConfirmedFlightPlanStore {
            override suspend fun load(): FlightLegPlan? = null
            override suspend fun save(plan: FlightLegPlan) = error("No save during reconciliation")
            override suspend fun loadDay(date: String, timezone: String, today: String): DailyFlightPlan =
                if (date == DATE) h.remoteDay() else DailyFlightPlan(date, timezone, ownerScope = FlightPlanOwnership.scope())
            override suspend fun saveDay(day: DailyFlightPlan): DailyFlightPlan = error("No full upsert during reconciliation")
        }
        val vm = FlightPreparationViewModel(SavedStateHandle(), actions, store, PreparationSceneSource { emptyList() },
            h.clock, h.runtime, h.entitlement, h.global, h.dispatch).also(models::add)
        runCurrent()
        vm.bindPlanHost("r167-vm")
        vm.togglePlan(true); runCurrent()
        assertThat(h.gateway.getCalls).isEqualTo(2) // actual initial VM load plus initial admission refresh.
        assertThat(h.gateway.requests).hasSize(2)
        assertThat(vm.state.value.pendingGlobalSeat).isNull()
        h.gateway.ack(1, h.gateway.server.copy(legs = h.gateway.server.legs.map {
            if (it.legIndex == 7) it.copy(legStatus = "completed") else it
        })); runCurrent(); advanceTimeBy(500); runCurrent()
        assertThat(h.gateway.getCalls).isEqualTo(3)
        assertThat(vm.state.value.pendingGlobalSeat?.candidate?.snapshot?.legIndex).isEqualTo(9)
        assertThat(vm.state.value.dailyPlan?.legs?.first { it.snapshot.legIndex == 7 }?.snapshot?.legStatus).isEqualTo("completed")
        assertThat(h.bridge.pendingStatus()).isNull()
        assertThat(actions.starts).isEqualTo(0)
    }

    private inner class Harness(scope: CoroutineScope, ignoreCancellation: Boolean = false) {
        val clock = Clock()
        val globalPrefs = preferences("global")
        val pendingPrefs = preferences("pending")
        val receiptPrefs = preferences("receipt")
        val pendingBoundary = FailNextCommit(pendingPrefs)
        val global = SharedPreferencesGlobalFlightPlanStore(globalPrefs, clock)
        val runtime = DailyFlightPlanRuntime(clock)
        var day = DailyFlightPlan(DATE, ZONE, legs = listOf(leg(9, origin = "PEK"), leg(7, origin = "PVG")),
            ownerScope = FlightPlanOwnership.scope())
        val gateway = Gateway(DailyFlightPlanSnapshot(DATE, ZONE, day.legs.map { it.snapshot }), ignoreCancellation)
        val dispatch = SharedPreferencesPlanCompletionDispatch(pendingBoundary, receiptPrefs, clock, gateway, scope,
            currentBacking = { runtime.snapshot().selectedDay?.legs.orEmpty() },
            deviceTimezone = { TimeZone.getTimeZone(ZONE) }, clearActiveIfMatches = global::clearActiveIfMatches)
        val bridge = dispatch.originReconciliation
        val entitlement = Entitlement()
        var revision = 0L
        var origin = "PEK"
        val notices = mutableListOf<PlanEnableMessage>()
        val emitted = mutableListOf<DailyFlightPlan>()
        val controller = PlanEnableController(scope, entitlement, global, clock, { day }, { null },
            { revision }, { origin }, { remoteDay() }, { install(it) }, { false }, { false },
            dispatch::complete, dispatch::recoverySelection, reconciliation = bridge)
        init {
            runtime.bind(day)
            controller.bindHost("r167-controller")
            scope.launch { controller.messages.collect { notices += it } }
            scope.launch { dispatch.updates.collect { emitted += it; install(it) } }
        }
        fun install(value: DailyFlightPlan) { day = value; runtime.bind(value) }
        suspend fun remoteDay(): DailyFlightPlan {
            val date = clock.now().toLocalDate()
            val offset = SourceFlightPlanDates.get(date, ZoneId.of(ZONE)).timezoneOffsetMinutes
            val value = checkNotNull(gateway.get(GetFlightPlanRequest(date.toString(), date.toString(), ZONE,
                offset, "r167_fixture_get")))
            return DailyFlightPlans.fromSnapshot(value, day).copy(ownerScope = FlightPlanOwnership.scope())
        }
    }

    private class Gateway(var server: DailyFlightPlanSnapshot, private val ignoreCancellation: Boolean) : FlightPlanGateway {
        val requests = mutableListOf<UpdateFlightPlanLegStatusRequest>()
        val replies = mutableListOf<CompletableDeferred<DailyFlightPlanSnapshot?>>()
        val failGets = mutableSetOf<Int>()
        var getCalls = 0
        override suspend fun get(request: GetFlightPlanRequest): DailyFlightPlanSnapshot? {
            getCalls++
            if (getCalls in failGets) throw IOException("actual remote GET failure fixture")
            return server
        }
        override suspend fun updateLegStatus(request: UpdateFlightPlanLegStatusRequest): DailyFlightPlanSnapshot? {
            requests += request
            val result = CompletableDeferred<DailyFlightPlanSnapshot?>().also { replies += it }
            return if (ignoreCancellation) withContext(NonCancellable) { result.await() } else result.await()
        }
        fun ack(at: Int, value: DailyFlightPlanSnapshot?) { if (value != null) server = value; replies[at].complete(value) }
        override suspend fun upsert(request: UpsertFlightPlanRequest): DailyFlightPlanSnapshot? = error("No invented repair upsert")
        override suspend fun history(request: FlightPlanHistoryRequest): List<DailyFlightPlanSnapshot> = error("No invented history sort")
    }
    private class Entitlement : PlanEntitlementAccess {
        override val state = MutableStateFlow(PlanEntitlementState(FlightPlanOwnership.scope(), true, true)).asStateFlow()
        override fun admit() = true
        override fun refresh() = Unit
    }
    private class Clock : PreparationClock {
        var current = ZonedDateTime.parse("2026-10-31T12:34:56.789+08:00[Asia/Shanghai]")
        override fun now() = current
        override fun elapsedRealtimeMillis() = 10_000L
    }
    private class FailNextCommit(private val real: SharedPreferences) : SharedPreferences by real {
        var failNext = false
        override fun edit(): SharedPreferences.Editor {
            val editor = real.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { editor.putLong(key, value) }
                override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }
                override fun commit(): Boolean {
                    val result = editor.commit()
                    return if (failNext) { failNext = false; false } else result
                }
            }
        }
    }
    private fun preferences(kind: String): SharedPreferences = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("r167_${kind}_" + UUID.randomUUID(), Context.MODE_PRIVATE)
    private fun leg(index: Int, status: String = "waiting_takeoff", origin: String = "PEK") = DailyFlightLeg(
        FlightPlanLegSnapshot(index, airport(origin), airport(if (origin == "PEK") "PVG" else "PEK"),
            "2026-10-31T05:00:00Z", status, 100.0, "PF$index", "01A", 1, "Study"), null)
    private fun airport(code: String) = if (code == "PEK")
        FlightPlanAirportDto(1, code, "Capital", "Beijing", "40.08", "116.58", "CN", countryRegionId = 156) else
        FlightPlanAirportDto(2, code, "Pudong", "Shanghai", "31.14", "121.8", "CN", countryRegionId = 156)
    private enum class Change { GENERATION, DAY, REVISION }
    private companion object { const val DATE = "2026-10-31"; const val ZONE = "Asia/Shanghai"; const val TOKEN = "r167-own-fixture-account" }
}
