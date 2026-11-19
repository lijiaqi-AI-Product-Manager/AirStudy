package com.projectfocus.app.booking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.PlannedDeparture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FlightPreparationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<FlightPreparationViewModel>()

    @get:Rule
    val mainDispatcher: TestRule = object : TestWatcher() {
        override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }

    @After
    fun closeModels() { models.forEach { it.viewModelScope.cancel() } }

    @Test
    fun `explicit airports prepare the real route without saving or starting`() = runTest(dispatcher) {
        val actions = FakeActions()
        val store = FakeStore(FlightLegPlan.between(CAN, PVG))
        val vm = model(actions, store, selectedArguments())
        advanceUntilIdle()

        assertThat(vm.state.value.plan).isEqualTo(FlightLegPlan.between(PEK, PVG))
        assertThat(vm.state.value.confirmed).isFalse()
        assertThat(vm.state.value.loading).isFalse()
        assertThat(store.dayLoads).containsExactly(FIXED_DEPARTURE.date, "2026-11-01").inOrder()
        assertThat(store.saves).isEmpty()
        assertThat(actions.starts).isEmpty()
    }

    @Test
    fun `confirmed full snapshot restores without replacing its airports from a new lookup`() = runTest(dispatcher) {
        val snapshot = FlightLegPlan.between(PEK, PVG).copy(seat = "01A", categoryId = 7L)
        val actions = FakeActions()
        val vm = model(actions, FakeStore(snapshot))
        advanceUntilIdle()

        assertThat(vm.state.value.plan).isEqualTo(snapshot)
        assertThat(vm.state.value.confirmed).isTrue()
        assertThat(actions.lookups).isEmpty()
        assertThat(actions.starts).isEmpty()
    }

    @Test
    fun `late initial load cannot overwrite a newly edited route`() = runTest(dispatcher) {
        val gate = CompletableDeferred<FlightLegPlan?>()
        val store = FakeStore().apply { onLoad = { withContext(NonCancellable) { gate.await() } } }
        val vm = model(FakeActions(), store)
        runCurrent()
        vm.selectAirport("CAN", asOrigin = true)
        vm.selectAirport("PVG", asOrigin = false)
        runCurrent()
        gate.complete(FlightLegPlan.between(PEK, PVG))
        advanceUntilIdle()

        assertThat(vm.state.value.plan).isEqualTo(FlightLegPlan.between(CAN, PVG))
        assertThat(vm.state.value.confirmed).isFalse()
    }

    @Test
    fun `late airport lookup cannot overwrite the next selection`() = runTest(dispatcher) {
        val actions = FakeActions()
        val vm = model(actions, FakeStore(), selectedArguments())
        advanceUntilIdle()
        val gate = CompletableDeferred<PlannedAirport?>()
        actions.onLookup = { code ->
            if (code == "CAN") withContext(NonCancellable) { gate.await() } else AIRPORTS[code]
        }
        vm.selectAirport("CAN", asOrigin = true)
        runCurrent()
        vm.selectAirport("HKG", asOrigin = true)
        runCurrent()
        gate.complete(CAN)
        advanceUntilIdle()

        assertThat(vm.state.value.origin).isEqualTo(HKG)
        assertThat(vm.state.value.plan).isEqualTo(FlightLegPlan.between(HKG, PVG))
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun `failed confirmation remains unconfirmed and cannot start`() = runTest(dispatcher) {
        val store = FakeStore().apply { onSave = { throw IOException("disk unavailable") } }
        val actions = FakeActions()
        val vm = model(actions, store, selectedArguments())
        advanceUntilIdle()
        preview(vm)
        vm.confirmPlan()
        advanceUntilIdle()

        assertThat(vm.state.value.error).isEqualTo(PreparationError.SAVE_FAILED)
        assertThat(vm.state.value.confirmed).isFalse()
        assertThat(vm.state.value.busy).isFalse()
        vm.startFlight()
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(PreparationError.NOT_CONFIRMED)
        assertThat(actions.starts).isEmpty()
    }

    @Test
    fun `save only confirms and rapid start clicks submit the same snapshot once`() = runTest(dispatcher) {
        val actions = FakeActions()
        val store = FakeStore()
        val vm = model(actions, store, selectedArguments())
        advanceUntilIdle()
        vm.selectSeat("01A")
        preview(vm)
        vm.confirmPlan()
        preview(vm)
        vm.confirmPlan()
        advanceUntilIdle()
        val snapshot = store.persisted!!
        assertThat(snapshot.seat).isEqualTo("01A")
        assertThat(vm.state.value.confirmed).isTrue()
        assertThat(store.saves).containsExactly(snapshot)
        assertThat(actions.starts).isEmpty()

        val gate = CompletableDeferred<Boolean>()
        actions.onStart = { gate.await() }
        vm.startFlight()
        vm.startFlight()
        runCurrent()
        vm.selectAirport("CAN", asOrigin = true)
        vm.selectSeat("02C")
        assertThat(vm.state.value.plan).isEqualTo(snapshot)
        assertThat(actions.starts).containsExactly(snapshot)
        gate.complete(true)
        advanceUntilIdle()
        vm.startFlight()
        advanceUntilIdle()
        assertThat(actions.starts).containsExactly(snapshot)
        assertThat(vm.state.value.started).isTrue()
        assertThat(vm.state.value.busy).isFalse()
    }

    @Test
    fun `editing a confirmed destination preserves its source seat and unconfirmed process state`() = runTest(dispatcher) {
        val saved = selectedArguments()
        val store = FakeStore()
        val vm = model(FakeActions(), store, saved)
        advanceUntilIdle()
        vm.selectSeat("01A")
        preview(vm)
        vm.confirmPlan()
        advanceUntilIdle()
        val previousConfirmed = store.persisted
        vm.selectAirport("CAN", asOrigin = false)
        vm.selectSeat("01A") // An old route's result cannot be applied while the new route is loading.
        advanceUntilIdle()
        assertThat(vm.state.value.plan?.seat).isEqualTo("01A")
        assertThat(vm.state.value.confirmed).isFalse()
        assertThat(store.persisted).isEqualTo(previousConfirmed)

        val restored = model(FakeActions(), store, copiedState(saved))
        advanceUntilIdle()
        assertThat(restored.state.value.plan).isEqualTo(FlightLegPlan.between(PEK, CAN).copy(seat = "01A", plannedDeparture = FIXED_DEPARTURE))
        assertThat(restored.state.value.confirmed).isFalse()
        assertThat(store.dayLoads.count { it == FIXED_DEPARTURE.date }).isEqualTo(1)
        assertThat(store.dayLoads.filter { it != FIXED_DEPARTURE.date }).containsExactly("2026-11-01", "2026-11-01")
    }

    @Test
    fun `editing during save cannot let an old confirmation claim the new route`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore().apply { onSave = { withContext(NonCancellable) { gate.await() } } }
        val vm = model(FakeActions(), store, selectedArguments())
        advanceUntilIdle()
        preview(vm)
        vm.confirmPlan()
        runCurrent()
        vm.selectAirport("CAN", asOrigin = true)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.state.value.plan).isEqualTo(FlightLegPlan.between(CAN, PVG).copy(plannedDeparture = FIXED_DEPARTURE))
        assertThat(vm.state.value.confirmed).isFalse()
        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `confirmed saved state retains the exact seat and snapshot across recreation`() = runTest(dispatcher) {
        val saved = selectedArguments()
        val store = FakeStore()
        val vm = model(FakeActions(), store, saved)
        advanceUntilIdle()
        vm.selectSeat("02C")
        preview(vm)
        vm.confirmPlan()
        advanceUntilIdle()
        val restored = model(FakeActions(), store, copiedState(saved))
        advanceUntilIdle()
        assertThat(restored.state.value.plan).isEqualTo(store.persisted)
        assertThat(restored.state.value.plan?.seat).isEqualTo("02C")
        assertThat(restored.state.value.confirmed).isTrue()
        assertThat(store.dayLoads.count { it == FIXED_DEPARTURE.date }).isEqualTo(1)
        assertThat(store.dayLoads.filter { it != FIXED_DEPARTURE.date }).containsExactly("2026-11-01", "2026-11-01")
    }

    @Test
    fun `active session rejects start without submitting another session`() = runTest(dispatcher) {
        val actions = FakeActions().apply { active = true }
        val vm = model(actions, FakeStore(FlightLegPlan.between(PEK, PVG)))
        advanceUntilIdle()
        vm.startFlight()
        advanceUntilIdle()
        assertThat(actions.starts).isEmpty()
        assertThat(vm.state.value.error).isEqualTo(PreparationError.ACTIVE_SESSION)
        assertThat(vm.state.value.started).isFalse()
        assertThat(vm.state.value.busy).isFalse()
    }

    @Test
    fun `confirmation cancellation propagates and releases busy without reporting a disk failure`() = runTest(dispatcher) {
        var savingJob: Job? = null
        val store = FakeStore().apply { onSave = {
            savingJob = currentCoroutineContext()[Job]
            throw CancellationException("confirmation cancelled")
        } }
        val vm = model(FakeActions(), store, selectedArguments())
        advanceUntilIdle()
        preview(vm)
        vm.confirmPlan()
        advanceUntilIdle()
        assertThat(savingJob?.isCancelled).isTrue()
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.confirmed).isFalse()
        assertThat(vm.state.value.busy).isFalse()
    }

    @Test
    fun `start cancellation propagates and retry uses the same already confirmed snapshot`() = runTest(dispatcher) {
        var startingJob: Job? = null
        val actions = FakeActions().apply { onStart = {
            startingJob = currentCoroutineContext()[Job]
            throw CancellationException("start cancelled")
        } }
        val snapshot = FlightLegPlan.between(PEK, PVG)
        val vm = model(actions, FakeStore(snapshot))
        advanceUntilIdle()
        vm.startFlight()
        advanceUntilIdle()
        assertThat(startingJob?.isCancelled).isTrue()
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.started).isFalse()
        assertThat(vm.state.value.busy).isFalse()
        actions.onStart = { true }
        vm.startFlight()
        advanceUntilIdle()
        assertThat(actions.starts).containsExactly(snapshot, snapshot).inOrder()
        assertThat(vm.state.value.started).isTrue()
    }

    @Test
    fun `unknown airports invalid routes and lookup errors remain distinguishable`() = runTest(dispatcher) {
        val actions = FakeActions()
        val vm = model(actions, FakeStore(), selectedArguments())
        advanceUntilIdle()
        vm.selectAirport("missing", asOrigin = false)
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(PreparationError.MISSING_AIRPORT)
        vm.selectAirport("PEK", asOrigin = false)
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(PreparationError.INVALID_ROUTE)
        actions.onLookup = { throw IOException("database unavailable") }
        vm.selectAirport("PVG", asOrigin = false)
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(PreparationError.LOAD_FAILED)
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.plan).isNull()
    }

    @Test
    fun `load and start failures expose recoverable errors without false success`() = runTest(dispatcher) {
        val store = FakeStore().apply { onLoad = { throw IOException("bad snapshot") } }
        val actions = FakeActions().apply { onStart = { throw IOException("start failed") } }
        val vm = model(actions, store)
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(PreparationError.LOAD_FAILED)
        assertThat(vm.state.value.loading).isFalse()
        store.onLoad = null // A healthy whole-day read is required before any full-list replacement.
        vm.selectAirport("PEK", asOrigin = true)
        vm.selectAirport("PVG", asOrigin = false)
        advanceUntilIdle()
        preview(vm)
        vm.confirmPlan()
        advanceUntilIdle()
        vm.startFlight()
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(PreparationError.START_FAILED)
        assertThat(vm.state.value.started).isFalse()
        assertThat(vm.state.value.busy).isFalse()
    }

    @Test
    fun `invalid seat labels cannot replace the confirmed seat or persist another snapshot`() = runTest(dispatcher) {
        val snapshot = FlightLegPlan.between(PEK, PVG).copy(seat = "01A")
        val store = FakeStore(snapshot)
        val vm = model(FakeActions(), store)
        advanceUntilIdle()
        for (invalid in listOf("1A", "01B", "00A", "31A", "-")) {
            vm.selectSeat(invalid)
            assertThat(vm.state.value.plan).isEqualTo(snapshot)
            assertThat(vm.state.value.confirmed).isTrue()
            assertThat(vm.state.value.error).isEqualTo(PreparationError.INVALID_ROUTE)
            assertThat(store.persisted).isEqualTo(snapshot)
        }
        assertThat(store.saves).isEmpty()
    }

    @Test
    fun `absent seat remains null and results without a current route cannot seed a later route`() = runTest(dispatcher) {
        val snapshot = FlightLegPlan.between(PEK, PVG)
        val store = FakeStore(snapshot)
        val vm = model(FakeActions(), store)
        advanceUntilIdle()
        for (missing in listOf(null, "", "  ")) {
            vm.selectSeat(missing)
            assertThat(vm.state.value.plan).isEqualTo(snapshot)
            assertThat(vm.state.value.plan?.seat).isNull()
            assertThat(vm.state.value.confirmed).isTrue()
            assertThat(vm.state.value.error).isNull()
        }

        val empty = model(FakeActions(), FakeStore())
        advanceUntilIdle()
        empty.selectSeat("01A")
        empty.selectSeat(null)
        assertThat(empty.state.value.plan).isNull()
        empty.selectAirport("PEK", asOrigin = true)
        empty.selectAirport("PVG", asOrigin = false)
        advanceUntilIdle()
        assertThat(empty.state.value.plan?.seat).isNull()
        assertThat(empty.state.value.confirmed).isFalse()
        assertThat(store.saves).isEmpty()
    }

    @Test
    fun `three steps and preview never save while final confirmation keeps scene clock and category independent`() = runTest(dispatcher) {
        val sceneA = PlannedScene("first", "A", "study", 0xFF3CC612.toInt(), true, displayName = "A")
        val sceneB = sceneA.copy(optionId = "second", name = "B", displayName = "B")
        val actions = FakeActions()
        val store = FakeStore()
        var now = FIXED_NOW
        val vm = model(actions, store, selectedArguments(), PreparationSceneSource { listOf(sceneA, sceneB) }, PreparationClock { now })
        advanceUntilIdle()
        vm.confirmPlan()
        assertThat(vm.state.value.error).isEqualTo(PreparationError.NOT_PREVIEWED)
        assertThat(store.saves).isEmpty()
        vm.nextDestination()
        assertThat(vm.state.value.stage).isEqualTo(PreparationStage.SCENE)
        assertThat(vm.state.value.scene).isEqualTo(sceneA)
        vm.selectScene(sceneB)
        assertThat(vm.state.value.stage).isEqualTo(PreparationStage.SCENE)
        vm.nextScene()
        assertThat(vm.state.value.departureClock).isEqualTo(FIXED_DEPARTURE)
        vm.selectDepartureTime(12, 35)
        vm.previewPlan()
        assertThat(vm.state.value.stage).isEqualTo(PreparationStage.PREVIEW)
        assertThat(vm.state.value.confirmed).isFalse()
        assertThat(store.saves).isEmpty()
        assertThat(actions.starts).isEmpty()
        vm.confirmPlan()
        advanceUntilIdle()
        val saved = store.persisted!!
        assertThat(saved.scene).isEqualTo(sceneB)
        assertThat(saved.categoryId).isNull()
        assertThat(saved.plannedDeparture).isEqualTo(FIXED_DEPARTURE.copy(minute = 35))
        assertThat(vm.state.value.stage).isEqualTo(PreparationStage.EXPANDED)
        vm.startFlight()
        advanceUntilIdle()
        assertThat(actions.starts).containsExactly(saved)
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.started).isTrue()
        // The planned clock survives an early manual start. P/w0 do not impose
        // the previously invented scheduled-time admission gate.
        assertThat(actions.starts.single().plannedDeparture).isEqualTo(FIXED_DEPARTURE.copy(minute = 35))
        now = now.plusMinutes(1)
        vm.refreshClock()
        vm.startFlight()
        advanceUntilIdle()
        assertThat(actions.starts).containsExactly(saved)
    }

    @Test
    fun `new cancel returns compact without saving and edit cancel restores the original whole snapshot`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = model(FakeActions(), store, selectedArguments())
        advanceUntilIdle()
        vm.nextDestination()
        vm.nextScene()
        vm.back()
        assertThat(vm.state.value.stage).isEqualTo(PreparationStage.SCENE)
        vm.back()
        assertThat(vm.state.value.stage).isEqualTo(PreparationStage.DESTINATION)
        vm.back()
        assertThat(vm.state.value.stage).isEqualTo(PreparationStage.COMPACT)
        assertThat(store.saves).isEmpty()

        val original = FlightLegPlan.between(PEK, PVG).copy(seat = "12A", categoryId = 7L,
            scene = PlannedScene("saved", "晨读", "reading", 0xFFFDAA0F.toInt(), true, displayName = "晨读"),
            plannedDeparture = FIXED_DEPARTURE)
        val savedStore = FakeStore(original)
        val edit = model(FakeActions(), savedStore)
        advanceUntilIdle()
        edit.beginEditing()
        edit.selectAirport("CAN", asOrigin = true)
        advanceUntilIdle()
        edit.nextDestination()
        edit.nextScene()
        edit.selectDepartureTime(13, 45)
        edit.back(); edit.back(); edit.back()
        assertThat(edit.state.value.stage).isEqualTo(PreparationStage.EXPANDED)
        assertThat(edit.state.value.confirmed).isTrue()
        assertThat(edit.state.value.plan).isEqualTo(original)
        assertThat(savedStore.persisted).isEqualTo(original)
        assertThat(savedStore.saves).isEmpty()
    }

    @Test
    fun `empty source scenes stay null and past clock cannot be previewed`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = model(FakeActions(), store, selectedArguments())
        advanceUntilIdle()
        vm.nextDestination()
        assertThat(vm.state.value.scene).isNull()
        vm.nextScene()
        vm.selectDepartureTime(12, 33)
        assertThat(vm.state.value.timeCanPreview).isFalse()
        vm.previewPlan()
        assertThat(vm.state.value.stage).isEqualTo(PreparationStage.DEPARTURE_TIME)
        assertThat(vm.state.value.error).isEqualTo(PreparationError.INVALID_DEPARTURE)
        assertThat(store.saves).isEmpty()
        vm.selectDepartureTime(12, 34)
        vm.previewPlan()
        vm.confirmPlan()
        advanceUntilIdle()
        assertThat(store.persisted?.scene).isNull()
        assertThat(store.persisted?.categoryId).isNull()
        assertThat(store.persisted?.durationMinutes).isEqualTo(FlightLegPlan.between(PEK, PVG).durationMinutes)
    }

    @Test
    fun `preview scene clock stage and edit baseline survive SavedState restoration without a write`() = runTest(dispatcher) {
        val saved = selectedArguments()
        val store = FakeStore()
        val vm = model(FakeActions(), store, saved)
        advanceUntilIdle()
        preview(vm)
        val snapshot = vm.state.value.plan
        val restored = model(FakeActions(), store, copiedState(saved))
        advanceUntilIdle()
        assertThat(restored.state.value.stage).isEqualTo(PreparationStage.PREVIEW)
        assertThat(restored.state.value.plan).isEqualTo(snapshot)
        assertThat(restored.state.value.confirmed).isFalse()
        assertThat(store.saves).isEmpty()
        restored.confirmPlan()
        advanceUntilIdle()
        assertThat(store.saves).containsExactly(snapshot)
    }

    @Test
    fun `scene load errors do not become an empty successful default and can retry`() = runTest(dispatcher) {
        var unavailable = true
        val source = PreparationSceneSource {
            if (unavailable) throw IOException("unavailable")
            emptyList()
        }
        val vm = model(FakeActions(), FakeStore(), selectedArguments(), source)
        advanceUntilIdle()
        vm.nextDestination()
        assertThat(vm.state.value.stage).isEqualTo(PreparationStage.DESTINATION)
        assertThat(vm.state.value.error).isEqualTo(PreparationError.SCENES_UNAVAILABLE)
        unavailable = false
        vm.nextDestination()
        assertThat(vm.state.value.stage).isEqualTo(PreparationStage.SCENE)
        assertThat(vm.state.value.scene).isNull()
    }

    @Test
    fun assignedNumberInConfirmedPlanReachesTheActualStartSnapshot() = runTest(dispatcher) {
        val plan = FlightLegPlan.between(PEK, PVG).copy(plannedDeparture = FIXED_DEPARTURE,
            seat = "04F", flightNumber = "AS2701")
        val actions = FakeActions()
        val vm = model(actions, FakeStore(plan))
        advanceUntilIdle()
        assertThat(vm.state.value.confirmed).isTrue()
        assertThat(vm.state.value.plan!!.flightNumber).isEqualTo("AS2701")
        vm.startFlight(); advanceUntilIdle()
        assertThat(actions.starts.single()).isEqualTo(plan)
        assertThat(vm.state.value.started).isTrue()
    }

    private fun preview(vm: FlightPreparationViewModel) {
        if (vm.state.value.stage in setOf(PreparationStage.EXPANDED, PreparationStage.COLLAPSED, PreparationStage.COMPACT)) vm.beginEditing()
        if (vm.state.value.stage == PreparationStage.DESTINATION) vm.nextDestination()
        if (vm.state.value.stage == PreparationStage.SCENE) vm.nextScene()
        if (vm.state.value.stage == PreparationStage.DEPARTURE_TIME) vm.previewPlan()
    }

    private fun model(
        actions: FakeActions,
        store: FakeStore,
        saved: SavedStateHandle = SavedStateHandle(),
        source: PreparationSceneSource = PreparationSceneSource { emptyList() },
        clock: PreparationClock = PreparationClock { FIXED_NOW },
    ) = FlightPreparationViewModel(saved, actions, store, source, clock).also { models += it }

    private fun selectedArguments() = SavedStateHandle(mapOf("origin" to " pek ", "destination" to "PVG"))
    private fun copiedState(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private class FakeActions : FlightPreparationActions {
        val lookups = mutableListOf<String>()
        val starts = mutableListOf<FlightLegPlan>()
        var active = false
        var onLookup: suspend (String) -> PlannedAirport? = { AIRPORTS[it] }
        var onStart: suspend (FlightLegPlan) -> Boolean = { true }
        override suspend fun findAirport(code: String): PlannedAirport? {
            lookups += code
            return onLookup(code)
        }
        override suspend fun startFlight(plan: FlightLegPlan): Boolean {
            starts += plan
            return onStart(plan).also { if (it) active = true }
        }
        override fun hasActiveSession(): Boolean = active
    }

    private class FakeStore(var persisted: FlightLegPlan? = null) : ConfirmedFlightPlanStore {
        var loads = 0
        val dayLoads = mutableListOf<String>()
        val saves = mutableListOf<FlightLegPlan>()
        var onLoad: (suspend () -> FlightLegPlan?)? = null
        var onSave: (suspend (FlightLegPlan) -> Unit)? = null
        override suspend fun load(): FlightLegPlan? {
            loads++
            val loader = onLoad
            return if (loader != null) loader() else persisted
        }
        override suspend fun loadDay(date: String, timezone: String, today: String): DailyFlightPlan {
            dayLoads += date
            return super<ConfirmedFlightPlanStore>.loadDay(date, timezone, today)
        }
        override suspend fun save(plan: FlightLegPlan) {
            saves += plan
            onSave?.invoke(plan)
            persisted = plan
        }
    }

    private companion object {
        val FIXED_NOW = java.time.ZonedDateTime.of(2026, 10, 31, 12, 34, 0, 0, java.time.ZoneId.of("Asia/Shanghai"))
        val FIXED_DEPARTURE = com.projectfocus.core.model.flight.PlannedDeparture("2026-10-31", 12, 34, "Asia/Shanghai")
        val PEK = PlannedAirport(1, "PEK", "首都机场", "北京", "中国", 40.08, 116.58)
        val PVG = PlannedAirport(2, "PVG", "浦东机场", "上海", "中国", 31.14, 121.8)
        val CAN = PlannedAirport(3, "CAN", "白云机场", "广州", "中国", 23.39, 113.3)
        val HKG = PlannedAirport(4, "HKG", "香港机场", "香港", "中国", 22.3, 113.9)
        val AIRPORTS = listOf(PEK, PVG, CAN, HKG).associateBy { it.code }
    }
}
