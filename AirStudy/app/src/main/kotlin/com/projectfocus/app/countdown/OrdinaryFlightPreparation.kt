package com.projectfocus.app.countdown

import com.projectfocus.app.booking.SourceFlightNumber
import com.projectfocus.core.common.seat.SeatMapGeometry
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.booking.seat.SeatSceneChoice
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OrdinaryPreparationStep { IDLE, LOADING, SEAT, BOARDING, INSPECTION, PREPARE, READY, STARTING, STARTED, CLOSED, UNAVAILABLE }
enum class OrdinaryPreparationError { ROUTE, SELECTION, CHANGED, START }

data class OrdinaryFlightContext(
    val account: TokenStore.Snapshot,
    val originCode: String,
    val destinationCode: String,
    val originMarker: String?,
)

data class OrdinarySeatChoice(val seat: String, val scene: SeatSceneChoice)

data class OrdinaryPreparationState(
    val step: OrdinaryPreparationStep = OrdinaryPreparationStep.IDLE,
    val requestId: String? = null,
    val context: OrdinaryFlightContext? = null,
    val route: FlightLegPlan? = null,
    val selection: OrdinarySeatChoice? = null,
    val boardingAt: ZonedDateTime? = null,
    val error: OrdinaryPreparationError? = null,
) {
    val active: Boolean get() = step in setOf(OrdinaryPreparationStep.LOADING, OrdinaryPreparationStep.SEAT,
        OrdinaryPreparationStep.BOARDING, OrdinaryPreparationStep.INSPECTION, OrdinaryPreparationStep.PREPARE, OrdinaryPreparationStep.READY,
        OrdinaryPreparationStep.STARTING)
}

interface OrdinaryFlightPreparationActions {
    fun context(): OrdinaryFlightContext
    fun available(context: OrdinaryFlightContext): Boolean
    suspend fun loadRoute(context: OrdinaryFlightContext): FlightLegPlan?
    fun resolveScene(choice: SeatSceneChoice): PlannedScene?
    fun now(): ZonedDateTime
    suspend fun start(context: OrdinaryFlightContext, route: FlightLegPlan): Boolean
}

/** Ordinary numbering uses the airport city getter, not the planned wire-city rule. */
internal object OrdinaryFlightNumber {
    fun assign(route: FlightLegPlan): FlightLegPlan = route.copy(flightNumber = SourceFlightNumber.generate(
        city(route.departure), city(route.destination), upMode = false,
    ))

    internal fun city(airport: PlannedAirport): String = sequenceOf(airport.displayCity, airport.city, airport.name)
        .map { it.trim { char -> char <= '\u0020' } }.firstOrNull(String::isNotEmpty) ?: "--"
}

/** One frozen normal route. Neither a seat choice nor inspection creates a session. */
class OrdinaryFlightPreparationCoordinator(
    private val actions: OrdinaryFlightPreparationActions,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(OrdinaryPreparationState())
    val state = mutableState.asStateFlow()
    private var operation = 0L
    private var loading: Job? = null

    fun selectedContext(): OrdinaryFlightContext = actions.context()

    fun begin() {
        if (state.value.active) return
        val context = actions.context()
        val ticket = ++operation
        val request = UUID.randomUUID().toString()
        if (!actions.available(context)) { unavailable(OrdinaryPreparationError.CHANGED); return }
        mutableState.value = OrdinaryPreparationState(OrdinaryPreparationStep.LOADING, request, context)
        loading = scope.launch {
            try {
                val route = actions.loadRoute(context)
                if (ticket != operation) return@launch
                if (!actions.available(context)) { unavailable(OrdinaryPreparationError.CHANGED); return@launch }
                if (route == null || route.departure.code != context.originCode || route.destination.code != context.destinationCode) {
                    unavailable(OrdinaryPreparationError.ROUTE); return@launch
                }
                mutableState.value = state.value.copy(step = OrdinaryPreparationStep.SEAT,
                    route = OrdinaryFlightNumber.assign(route.copy(seat = null, scene = null, categoryId = null, plannedDeparture = null)))
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (ticket == operation) unavailable(OrdinaryPreparationError.ROUTE) }
        }
    }

    fun chooseSeat(requestId: String, choice: OrdinarySeatChoice) {
        val current = current(requestId, OrdinaryPreparationStep.SEAT) ?: return
        if (!validContext(current)) return
        val seat = canonicalSeat(choice.seat) ?: return
        if (actions.resolveScene(choice.scene) == null) {
            mutableState.value = current.copy(selection = null, error = OrdinaryPreparationError.SELECTION); return
        }
        mutableState.value = current.copy(selection = choice.copy(seat = seat), error = null)
    }

    fun confirmSeat(requestId: String, choice: OrdinarySeatChoice) {
        val current = current(requestId, OrdinaryPreparationStep.SEAT) ?: return
        if (!validContext(current)) return
        val seat = canonicalSeat(choice.seat) ?: return
        if (current.selection != choice.copy(seat = seat)) return
        val scene = actions.resolveScene(choice.scene)
        if (scene == null) { mutableState.value = current.copy(selection = null, error = OrdinaryPreparationError.SELECTION); return }
        mutableState.value = current.copy(step = OrdinaryPreparationStep.BOARDING,
            route = requireNotNull(current.route).copy(seat = seat, scene = scene), boardingAt = actions.now(), error = null)
    }

    fun checkIn(requestId: String) {
        val current = current(requestId, OrdinaryPreparationStep.BOARDING) ?: return
        if (validContext(current)) mutableState.value = current.copy(step = OrdinaryPreparationStep.INSPECTION, error = null)
    }

    fun inspectionCompleted(requestId: String) {
        val current = current(requestId, OrdinaryPreparationStep.INSPECTION) ?: return
        if (validContext(current)) mutableState.value = current.copy(step = OrdinaryPreparationStep.PREPARE, error = null)
    }

    fun prepareBoarding(requestId: String) {
        val current = current(requestId, OrdinaryPreparationStep.PREPARE) ?: return
        if (validContext(current)) mutableState.value = current.copy(step = OrdinaryPreparationStep.READY, error = null)
    }

    fun depart(requestId: String) {
        val current = current(requestId, OrdinaryPreparationStep.READY) ?: return
        if (!validContext(current)) return
        val context = requireNotNull(current.context)
        val route = requireNotNull(current.route)
        val ticket = operation
        mutableState.value = current.copy(step = OrdinaryPreparationStep.STARTING, error = null)
        scope.launch {
            try {
                val accepted = actions.start(context, route)
                if (ticket != operation) return@launch
                mutableState.value = if (accepted) state.value.copy(step = OrdinaryPreparationStep.STARTED, error = null)
                    else if (actions.available(context)) current.copy(error = OrdinaryPreparationError.START)
                    else current.copy(step = OrdinaryPreparationStep.UNAVAILABLE, error = OrdinaryPreparationError.CHANGED)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (ticket == operation) mutableState.value = current.copy(error = OrdinaryPreparationError.START)
            }
        }
    }

    fun cancel(): Boolean {
        if (!state.value.active || state.value.step == OrdinaryPreparationStep.STARTING) return false
        ++operation
        loading?.cancel()
        mutableState.value = OrdinaryPreparationState(step = OrdinaryPreparationStep.CLOSED)
        return true
    }

    private fun current(requestId: String, step: OrdinaryPreparationStep): OrdinaryPreparationState? =
        state.value.takeIf { it.requestId == requestId && it.step == step }

    private fun validContext(current: OrdinaryPreparationState): Boolean {
        if (current.context?.let(actions::available) == true) return true
        unavailable(OrdinaryPreparationError.CHANGED)
        return false
    }

    private fun unavailable(error: OrdinaryPreparationError) {
        mutableState.value = state.value.copy(step = OrdinaryPreparationStep.UNAVAILABLE, error = error)
    }

    private fun canonicalSeat(value: String): String? {
        val text = value.trim { it <= '\u0020' }.uppercase(Locale.ROOT)
        val match = Regex("^0?([1-9]|[12][0-9]|30)([ACDF])$").matchEntire(text) ?: return null
        val row = match.groupValues[1].toInt()
        val column = match.groupValues[2]
        val padded = String.format(Locale.ROOT, "%02d%s", row, column)
        return if (SeatMapGeometry.isValid(padded)) "$row$column" else null
    }
}
