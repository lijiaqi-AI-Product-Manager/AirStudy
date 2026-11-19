package com.projectfocus.app.booking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedDeparture
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.ZonedDateTime

/** Hand-derived cases from authenticated g/X/u/s/p/v bytes, not inverse tests of the implementation. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceDailyPlanOperationsTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<FlightPreparationViewModel>()
    private val clock = object : PreparationClock {
        override fun now() = NOW
        override fun elapsedRealtimeMillis() = 2_000L
    }
    @Before fun setup() { TokenStore.clear(); Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain(); TokenStore.clear() }

    @Test fun uiTailEditPreviewsBButBuildsFromBackingAAndResetsOnlyItsArrivalResults() = runTest(dispatcher) {
        val a = leg(1, PEK, PVG, 12, 34).let { it.copy(snapshot = it.snapshot.copy(
            flightNo = "  FL-A ", seatNo = " 01A ", actualArrivalAt = "old actual", expectedArrivalAt = "old expected",
            scheduledArrivalAt = "old scheduled", arrivalDiffMinutes = 9, archived = true, readOnly = true)) }
        val b = leg(2, PVG, CAN, 14, 1)
        val store = Store(day(b, a))
        val vm = model(store)
        advanceUntilIdle()
        vm.editSegment(vm.segmentId(1))
        assertThat(vm.state.value.plan?.departure).isEqualTo(PVG)
        vm.selectAirport("HKG", false); advanceUntilIdle(); preview(vm)
        vm.confirmPlan(); advanceUntilIdle()
        val saved = store.saves.single()
        assertThat(saved.legs[0]).isEqualTo(b.copy(snapshot = b.snapshot.copy(legIndex = 1)))
        val changed = saved.legs[1]
        assertThat(changed.snapshot.departureAirport?.code).isEqualTo("PEK")
        assertThat(changed.route?.departure).isEqualTo(PEK)
        assertThat(changed.snapshot.arrivalAirport?.code).isEqualTo("HKG")
        assertThat(changed.snapshot.flightNo).isEqualTo("FL-A")
        assertThat(changed.snapshot.seatNo).isEqualTo("01A")
        assertThat(changed.snapshot.actualArrivalAt).isEmpty()
        assertThat(changed.snapshot.expectedArrivalAt).isEmpty()
        assertThat(changed.snapshot.scheduledArrivalAt).isEmpty()
        assertThat(changed.snapshot.arrivalDiffMinutes).isEqualTo(Int.MIN_VALUE)
        assertThat(changed.snapshot.archived).isFalse()
        assertThat(changed.snapshot.readOnly).isFalse()
        // Source keeps the draft distance even though its old backing origin differs.
        assertThat(changed.snapshot.distanceKm).isEqualTo(FlightLegPlan.between(PVG, HKG).distanceKm)
        assertThat(changed.route?.distanceKm).isEqualTo(FlightLegPlan.between(PEK, HKG).distanceKm)
    }

    @Test fun aWaitingUiTailCannotPassFinalSaveWhenTheBackingTailIsCompleted() = runTest(dispatcher) {
        val a = leg(1, PEK, PVG, 12, 34, "completed")
        val b = leg(2, PVG, CAN, 14, 1)
        val store = Store(day(b, a)); val vm = model(store); advanceUntilIdle()
        assertThat(vm.canEdit(1)).isTrue()
        vm.editSegment(vm.segmentId(1)); vm.selectAirport("HKG", false); advanceUntilIdle(); preview(vm)
        vm.confirmPlan(); advanceUntilIdle()
        assertThat(store.saves).isEmpty()
        assertThat(vm.state.value.error).isEqualTo(PreparationError.SAVE_FAILED)
        assertThat(vm.state.value.dailyPlan).isEqualTo(day(b, a))
    }

    @Test fun singleDeleteFromTheSortedTailStillDropsTheBackingTail() = runTest(dispatcher) {
        val a = leg(1, PEK, PVG, 12, 34); val b = leg(2, PVG, CAN, 14, 1)
        val store = Store(day(b, a)); val vm = model(store); advanceUntilIdle()
        vm.removeSegment(vm.segmentId(1)); advanceUntilIdle()
        val retained = b.copy(snapshot = b.snapshot.copy(legIndex = 1))
        assertThat(store.saves.single().legs).containsExactly(retained)
        assertThat(vm.state.value.dailyPlan?.legs).containsExactly(retained)
    }

    @Test fun newPreviewUsesUiArrivalButAppendUsesBackingLastArrival() = runTest(dispatcher) {
        val a = leg(1, PEK, PVG, 12, 34); val b = leg(2, PVG, CAN, 14, 1)
        val store = Store(day(b, a)); val vm = model(store); advanceUntilIdle()
        vm.addSegment()
        assertThat(vm.state.value.origin).isEqualTo(CAN)
        vm.selectAirport("HKG", false); advanceUntilIdle(); preview(vm); vm.confirmPlan(); advanceUntilIdle()
        val saved = store.saves.single()
        assertThat(saved.legs.map { it.snapshot.legIndex }).containsExactly(1, 2, 3).inOrder()
        assertThat(saved.legs.last().snapshot.departureAirport?.code).isEqualTo("PVG")
        assertThat(saved.legs.last().route?.departure).isEqualTo(PVG)
    }

    @Test fun manualCandidateIsFirstSortedWaitingLegAndDoesNotWaitForScheduledClock() = runTest(dispatcher) {
        val a = leg(1, PEK, PVG, 13, 0, "等待起飞"); val b = leg(2, PVG, CAN, 14, 1)
        val actions = Actions(); val vm = model(Store(day(b, a)), actions); advanceUntilIdle()
        assertThat(vm.state.value.selectedLegIndex).isEqualTo(0)
        assertThat(vm.state.value.departureDue).isFalse()
        vm.startFlight(); advanceUntilIdle()
        assertThat(actions.starts).containsExactly(a.route)
        assertThat(vm.state.value.started).isTrue()
    }

    @Test fun previousArrivalUsesDraftLegNumberRatherThanTheClickedPositionAndSurvivesRecreation() = runTest(dispatcher) {
        val a = leg(10, PEK, PVG, 12, 34); val b = leg(20, PVG, CAN, 14, 1)
        val saved = SavedStateHandle(); val store = Store(day(b, a)); val actions = Actions()
        val vm = model(store, actions, saved); advanceUntilIdle()
        vm.editSegment(vm.segmentId(1))
        // 20 exceeds UI size=2, so v chooses UI.last (B itself), not A.
        val previous = vm.state.value.previousArrivalText
        val expected = FlightPlanCardPresentation.arrival(b.snapshot, NOW.zone)
        assertThat(previous).isEqualTo(expected)
        val restored = model(store, actions, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        advanceUntilIdle()
        assertThat(restored.state.value.previousArrivalText).isEqualTo(expected)
        assertThat(restored.state.value.selectedLegIndex).isEqualTo(1)
    }

    @Test fun assignmentPlaceholdersUseJavaTrimAndExactCaseSensitiveLocaleMembership() {
        for (token in listOf("待分配", "Pending", "未割り当て", "할당 대기 중", "Ожидает назначения")) {
            assertThat(DailyFlightPlans.normalizeAssignment(" \t$token\r\n")).isEmpty()
        }
        assertThat(DailyFlightPlans.normalizeAssignment(" pending ")).isEqualTo("pending")
        assertThat(DailyFlightPlans.normalizeAssignment("\u00a0Pending\u00a0")).isEqualTo("\u00a0Pending\u00a0")
        assertThat(DailyFlightPlans.normalizeAssignment(" 1A ")).isEqualTo("1A")
        assertThat(DailyFlightPlans.normalizeAssignment(" A1 ")).isEqualTo("A1")
    }

    @Test fun unchangedArrivalPreservesExactOldObjectAndDistanceWithJavaLocaleComparisonAndMathMax() {
        val original = leg(1, PEK, PVG, 12, 34)
        val decorated = original.copy(snapshot = original.snapshot.copy(
            arrivalAirport = original.snapshot.arrivalAirport!!.copy(code = " pvg ", name = "Old server metadata"),
            distanceKm = 41.25, focusIconCode = 19, focusSceneText = " Old scene "))
        val draft = FlightLegPlan.between(PEK, PVG).copy(plannedDeparture = original.route!!.plannedDeparture)
        val replacement = DailyFlightPlans.replacement(day(decorated), 0, draft, studyTitle = "Study")
        assertThat(replacement.snapshot.arrivalAirport).isEqualTo(decorated.snapshot.arrivalAirport)
        assertThat(replacement.snapshot.distanceKm).isEqualTo(41.25)
        assertThat(replacement.snapshot.focusIconCode).isEqualTo(19)
        assertThat(replacement.snapshot.focusSceneText).isEqualTo("Old scene")
        val negative = decorated.copy(snapshot = decorated.snapshot.copy(distanceKm = -2.0))
        assertThat(DailyFlightPlans.replacement(day(negative), 0, draft).snapshot.distanceKm).isEqualTo(0.0)
        val nan = decorated.copy(snapshot = decorated.snapshot.copy(distanceKm = Double.NaN))
        assertThat(DailyFlightPlans.replacement(day(nan), 0, draft).snapshot.distanceKm.isNaN()).isTrue()
    }

    @Test fun nullSceneFallsBackToOldClampedFieldsButExplicitEmptySceneUsesStudyDefaults() {
        val original = leg(1, PEK, PVG, 12, 34).let { it.copy(snapshot = it.snapshot.copy(
            focusIconCode = 21, focusSceneText = " \t ")) }
        val draft = original.route!!.copy(scene = null)
        val nullScene = DailyFlightPlans.replacement(day(original), 0, draft, studyTitle = "Study")
        assertThat(nullScene.snapshot.focusIconCode).isEqualTo(20)
        assertThat(nullScene.snapshot.focusSceneText).isEqualTo("Study")
        val explicit = draft.copy(scene = PlannedScene("explicit", "", "", 0, true, displayName = ""))
        val explicitScene = DailyFlightPlans.replacement(day(original), 0, explicit, studyTitle = "Study")
        assertThat(explicitScene.snapshot.focusIconCode).isEqualTo(1)
        assertThat(explicitScene.snapshot.focusSceneText).isEqualTo("Study")
    }

    @Test fun tomorrowPrefetchRebindsActualTodayEligibilityAndMutationRechecksIt() = runTest(dispatcher) {
        val store = Store(day(leg(1, PEK, PVG, 12, 34)))
        val delayed = CompletableDeferred<DailyFlightPlan>()
        store.tomorrow = { delayed.await() }
        val vm = model(store); runCurrent()
        assertThat(vm.canEdit(0)).isTrue()
        delayed.complete(day(leg(1, PEK, PVG, 12, 34, "completed")).copy(localDate = TOMORROW))
        advanceUntilIdle()
        assertThat(vm.state.value.todayBlocked).isTrue()
        assertThat(vm.state.value.canAddSegment).isFalse()
        assertThat(vm.canEdit(0)).isFalse()
        assertThat(vm.canRemove(0)).isFalse()
        vm.editSegment(vm.segmentId(0)); vm.removeSegment(vm.segmentId(0)); advanceUntilIdle()
        assertThat(store.saves).isEmpty()
    }

    @Test fun allTerminalPreviewUsesPersistedOriginBeforeAnActiveCachedTodayFallback() = runTest(dispatcher) {
        val base = day(leg(1, PEK, PVG, 12, 34, "completed"))
        val actions = Actions().apply { default = PreparationOrigin("HKG", HKG) }
        val runtime = DailyFlightPlanRuntime(clock)
        val vm = model(Store(base), actions, runtime = runtime); advanceUntilIdle()
        runtime.bind(day(leg(1, PEK, PVG, 12, 34), leg(2, PVG, CAN, 14, 1, "completed")))
        vm.addSegment(); advanceUntilIdle()
        assertThat(vm.state.value.origin).isEqualTo(HKG)
        assertThat(actions.origins).containsExactly("default")
    }

    @Test fun emptyTomorrowPreviewUsesLastNoncanceledCachedTodayArrivalBeforePersistedOrigin() = runTest(dispatcher) {
        val actions = Actions().apply { default = PreparationOrigin("HKG", HKG) }
        val vm = model(Store(day(leg(1, PEK, PVG, 12, 34), leg(2, PVG, CAN, 14, 1, "completed"))), actions)
        advanceUntilIdle(); vm.browseDate(1); advanceUntilIdle(); vm.addSegment(); advanceUntilIdle()
        assertThat(vm.state.value.origin).isEqualTo(CAN)
        assertThat(actions.origins).isEmpty()
    }

    @Test fun missingPersistedCoordinatesKeepCodeAndUnsuccessfulFallbackDoesNotInventZeroCoordinates() = runTest(dispatcher) {
        val actions = Actions().apply { default = PreparationOrigin("KNOWN", null) }
        val vm = model(Store(day()), actions); advanceUntilIdle(); vm.addSegment(); advanceUntilIdle()
        assertThat(vm.state.value.originCode).isEqualTo("KNOWN")
        assertThat(vm.state.value.origin).isNull()
        assertThat(actions.origins).containsExactly("default")
        assertThat(vm.state.value.plan).isNull()
        assertThat(vm.state.value.confirmed).isFalse()
    }

    @Test fun asynchronousDefaultOriginCannotOverwriteAnExplicitNewChoice() = runTest(dispatcher) {
        val delayed = CompletableDeferred<PreparationOrigin?>()
        val actions = Actions().apply { originLoader = { delayed.await() } }
        val vm = model(Store(day()), actions); advanceUntilIdle(); vm.addSegment(); runCurrent()
        vm.selectAirport("PEK", true); vm.selectAirport("PVG", false); runCurrent()
        delayed.complete(PreparationOrigin("HKG", HKG)); advanceUntilIdle()
        assertThat(vm.state.value.origin).isEqualTo(PEK)
        assertThat(vm.state.value.destination).isEqualTo(PVG)
    }

    private fun model(store: Store, actions: Actions = Actions(), saved: SavedStateHandle = SavedStateHandle(),
                      runtime: DailyFlightPlanRuntime = DailyFlightPlanRuntime(clock)) =
        FlightPreparationViewModel(saved, actions, store, PreparationSceneSource { emptyList() }, clock, runtime)
            .also { models += it }
    private fun preview(vm: FlightPreparationViewModel) { vm.nextDestination(); vm.nextScene(); vm.previewPlan() }
    private fun leg(index: Int, from: PlannedAirport, to: PlannedAirport, hour: Int, minute: Int,
                    status: String = "waiting_takeoff") = DailyFlightPlans.fromRoute(
        FlightLegPlan.between(from, to).copy(plannedDeparture = PlannedDeparture(DATE, hour, minute, ZONE), seat = "01A"),
        DATE, ZONE, index).let { it.copy(snapshot = it.snapshot.copy(legStatus = status)) }
    private fun day(vararg legs: DailyFlightLeg) = DailyFlightPlan(DATE, ZONE, legs = legs.toList())
    private class Actions : FlightPreparationActions {
        val starts = mutableListOf<FlightLegPlan>()
        val origins = mutableListOf<String>()
        var default: PreparationOrigin? = null
        var originLoader: suspend () -> PreparationOrigin? = { default }
        override suspend fun findAirport(code: String) = AIRPORTS.firstOrNull { it.code == code }
        override suspend fun startFlight(plan: FlightLegPlan): Boolean { starts += plan; return true }
        override fun hasActiveSession() = false
        override suspend fun defaultOrigin(): PreparationOrigin? { origins += "default"; return originLoader() }
        override suspend fun lastArrivalOrigin(): PreparationOrigin? { origins += "last-arrival"; return null }
    }
    private class Store(private var current: DailyFlightPlan) : ConfirmedFlightPlanStore {
        val saves = mutableListOf<DailyFlightPlan>()
        var tomorrow: suspend () -> DailyFlightPlan = { DailyFlightPlans.empty(TOMORROW, ZONE) }
        override suspend fun load(): FlightLegPlan? = error("not legacy")
        override suspend fun save(plan: FlightLegPlan) = error("not legacy")
        override suspend fun loadDay(date: String, timezone: String, today: String) =
            if (date == DATE) current else tomorrow()
        override suspend fun saveDay(day: DailyFlightPlan): DailyFlightPlan { saves += day; current = day; return day }
    }
    private companion object {
        const val DATE = "2026-10-31"; const val TOMORROW = "2026-11-01"; const val ZONE = "Asia/Shanghai"
        val NOW = ZonedDateTime.parse("2026-10-31T12:34:00+08:00[Asia/Shanghai]")
        val PEK = PlannedAirport(1, "PEK", "首都", "北京", "CN", 40.08, 116.58)
        val PVG = PlannedAirport(2, "PVG", "浦东", "上海", "CN", 31.14, 121.8)
        val CAN = PlannedAirport(3, "CAN", "白云", "广州", "CN", 23.39, 113.3)
        val HKG = PlannedAirport(4, "HKG", "香港", "香港", "CN", 22.3, 113.9)
        val AIRPORTS = listOf(PEK, PVG, CAN, HKG)
    }
}
