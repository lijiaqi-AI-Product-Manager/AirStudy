package com.projectfocus.service.focus.history

import com.projectfocus.core.database.dao.HistoryReadDao
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.database.entity.FreeFlightArchiveEntity
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.freeflight.CompletedFreeFlight
import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.core.model.flight.freeflight.FreeFlightArchiveCodec
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.core.network.di.TokenStore
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter

data class HistoryAccount(val scope: String, val generation: Long) {
    fun isCurrent(): Boolean = TokenStore.snapshot().let {
        generation == it.generation && scope == TokenStore.storageScope(it)
    }
}

enum class FlightRecordKind { COUNTDOWN, COUNT_UP }
data class FlightRecordKey(val kind: FlightRecordKind, val id: String)
data class RecordedScene(val snapshot: PlannedScene, val revision: Long)

sealed interface RecordedFlight {
    val key: FlightRecordKey
    val completed: Boolean
    val distanceKm: Double
    val focusMinutes: Int
    val recordedAtWallMs: Long
    val destinationCode: String?
    val destinationCountry: String?
    val editedScene: RecordedScene?

    data class Countdown(val record: FlightHistoryEntity, override val editedScene: RecordedScene? = null) : RecordedFlight {
        override val key get() = FlightRecordKey(FlightRecordKind.COUNTDOWN, record.id)
        override val completed get() = record.status == "COMPLETED"
        override val distanceKm get() = record.distanceKm
        override val focusMinutes get() = record.actualFocusMinutes.coerceAtLeast(0)
        override val recordedAtWallMs get() = record.startTimeMillis
        override val destinationCode get() = record.destinationAirportCode
        override val destinationCountry get() = record.destinationCountry
    }

    data class CountUp(val archive: FreeFlightArchive, override val editedScene: RecordedScene? = null) : RecordedFlight {
        init { archive.validated() }
        override val key get() = FlightRecordKey(FlightRecordKind.COUNT_UP, archive.sessionId)
        override val completed get() = archive.status == FreeFlightStatus.COMPLETED
        override val distanceKm get() = archive.cumulativeDistanceKm
        override val focusMinutes get() = Math.round(archive.clock.activeElapsedMs / 60_000.0)
            .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        override val recordedAtWallMs get() = archive.startedAtWallMs
        override val destinationCode get() = archive.landingAirport?.code
        override val destinationCountry get() = archive.landingAirport?.country
    }
}

sealed interface HistoryReadState {
    val account: HistoryAccount
    data class Loading(override val account: HistoryAccount) : HistoryReadState
    data class Loaded(override val account: HistoryAccount, val records: List<RecordedFlight>,
                      val airportCounts: Map<String, Int>, val airportCodes: Map<String, Set<String>>) : HistoryReadState
    data class Failure(override val account: HistoryAccount, val error: Throwable) : HistoryReadState
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class FlightHistoryReader @Inject constructor(private val dao: HistoryReadDao) {
    private val refresh = MutableStateFlow(0L)
    fun retry() { refresh.value = refresh.value + 1L }
    val records: Flow<HistoryReadState> = combine(TokenStore.identities, refresh) { identity, _ -> identity }.flatMapLatest { identity ->
        val account = HistoryAccount(TokenStore.storageScope(identity), identity.generation)
        flow<HistoryReadState> {
            if (identity != TokenStore.snapshot()) return@flow
            emit(HistoryReadState.Loading(account))
            emitAll(combine(dao.countdowns(account.scope), dao.countUps(account.scope), dao.airportDirectory(),
                dao.scenes(account.scope)) { countdowns, countUps, directory, scenes ->
                val byKey = scenes.associateBy { FlightRecordKey(FlightRecordKind.valueOf(it.recordKind), it.recordId) }
                fun edited(kind: FlightRecordKind, id: String, startedAt: Long): RecordedScene? =
                    byKey[FlightRecordKey(kind, id)]?.takeIf {
                        it.ownerScope == account.scope && it.startedAtWallMs == startedAt
                    }?.let { row ->
                        RecordedScene(requireNotNull(PlannedFlightMetadataCodec.decodeSceneOrNull(row.sceneJson)), row.revision)
                    }
                val records: List<RecordedFlight> = countdowns.map {
                    RecordedFlight.Countdown(it, edited(FlightRecordKind.COUNTDOWN, it.id, it.startTimeMillis))
                } + countUps.map { row ->
                    val archive = decodeHistoryArchive(row)
                    RecordedFlight.CountUp(archive, edited(FlightRecordKind.COUNT_UP, archive.sessionId, archive.startedAtWallMs))
                }
                require(records.all { it.distanceKm.isFinite() && it.distanceKm >= 0.0 })
                val codes = directory.groupBy { it.country }.mapValues { (_, rows) -> rows.map { it.code }.toSet() }
                HistoryReadState.Loaded(account, records, directory.groupingBy { it.country }.eachCount(), codes)
            })
        }.catch { error ->
            if (error is CancellationException) throw error
            if (account.isCurrent()) emit(HistoryReadState.Failure(account, error))
        }
    }.filter { it.account.isCurrent() }.flowOn(Dispatchers.IO)
}

internal fun decodeHistoryArchive(row: FreeFlightArchiveEntity): FreeFlightArchive =
    FreeFlightArchiveCodec.decode(row.payloadJson).also { archive ->
        require(archive.sessionId == row.sessionId && archive.ownerScope == row.ownerScope &&
            archive.revision == row.revision && archive.status.name == row.status &&
            archive.updatedAtWallMs == row.updatedAtWallMs &&
            row.activeSlot == (if (archive.status == FreeFlightStatus.ONGOING) 1 else null))
    }

data class ArrivalDailyTotals(val distanceKm: Double, val focusMinutes: Int)

object ArrivalHistoryTotals {
    fun calculate(records: List<RecordedFlight>, current: CompletedFreeFlight, nowWallMs: Long, zone: ZoneId): ArrivalDailyTotals {
        val start = Instant.ofEpochMilli(nowWallMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = Math.addExact(nowWallMs, 1L)
        var distance = 0.0
        var minutes = 0
        var currentDistance = 0.0
        var currentMinutes = 0
        var included = false
        for (record in records) {
            if (!record.completed || record.recordedAtWallMs !in start until end) continue
            val actualDistance = record.distanceKm.coerceAtLeast(0.0)
            val actualMinutes = if (record.focusMinutes > 0) record.focusMinutes
                else Math.round(actualDistance / 800.0 * 60.0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            distance += actualDistance
            minutes = Math.addExact(minutes, actualMinutes)
            if (record.key.kind == FlightRecordKind.COUNT_UP && record.key.id.equals(current.sessionId, true)) {
                included = true
                currentDistance = actualDistance
                currentMinutes = actualMinutes
            }
        }
        distance += if (included) (current.distanceKm - currentDistance).coerceAtLeast(0.0) else current.distanceKm
        minutes = Math.addExact(minutes, if (included) (current.displayFocusMinutes - currentMinutes).coerceAtLeast(0)
            else current.displayFocusMinutes)
        require(distance.isFinite() && distance >= 0.0)
        return ArrivalDailyTotals(distance, minutes)
    }
}
