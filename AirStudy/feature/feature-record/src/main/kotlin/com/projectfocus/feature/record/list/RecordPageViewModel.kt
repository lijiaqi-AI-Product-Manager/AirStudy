package com.projectfocus.feature.record.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.FlightHistoryReader
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.service.focus.history.HistoryReadState
import com.projectfocus.service.focus.history.RecordedFlight
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

enum class RecordStatusFilter { ALL, ARRIVED, IN_PROGRESS, CANCELLED }
enum class RecordPeriod { DAY, WEEK, MONTH, ALL }

data class RecordPageQuery(
    val status: RecordStatusFilter = RecordStatusFilter.ALL,
    val period: RecordPeriod = RecordPeriod.ALL,
    val page: Int = 1,
    val generation: Long = TokenStore.snapshot().generation,
)

data class RecordPageState(
    val account: HistoryAccount? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val query: RecordPageQuery = RecordPageQuery(),
    val items: List<RecordItem> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
    val matchedCount: Int = 0,
    val completedDistanceKm: Double? = null,
) {
    val ready get() = !loading && !failed && account?.isCurrent() == true
}

object RecordPageProjection {
    const val PAGE_SIZE = 10

    fun build(read: HistoryReadState, query: RecordPageQuery, now: Long, zone: ZoneId): RecordPageState {
        val empty = RecordPageState(account = read.account, query = query)
        if (!read.account.isCurrent()) return empty.copy(account = null)
        if (read is HistoryReadState.Loading) return empty
        if (read is HistoryReadState.Failure) return empty.copy(loading = false, failed = true)
        read as HistoryReadState.Loaded
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = when (query.period) {
            RecordPeriod.DAY -> today
            RecordPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            RecordPeriod.MONTH -> today.withDayOfMonth(1)
            RecordPeriod.ALL -> null
        }
        val end = when (query.period) {
            RecordPeriod.DAY -> first?.plusDays(1)
            RecordPeriod.WEEK -> first?.plusWeeks(1)
            RecordPeriod.MONTH -> first?.plusMonths(1)
            RecordPeriod.ALL -> null
        }
        val from = first?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val until = end?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val matching = read.records.filter { record ->
            val status = when (record) {
                is RecordedFlight.Countdown -> record.record.status
                is RecordedFlight.CountUp -> record.archive.status.name
            }
            require(status in setOf("COMPLETED", "ONGOING", "CANCELLED"))
            val accepted = when (query.status) {
                RecordStatusFilter.ALL -> true
                RecordStatusFilter.ARRIVED -> status == "COMPLETED"
                RecordStatusFilter.IN_PROGRESS -> status == "ONGOING"
                RecordStatusFilter.CANCELLED -> status == "CANCELLED"
            }
            accepted && (from == null || (record.recordedAtWallMs >= from && record.recordedAtWallMs < requireNotNull(until)))
        }.sortedWith(compareByDescending<RecordedFlight> { it.recordedAtWallMs }
            .thenBy { it.key.kind.ordinal }.thenBy { it.key.id })
        val pages = ((matching.size.toLong() + PAGE_SIZE - 1) / PAGE_SIZE).toInt().coerceAtLeast(1)
        val requested = if (query.generation == read.account.generation) query.page else 1
        val page = requested.coerceIn(1, pages)
        val distance = read.records.filter { it.completed }.sumOf { it.distanceKm }
        require(distance.isFinite() && distance >= 0.0)
        return empty.copy(
            loading = false,
            items = matching.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE).map { RecordItemMapping.item(it, zone) },
            page = page,
            pageCount = pages,
            matchedCount = matching.size,
            completedDistanceKm = distance,
        )
    }
}

@HiltViewModel
class RecordPageViewModel @Inject constructor(
    private val reader: FlightHistoryReader,
    private val clock: Clock,
    private val zone: ZoneId,
) : ViewModel() {
    private val query = MutableStateFlow(RecordPageQuery())
    private val now = MutableStateFlow(clock.millis())
    val state = combine(reader.records, query, now) { records, filter, wall ->
        try {
            RecordPageProjection.build(records, filter, wall, zone)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            RecordPageState(records.account, loading = false, failed = true, query = filter)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), RecordPageState())

    fun onVisible() { now.value = clock.millis() }
    fun retry() { now.value = clock.millis(); reader.retry() }
    fun selectStatus(status: RecordStatusFilter) {
        query.value = query.value.copy(status = status, page = 1, generation = TokenStore.snapshot().generation)
    }
    fun selectPeriod(period: RecordPeriod) {
        query.value = query.value.copy(period = period, page = 1, generation = TokenStore.snapshot().generation)
    }
    fun previous() = changePage(-1)
    fun next() = changePage(1)
    private fun changePage(delta: Int) {
        val current = state.value.takeIf { it.ready } ?: return
        query.value = current.query.copy(page = (current.page + delta).coerceIn(1, current.pageCount),
            generation = requireNotNull(current.account).generation)
    }
}
