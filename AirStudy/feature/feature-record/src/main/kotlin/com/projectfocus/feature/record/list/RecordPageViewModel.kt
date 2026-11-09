package com.projectfocus.feature.record.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.FlightHistoryReader
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.service.focus.history.HistoryReadState
import com.projectfocus.service.focus.history.RecordedFlight
import com.projectfocus.service.focus.history.RecordSummaryStore
import com.projectfocus.service.focus.history.RecordSummaryState
import com.projectfocus.core.network.dto.FlightRecordMode
import com.projectfocus.service.focus.history.FlightRecordKind
import com.projectfocus.service.focus.history.FlightRecordKey
import com.projectfocus.service.focus.history.RecordDeletionTarget
import com.projectfocus.service.focus.history.RecordSceneOperations
import com.projectfocus.service.focus.history.RecordCloudFailure
import com.projectfocus.core.network.record.RecordMutationOutcome
import com.projectfocus.service.focus.history.RecordSceneEditTarget
import com.projectfocus.service.focus.history.RecordSceneSaveResult
import com.projectfocus.core.model.flight.PlannedScene
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

enum class RecordSceneFailure { WRITE, CHANGED, NOT_EDITABLE, LOGIN_REQUIRED, CLOUD }
data class RecordSceneSaveState(
    val attempt: Long = 0L,
    val target: RecordSceneEditTarget? = null,
    val saving: Boolean = false,
    val failure: RecordSceneFailure? = null,
)

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
    internal val deletionTargets: Map<FlightRecordKey, RecordDeletionTarget> = emptyMap(),
) {
    val ready get() = !loading && !failed && account?.isCurrent() == true

    fun deletionTarget(item: RecordItem): RecordDeletionTarget? {
        if (!ready || item.cloudSummary != null || items.none { it == item }) return null
        return deletionTargets[item.key]?.takeIf { it.account == account }
    }
}

object RecordPageProjection {
    const val PAGE_SIZE = 10

    fun build(read: HistoryReadState, query: RecordPageQuery, now: Long, zone: ZoneId,
        summaries: RecordSummaryState = RecordSummaryState()): RecordPageState {
        val empty = RecordPageState(account = read.account, query = query)
        if (!read.account.isCurrent()) return empty.copy(account = null)
        if (read is HistoryReadState.Loading) return empty
        if (read is HistoryReadState.Failure) return empty.copy(loading = false, failed = true)
        read as HistoryReadState.Loaded
        require(read.records.all { record ->
            val status = when (record) {
                is RecordedFlight.Countdown -> record.record.status
                is RecordedFlight.CountUp -> record.archive.status.name
            }
            status in setOf("ONGOING", "COMPLETED", "CANCELLED")
        }) { "Unsupported stored flight status" }
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
        val known = summaries.takeIf { it.account == read.account && !TokenStore.snapshot().token.isNullOrBlank() }
        fun remoteKey(record: RecordedFlight) =
            known?.aliases?.get(record.key) ?: (if (record.key.kind == FlightRecordKind.COUNTDOWN) FlightRecordMode.DOWN to record.key.id
                else FlightRecordMode.UP to ((record as RecordedFlight.CountUp).archive.remoteRecordId?.trim()?.takeIf(String::isNotEmpty) ?: record.key.id))
        val remoteKeys = read.records.map { remoteKey(it) }.toSet()
        val metadata = known?.records.orEmpty().associateBy { it.mode to it.id }
        val partial = metadata.values.filter { (it.mode to it.id) !in remoteKeys }.map { RecordSummaryItemMapping.item(it, zone) }
        val all = read.records.map { record ->
            val item = RecordItemMapping.item(record, zone)
            val details = metadata[remoteKey(record)]
            item.copy(flightNumber = item.flightNumber ?: details?.flightNumber, seat = item.seat ?: details?.seat)
        } + partial
        val matching = all.filter { record ->
            val accepted = when (query.status) {
                RecordStatusFilter.ALL -> true
                RecordStatusFilter.ARRIVED -> record.status == FlightStatus.COMPLETED
                RecordStatusFilter.IN_PROGRESS -> record.status == FlightStatus.ONGOING
                RecordStatusFilter.CANCELLED -> record.status == FlightStatus.CANCELLED
            }
            accepted && (from == null || record.recordedAtWallMs?.let { it >= from && it < requireNotNull(until) } == true)
        }.sortedWith(compareByDescending<RecordItem> { it.recordedAtWallMs ?: Long.MIN_VALUE }
            .thenBy { it.kind.ordinal }.thenBy { it.id })
        val pages = ((matching.size.toLong() + PAGE_SIZE - 1) / PAGE_SIZE).toInt().coerceAtLeast(1)
        val requested = if (query.generation == read.account.generation) query.page else 1
        val page = requested.coerceIn(1, pages)
        val distance = all.filter { it.status == FlightStatus.COMPLETED }.sumOf { it.distanceKm ?: 0.0 }
        require(distance.isFinite() && distance >= 0.0)
        val visible = matching.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return empty.copy(
            loading = false,
            items = visible,
            page = page,
            pageCount = pages,
            matchedCount = matching.size,
            completedDistanceKm = distance.takeUnless { summaries.account == read.account && (!summaries.complete || summaries.failed) ||
                partial.any { it.status == FlightStatus.UNKNOWN ||
                it.status == FlightStatus.COMPLETED && it.distanceKm == null } },
            deletionTargets = java.util.Collections.unmodifiableMap(read.records.filter { record ->
                visible.any { it.cloudSummary == null && it.key == record.key }
            }.associate { it.key to RecordDeletionTarget(read.account, it) }),
        )
    }
}

@HiltViewModel
class RecordPageViewModel @Inject constructor(
    private val reader: FlightHistoryReader,
    private val clock: Clock,
    private val zone: ZoneId,
    private val sceneStore: RecordSceneOperations,
    private val recoveryStore: com.projectfocus.service.focus.history.RecordRecoveryStatusStore? = null,
    private val summaries: RecordSummaryStore? = null,
) : ViewModel() {
    private val query = MutableStateFlow(RecordPageQuery())
    private val now = MutableStateFlow(clock.millis())
    private val mutableSceneSave = MutableStateFlow(RecordSceneSaveState())
    val sceneSave: kotlinx.coroutines.flow.StateFlow<RecordSceneSaveState> = mutableSceneSave
    val recoveryStatus = (recoveryStore?.observe() ?: kotlinx.coroutines.flow.flowOf(com.projectfocus.service.focus.history.RecordRecoveryStatus()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), com.projectfocus.service.focus.history.RecordRecoveryStatus())
    val state = combine(reader.records, query, now, summaries?.observe() ?: kotlinx.coroutines.flow.flowOf(RecordSummaryState())) { records, filter, wall, remote ->
        try {
            RecordPageProjection.build(records, filter, wall, zone, remote)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            RecordPageState(records.account, loading = false, failed = true, query = filter)
        }
    }.onEach { page ->
        if (page.ready && page.query.page != page.page)
            query.compareAndSet(page.query, page.query.copy(page = page.page, generation = requireNotNull(page.account).generation))
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
    fun changeScene(item: RecordItem, scene: PlannedScene) {
        if (item.cloudSummary != null) return
        val current = state.value.takeIf { it.ready } ?: return
        if (mutableSceneSave.value.saving || item.status != FlightStatus.COMPLETED || item.recordedAtWallMs == null ||
            current.items.none { it == item }) return
        val target = RecordSceneEditTarget(requireNotNull(current.account), item.key, item.recordedAtWallMs, item.sceneRevision)
        val request = RecordSceneSaveState(Math.addExact(mutableSceneSave.value.attempt, 1), target, saving = true)
        mutableSceneSave.value = request
        viewModelScope.launch {
            try {
                val failure = when (sceneStore.save(target, scene)) {
                    is RecordSceneSaveResult.Saved, RecordSceneSaveResult.Obsolete -> null
                    RecordSceneSaveResult.Changed -> RecordSceneFailure.CHANGED
                    RecordSceneSaveResult.NotEditable -> RecordSceneFailure.NOT_EDITABLE
                }
                mutableSceneSave.value = request.copy(saving = false, failure = failure)
            } catch (error: CancellationException) {
                throw error
            } catch (failure: RecordCloudFailure) {
                val reason = failure.reason
                val message = when {
                    reason is RecordMutationOutcome.Rejected && reason.status == 401 -> RecordSceneFailure.LOGIN_REQUIRED
                    reason is RecordMutationOutcome.Rejected && reason.status in setOf(404, 409, 410) -> RecordSceneFailure.CHANGED
                    else -> RecordSceneFailure.CLOUD
                }
                mutableSceneSave.value = request.copy(saving = false, failure = message)
            } catch (_: Exception) {
                mutableSceneSave.value = request.copy(saving = false, failure = RecordSceneFailure.WRITE)
            } finally {
                if (mutableSceneSave.value.attempt == request.attempt && mutableSceneSave.value.saving)
                    mutableSceneSave.value = request.copy(saving = false)
            }
        }
    }

    fun acknowledgeSceneFailure(attempt: Long) {
        if (mutableSceneSave.value.attempt == attempt)
            mutableSceneSave.value = mutableSceneSave.value.copy(failure = null)
    }

    private fun changePage(delta: Int) {
        val current = state.value.takeIf { it.ready } ?: return
        if (query.value != current.query) return
        query.value = current.query.copy(page = (current.page + delta).coerceIn(1, current.pageCount),
            generation = requireNotNull(current.account).generation)
    }
}
