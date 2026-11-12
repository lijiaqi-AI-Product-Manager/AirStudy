package com.projectfocus.feature.stats

import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import com.projectfocus.service.focus.history.*

internal data class StatsPlace(val code: String?, val name: String?, val city: String?, val country: String?)

/** Statistical facts can be useful even when a resumable archive is unavailable. */
internal data class StatsFlightFact(val key: FlightRecordKey, val completed: Boolean?, val recordedAtWallMs: Long?,
    val focusMinutes: Double?, val distanceKm: Double?, val destination: StatsPlace?, val scene: StatsSceneEntry?,
    val hourDurationMs: Long?, val sceneKnown: Boolean = true) {
    fun inRange(range: StatsDateRange) = recordedAtWallMs?.let { it in range } == true
    fun matchesScene(selected: String) = selected.isEmpty() || scene?.canonicalKey == StatsScenes.canonical(selected)
    fun possiblyIn(range: StatsDateRange?, selected: String = "") = completed != false &&
        (range == null || recordedAtWallMs == null || inRange(range)) &&
        (selected.isEmpty() || !sceneKnown || matchesScene(selected))

    companion object {
        fun recorded(record: RecordedFlight): StatsFlightFact {
            val status: String
            val place: StatsPlace?
            val minutes = StatsFocusMinutes.value(record)
            when (record) {
                is RecordedFlight.Countdown -> {
                    val row = record.record; status = row.status
                    place = StatsPlace(row.destinationAirportCode, row.destinationAirportName, row.destinationCity, row.destinationCountry)
                }
                is RecordedFlight.CountUp -> {
                    status = record.archive.status.name
                    place = record.archive.landingAirport?.let { StatsPlace(it.code, it.displayName, it.displayCity, it.country) }
                }
            }
            require(minutes.isFinite() && minutes >= 0 && record.distanceKm.isFinite() && record.distanceKm >= 0)
            val duration = when (record) {
                is RecordedFlight.Countdown -> record.record.actualFocusMinutes.coerceAtLeast(0).toLong() * 60_000L
                is RecordedFlight.CountUp -> record.archive.clock.activeElapsedMs
            }
            return StatsFlightFact(record.key, when (status) {
                "COMPLETED" -> true; "CANCELLED", "ONGOING" -> false; else -> null
            }, record.recordedAtWallMs, minutes, record.distanceKm, place, StatsScenes.identity(record), duration)
        }

        fun summary(record: FlightRecordSummary): StatsFlightFact {
            val builtin = SourceSceneCatalog.builtins.firstOrNull { it.type == record.sceneType }
            val name = record.sceneLabel?.trim().orEmpty().ifEmpty { builtin?.iconKey.orEmpty() }
            val scene = name.takeIf(String::isNotEmpty)?.let {
                StatsSceneEntry(it, StatsScenes.canonical(it).orEmpty(), builtin?.iconKey ?: StatsScenes.canonical(it).orEmpty(), 0.0, 0)
            }
            val millis = record.durationSeconds?.times(1000.0)?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() }
            val start = record.startedAtWallMs; val end = record.endedAtWallMs
            val continuous = if (millis == 0.0) 0L else if (millis != null && start != null && end != null &&
                end.toDouble() - start.toDouble() == millis) millis.toLong() else null
            return StatsFlightFact(FlightRecordKey(if (record.mode == FlightRecordMode.DOWN) FlightRecordKind.COUNTDOWN else FlightRecordKind.COUNT_UP, record.id),
                when (record.status) {
                    SummaryFlightStatus.ARRIVED -> true
                    SummaryFlightStatus.CANCELLED, SummaryFlightStatus.IN_PROGRESS, SummaryFlightStatus.LANDING -> false
                    SummaryFlightStatus.PENDING, SummaryFlightStatus.UNKNOWN -> null
                }, record.startedAtWallMs, record.durationSeconds?.div(60.0), record.distanceKm,
                record.arrival?.let { StatsPlace(it.code, it.name, it.city, it.country) }, scene, continuous, scene != null)
        }
    }
}

internal data class StatsFactSet(val records: List<StatsFlightFact>, val complete: Boolean) {
    companion object {
        fun merge(read: HistoryReadState.Loaded, remote: RecordSummaryState?): StatsFactSet {
            val signedIn = !TokenStore.snapshot().token.isNullOrBlank()
            val owned = remote?.takeIf { signedIn && it.account == read.account }
            fun remoteKey(record: RecordedFlight): Pair<FlightRecordMode, String> = owned?.aliases?.get(record.key) ?:
                if (record is RecordedFlight.CountUp) FlightRecordMode.UP to (record.archive.remoteRecordId?.trim()?.takeIf(String::isNotEmpty) ?: record.key.id)
                else FlightRecordMode.DOWN to record.key.id
            val metadata = owned?.records.orEmpty().associateBy { it.mode to it.id }
            val keys = read.records.map(::remoteKey).toSet()
            val aliasPending = owned != null && read.records.any { row ->
                row.key.kind == FlightRecordKind.COUNTDOWN &&
                    (row.key.id.startsWith("cloud-down-") || row.key.id.startsWith("cloud-summary-down-")) && row.key !in owned.aliases
            }
            val full = read.records.map { row ->
                val fact = StatsFlightFact.recorded(row)
                fact.copy(scene = fact.scene ?: metadata[remoteKey(row)]?.let(StatsFlightFact::summary)?.scene)
            }
            val partial = if(aliasPending)emptyList() else metadata.values.filter { (it.mode to it.id) !in keys }.map(StatsFlightFact::summary)
            return StatsFactSet(full + partial, !aliasPending && (remote == null || !signedIn || owned?.let { it.complete && !it.failed } == true))
        }
    }
}

data class StatsCoverage(val periodFlights: Boolean = true, val periodFocus: Boolean = true, val periodDates: Boolean = true,
    val periodScenes: Boolean = true, val allScenes: Boolean = true, val hours: Boolean = true, val annual: Boolean = true,
    val allFlights: Boolean = true, val allDistance: Boolean = true, val allCities: Boolean = true, val allCountries: Boolean = true,
    val comparison: Boolean = true, val periodDistance: Boolean = true, val periodTravel: Boolean = true) {
    val incomplete get() = listOf(periodFlights, periodFocus, periodDates, periodScenes, allScenes, hours, annual,
        allFlights, allDistance, allCities, allCountries, comparison, periodDistance, periodTravel).any { !it }
}

internal fun StatsFactSet.coverage(range: StatsDateRange, previous: StatsDateRange, annual: StatsDateRange, selected: String): StatsCoverage {
    fun rowsKnown(within: StatsDateRange?, scene: String = "") = complete && records.filter { it.possiblyIn(within, scene) }.all {
        it.completed != null && (within == null || it.recordedAtWallMs != null) && (scene.isEmpty() || it.sceneKnown)
    }
    fun completed(within: StatsDateRange?, scene: String = "") = records.filter { it.completed == true &&
        (within == null || it.inRange(within)) && it.matchesScene(scene) }
    fun focusKnown(within: StatsDateRange?, scene: String = "") = rowsKnown(within, scene) && completed(within, scene).all { it.focusMinutes != null }
    val period = completed(range, selected)
    val all = completed(null)
    val periodsKnown = rowsKnown(range, selected)
    fun known(value: String?) = !value.isNullOrBlank() && value !in setOf("UNKNOWN", "Unknown", "---", "—")
    return StatsCoverage(periodsKnown, focusKnown(range, selected), periodsKnown,
        focusKnown(range) && completed(range).all { it.sceneKnown }, focusKnown(null) && all.all { it.sceneKnown },
        periodsKnown && period.all { it.hourDurationMs != null }, focusKnown(annual, selected), rowsKnown(null),
        rowsKnown(null) && all.all { it.distanceKm != null },
        rowsKnown(null) && all.all { known(it.destination?.city) && known(it.destination?.country) },
        rowsKnown(null) && all.all { known(it.destination?.country) && known(it.destination?.code) },
        focusKnown(range, selected) && focusKnown(previous, selected),
        periodDistance = periodsKnown && period.all { it.distanceKm != null },
        periodTravel = periodsKnown && period.all { known(it.destination?.country) && known(it.destination?.code) })
}
