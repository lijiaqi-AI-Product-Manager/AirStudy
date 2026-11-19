package com.projectfocus.app.booking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedDeparture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class DailyFlightPreparationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<FlightPreparationViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    @Test fun addUsesPreviousDestinationAndSavesEveryExistingLegOnlyAfterPreviewConfirmation() = runTest(dispatcher) {
        val store = Store(day())
        val vm = model(store)
        advanceUntilIdle()
        val first = vm.state.value.dailyPlan!!.legs.single()
        vm.addSegment()
        assertThat(vm.state.value.origin).isEqualTo(PVG)
        assertThat(vm.state.value.destination).isNull()
        vm.selectAirport("CAN", false)
        advanceUntilIdle()
        preview(vm)
        assertThat(store.saves).isEmpty()
        assertThat(vm.state.value.dailyPlan!!.legs).containsExactly(first)
        vm.confirmPlan()
        advanceUntilIdle()
        val saved = store.saves.single()
        assertThat(saved.legs.size).isEqualTo(2)
        assertThat(saved.legs.first()).isEqualTo(first)
        assertThat(saved.legs.last().route?.departure).isEqualTo(PVG)
        assertThat(saved.legs.last().route?.destination).isEqualTo(CAN)
        assertThat(vm.state.value.confirmed).isTrue()
    }

    @Test fun editKeepsOriginalDepartureSeatAndFlightNumberAndDoesNotReplaceEarlierLegs() = runTest(dispatcher) {
        val first = day().legs.single()
        val lastRoute = route(PVG, CAN).copy(seat = "30F", plannedDeparture = PlannedDeparture(DATE, 14, 1, ZONE))
        val last = DailyFlightPlans.fromRoute(lastRoute, DATE, ZONE, 2).let {
            it.copy(snapshot = it.snapshot.copy(flightNo = "RF-002"))
        }
        val store = Store(day().copy(legs = listOf(first, last)))
        val vm = model(store)
        advanceUntilIdle()
        assertThat(vm.canEdit(0)).isFalse()
        vm.editSegment(vm.segmentId(1))
        vm.selectAirport("PEK", true)
        assertThat(vm.state.value.error).isEqualTo(PreparationError.PLAN_OPERATION_UNAVAILABLE)
        assertThat(vm.state.value.origin).isEqualTo(PVG)
        vm.selectAirport("HKG", false)
        advanceUntilIdle()
        preview(vm); vm.confirmPlan(); advanceUntilIdle()
        val saved = store.saves.single()
        assertThat(saved.legs.first()).isEqualTo(first)
        assertThat(saved.legs.last().snapshot.flightNo).isEqualTo("RF-002")
        assertThat(saved.legs.last().snapshot.seatNo).isEqualTo("30F")
        assertThat(saved.legs.last().route?.destination).isEqualTo(HKG)
        assertThat(saved.legs.last().route?.departure).isEqualTo(PVG)
    }

    @Test fun completedAndReadOnlyLegsCannotBeEditedOrDeletedAsWaitingLegs() = runTest(dispatcher) {
        for (snapshot in listOf(day().legs.single().snapshot.copy(legStatus = "completed"),
            day().legs.single().snapshot.copy(readOnly = true), day().legs.single().snapshot.copy(archived = true))) {
            val base = day().copy(legs = listOf(day().legs.single().copy(snapshot = snapshot)))
            val store = Store(base)
            val vm = model(store)
            advanceUntilIdle()
            assertThat(vm.canEdit(0)).isFalse()
            assertThat(vm.canRemove(0)).isFalse()
            vm.editSegment(vm.segmentId(0)); vm.removeSegment(vm.segmentId(0)); advanceUntilIdle()
            assertThat(vm.state.value.dailyPlan).isEqualTo(base)
            assertThat(store.saves).isEmpty()
        }
    }

    @Test fun eligibleLastCanceledLegHasDistinctSingleAndBatchDeletionWhilePastAndMalformedDatesDoNot() = runTest(dispatcher) {
        val original = day().legs.single()
        val canceled = original.copy(snapshot = original.snapshot.copy(legStatus = "canceled"))
        val store = Store(day().copy(legs = listOf(canceled)))
        val vm = model(store)
        advanceUntilIdle()
        assertThat(vm.canEdit(0)).isFalse()
        assertThat(vm.canRemove(0)).isTrue()
        assertThat(vm.canDeleteCanceled(0)).isTrue()
        vm.removeSegment(vm.segmentId(0)); advanceUntilIdle()
        assertThat(store.saves.single().legs).isEmpty()
        for (date in listOf("2026-10-30T04:34:00Z", "invalid", "")) {
            val blocked = model(Store(day().copy(legs = listOf(canceled.copy(snapshot = canceled.snapshot.copy(scheduledDepartureAt = date))))))
            advanceUntilIdle()
            assertThat(blocked.canDeleteCanceled(0)).isFalse()
            assertThat(blocked.canRemove(0)).isFalse()
        }
    }

    @Test fun batchDeletionWaitsForAckThenReloadsAndDoesNotSubmitAFabricatedRemainder() = runTest(dispatcher) {
        val first = day().legs.single()
        val base = day().copy(legs = listOf(first.copy(snapshot = first.snapshot.copy(legStatus = "canceled")), first))
        val store = Store(base)
        val vm = model(store)
        advanceUntilIdle()
        store.gate = CompletableDeferred()
        val oldId = vm.segmentId(0)
        vm.deleteCanceledSegments(oldId); runCurrent()
        assertThat(vm.state.value.dailyPlan).isEqualTo(base)
        assertThat(vm.state.value.busy).isTrue()
        assertThat(store.saves).isEmpty()
        store.gate!!.complete(Unit); advanceUntilIdle()
        assertThat(store.deletes).containsExactly(base)
        assertThat(store.loads).containsExactly(DATE, "2026-11-01", DATE).inOrder()
        assertThat(vm.state.value.dailyPlan!!.legs).containsExactly(first)
        vm.deleteCanceledSegments(oldId); advanceUntilIdle()
        assertThat(store.deletes).hasSize(1)
    }

    @Test fun batchFailureRetainsConfirmedDayAndDoesNotRefreshOrOfferFakeSuccess() = runTest(dispatcher) {
        val first = day().legs.single()
        val base = day().copy(legs = listOf(first.copy(snapshot = first.snapshot.copy(legStatus = "canceled"))))
        val store = Store(base)
        val vm = model(store)
        advanceUntilIdle()
        store.fail = true
        vm.deleteCanceledSegments(vm.segmentId(0)); advanceUntilIdle()
        assertThat(vm.state.value.dailyPlan).isEqualTo(base)
        assertThat(vm.state.value.error).isEqualTo(PreparationError.SAVE_FAILED)
        assertThat(store.loads).containsExactly(DATE, "2026-11-01").inOrder()
        assertThat(store.saves).isEmpty()
    }

    @Test fun requestLoadTimeoutIsVisibleAndDoesNotInventAnEmptyConfirmedPlan() = runTest(dispatcher) {
        val store = Store(day()).apply { loadTimeout = true }
        val vm = model(store)
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(PreparationError.LOAD_FAILED)
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.confirmed).isFalse()
        assertThat(store.days[DATE]).isEqualTo(day())
        store.loadTimeout = false
        vm.selectAirport("PEK", true); vm.selectAirport("PVG", false); advanceUntilIdle()
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.dailyPlan).isEqualTo(day())
    }

    @Test fun wholeSaveTimeoutRetainsConfirmedDayAndTheSamePreviewCanRetry() = runTest(dispatcher) {
        val store = Store(day())
        val vm = model(store)
        advanceUntilIdle()
        vm.addSegment(); vm.selectAirport("CAN", false); advanceUntilIdle(); preview(vm)
        val preview = vm.state.value.plan
        store.saveTimeout = true
        vm.confirmPlan(); advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(PreparationError.SAVE_FAILED)
        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.confirmed).isFalse()
        assertThat(vm.state.value.plan).isEqualTo(preview)
        assertThat(store.days[DATE]).isEqualTo(day())
        store.saveTimeout = false
        vm.confirmPlan(); advanceUntilIdle()
        assertThat(vm.state.value.confirmed).isTrue()
        assertThat(vm.state.value.dailyPlan!!.legs).hasSize(2)
    }

    @Test fun singleDeleteTimeoutKeepsBothLegsAndBatchTimeoutDoesNotEmitAReplacement() = runTest(dispatcher) {
        val first = day().legs.single()
        val second = DailyFlightPlans.fromRoute(route(PVG, CAN), DATE, ZONE, 2)
        val initial = day().copy(legs = listOf(first, second))
        val singleStore = Store(initial)
        val single = model(singleStore)
        advanceUntilIdle()
        singleStore.saveTimeout = true
        single.removeSegment(single.segmentId(1)); advanceUntilIdle()
        assertThat(single.state.value.error).isEqualTo(PreparationError.SAVE_FAILED)
        assertThat(single.state.value.dailyPlan).isEqualTo(initial)
        assertThat(single.state.value.busy).isFalse()
        val canceled = day().copy(legs = listOf(first.copy(snapshot = first.snapshot.copy(legStatus = "canceled"))))
        val batchStore = Store(canceled)
        val batch = model(batchStore)
        advanceUntilIdle()
        batchStore.batchTimeout = true
        batch.deleteCanceledSegments(batch.segmentId(0)); advanceUntilIdle()
        assertThat(batch.state.value.error).isEqualTo(PreparationError.SAVE_FAILED)
        assertThat(batch.state.value.dailyPlan).isEqualTo(canceled)
        assertThat(batch.state.value.busy).isFalse()
        assertThat(batchStore.loads).containsExactly(DATE, "2026-11-01").inOrder()
    }

    @Test fun cancellingANewDraftAndFailedSaveLeaveTheConfirmedDayUntouched() = runTest(dispatcher) {
        val store = Store(day())
        val vm = model(store)
        advanceUntilIdle()
        vm.addSegment(); vm.back()
        assertThat(vm.state.value.dailyPlan).isEqualTo(day())
        assertThat(vm.state.value.confirmed).isTrue()
        assertThat(store.saves).isEmpty()
        vm.addSegment(); vm.selectAirport("CAN", false); advanceUntilIdle(); preview(vm)
        store.fail = true
        vm.confirmPlan(); advanceUntilIdle()
        assertThat(vm.state.value.confirmed).isFalse()
        assertThat(vm.state.value.dailyPlan).isEqualTo(day())
        assertThat(vm.state.value.error).isEqualTo(PreparationError.SAVE_FAILED)
        assertThat(store.days[DATE]).isEqualTo(day())
    }

    @Test fun deleteWaitsForAcknowledgementSendsWholeRemainderAndRejectsAnOldMenuId() = runTest(dispatcher) {
        val base = day().copy(legs = day().legs + DailyFlightPlans.fromRoute(route(PVG, CAN)
            .copy(plannedDeparture = PlannedDeparture(DATE, 14, 1, ZONE)), DATE, ZONE, 2))
        val store = Store(base)
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        val vm = model(store)
        advanceUntilIdle()
        val id = vm.segmentId(1)
        vm.removeSegment(id); runCurrent()
        assertThat(vm.state.value.dailyPlan).isEqualTo(base)
        assertThat(vm.state.value.busy).isTrue()
        gate.complete(Unit); advanceUntilIdle()
        assertThat(store.saves.single().legs).containsExactly(base.legs.first())
        assertThat(vm.state.value.dailyPlan!!.legs.size).isEqualTo(1)
        vm.removeSegment(id); advanceUntilIdle()
        assertThat(store.saves.size).isEqualTo(1)
    }

    @Test fun dateBrowsingLoadsSeparateDaysAndEmptyTomorrowCanCreateItsOwnPlan() = runTest(dispatcher) {
        val store = Store(day())
        val vm = model(store)
        advanceUntilIdle()
        vm.browseDate(-1); advanceUntilIdle()
        assertThat(vm.state.value.selectedDate).isEqualTo("2026-10-30")
        assertThat(vm.state.value.dailyPlan!!.legs).isEmpty()
        assertThat(vm.state.value.canAddSegment).isFalse()
        vm.browseDate(1); advanceUntilIdle()
        assertThat(vm.state.value.dailyPlan).isEqualTo(day())
        vm.browseDate(1); advanceUntilIdle()
        assertThat(vm.state.value.canAddSegment).isTrue()
        vm.addSegment(); vm.selectAirport("PEK", true); vm.selectAirport("PVG", false)
        advanceUntilIdle(); vm.nextDestination(); vm.nextScene()
        assertThat(vm.state.value.departureClock?.date).isEqualTo("2026-11-01")
        vm.previewPlan(); vm.confirmPlan(); advanceUntilIdle()
        assertThat(store.days["2026-11-01"]!!.legs.size).isEqualTo(1)
        assertThat(store.days[DATE]).isEqualTo(day())
    }

    @Test fun savedStateRecreationRetainsWholeListAndEditingIndexWithoutAnotherLoad() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val base = day().copy(legs = day().legs + DailyFlightPlans.fromRoute(route(PVG, CAN)
            .copy(plannedDeparture = PlannedDeparture(DATE, 14, 1, ZONE)), DATE, ZONE, 2))
        val store = Store(base)
        val vm = model(store, saved)
        advanceUntilIdle()
        vm.editSegment(vm.segmentId(1)); vm.selectAirport("HKG", false); advanceUntilIdle()
        val recreated = model(store, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        advanceUntilIdle()
        assertThat(store.loads.count { it == DATE }).isEqualTo(1)
        assertThat(store.loads.filter { it != DATE }).containsExactly("2026-11-01", "2026-11-01")
        assertThat(recreated.state.value.dailyPlan).isEqualTo(base)
        preview(recreated); recreated.confirmPlan(); advanceUntilIdle()
        assertThat(store.saves.single().legs.size).isEqualTo(2)
        assertThat(store.saves.single().legs.first()).isEqualTo(base.legs.first())
        assertThat(store.saves.single().legs.last().route?.destination).isEqualTo(HKG)
    }

    @Test fun subsequentLegUsesPriorArrivalPlusTwoMinimumAndPlusFiveDefault() = runTest(dispatcher) {
        val vm = model(Store(day()))
        advanceUntilIdle()
        vm.addSegment(); vm.selectAirport("CAN", false); advanceUntilIdle()
        vm.nextDestination(); vm.nextScene()
        assertThat(vm.state.value.previousArrivalText).isEqualTo("13:56")
        assertThat(vm.state.value.earliestDeparture).isEqualTo("13:58")
        assertThat(vm.state.value.departureClock?.hour).isEqualTo(14)
        assertThat(vm.state.value.departureClock?.minute).isEqualTo(1)
        vm.selectDepartureTime(13, 57)
        assertThat(vm.state.value.timeCanPreview).isFalse()
        vm.selectDepartureTime(13, 58)
        assertThat(vm.state.value.timeCanPreview).isTrue()
    }

    @Test fun tomorrowFirstLegDefaultsToMidnightAndTheNextArrowStopsAtTomorrow() = runTest(dispatcher) {
        val store = Store(day())
        val vm = model(store)
        advanceUntilIdle()
        vm.browseDate(1); advanceUntilIdle()
        val loads = store.loads.toList()
        assertThat(vm.state.value.canBrowseNextDate).isFalse()
        vm.browseDate(1); advanceUntilIdle()
        assertThat(store.loads).isEqualTo(loads)
        assertThat(vm.state.value.selectedDate).isEqualTo("2026-11-01")
        vm.addSegment(); vm.selectAirport("PEK", true); vm.selectAirport("PVG", false); advanceUntilIdle()
        vm.nextDestination(); vm.nextScene()
        assertThat(vm.state.value.departureClock?.minuteOfDay).isEqualTo(0)
        assertThat(vm.state.value.earliestDeparture).isEqualTo("00:00")
        assertThat(vm.state.value.timeCanPreview).isTrue()
    }

    private fun model(store: Store, saved: SavedStateHandle = SavedStateHandle()) = FlightPreparationViewModel(saved,
        object : FlightPreparationActions {
            override suspend fun findAirport(code: String) = listOf(PEK, PVG, CAN, HKG).firstOrNull { it.code == code }
            override suspend fun startFlight(plan: FlightLegPlan) = error("A plan operation must not start a flight")
            override fun hasActiveSession() = false
        }, store, PreparationSceneSource { emptyList() }, PreparationClock { NOW }).also { models += it }

    private fun preview(vm: FlightPreparationViewModel) { vm.nextDestination(); vm.nextScene(); vm.previewPlan() }
    private fun route(from: PlannedAirport = PEK, to: PlannedAirport = PVG) = FlightLegPlan.between(from, to)
        .copy(plannedDeparture = PlannedDeparture(DATE, 12, 34, ZONE), seat = "01A")
    private fun day() = DailyFlightPlan(DATE, ZONE, legs = listOf(DailyFlightPlans.fromRoute(route(), DATE, ZONE, 1)))
    private class Store(initial: DailyFlightPlan) : ConfirmedFlightPlanStore {
        val days = mutableMapOf(initial.localDate to initial)
        val saves = mutableListOf<DailyFlightPlan>()
        val loads = mutableListOf<String>()
        val deletes = mutableListOf<DailyFlightPlan>()
        var fail = false
        var loadTimeout = false
        var saveTimeout = false
        var batchTimeout = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(): FlightLegPlan? = error("Daily host must not read the legacy singleton")
        override suspend fun save(plan: FlightLegPlan) = error("Daily host must not overwrite the legacy singleton")
        override suspend fun loadDay(date: String, timezone: String, today: String): DailyFlightPlan {
            loads += date
            if (loadTimeout) withTimeout(1L) { awaitCancellation() }
            return days[date] ?: DailyFlightPlans.empty(date, timezone)
        }
        override suspend fun saveDay(day: DailyFlightPlan): DailyFlightPlan {
            saves += day
            if (saveTimeout) withTimeout(1L) { awaitCancellation() }
            gate?.await()
            if (fail) throw IOException("save failed")
            days[day.localDate] = day
            return day
        }
        override suspend fun deleteCanceledDay(day: DailyFlightPlan, localToday: String): DailyPlanDeletionResult {
            deletes += day
            if (batchTimeout) withTimeout(1L) { awaitCancellation() }
            gate?.await()
            if (fail) throw IOException("delete failed")
            // Deliberately return a different ack than the subsequent store read,
            // proving the VM performs the source re-query rather than installing it.
            val retained = day.copy(legs = day.legs.filter { it.snapshot.legStatus != "canceled" })
            days[day.localDate] = retained
            return DailyPlanDeletionResult(day.copy(legs = emptyList()), day.legs.size - retained.legs.size)
        }
    }
    private companion object {
        const val DATE = "2026-10-31"; const val ZONE = "Asia/Shanghai"
        val NOW = ZonedDateTime.parse("2026-10-31T12:34:00+08:00[Asia/Shanghai]")
        val PEK = PlannedAirport(1, "PEK", "首都", "北京", "CN", 40.08, 116.58)
        val PVG = PlannedAirport(2, "PVG", "浦东", "上海", "CN", 31.14, 121.8)
        val CAN = PlannedAirport(3, "CAN", "白云", "广州", "CN", 23.39, 113.3)
        val HKG = PlannedAirport(4, "HKG", "香港", "香港", "CN", 22.3, 113.9)
    }
}
