package com.projectfocus.app.booking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.common.seat.SeatMapGeometry
import com.projectfocus.core.common.plan.SourceTakeoffClock
import com.projectfocus.core.common.plan.SourceFlightPlanTiming
import com.projectfocus.core.common.plan.SourceFlightPlanStatus
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedDeparture
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import javax.inject.Inject

enum class PreparationStage { DESTINATION, SCENE, DEPARTURE_TIME, PREVIEW, COMPACT, EXPANDED, COLLAPSED }

data class PreparationUiState(
    val originCode: String? = null,
    val destinationCode: String? = null,
    val origin: PlannedAirport? = null,
    val destination: PlannedAirport? = null,
    val plan: FlightLegPlan? = null,
    val confirmed: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val started: Boolean = false,
    val error: PreparationError? = null,
    val stage: PreparationStage = PreparationStage.DESTINATION,
    val scene: PlannedScene? = null,
    val departureClock: PlannedDeparture? = null,
    val editing: Boolean = false,
    val departureDue: Boolean = true,
    val timeCanPreview: Boolean = false,
    val earliestDeparture: String = "",
    val dailyPlan: DailyFlightPlan? = null,
    val selectedDate: String = "",
    val timezone: String = "",
    val selectedLegIndex: Int? = null,
    val canAddSegment: Boolean = false,
    val canBrowseNextDate: Boolean = true,
    val previousArrivalText: String? = null,
    val todayBlocked: Boolean = false,
    val tomorrowBlockedByCanceledToday: Boolean = false,
    val planEnabled: Boolean = false,
    val planOperationBusy: Boolean = false,
    val entitlementGateVisible: Boolean = false,
    val entitlementGateAuthenticated: Boolean = false,
    val pendingGlobalSeat: PendingPlanSeatSelection? = null,
    val globalSeatText: String? = null,
    val completionReceipts: Map<Int, Long> = emptyMap(),
)

enum class PreparationError {
    MISSING_AIRPORT, INVALID_ROUTE, LOAD_FAILED, SAVE_FAILED, START_FAILED, ACTIVE_SESSION, NOT_CONFIRMED,
    SCENES_UNAVAILABLE, INVALID_DEPARTURE, NOT_PREVIEWED, DEPARTURE_NOT_DUE,
    PLAN_OPERATION_UNAVAILABLE,
}

@HiltViewModel
class FlightPreparationViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val actions: FlightPreparationActions,
    private val store: ConfirmedFlightPlanStore,
    private val scenes: PreparationSceneSource,
    private val clock: PreparationClock,
    private val runtime: DailyFlightPlanRuntime,
    private val entitlement: PlanEntitlementAccess,
    private val globalPlan: GlobalFlightPlanStore,
    private val completion: PlanCompletionActions,
) : ViewModel() {
    /** Older direct callers still exercise a real provider, without Hilt's shared lifetime. */
    constructor(savedState: SavedStateHandle, actions: FlightPreparationActions, store: ConfirmedFlightPlanStore,
                scenes: PreparationSceneSource, clock: PreparationClock) :
        this(savedState, actions, store, scenes, clock, DailyFlightPlanRuntime(clock))

    constructor(savedState: SavedStateHandle, actions: FlightPreparationActions, store: ConfirmedFlightPlanStore,
                scenes: PreparationSceneSource, clock: PreparationClock, runtime: DailyFlightPlanRuntime) :
        this(savedState, actions, store, scenes, clock, runtime, AnonymousPlanEntitlementAccess,
            MemoryGlobalFlightPlanStore(clock), UnconnectedPlanCompletionActions)

    private val mutableState = MutableStateFlow(PreparationUiState())
    val state: StateFlow<PreparationUiState> = mutableState.asStateFlow()
    private val mutableBatchDeletionEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val batchDeletionEvents = mutableBatchDeletionEvents.asSharedFlow()
    private var revision = 0L
    private var lookupJob: Job? = null
    private var confirmationJob: Job? = null
    private var starting = false
    private var confirmedSnapshot: FlightLegPlan? = null
    private var editingBase: FlightLegPlan? = null
    private var editingIndex: Int? = null
    private var draftLegNumber: Int? = null
    // Standalone host seat-result adapter; ordinary source s edits keep the old seat.
    private var seatOverrideOnSave = false
    private var currentDay = DailyFlightPlans.empty(clock.now().toLocalDate().toString(), clock.now().zone.id)
        .copy(ownerScope = FlightPlanOwnership.scope())
    private var dayLoaded = false
    private val stateJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val planSwitch by lazy {
        PlanEnableController(viewModelScope, entitlement, globalPlan, clock, { currentDay }, {
            state.value.plan?.takeIf { state.value.stage in setOf(PreparationStage.DESTINATION, PreparationStage.SCENE,
                PreparationStage.DEPARTURE_TIME, PreparationStage.PREVIEW) }?.let {
                DailyFlightPlans.fromRoute(it, currentDay.localDate, currentDay.timezone, draftLegNumber ?: (currentDay.legs.size + 1))
            }
        }, { revision }, { actions.currentOriginCode() }, {
            store.loadDay(clock.now().toLocalDate().toString(), currentDay.timezone, clock.now().toLocalDate().toString())
        }, { loaded -> installDay(loaded); showDay() }, { actions.hasActualFlight() }, { actions.hasFlightArchive() },
            { selection -> completion.complete(selection) }, { completion.recoverySelection() },
            reconciliation = completion.originReconciliation)
    }
    val planEnableMessages get() = planSwitch.messages
    fun bindPlanHost(token: String) = planSwitch.bindHost(token)
    fun togglePlan(enabled: Boolean) {
        if (state.value.loading || state.value.busy || state.value.started) return
        if (!FlightPlanOwnership.isCurrent(currentDay)) { reloadForIdentity(); return }
        planSwitch.toggle(enabled)
    }
    fun globalSeatNavigationOpened(requestId: String) = planSwitch.navigationOpened(requestId)
    fun acceptGlobalSeat(requestId: String, seat: String?) = planSwitch.acceptSeat(requestId, seat)
    fun refreshPlanEntitlement() {
        planSwitch.refreshGate()
        if (!FlightPlanOwnership.isCurrent(currentDay)) reloadForIdentity()
    }

    /** Source state4→5. An empty enabled list stays null; no fabricated study/category. */
    fun nextDestination() {
        val current = state.value
        if (current.busy || current.loading || current.started) return
        if (current.stage in setOf(PreparationStage.COLLAPSED, PreparationStage.EXPANDED, PreparationStage.COMPACT)) {
            beginEditing()
            return
        }
        if (current.stage != PreparationStage.DESTINATION) return
        enterScene(current)
    }

    private fun enterScene(current: PreparationUiState) {
        try {
            val selected = current.scene ?: scenes.enabledScenes().firstOrNull()
            publish(current.copy(stage = PreparationStage.SCENE, scene = selected,
                error = if (current.destination == null) PreparationError.MISSING_AIRPORT else null))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            publish(current.copy(error = PreparationError.SCENES_UNAVAILABLE))
        }
    }

    fun selectScene(scene: PlannedScene) {
        val current = state.value
        if (starting || current.started || current.loading || current.stage != PreparationStage.SCENE) return
        if (current.scene == scene) return
        invalidate()
        publish(current.copy(scene = scene, confirmed = false, busy = false, error = null))
    }

    /** Source state5→6, including the legitimate scene=null branch. */
    fun nextScene() {
        val current = state.value
        if (current.busy || current.loading || current.started || current.stage != PreparationStage.SCENE) return
        val now = clock.now()
        val defaultClock = previousArrivalClock()?.let { SourceTakeoffClock.shift(5, it) }
            ?: if (currentDay.localDate == now.toLocalDate().toString() ||
                !SourceTakeoffClock.newDateSupported(currentDay.localDate, now.toLocalDate()))
                SourceTakeoffClock.format(now.hour * 60 + now.minute) else "00:00"
        val minutes = SourceTakeoffClock.parseMinutes(defaultClock) ?: return
        val departure = current.departureClock ?: PlannedDeparture(currentDay.localDate,
            minutes / 60, minutes % 60, currentDay.timezone)
        publish(current.copy(stage = PreparationStage.DEPARTURE_TIME, departureClock = departure, error = null))
    }

    fun selectDepartureTime(hour: Int, minute: Int) {
        val current = state.value
        if (starting || current.started || current.loading || current.stage != PreparationStage.DEPARTURE_TIME) return
        if (hour !in 0..23 || minute !in 0..59) return
        val old = current.departureClock ?: return
        if (old.hour == hour && old.minute == minute) return
        invalidate()
        publish(current.copy(departureClock = old.copy(hour = hour, minute = minute),
            confirmed = false, busy = false, error = null))
    }

    /** Source state6→3/J=true is a preview only, never a confirmed-store write. */
    fun previewPlan() {
        val current = state.value
        if (current.busy || current.loading || current.started || current.stage != PreparationStage.DEPARTURE_TIME) return
        if (current.plan == null) {
            publish(current.copy(error = if (current.origin == null || current.destination == null)
                PreparationError.MISSING_AIRPORT else PreparationError.INVALID_ROUTE))
            return
        }
        if (!departureTimeValid(current.departureClock)) {
            publish(current.copy(error = PreparationError.INVALID_DEPARTURE))
            return
        }
        invalidate()
        publish(current.copy(stage = PreparationStage.PREVIEW, confirmed = false, busy = false, error = null))
    }

    /** Source floating arrow semantics; Android system Back is an explicit host adapter. */
    fun back(): Boolean {
        val current = state.value
        if (current.busy || current.started || starting) return true
        when (current.stage) {
            PreparationStage.DEPARTURE_TIME -> enterScene(current)
            PreparationStage.SCENE -> publish(current.copy(stage = PreparationStage.DESTINATION, error = null))
            PreparationStage.DESTINATION -> {
                invalidate()
                val original = editingBase
                if (current.editing && original != null) {
                    editingBase = null
                    confirmedSnapshot = original
                    if (currentDay.legs.isNotEmpty()) showDay() else publish(PreparationUiState(originCode = original.departure.code,
                        destinationCode = original.destination.code, origin = original.departure,
                        destination = original.destination, plan = original, confirmed = true,
                        stage = PreparationStage.EXPANDED, scene = original.scene,
                        departureClock = original.plannedDeparture, selectedLegIndex = editingIndex))
                    editingIndex = null
                } else if (currentDay.legs.isNotEmpty()) {
                    showDay()
                } else publish(current.copy(stage = PreparationStage.COMPACT, confirmed = false, busy = false, loading = false, error = null))
            }
            else -> return false
        }
        return true
    }

    fun beginEditing() {
        val current = state.value
        if (current.loading || current.busy || current.started) return
        if (current.confirmed && current.plan != null) {
            val index = current.selectedLegIndex
            if (index != null && !canEdit(index)) { unavailable(); return }
            editingBase = current.plan
            editingIndex = index
            draftLegNumber = index?.let { currentDay.displayLegs().getOrNull(it)?.snapshot?.legIndex }
            seatOverrideOnSave = false
        }
        invalidate()
        publish(current.copy(stage = PreparationStage.DESTINATION,
            editing = editingBase != null, confirmed = false, error = null))
    }

    fun expand(expanded: Boolean) {
        val current = state.value
        if (current.loading || current.busy || current.started || current.stage !in setOf(
                PreparationStage.EXPANDED, PreparationStage.COLLAPSED, PreparationStage.COMPACT)) return
        publish(current.copy(stage = if (expanded) PreparationStage.EXPANDED else PreparationStage.COLLAPSED))
    }

    fun browseDate(offset: Int) {
        val current = state.value
        if (offset !in setOf(-1, 1) || current.busy || current.loading || current.started ||
            current.stage !in setOf(PreparationStage.EXPANDED, PreparationStage.COLLAPSED, PreparationStage.COMPACT)) return
        val date = try { LocalDate.parse(currentDay.localDate).plusDays(offset.toLong()).toString() }
            catch (_: RuntimeException) { return }
        if (LocalDate.parse(date).isAfter(clock.now().toLocalDate().plusDays(1))) return
        invalidate()
        editingBase = null
        editingIndex = null
        draftLegNumber = null
        seatOverrideOnSave = false
        currentDay = DailyFlightPlans.empty(date, currentDay.timezone)
        dayLoaded = false
        runtime.selectDate(date)
        publish(PreparationUiState(stage = PreparationStage.EXPANDED, loading = true))
        loadConfirmed()
    }

    /** Source's backward non-cancelled endpoint scan, followed by the real three-step editor. */
    fun addSegment() {
        val current = state.value
        if (current.busy || current.loading || current.started || !canAdd()) return
        invalidate()
        editingBase = null
        editingIndex = null
        seatOverrideOnSave = false
        draftLegNumber = currentDay.legs.size + 1
        if (currentDay.legs.isNotEmpty() && !currentDay.allTerminal()) {
            val origin = currentDay.nextOrigin()
            publish(PreparationUiState(originCode = origin?.code, origin = origin, stage = PreparationStage.DESTINATION))
            return
        }
        val expectedRevision = revision
        publish(PreparationUiState(stage = PreparationStage.DESTINATION, loading = true))
        lookupJob = viewModelScope.launch {
            try {
                var origin = if (currentDay.allTerminal()) actions.defaultOrigin() else null
                if (origin == null) {
                    val today = runtime.snapshot().today?.legs.orEmpty()
                    if (today.any { SourceFlightPlanStatus.parse(it.snapshot.legStatus) in
                            setOf(SourceFlightPlanStatus.WAITING, SourceFlightPlanStatus.IN_PROGRESS) }) {
                        val previous = today.asReversed().firstOrNull {
                            SourceFlightPlanStatus.parse(it.snapshot.legStatus) != SourceFlightPlanStatus.CANCELED
                        }?.snapshot?.arrivalAirport
                        origin = previous?.let { PreparationOrigin(it.code, DailyFlightPlans.plannedAirport(it)) }
                    }
                }
                if (origin == null) origin = actions.defaultOrigin()
                if (origin == null) origin = actions.lastArrivalOrigin()
                currentCoroutineContext().ensureActive()
                if (revision == expectedRevision) publish(state.value.copy(originCode = origin?.code, origin = origin?.airport))
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (revision == expectedRevision) publish(state.value.copy(error = PreparationError.LOAD_FAILED))
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (revision == expectedRevision) publish(state.value.copy(error = PreparationError.LOAD_FAILED))
            } finally {
                if (revision == expectedRevision) publish(state.value.copy(loading = false))
            }
        }
    }

    fun segmentId(index: Int): String = "${currentDay.localDate}@${currentDay.timezone}:$index:${currentDay.displayLegs()[index].snapshot.hashCode()}"

    fun canEdit(index: Int): Boolean {
        val leg = currentDay.displayLegs().getOrNull(index) ?: return false
        return index == currentDay.legs.lastIndex && leg.route != null &&
            !runtime.todayBlocked(currentDay.localDate) &&
            SourceFlightPlanStatus.parse(leg.snapshot.legStatus) == SourceFlightPlanStatus.WAITING &&
            !FlightPlanCardPresentation.canceled(leg.snapshot, clock.now().toLocalDate(), clock.now().zone) &&
            !leg.snapshot.archived && !leg.snapshot.readOnly &&
            SourceTakeoffClock.newDateSupported(currentDay.localDate, clock.now().toLocalDate())
    }

    // Cancellation/batch-delete have distinct source operations. Do not substitute this full-list delete for them.
    fun canRemove(index: Int): Boolean {
        val leg = currentDay.displayLegs().getOrNull(index)?.snapshot ?: return false
        return index == currentDay.legs.lastIndex && !runtime.todayBlocked(currentDay.localDate) &&
            ((SourceFlightPlanStatus.parse(leg.legStatus) == SourceFlightPlanStatus.WAITING && !leg.archived && !leg.readOnly &&
                !FlightPlanCardPresentation.canceled(leg, clock.now().toLocalDate(), clock.now().zone)) ||
                canDeleteCanceled(index)) && SourceTakeoffClock.newDateSupported(currentDay.localDate, clock.now().toLocalDate())
    }

    fun canDeleteCanceled(index: Int): Boolean {
        val leg = currentDay.displayLegs().getOrNull(index)?.snapshot ?: return false
        if (!SourceTakeoffClock.newDateSupported(currentDay.localDate, clock.now().toLocalDate()) ||
            leg.archived || leg.readOnly || SourceFlightPlanStatus.parse(leg.legStatus) != SourceFlightPlanStatus.CANCELED) return false
        return FlightPlanCardPresentation.departureDate(leg, clock.now().zone)?.isBefore(clock.now().toLocalDate()) == false
    }

    fun deleteCanceledSegments(id: String) {
        if (!FlightPlanOwnership.isCurrent(currentDay)) { reloadForIdentity(); return }
        val current = state.value
        if (current.busy || current.loading || current.started) return
        val index = currentDay.legs.indices.firstOrNull { segmentId(it) == id } ?: return
        if (!canDeleteCanceled(index)) { unavailable(); return }
        val base = currentDay
        invalidate()
        val expectedRevision = revision
        publish(current.copy(busy = true, error = null))
        confirmationJob = viewModelScope.launch {
            try {
                val result = store.deleteCanceledDay(base, clock.now().withZoneSameInstant(java.time.ZoneId.of(base.timezone))
                    .toLocalDate().toString())
                currentCoroutineContext().ensureActive()
                if (revision != expectedRevision) return@launch
                require(result.day == null || (result.day.localDate == base.localDate && result.day.timezone == base.timezone))
                // Source success clears the authoritative list then queries the selected
                // date. Never synthesize remaining rows locally for an authenticated call.
                currentDay = base.copy(legs = emptyList())
                dayLoaded = false
                runtime.clearSelectedDay(base.localDate)
                publish(PreparationUiState(stage = PreparationStage.EXPANDED, loading = true))
                mutableBatchDeletionEvents.tryEmit(result.deletedCount)
                loadConfirmed()
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (revision == expectedRevision) { showDay(current.selectedLegIndex); publish(state.value.copy(error = PreparationError.SAVE_FAILED)) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (revision == expectedRevision) { showDay(current.selectedLegIndex); publish(state.value.copy(error = PreparationError.SAVE_FAILED)) }
            } finally {
                if (revision == expectedRevision) publish(state.value.copy(busy = false))
            }
        }
    }

    fun editSegment(id: String) {
        val current = state.value
        if (current.busy || current.loading || current.started) return
        val index = currentDay.legs.indices.firstOrNull { segmentId(it) == id } ?: return
        if (!canEdit(index)) { unavailable(); return }
        val clicked = currentDay.displayLegs()[index]
        val plan = requireNotNull(clicked.route)
        editingBase = plan
        editingIndex = index
        draftLegNumber = clicked.snapshot.legIndex
        seatOverrideOnSave = false
        invalidate()
        publish(PreparationUiState(originCode = plan.departure.code, destinationCode = plan.destination.code,
            origin = plan.departure, destination = plan.destination, plan = plan, scene = plan.scene,
            departureClock = plan.plannedDeparture, editing = true, stage = PreparationStage.DESTINATION,
            selectedLegIndex = index))
    }

    fun removeSegment(id: String) {
        val current = state.value
        if (current.busy || current.loading || current.started) return
        val index = currentDay.legs.indices.firstOrNull { segmentId(it) == id } ?: return
        if (!canRemove(index)) { unavailable(); return }
        val base = currentDay
        invalidate()
        val expectedRevision = revision
        publish(current.copy(busy = true, error = null))
        confirmationJob = viewModelScope.launch {
            try {
                val saved = store.deleteLastDay(base.copy(legs = base.legs.dropLast(1).mapIndexed { i, leg ->
                    leg.copy(snapshot = leg.snapshot.copy(legIndex = i + 1))
                }))
                currentCoroutineContext().ensureActive()
                if (revision == expectedRevision) {
                    require(saved.localDate == base.localDate && saved.timezone == base.timezone)
                    installDay(saved)
                    showDay()
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (revision == expectedRevision) { showDay(current.selectedLegIndex); publish(state.value.copy(error = PreparationError.SAVE_FAILED)) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (revision == expectedRevision) { showDay(current.selectedLegIndex); publish(state.value.copy(error = PreparationError.SAVE_FAILED)) }
            } finally {
                if (revision == expectedRevision) publish(state.value.copy(busy = false))
            }
        }
    }

    private fun canAdd(): Boolean {
        if (!dayLoaded || !SourceTakeoffClock.newDateSupported(currentDay.localDate, clock.now().toLocalDate())) return false
        if (runtime.todayBlocked(currentDay.localDate)) return false
        if (actions.hasActiveSession()) return false // Product host guard; source r/q planner lifecycle remains separate.
        val last = currentDay.legs.lastOrNull()?.snapshot
        if (last != null && SourceFlightPlanStatus.parse(last.legStatus) !in
                setOf(SourceFlightPlanStatus.WAITING, SourceFlightPlanStatus.COMPLETED)) return false
        return !runtime.tomorrowBlockedByCanceledToday(currentDay.localDate)
    }

    private fun unavailable() = publish(state.value.copy(error = PreparationError.PLAN_OPERATION_UNAVAILABLE))

    private fun showDay(preferred: Int? = null) {
        draftLegNumber = null
        seatOverrideOnSave = false
        val displayed = currentDay.displayLegs()
        val index = displayed.indexOfFirst { SourceFlightPlanStatus.parse(it.snapshot.legStatus) == SourceFlightPlanStatus.WAITING && it.route != null }.takeIf { it >= 0 }
            ?: preferred?.takeIf { it in currentDay.legs.indices }
            ?: currentDay.legs.lastIndex.takeIf { it >= 0 }
        val leg = index?.let { displayed[it] }
        val plan = leg?.route
        val confirmed = leg != null && plan != null && SourceFlightPlanStatus.parse(leg.snapshot.legStatus) == SourceFlightPlanStatus.WAITING &&
            !leg.snapshot.archived && !leg.snapshot.readOnly
        confirmedSnapshot = plan.takeIf { confirmed }
        publish(PreparationUiState(originCode = plan?.departure?.code, destinationCode = plan?.destination?.code,
            origin = plan?.departure, destination = plan?.destination, plan = plan, confirmed = confirmed,
            stage = PreparationStage.EXPANDED, scene = plan?.scene, departureClock = plan?.plannedDeparture,
            selectedLegIndex = index, started = state.value.started, busy = starting))
    }

    fun refreshClock() {
        if (!state.value.started && !FlightPlanOwnership.isCurrent(currentDay)) { reloadForIdentity(); return }
        val current = state.value
        val due = departureIsDue(current.plan)
        val valid = departureTimeValid(current.departureClock)
        val earliest = earliestDepartureText()
        if (due != current.departureDue || valid != current.timeCanPreview || earliest != current.earliestDeparture || canAdd() != current.canAddSegment) {
            publish(current.copy(departureDue = due), persist = false)
        }
    }

    fun departureTimeValid(value: PlannedDeparture? = state.value.departureClock, editing: Boolean = state.value.editing): Boolean {
        value ?: return false
        val now = clock.now()
        val date = LocalDate.parse(value.date)

        // timestamp conversion. Do not apply that admission check to legacy edits.
        if (!editing && !SourceTakeoffClock.newDateSupported(value.date, now.toLocalDate())) return false
        return SourceTakeoffClock.canSelect(SourceTakeoffClock.format(value.minuteOfDay), earliestDepartureText())
    }

    fun earliestDepartureText(): String {
        previousArrivalClock()?.let { return SourceTakeoffClock.shift(2, it).orEmpty() }
        val now = clock.now()
        return if (currentDay.localDate == now.toLocalDate().toString() ||
            !SourceTakeoffClock.newDateSupported(currentDay.localDate, now.toLocalDate()))
            SourceTakeoffClock.format(now.hour * 60 + now.minute) else "00:00"
    }

    /** Source v uses the preceding UI-list leg and device-zone HH:mm, even for cancelled entries. */
    private fun previousArrivalClock(): String? {
        val displayed = currentDay.displayLegs()
        val number = draftLegNumber
        val previousIndex = if (number != null && number in 1..displayed.size) number - 2 else displayed.lastIndex
        val previous = displayed.getOrNull(previousIndex)?.snapshot ?: return null
        return try {
            DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(
                Instant.ofEpochMilli(requireNotNull(FlightPlanCardPresentation.instantMillis(previous.scheduledDepartureAt)))
                    .plusMillis(SourceFlightPlanTiming.durationMillis(previous.distanceKm))
                    .atZone(clock.now().zone))
        } catch (_: Exception) { null }
    }

    private fun reloadForIdentity() {
        invalidate()
        editingBase = null
        editingIndex = null
        draftLegNumber = null
        seatOverrideOnSave = false
        currentDay = DailyFlightPlans.empty(currentDay.localDate, currentDay.timezone)
            .copy(ownerScope = FlightPlanOwnership.scope())
        dayLoaded = false
        runtime.selectDate(currentDay.localDate)
        publish(PreparationUiState(stage = PreparationStage.EXPANDED, loading = true))
        loadConfirmed()
    }

    private fun departureIsDue(plan: FlightLegPlan?): Boolean =
        plan?.plannedDeparture?.let { !clock.now().toInstant().isBefore(it.instant()) } ?: true

    init {
        runtime.selectDate(currentDay.localDate)
        if (savedState.get<Boolean>(DRAFT_PRESENT) == true) {
            restoreDraft()
        } else {
            val origin = normalizeCode(savedState[ARG_ORIGIN]) ?: normalizeCode(savedState[LEGACY_ORIGIN])
            val destination = normalizeCode(savedState[ARG_DESTINATION]) ?: normalizeCode(savedState[LEGACY_DESTINATION])
            if (origin != null || destination != null) resolve(origin, destination)
            else loadConfirmed()
        }
        viewModelScope.launch {
            runtime.state.collect {
                // Source callback mutates r without redraw. This shared StateFlow
                // additionally rebinds eligibility; mutations still recheck it.
                publish(state.value, persist = false)
            }
        }
        viewModelScope.launch { planSwitch.state.collect { publish(state.value, persist = false) } }
        viewModelScope.launch { globalPlan.state.collect { publish(state.value, persist = false) } }
        viewModelScope.launch {
            completion.updates.collect { updated ->
                if (updated.ownerScope == FlightPlanOwnership.scope() && updated.localDate == currentDay.localDate &&
                    updated.timezone == currentDay.timezone) {
                    installDay(updated)
                    showDay()
                }
            }
        }
    }

    private fun installDay(day: DailyFlightPlan, prefetch: Boolean = true) {
        currentDay = day
        dayLoaded = true
        runtime.bind(day)
        if (prefetch && day.localDate == clock.now().toLocalDate().toString()) {
            viewModelScope.launch { runtime.prefetchTomorrow(store) }
        }
    }

    fun selectAirport(code: String, asOrigin: Boolean) {
        val current = state.value
        if (starting || current.started) return
        val normalized = normalizeCode(code)
        if (asOrigin && (current.editing || current.confirmed) && normalized != current.originCode) { unavailable(); return }
        if (normalized == (if (asOrigin) current.originCode else current.destinationCode) && current.error == null) return
        if (current.confirmed && current.plan != null) {
            val index = current.selectedLegIndex
            if (index != null && !canEdit(index)) { unavailable(); return }
            editingBase = current.plan
            editingIndex = index
            draftLegNumber = index?.let { currentDay.displayLegs().getOrNull(it)?.snapshot?.legIndex }
        }
        invalidate()
        resolve(
            originCode = if (asOrigin) normalized else current.originCode,
            destinationCode = if (asOrigin) current.destinationCode else normalized,
        )
    }

    fun selectSeat(seat: String?) {
        val current = state.value
        if (starting || current.started || current.loading) return
        val plan = current.plan ?: return
        if (current.confirmed && current.selectedLegIndex?.let { !canEdit(it) } == true) { unavailable(); return }
        val normalized = seat?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
        if (normalized != null && !SeatMapGeometry.isValid(normalized)) {
            publish(current.copy(error = PreparationError.INVALID_ROUTE))
            return
        }
        if (normalized == plan.seat) return
        invalidate()
        if (current.confirmed) editingBase = plan
        if (current.confirmed) editingIndex = current.selectedLegIndex
        if (current.confirmed) {
            draftLegNumber = current.selectedLegIndex?.let { currentDay.displayLegs().getOrNull(it)?.snapshot?.legIndex }
            seatOverrideOnSave = true
        }
        publish(current.copy(plan = plan.copy(seat = normalized), confirmed = false, busy = false, error = null,
            stage = if (current.stage in setOf(PreparationStage.EXPANDED, PreparationStage.COLLAPSED)) PreparationStage.PREVIEW else current.stage,
            editing = editingBase != null))
    }

    fun confirmPlan() {
        val current = state.value
        if (current.busy || current.loading || current.started) return
        if (current.stage != PreparationStage.PREVIEW) {
            publish(current.copy(error = PreparationError.NOT_PREVIEWED))
            return
        }
        val plan = current.plan
        if (plan == null) {
            publish(current.copy(error = if (current.origin == null || current.destination == null) {
                PreparationError.MISSING_AIRPORT
            } else PreparationError.INVALID_ROUTE))
            return
        }
        if (current.confirmed && plan == confirmedSnapshot) return
        val expectedRevision = revision
        confirmedSnapshot = null
        publish(current.copy(busy = true, confirmed = false, error = null))
        confirmationJob = viewModelScope.launch {
            try {
                if (!dayLoaded) {
                    val loaded = store.loadDay(currentDay.localDate, currentDay.timezone, clock.now().toLocalDate().toString())
                    currentCoroutineContext().ensureActive()
                    if (revision != expectedRevision) return@launch
                    require(loaded.localDate == currentDay.localDate && loaded.timezone == currentDay.timezone)
                    installDay(loaded)
                    if (editingBase != null && editingIndex == null) {
                        editingIndex = loaded.displayLegs().indexOfFirst { it.route == editingBase }.takeIf { it >= 0 }
                    }
                }
                val base = currentDay
                val index = editingIndex
                if (index != null && !canEdit(index)) throw IllegalStateException("This leg cannot be edited")
                if (index == null && !canAdd()) throw IllegalStateException("A leg cannot be appended to this plan")
                val legs = base.legs.toMutableList()
                val replacement = DailyFlightPlans.replacement(base, index, plan, seatOverrideOnSave, scenes.studyTitle())
                if (index == null) legs.add(replacement) else legs[index] = replacement
                val saved = store.saveDay(base.copy(legs = legs.mapIndexed { i, leg ->
                    leg.copy(snapshot = leg.snapshot.copy(legIndex = i + 1))
                }))
                currentCoroutineContext().ensureActive()
                if (revision == expectedRevision && state.value.plan == plan) {
                    require(saved.localDate == base.localDate && saved.timezone == base.timezone)
                    installDay(saved)
                    editingBase = null
                    editingIndex = null
                    showDay(index ?: saved.legs.lastIndex)
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (revision == expectedRevision) publish(state.value.copy(error = PreparationError.SAVE_FAILED))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision == expectedRevision) publish(state.value.copy(error = PreparationError.SAVE_FAILED))
            } finally {
                if (revision == expectedRevision) publish(state.value.copy(busy = false))
            }
        }
    }

    fun startFlight() {
        if (!FlightPlanOwnership.isCurrent(currentDay)) { reloadForIdentity(); return }
        val current = state.value
        if (current.busy || current.loading || current.started || starting) return
        val snapshot = confirmedSnapshot
        if (!current.confirmed || snapshot == null || current.plan != snapshot) {
            publish(current.copy(error = PreparationError.NOT_CONFIRMED))
            return
        }
        // P/w0 do not compare the selected scheduled time. The host session
        // still returns its own accepted result; a source dispatch is not acceptance.
        val expectedRevision = revision
        val identity = TokenStore.snapshot()
        starting = true
        publish(current.copy(busy = true, error = null))
        viewModelScope.launch {
            try {
                if (TokenStore.snapshot() != identity) { reloadForIdentity(); return@launch }
                if (actions.hasActiveSession()) {
                    publish(state.value.copy(error = PreparationError.ACTIVE_SESSION))
                } else {
                    val global = globalPlan.current()
                    val actualSnapshot = if (global.enabledDate == clock.now().toLocalDate().toString())
                        snapshot.copy(seat = global.inheritedSeat?.takeIf(String::isNotEmpty) ?: snapshot.seat) else snapshot
                    val accepted = actions.startFlight(actualSnapshot)
                    currentCoroutineContext().ensureActive()
                    if (revision == expectedRevision) {
                        publish(state.value.copy(
                            started = accepted,
                            error = if (accepted) null else if (actions.hasActiveSession()) {
                                PreparationError.ACTIVE_SESSION
                            } else PreparationError.START_FAILED,
                        ))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision == expectedRevision) publish(state.value.copy(error = PreparationError.START_FAILED))
            } finally {
                starting = false
                if (revision == expectedRevision) publish(state.value.copy(busy = false))
            }
        }
    }

    private fun invalidate() {
        revision++
        lookupJob?.cancel()
        confirmationJob?.cancel()
        confirmedSnapshot = null
    }

    private fun resolve(originCode: String?, destinationCode: String?, restoreStage: PreparationStage? = null) {
        val expectedRevision = revision
        val beforeLookup = state.value
        publish(beforeLookup.copy(originCode = originCode, destinationCode = destinationCode,
            origin = null, destination = null, plan = null, confirmed = false, busy = false, loading = true,
            stage = restoreStage ?: PreparationStage.DESTINATION, editing = editingBase != null, error = null))
        lookupJob = viewModelScope.launch {
            try {
                val fromStore = !dayLoaded
                val day = if (!fromStore) currentDay else store.loadDay(currentDay.localDate, currentDay.timezone,
                    clock.now().toLocalDate().toString())
                val (origin, destination) = coroutineScope {
                    val departure = async { originCode?.let { actions.findAirport(it) } }
                    val arrival = async { destinationCode?.let { actions.findAirport(it) } }
                    departure.await() to arrival.await()
                }
                currentCoroutineContext().ensureActive()
                if (revision != expectedRevision) return@launch
                require(day.localDate == currentDay.localDate && day.timezone == currentDay.timezone)
                installDay(day, prefetch = fromStore)
                val plan = if (origin != null && destination != null) {
                    try { FlightLegPlan.between(origin, destination).copy(
                        seat = editingBase?.seat, categoryId = editingBase?.categoryId,
                        scene = state.value.scene, plannedDeparture = state.value.departureClock,
                    ) } catch (_: IllegalArgumentException) { null }
                } else null
                publish(state.value.copy(
                    originCode = origin?.code ?: originCode,
                    destinationCode = destination?.code ?: destinationCode,
                    origin = origin, destination = destination, plan = plan,
                    error = when {
                        origin == null || destination == null -> PreparationError.MISSING_AIRPORT
                        plan == null -> PreparationError.INVALID_ROUTE
                        else -> null
                    },
                ))
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (revision == expectedRevision) publish(state.value.copy(error = PreparationError.LOAD_FAILED))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision == expectedRevision) publish(state.value.copy(error = PreparationError.LOAD_FAILED))
            } finally {
                if (revision == expectedRevision) publish(state.value.copy(loading = false))
            }
        }
    }

    private fun loadConfirmed() {
        val expectedRevision = revision
        val date = currentDay.localDate
        val zone = currentDay.timezone
        publish(state.value.copy(loading = true), persist = false)
        lookupJob = viewModelScope.launch {
            try {
                val day = store.loadDay(date, zone, clock.now().toLocalDate().toString())
                currentCoroutineContext().ensureActive()
                if (revision != expectedRevision) return@launch
                require(day.localDate == date && day.timezone == zone)
                installDay(day)
                if (day.legs.isNotEmpty()) showDay()
                else publish(PreparationUiState(stage = if (state.value.stage == PreparationStage.EXPANDED)
                    PreparationStage.EXPANDED else PreparationStage.DESTINATION), persist = false)
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (revision == expectedRevision) publish(state.value.copy(error = PreparationError.LOAD_FAILED), persist = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision == expectedRevision) publish(state.value.copy(error = PreparationError.LOAD_FAILED), persist = false)
            } finally {
                if (revision == expectedRevision) publish(state.value.copy(loading = false), persist = false)
            }
        }
    }

    private fun restoreDraft() {
        val origin = normalizeCode(savedState[DRAFT_ORIGIN])
        val destination = normalizeCode(savedState[DRAFT_DESTINATION])
        val encoded = savedState.get<String>(DRAFT_PLAN)
        try {
            savedState.get<String>(DRAFT_DAY)?.let {
                currentDay = stateJson.decodeFromString(DailyFlightPlan.serializer(), it)
                dayLoaded = savedState.get<Boolean>(DRAFT_DAY_LOADED) == true
            }
            if (!FlightPlanOwnership.isCurrent(currentDay)) { reloadForIdentity(); return }
            runtime.selectDate(currentDay.localDate)
            if (dayLoaded) installDay(currentDay)
            editingIndex = savedState.get<Int>(DRAFT_EDIT_INDEX)
            draftLegNumber = savedState.get<Int>(DRAFT_LEG_NUMBER)
                ?: editingIndex?.let { currentDay.displayLegs().getOrNull(it)?.snapshot?.legIndex }
            seatOverrideOnSave = savedState.get<Boolean>(DRAFT_SEAT_OVERRIDE) == true
            editingBase = savedState.get<String>(DRAFT_BASE)?.let(FlightPlanJson::decode)
            val confirmed = savedState.get<Boolean>(DRAFT_CONFIRMED) == true
            val stage = savedState.get<String>(DRAFT_STAGE)?.let { value -> PreparationStage.entries.firstOrNull { it.name == value } }
                ?: if (confirmed) PreparationStage.EXPANDED else PreparationStage.DESTINATION
            if (encoded == null) {
                publish(PreparationUiState(originCode = origin, destinationCode = destination, stage = stage,
                    scene = savedState.get<String>(DRAFT_SCENE)?.let { stateJson.decodeFromString(PlannedScene.serializer(), it) },
                    departureClock = savedState.get<String>(DRAFT_CLOCK)?.let { stateJson.decodeFromString(PlannedDeparture.serializer(), it) },
                    editing = savedState.get<Boolean>(DRAFT_EDITING) == true), persist = false)
                resolve(origin, destination, restoreStage = stage)
                return
            }
            val plan = FlightPlanJson.decode(encoded)
            require(plan.departure.code == origin && plan.destination.code == destination)
            if (savedState.get<String>(DRAFT_DAY) == null) {
                currentDay = DailyFlightPlans.empty(plan.plannedDeparture?.date ?: currentDay.localDate,
                    plan.plannedDeparture?.zoneId ?: currentDay.timezone)
                dayLoaded = false
                if (!FlightPlanOwnership.isCurrent(currentDay)) { reloadForIdentity(); return }
                if (confirmed) {
                    publish(PreparationUiState(stage = PreparationStage.EXPANDED, loading = true), persist = false)
                    loadConfirmed()
                    return
                }
            }
            confirmedSnapshot = plan.takeIf { confirmed }
            publish(PreparationUiState(
                originCode = origin, destinationCode = destination, origin = plan.departure,
                destination = plan.destination, plan = plan, confirmed = confirmed,
                stage = stage, scene = plan.scene, departureClock = plan.plannedDeparture,
                selectedLegIndex = savedState.get<Int>(DRAFT_SELECTED_INDEX),
                editing = savedState.get<Boolean>(DRAFT_EDITING) == true,
                started = savedState.get<Boolean>(DRAFT_STARTED) == true,
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publish(PreparationUiState(originCode = origin, destinationCode = destination, error = PreparationError.LOAD_FAILED))
        }
    }

    private fun publish(next: PreparationUiState, persist: Boolean = true) {
        val global = globalPlan.current()
        val control = planSwitch.state.value
        val normalized = next.copy(plan = next.plan?.copy(scene = next.scene, plannedDeparture = next.departureClock))
        val actual = normalized.copy(departureDue = departureIsDue(normalized.plan),
            dailyPlan = currentDay, selectedDate = currentDay.localDate, timezone = currentDay.timezone,
            canAddSegment = canAdd(),
            todayBlocked = runtime.todayBlocked(currentDay.localDate),
            tomorrowBlockedByCanceledToday = runtime.tomorrowBlockedByCanceledToday(currentDay.localDate),
            planEnabled = control.switchOverride ?: (global.enabledDate == currentDay.localDate),
            planOperationBusy = control.busy,
            entitlementGateVisible = control.gateVisible,
            entitlementGateAuthenticated = control.gateAuthenticated,
            pendingGlobalSeat = control.pendingNavigation,
            globalSeatText = if (global.selectionPending) null else global.lastSeatResult ?: global.inheritedSeat,
            completionReceipts = currentDay.legs.mapNotNull { leg ->
                completion.receiptMillis(leg)?.let { leg.snapshot.legIndex to it }
            }.toMap(),
            canBrowseNextDate = LocalDate.parse(currentDay.localDate).isBefore(clock.now().toLocalDate().plusDays(1)),
            previousArrivalText = previousArrivalClock(),
            timeCanPreview = departureTimeValid(normalized.departureClock, normalized.editing), earliestDeparture = earliestDepartureText())
        mutableState.value = actual
        if (persist) {
            savedState[DRAFT_PRESENT] = true
            savedState[DRAFT_ORIGIN] = actual.originCode
            savedState[DRAFT_DESTINATION] = actual.destinationCode
            savedState[DRAFT_PLAN] = actual.plan?.let(FlightPlanJson::encode)
            savedState[DRAFT_CONFIRMED] = actual.confirmed
            savedState[DRAFT_STARTED] = actual.started
            savedState[DRAFT_STAGE] = actual.stage.name
            savedState[DRAFT_EDITING] = actual.editing
            savedState[DRAFT_BASE] = editingBase?.let(FlightPlanJson::encode)
            savedState[DRAFT_SCENE] = actual.scene?.let { stateJson.encodeToString(PlannedScene.serializer(), it) }
            savedState[DRAFT_CLOCK] = actual.departureClock?.let { stateJson.encodeToString(PlannedDeparture.serializer(), it) }
            savedState[DRAFT_DAY] = stateJson.encodeToString(DailyFlightPlan.serializer(), currentDay)
            savedState[DRAFT_DAY_LOADED] = dayLoaded
            savedState[DRAFT_EDIT_INDEX] = editingIndex
            savedState[DRAFT_LEG_NUMBER] = draftLegNumber
            savedState[DRAFT_SEAT_OVERRIDE] = seatOverrideOnSave
            savedState[DRAFT_SELECTED_INDEX] = actual.selectedLegIndex
        }
    }

    private fun normalizeCode(value: String?): String? = value?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotEmpty)

    companion object {
        const val ARG_ORIGIN = "origin"
        const val ARG_DESTINATION = "destination"
        private const val LEGACY_ORIGIN = "originCode"
        private const val LEGACY_DESTINATION = "destinationCode"
        private const val DRAFT_PRESENT = "flight_preparation_draft_present_v1"
        private const val DRAFT_ORIGIN = "flight_preparation_origin_v1"
        private const val DRAFT_DESTINATION = "flight_preparation_destination_v1"
        private const val DRAFT_PLAN = "flight_preparation_snapshot_v1"
        private const val DRAFT_CONFIRMED = "flight_preparation_confirmed_v1"
        private const val DRAFT_STARTED = "flight_preparation_started_v1"
        private const val DRAFT_STAGE = "flight_preparation_stage_v2"
        private const val DRAFT_EDITING = "flight_preparation_editing_v2"
        private const val DRAFT_BASE = "flight_preparation_edit_base_v2"
        private const val DRAFT_SCENE = "flight_preparation_scene_v2"
        private const val DRAFT_CLOCK = "flight_preparation_clock_v2"
        private const val DRAFT_DAY = "flight_preparation_day_v3"
        private const val DRAFT_DAY_LOADED = "flight_preparation_day_loaded_v3"
        private const val DRAFT_EDIT_INDEX = "flight_preparation_edit_index_v3"
        private const val DRAFT_SELECTED_INDEX = "flight_preparation_selected_index_v3"
        private const val DRAFT_LEG_NUMBER = "flight_preparation_leg_number_v4"
        private const val DRAFT_SEAT_OVERRIDE = "flight_preparation_seat_override_v4"
    }
}

/** Older direct callers have no global completion transport; do not fake success. */
private object UnconnectedPlanCompletionActions : PlanCompletionActions {
    override val updates = MutableSharedFlow<DailyFlightPlan>().asSharedFlow()
    override fun complete(selection: ActivePlanSelection) = throw UnsupportedOperationException("Completion transport is unavailable")
    override fun receiptMillis(leg: DailyFlightLeg): Long? = null
    override fun recoverySelection(): ActivePlanSelection? = null
}
