package com.projectfocus.app.booking

import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedDeparture
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.FlightPlanAirportDto
import com.projectfocus.core.network.dto.FlightPlanLegDto
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.common.plan.SourceFlightPlanStatus
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.security.MessageDigest

internal object FlightPlanOwnership {
    fun scope(account: TokenStore.Snapshot = TokenStore.snapshot()): String =
        account.token?.takeIf { it.isNotBlank() }?.let { token ->
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } ?: "local"

    fun isCurrent(day: DailyFlightPlan): Boolean = day.ownerScope == scope()
}

/** Whole-day order and server fields survive edits; cancelled legs remain in the list. */
@Serializable
data class DailyFlightPlan(
    val localDate: String,
    val timezone: String,
    val planDate: String? = null,
    val legs: List<DailyFlightLeg> = emptyList(),
    /** Local cache ownership only; never part of the observed wire payload. */
    val ownerScope: String = "local",
) {
    init {
        require(LocalDate.parse(localDate).toString() == localDate)
        if (planDate != null) require(LocalDate.parse(planDate).toString() == planDate)
        ZoneId.of(timezone)
    }

    /** X gives the UI a separate stable leg_index sort; the wire list stays intact. */
    fun displayLegs(): List<DailyFlightLeg> = legs.sortedBy { it.snapshot.legIndex }

    /** Source f(): nonempty and all completed/canceled; unknown is not terminal. */
    fun allTerminal(): Boolean = legs.isNotEmpty() && legs.all {
        SourceFlightPlanStatus.parse(it.snapshot.legStatus) in
            setOf(SourceFlightPlanStatus.COMPLETED, SourceFlightPlanStatus.CANCELED)
    }

    /** Source u(): scan backwards in the UI list past cancelled legs. */
    fun nextOrigin(): PlannedAirport? {
        if (allTerminal()) return null // u consults persisted h before q for this branch.
        val displayed = displayLegs()
        val previous = displayed.asReversed().firstOrNull {
            SourceFlightPlanStatus.parse(it.snapshot.legStatus) != SourceFlightPlanStatus.CANCELED
        }
        return previous?.snapshot?.arrivalAirport?.let(DailyFlightPlans::plannedAirport)
            ?: displayed.firstOrNull()?.snapshot?.departureAirport?.let(DailyFlightPlans::plannedAirport)
    }
}

@Serializable
data class DailyFlightLeg(val snapshot: FlightPlanLegSnapshot, val route: FlightLegPlan?)

internal object DailyFlightPlans {
    fun empty(date: String, timezone: String) = DailyFlightPlan(date, timezone)

    fun fromRoute(route: FlightLegPlan, date: String, timezone: String, index: Int,
                  studyTitle: String = "学习"): DailyFlightLeg {
        val departure = route.plannedDeparture
        val moment = departure?.instant()?.toString().orEmpty()
        val scene = route.scene
        val type = scene?.let { SourceSceneCatalog.type(it.iconKey) } ?: 1
        val title = scene?.let { it.displayName ?: it.name }?.trim { it <= '\u0020' }
            ?.takeIf { it.isNotEmpty() } ?: studyTitle
        return DailyFlightLeg(FlightPlanLegSnapshot(index, airport(route.departure), airport(route.destination),
            moment, "waiting_takeoff", route.distanceKm, "", route.seat.orEmpty(), type.coerceIn(1, 20), title), route)
    }

    /** Source s/p build in backing-list order, separate from the sorted preview. */
    fun replacement(day: DailyFlightPlan, uiPosition: Int?, draft: FlightLegPlan,
                    seatOverride: Boolean = false, studyTitle: String = "学习"): DailyFlightLeg {
        val old = uiPosition?.let { day.legs.getOrNull(it) }
        if (uiPosition != null) {
            require(uiPosition == day.legs.lastIndex && old != null &&
                SourceFlightPlanStatus.parse(old.snapshot.legStatus) == SourceFlightPlanStatus.WAITING)
        }
        val fresh = fromRoute(draft, day.localDate, day.timezone, (uiPosition ?: day.legs.size) + 1, studyTitle)
        val oldCode = old?.snapshot?.arrivalAirport?.code.orEmpty().trim { it <= '\u0020' }.uppercase(java.util.Locale.getDefault())
        val draftCode = fresh.snapshot.arrivalAirport?.code.orEmpty().trim { it <= '\u0020' }.uppercase(java.util.Locale.getDefault())
        val keepsArrival = old != null && (draftCode.isEmpty() || draftCode == oldCode)
        val wire = if (old != null) fresh.snapshot.copy(
            legIndex = old.snapshot.legIndex,
            departureAirport = old.snapshot.departureAirport,
            legStatus = old.snapshot.legStatus.ifBlank { "waiting_takeoff" },
            flightNo = normalizeAssignment(old.snapshot.flightNo),
            seatNo = if (seatOverride) draft.seat.orEmpty() else normalizeAssignment(old.snapshot.seatNo),
            arrivalAirport = if (keepsArrival) old.snapshot.arrivalAirport else fresh.snapshot.arrivalAirport,
            distanceKm = Math.max(0.0, if (keepsArrival) old.snapshot.distanceKm else fresh.snapshot.distanceKm),
            focusIconCode = if (draft.scene == null) old.snapshot.focusIconCode.coerceIn(1, 20) else fresh.snapshot.focusIconCode,
            focusSceneText = if (draft.scene == null) old.snapshot.focusSceneText.trim { it <= '\u0020' }
                .takeIf(String::isNotEmpty) ?: studyTitle else fresh.snapshot.focusSceneText,
        ) else if (day.legs.isNotEmpty() && !day.allTerminal()) {
            fresh.snapshot.copy(departureAirport = day.legs.last().snapshot.arrivalAirport)
        } else fresh.snapshot

        // the resulting endpoints, so it cannot accidentally start the preview's
        // old origin when the source's UI/wire positions differ.
        val route = try {
            FlightLegPlan.between(requireNotNull(wire.departureAirport).toPlanned(),
                requireNotNull(wire.arrivalAirport).toPlanned()).copy(
                seat = wire.seatNo.takeIf(String::isNotEmpty), categoryId = draft.categoryId,
                scene = if (draft.scene != null) draft.scene else old?.let { previous ->
                    previous.route?.scene?.takeIf {
                        SourceSceneCatalog.type(it.iconKey) == wire.focusIconCode &&
                            (it.displayName ?: it.name).trim { char -> char <= '\u0020' } == wire.focusSceneText
                    } ?: SourceSceneCatalog.builtins.firstOrNull { it.type == wire.focusIconCode }?.let { builtin ->
                        PlannedScene("wire:${day.localDate}:${uiPosition}", wire.focusSceneText, builtin.iconKey,
                            builtin.color, true, displayName = wire.focusSceneText)
                    }
                }, plannedDeparture = draft.plannedDeparture,
                flightNumber = normalizeAssignment(wire.flightNo).takeIf(String::isNotEmpty),
            )
        } catch (_: IllegalArgumentException) { null }
        return DailyFlightLeg(wire, route)
    }

    /** m: Java trim and exact membership in the seven shipped resource configurations. */
    internal fun normalizeAssignment(value: String): String = value.trim { it <= '\u0020' }
        .takeUnless { it in pendingAssignments }.orEmpty()

    private val pendingAssignments = setOf("待分配", "Pending", "未割り当て", "할당 대기 중", "Ожидает назначения")

    fun fromSnapshot(snapshot: DailyFlightPlanSnapshot, previous: DailyFlightPlan? = null): DailyFlightPlan =
        DailyFlightPlan(snapshot.localPlanDate, snapshot.timezone, snapshot.planDate,
            snapshot.legs.mapIndexed { index, leg ->
                val previousLeg = previous?.legs?.getOrNull(index)
                val reindexed = previous != null && previous.legs.size != snapshot.legs.size
                val old = previousLeg?.takeIf { !reindexed && sameRouteMetadata(it, leg) }
                    // Batch deletion reindexes retained rows. Preserve the independent
                    // picker identity only for one exact matching previous route.
                    ?: previous?.legs?.filter { sameRouteMetadata(it, leg) && (!reindexed ||
                        (SourceFlightPlanStatus.parse(it.snapshot.legStatus) == SourceFlightPlanStatus.parse(leg.legStatus) &&
                            it.snapshot.flightNo == leg.flightNo && it.snapshot.seatNo == leg.seatNo)) }?.singleOrNull()
                val route = old?.route ?: route(leg, snapshot.timezone, snapshot.localPlanDate, index)
                DailyFlightLeg(leg, route?.let {
                    try {
                        it.copy(seat = leg.seatNo.takeIf(String::isNotBlank),
                            flightNumber = normalizeAssignment(leg.flightNo).takeIf(String::isNotEmpty))
                    } catch (_: IllegalArgumentException) { null }
                })
            })

    private fun sameRouteMetadata(previous: DailyFlightLeg, leg: FlightPlanLegSnapshot): Boolean {
        val route = previous.route ?: return false
        val wire = previous.snapshot
        return wire.departureAirport == leg.departureAirport && wire.arrivalAirport == leg.arrivalAirport &&
            wire.distanceKm == leg.distanceKm && wire.focusIconCode == leg.focusIconCode &&
            wire.focusSceneText == leg.focusSceneText &&
            route.plannedDeparture?.instant()?.toString() == leg.scheduledDepartureAt
    }

    fun outgoing(day: DailyFlightPlan): List<FlightPlanLegDto> = day.legs.mapIndexed { index, leg ->
        val wire = leg.snapshot
        FlightPlanLegDto(index + 1, requireNotNull(wire.departureAirport) { "Missing departure airport" },
            requireNotNull(wire.arrivalAirport) { "Missing arrival airport" }, wire.scheduledDepartureAt,
            wire.legStatus, wire.distanceKm, wire.flightNo, wire.seatNo, wire.focusIconCode, wire.focusSceneText,
            scheduledDepartureLocal = leg.route?.plannedDeparture?.let {
                "%s %02d:%02d:00".format(java.util.Locale.ROOT, it.date, it.hour, it.minute)
            }, arrivalDiffMinutes = wire.arrivalDiffMinutes, actualArrivalAt = wire.actualArrivalAt,
            expectedArrivalAt = wire.expectedArrivalAt)
    }

    private fun airport(value: PlannedAirport) = FlightPlanAirportDto(
        id = Math.toIntExact(value.id), code = value.code, name = value.name, city = value.city,
        latitude = value.latitude.toString(), longitude = value.longitude.toString(), region = value.country,
        countryRegionId = 0,
    )

    private fun route(leg: FlightPlanLegSnapshot, timezone: String, date: String, index: Int): FlightLegPlan? = try {
        val from = requireNotNull(leg.departureAirport).toPlanned()
        val to = requireNotNull(leg.arrivalAirport).toPlanned()
        val local = Instant.parse(leg.scheduledDepartureAt).atZone(ZoneId.of(timezone))
        val builtin = SourceSceneCatalog.builtins.firstOrNull { it.type == leg.focusIconCode }
        FlightLegPlan.between(from, to).copy(
            seat = leg.seatNo.takeIf(String::isNotBlank),
            flightNumber = normalizeAssignment(leg.flightNo).takeIf(String::isNotEmpty),
            plannedDeparture = PlannedDeparture(local.toLocalDate().toString(), local.hour, local.minute, timezone),
            // Server plans do not contain the picker's independent option/cloud identity.
            scene = PlannedScene("wire:$date:$index", leg.focusSceneText, builtin?.iconKey.orEmpty(),
                builtin?.color ?: 0, true, displayName = leg.focusSceneText),
        )
    } catch (_: Exception) { null } // Keep/display the wire leg even when it cannot start a local session.

    private fun FlightPlanAirportDto.toPlanned() = PlannedAirport(id.toLong(), code, name, city, region,
        latitude.toDouble(), longitude.toDouble())

    fun plannedAirport(value: FlightPlanAirportDto): PlannedAirport? = try { value.toPlanned() } catch (_: Exception) { null }
}
