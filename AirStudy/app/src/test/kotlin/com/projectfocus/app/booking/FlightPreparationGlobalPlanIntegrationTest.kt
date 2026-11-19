package com.projectfocus.app.booking

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedDeparture
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.SubscriptionStatusSnapshot
import com.projectfocus.core.network.dto.UpdateFlightPlanLegStatusRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import com.projectfocus.core.network.plan.FlightPlanGateway
import com.projectfocus.core.network.subscription.SubscriptionStatusGateway
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real nine-parameter VM + Runtime + two real SP stores; no fake completion flow. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlightPreparationGlobalPlanIntegrationTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<FlightPreparationViewModel>()
    @get:Rule val mainDispatcher = object : TestWatcher() {
        override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }
    @Before fun setup() { TokenStore.set(OWNER_A) }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; TokenStore.clear() }

    @Test fun qualifiedOnAndSeatResultChangeOnlyGlobalMetadataUntilExplicitStartUsesInheritedSeat() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        advanceUntilIdle()
        val originalDay = h.vm.state.value.dailyPlan!!
        val original = originalDay.legs.single()
        assertThat(h.vm.state.value.confirmed).isTrue()
        h.vm.bindPlanHost("vm-fixture")
        h.vm.togglePlan(true); runCurrent()
        val pending = h.vm.state.value.pendingGlobalSeat!!
        assertThat(h.reopen().enabledDate).isEqualTo(DATE)
        assertThat(h.reopen().selectionPending).isTrue()
        assertThat(h.reopen().activeSelection).isNull()
        assertThat(h.store.savedDays).isEmpty()
        assertThat(h.actions.starts).isEmpty()
        h.vm.globalSeatNavigationOpened(pending.requestId)
        assertThat(h.vm.acceptGlobalSeat(pending.requestId, " 02C ")).isTrue()
        runCurrent()
        val global = h.reopen()
        assertThat(global.selectionPending).isFalse()
        assertThat(global.pendingSelection).isNull()
        assertThat(global.inheritedSeat).isEqualTo("02C")
        assertThat(global.activeSelection?.seatNo).isEqualTo("02C")
        assertThat(global.activeSelection?.candidate).isEqualTo(original)
        assertThat(h.vm.state.value.globalSeatText).isEqualTo("02C")
        assertThat(h.vm.state.value.dailyPlan).isEqualTo(originalDay)
        assertThat(h.vm.state.value.plan?.scene).isEqualTo(SCENE)
        assertThat(h.vm.state.value.plan?.categoryId).isEqualTo(17L)
        assertThat(h.vm.state.value.plan?.seat).isEqualTo("01A")
        assertThat(h.store.savedDays).isEmpty()
        assertThat(h.store.legacySaves).isEmpty()
        assertThat(h.actions.starts).isEmpty()
        assertThat(h.gateway.requests).isEmpty()
        assertThat(h.subscriptionGateway.calls).isEqualTo(0) // fresh persisted cache admits immediately.

        h.vm.startFlight(); advanceUntilIdle()
        assertThat(h.actions.starts).containsExactly(original.route!!.copy(seat = "02C"))
        assertThat(h.actions.starts.single().scene).isEqualTo(SCENE)
        assertThat(h.actions.starts.single().categoryId).isEqualTo(17L)
        assertThat(h.vm.state.value.started).isTrue()
        assertThat(h.actions.actual).isTrue()
        assertThat(h.store.savedDays).isEmpty()
    }

    @Test fun emptyGlobalReturnPreservesInheritedSeatButClearsPendingAndKeepsActiveSeatEmpty() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        advanceUntilIdle()
        val before = h.vm.state.value.dailyPlan!!
        h.enableAndAccept("02C"); runCurrent()
        h.vm.togglePlan(true); runCurrent()
        val pending = h.vm.state.value.pendingGlobalSeat!!
        h.vm.globalSeatNavigationOpened(pending.requestId)
        assertThat(h.vm.acceptGlobalSeat(pending.requestId, " \t ")).isTrue()
        runCurrent()
        val global = h.reopen()
        assertThat(global.pendingSelection).isNull()
        assertThat(global.selectionPending).isFalse()
        assertThat(global.inheritedSeat).isEqualTo("02C")
        assertThat(global.activeSelection?.seatNo).isEmpty()
        assertThat(global.activeSelection?.candidate?.snapshot?.seatNo).isEqualTo("01A")
        assertThat(h.vm.state.value.globalSeatText).isEmpty()
        assertThat(h.vm.state.value.dailyPlan).isEqualTo(before)
        assertThat(h.vm.state.value.plan?.scene).isEqualTo(SCENE)
        assertThat(h.vm.state.value.plan?.categoryId).isEqualTo(17L)
        assertThat(h.actions.starts).isEmpty()
        assertThat(h.store.savedDays).isEmpty()
    }

    @Test fun actualCompletionDispatchRejectsWrongZoneAckThenItsMatchingOwnedDayRefreshesVm() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        advanceUntilIdle()
        h.enableAndAccept("02C"); runCurrent()
        val selection = h.reopen().activeSelection!!
        val before = h.vm.state.value.dailyPlan!!
        h.completion.complete(selection); runCurrent()
        assertThat(h.gateway.requests.single().targetStatus).isEqualTo("completed")
        h.gateway.replies[0].complete(ack(before).copy(timezone = "UTC")); runCurrent(); advanceUntilIdle()
        assertThat(h.vm.state.value.dailyPlan).isEqualTo(before)
        assertThat(h.emitted).isEmpty()
        assertThat(h.completion.recoverySelection()).isEqualTo(selection)
        assertThat(h.completion.diagnostic.value?.kind).isEqualTo(PlanCompletionFailure.INVALID_ACK)

        h.completion.complete(selection); runCurrent()
        h.gateway.replies[1].complete(ack(before, seat = "02C")); runCurrent(); advanceUntilIdle()
        val after = h.vm.state.value.dailyPlan!!
        assertThat(h.emitted).hasSize(1)
        assertThat(after.ownerScope).isEqualTo(FlightPlanOwnership.scope())
        assertThat(after.localDate).isEqualTo(DATE)
        assertThat(after.timezone).isEqualTo(ZONE)
        assertThat(after.legs.single().snapshot.flightNo).isEqualTo("server-completion")
        assertThat(after.legs.single().snapshot.legStatus).isEqualTo("completed")
        assertThat(after.legs.single().route?.scene).isEqualTo(SCENE)
        assertThat(after.legs.single().route?.categoryId).isEqualTo(17L)
        assertThat(h.vm.state.value.completionReceipts[1]).isEqualTo(h.clock.now().toInstant().toEpochMilli())
        assertThat(h.reopen().activeSelection).isNull()
        assertThat(h.store.savedDays).isEmpty()
        assertThat(h.actions.starts).isEmpty()
    }

    @Test fun lateCompletionCannotReplaceBrowsedDayOtherOwnerOrRecreatedTimezone() = runTest(dispatcher) {
        for (target in LateTarget.values()) {
            TokenStore.set(OWNER_A)
            val h = Harness(backgroundScope)
            advanceUntilIdle()
            h.enableAndAccept("02C"); runCurrent()
            val sourceDay = h.vm.state.value.dailyPlan!!
            h.completion.complete(h.reopen().activeSelection!!); runCurrent()
            when (target) {
                LateTarget.BROWSED_DAY -> {
                    h.store.seed(plannedDay("2026-11-01", ZONE, marker = "tomorrow"))
                    h.vm.browseDate(1)
                }
                LateTarget.OTHER_OWNER -> {
                    TokenStore.set(OWNER_B)
                    h.store.seed(plannedDay(DATE, ZONE, marker = "owner-b", from = CAN))
                    h.refreshEligibility(true)
                    h.vm.refreshPlanEntitlement()
                }
                LateTarget.RECREATED_ZONE -> {
                    h.vm.viewModelScope.cancel()
                    h.clock.current = h.clock.current.withZoneSameInstant(ZoneId.of("UTC"))
                    h.store.seed(plannedDay(DATE, "UTC", marker = "utc"))
                    h.replaceVm()
                }
            }
            runCurrent(); advanceUntilIdle()
            val current = h.vm.state.value.dailyPlan!!
            when (target) {
                LateTarget.BROWSED_DAY -> assertThat(current.localDate).isEqualTo("2026-11-01")
                LateTarget.OTHER_OWNER -> assertThat(current.ownerScope).isEqualTo(FlightPlanOwnership.scope())
                LateTarget.RECREATED_ZONE -> assertThat(current.timezone).isEqualTo("UTC")
            }
            h.gateway.replies.single().complete(ack(sourceDay)); runCurrent(); advanceUntilIdle()
            assertThat(h.vm.state.value.dailyPlan).isEqualTo(current)
            if (target == LateTarget.OTHER_OWNER) {
                assertThat(current.ownerScope).isNotEqualTo(sourceDay.ownerScope)
                assertThat(h.emitted).isEmpty() // production dispatch account guard rejects first.
            } else {
                // A valid old pending reply really reached the SharedFlow, so
                // this checks VM date/zone filtering rather than an upstream no-op.
                assertThat(h.emitted).hasSize(1)
                assertThat(h.emitted.single().localDate).isEqualTo(DATE)
                assertThat(h.emitted.single().timezone).isEqualTo(ZONE)
            }
            assertThat(h.store.savedDays).isEmpty()
            assertThat(h.actions.starts).isEmpty()
        }
    }

    @Test fun entitlementDowngradeClearsOnlyEnabledDateWithoutPretendingToStopAcceptedSession() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        advanceUntilIdle()
        h.enableAndAccept("02C"); runCurrent()
        h.vm.startFlight(); advanceUntilIdle()
        assertThat(h.vm.state.value.started).isTrue()
        assertThat(h.actions.actual).isTrue()
        val before = h.reopen()
        val beforeDay = h.vm.state.value.dailyPlan
        h.refreshEligibility(false); runCurrent(); advanceUntilIdle()
        assertThat(h.reopen()).isEqualTo(before.copy(enabledDate = null))
        assertThat(h.vm.state.value.planEnabled).isFalse()
        assertThat(h.vm.state.value.started).isTrue()
        assertThat(h.actions.hasActiveSession()).isTrue()
        assertThat(h.actions.actual).isTrue()
        assertThat(h.actions.starts).hasSize(1)
        assertThat(h.vm.state.value.dailyPlan).isEqualTo(beforeDay)
        assertThat(h.gateway.requests).isEmpty()
        assertThat(h.store.savedDays).isEmpty()
    }

    @Test fun completionAckCannotClearAnAlreadyAcceptedLocalStart() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        advanceUntilIdle()
        h.enableAndAccept("02C"); runCurrent()
        val sourceDay = h.vm.state.value.dailyPlan!!
        h.completion.complete(h.reopen().activeSelection!!); runCurrent()
        h.vm.startFlight(); advanceUntilIdle()
        assertThat(h.vm.state.value.started).isTrue()
        assertThat(h.actions.actual).isTrue()
        h.gateway.replies.single().complete(ack(sourceDay)); runCurrent(); advanceUntilIdle()
        assertThat(h.emitted).hasSize(1)
        assertThat(h.actions.starts).hasSize(1)
        assertThat(h.actions.actual).isTrue()
        // Product invariant: a plan refresh is not acknowledgement that the
        // already accepted local session stopped. The baseline showDay reset this.
        assertThat(h.vm.state.value.started).isTrue()

        val pendingStart = Harness(backgroundScope)
        advanceUntilIdle()
        pendingStart.enableAndAccept("02C"); runCurrent()
        val pendingDay = pendingStart.vm.state.value.dailyPlan!!
        pendingStart.completion.complete(pendingStart.reopen().activeSelection!!); runCurrent()
        val acceptance = CompletableDeferred<Boolean>()
        pendingStart.actions.onStart = { acceptance.await() }
        pendingStart.vm.startFlight(); runCurrent()
        assertThat(pendingStart.vm.state.value.busy).isTrue()
        assertThat(pendingStart.vm.state.value.started).isFalse()
        pendingStart.gateway.replies.single().complete(ack(pendingDay)); runCurrent(); advanceUntilIdle()
        assertThat(pendingStart.emitted).hasSize(1)
        assertThat(pendingStart.vm.state.value.busy).isTrue()
        assertThat(pendingStart.vm.state.value.started).isFalse()
        assertThat(pendingStart.actions.actual).isFalse()
        acceptance.complete(true); runCurrent(); advanceUntilIdle()
        assertThat(pendingStart.vm.state.value.started).isTrue()
        assertThat(pendingStart.vm.state.value.busy).isFalse()
        assertThat(pendingStart.actions.actual).isTrue()
    }

    private inner class Harness(scope: CoroutineScope) {
        val clock = Clock()
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val globalPrefs = preferences("global")
        private val completionPrefs = preferences("completion")
        private val receiptPrefs = preferences("receipt")
        private val entitlementPrefs = preferences("entitlement").apply {
            val owner = FlightPlanOwnership.scope()
            edit().putBoolean("$owner:qualified", true)
                .putLong("$owner:checked_at", clock.now().toInstant().toEpochMilli())
                .putString("$owner:expires_at", "2026-11-30T00:00:00Z").commit()
        }
        val global = SharedPreferencesGlobalFlightPlanStore(globalPrefs, clock)
        val runtime = DailyFlightPlanRuntime(clock)
        val subscriptionGateway = SubscriptionGateway()
        val entitlement = SharedPreferencesPlanEntitlementAccess(entitlementPrefs, clock, subscriptionGateway, scope)
        val gateway = Gateway()
        val completion = SharedPreferencesPlanCompletionDispatch(completionPrefs, receiptPrefs, clock, gateway, scope,
            currentBacking = { runtime.snapshot().selectedDay?.legs.orEmpty() },
            deviceTimezone = { TimeZone.getTimeZone(clock.now().zone) }, clearActiveIfMatches = global::clearActiveIfMatches)
        val actions = Actions()
        val store = Store().apply { seed(plannedDay(DATE, ZONE)) }
        val emitted = mutableListOf<DailyFlightPlan>()
        var vm = makeVm()
            private set
        init { scope.launch { completion.updates.collect { emitted.add(it) } } }
        private fun preferences(kind: String): SharedPreferences = context.getSharedPreferences(
            "plan_vm_${kind}_" + UUID.randomUUID(), Context.MODE_PRIVATE)
        private fun makeVm() = FlightPreparationViewModel(SavedStateHandle(), actions, store,
            PreparationSceneSource { listOf(SCENE) }, clock, runtime, entitlement, global, completion).also(models::add)
        fun replaceVm() { vm = makeVm() }
        fun reopen() = SharedPreferencesGlobalFlightPlanStore(globalPrefs, clock).current()
        fun refreshEligibility(qualified: Boolean) {
            subscriptionGateway.next = SubscriptionStatusSnapshot(qualified, qualified, if (qualified) 30 else 0,
                if (qualified) "2026-11-30T00:00:00Z" else null)
            entitlement.refresh()
        }
        fun enableAndAccept(seat: String?) {
            vm.togglePlan(true)
            dispatcher.scheduler.runCurrent()
            val pending = checkNotNull(vm.state.value.pendingGlobalSeat) { "Actual On did not request a global seat" }
            vm.globalSeatNavigationOpened(pending.requestId)
            assertThat(vm.acceptGlobalSeat(pending.requestId, seat)).isTrue()
            dispatcher.scheduler.runCurrent()
        }
    }

    private class Actions : FlightPreparationActions {
        val starts = mutableListOf<FlightLegPlan>()
        var active = false
        var actual = false
        var onStart: suspend (FlightLegPlan) -> Boolean = { true }
        override suspend fun findAirport(code: String) = AIRPORTS[code]
        override suspend fun startFlight(plan: FlightLegPlan): Boolean {
            starts.add(plan)
            return onStart(plan).also { if (it) { active = true; actual = true } }
        }
        override fun hasActiveSession() = active
        override fun hasActualFlight() = actual
        override fun currentOriginCode() = "PEK"
        override suspend fun hasFlightArchive() = false
    }
    private class Store : ConfirmedFlightPlanStore {
        private val days = mutableMapOf<Triple<String, String, String>, DailyFlightPlan>()
        val savedDays = mutableListOf<DailyFlightPlan>()
        val legacySaves = mutableListOf<FlightLegPlan>()
        fun seed(day: DailyFlightPlan) { days[Triple(day.ownerScope, day.localDate, day.timezone)] = day }
        override suspend fun load(): FlightLegPlan? = error("VM should use the real whole-day port")
        override suspend fun save(plan: FlightLegPlan) { legacySaves.add(plan) }
        override suspend fun loadDay(date: String, timezone: String, today: String): DailyFlightPlan {
            val owner = FlightPlanOwnership.scope()
            return days[Triple(owner, date, timezone)] ?: DailyFlightPlan(date, timezone, ownerScope = owner)
        }
        override suspend fun saveDay(day: DailyFlightPlan): DailyFlightPlan { savedDays.add(day); seed(day); return day }
    }
    private class SubscriptionGateway : SubscriptionStatusGateway {
        var calls = 0
        var next = SubscriptionStatusSnapshot(true, true, 30, "2026-11-30T00:00:00Z")
        override suspend fun fetch(): SubscriptionStatusSnapshot? { calls++; return next }
    }
    private class Gateway : FlightPlanGateway {
        val requests = mutableListOf<UpdateFlightPlanLegStatusRequest>()
        val replies = mutableListOf<CompletableDeferred<DailyFlightPlanSnapshot?>>()
        override suspend fun updateLegStatus(request: UpdateFlightPlanLegStatusRequest): DailyFlightPlanSnapshot? {
            requests.add(request)
            return CompletableDeferred<DailyFlightPlanSnapshot?>().also { replies.add(it) }.await()
        }
        override suspend fun get(request: GetFlightPlanRequest): DailyFlightPlanSnapshot? = error("No hidden completion GET")
        override suspend fun upsert(request: UpsertFlightPlanRequest): DailyFlightPlanSnapshot? = error("No hidden full upsert")
        override suspend fun history(request: FlightPlanHistoryRequest): List<DailyFlightPlanSnapshot> = error("No hidden completion history")
    }
    private class Clock : PreparationClock {
        var current = ZonedDateTime.parse("2026-10-31T12:34:56.789+08:00[Asia/Shanghai]")
        override fun now() = current
        override fun elapsedRealtimeMillis() = 10_000L
    }
    private enum class LateTarget { BROWSED_DAY, OTHER_OWNER, RECREATED_ZONE }
    private fun plannedDay(date: String, zone: String, marker: String = "original", from: PlannedAirport = PEK): DailyFlightPlan {
        val route = FlightLegPlan.between(from, PVG).copy(seat = "01A", categoryId = 17L, scene = SCENE,
            plannedDeparture = PlannedDeparture(date, 13, 0, zone))
        val leg = DailyFlightPlans.fromRoute(route, date, zone, 1).let { it.copy(snapshot = it.snapshot.copy(flightNo = marker)) }
        return DailyFlightPlan(date, zone, legs = listOf(leg), ownerScope = FlightPlanOwnership.scope())
    }
    private fun ack(day: DailyFlightPlan, seat: String? = null) = DailyFlightPlanSnapshot(day.localDate, day.timezone,
        day.legs.map { it.snapshot.copy(legStatus = "completed", flightNo = "server-completion", seatNo = seat ?: it.snapshot.seatNo) },
        planDate = day.planDate ?: "2026-10-30")
    private companion object {
        const val DATE = "2026-10-31"; const val ZONE = "Asia/Shanghai"
        const val OWNER_A = "vm-plan-owner-a"; const val OWNER_B = "vm-plan-owner-b"
        val PEK = PlannedAirport(1, "PEK", "首都", "北京", "CN", 40.08, 116.58)
        val PVG = PlannedAirport(2, "PVG", "浦东", "上海", "CN", 31.14, 121.8)
        val CAN = PlannedAirport(3, "CAN", "白云", "广州", "CN", 23.39, 113.3)
        val AIRPORTS = listOf(PEK, PVG, CAN).associateBy { it.code }
        val SCENE = PlannedScene("fixture-private-scene", "原始场景", "study", 0xFF88AA22.toInt(), true,
            cloudId = 501L, clientUuid = "scene-client-fixture", displayName = "私人场景")
    }
}
