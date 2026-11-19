package com.projectfocus.feature.record.detail

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.common.coroutines.runCatchingCancellable
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.completion.pass.BoardingPassData
import com.projectfocus.feature.record.share.ShareCardMapper
import com.projectfocus.service.focus.history.FlightHistoryReader
import com.projectfocus.service.focus.history.FlightRecordKey
import com.projectfocus.service.focus.history.FlightRecordKind
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.service.focus.history.RecordDeletionOperations
import com.projectfocus.service.focus.history.RecordDeletionResult
import com.projectfocus.service.focus.history.RecordDeletionTarget
import com.projectfocus.service.focus.history.RecordedFlight
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import timber.log.Timber

sealed interface RecordDetailState {
    data object Loading : RecordDetailState
    data object NotFound : RecordDetailState
    data class Content(val detail: RecordDetail, val source: RecordReplaySource) : RecordDetailState
    data class Error(val message: String) : RecordDetailState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordDetailViewModel @Inject constructor(
    private val history: FlightHistoryReader,
    private val clock: Clock,
    private val zone: ZoneId,
    private val savedState: SavedStateHandle,
    private val deletion: RecordDeletionOperations,
) : ViewModel() {
    private data class Target(val key: FlightRecordKey, val scope: String, val start: Long?)

    private val target = MutableStateFlow(savedState.get<Bundle>(KEY_TARGET)?.let {
        val id = it.getString("id") ?: return@let null
        val kind = it.getString("kind")?.let { name -> FlightRecordKind.entries.firstOrNull { mode -> mode.name == name } }
            ?: return@let null
        Target(FlightRecordKey(kind, id), it.getString("scope") ?: return@let null,
            it.getLong("start").takeIf { _ -> it.containsKey("start") })
    })
    private val reload = MutableStateFlow(0)
    val state: StateFlow<RecordDetailState> = combine(target, TokenStore.identities, reload) { destination, identity, _ ->
        destination to identity
    }.flatMapLatest { (destination, identity) ->
        if (destination == null) return@flatMapLatest flowOf(RecordDetailState.Loading)
        if (destination.key.id.isBlank()) return@flatMapLatest flowOf(RecordDetailState.NotFound)
        val account = HistoryAccount(TokenStore.storageScope(identity), identity.generation)
        if (account.scope != destination.scope || !account.isCurrent()) return@flatMapLatest flowOf(RecordDetailState.NotFound)
        history.record(account, destination.key).map<RecordedFlight?, RecordDetailState> { record ->
            if (!account.isCurrent() || target.value != destination || record == null ||
                (destination.start != null && destination.start != record.recordedAtWallMs)
            ) RecordDetailState.NotFound
            else RecordDetailState.Content(
                project(record),
                RecordReplaySource(account, record.key, record.recordedAtWallMs),
            )
        }.onStart { emit(RecordDetailState.Loading) }.catch { error ->
            Timber.w(error, "Could not read flight details")
            emit(if (account.isCurrent()) RecordDetailState.Error("读取记录失败，请重试") else RecordDetailState.NotFound)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), RecordDetailState.Loading)

    private val _deleteResult = MutableStateFlow<Boolean?>(null)
    val deleteResult: StateFlow<Boolean?> = _deleteResult.asStateFlow()
    private var deleteJob: Job? = null
    private var deleteAccount: HistoryAccount? = null
    fun isDeleteResultCurrent(): Boolean = deleteAccount?.isCurrent() == true

    private val _replayIntent = MutableStateFlow<ReplayIntent?>(null)
    val replayIntent: StateFlow<ReplayIntent?> = _replayIntent.asStateFlow()
    private var replayJob: Job? = null

    fun start(
        id: String,
        kind: FlightRecordKind = FlightRecordKind.COUNTDOWN,
        ownerScope: String = TokenStore.storageScope(),
        startedAtWallMs: Long? = null,
    ) {
        val request = Target(FlightRecordKey(kind, id), ownerScope, startedAtWallMs)
        if (target.value == request) return
        savedState[KEY_TARGET] = Bundle().apply {
            putString("id", id)
            putString("kind", kind.name)
            putString("scope", ownerScope)
            startedAtWallMs?.let { putLong("start", it) }
        }
        target.value = request
    }

    fun retry() { reload.value++ }

    fun currentContent(): RecordDetailState.Content? =
        (state.value as? RecordDetailState.Content)?.takeIf { matches(it.source) }

    suspend fun prepareDelete(): RecordDeletionTarget? {
        val source = currentContent()?.source ?: return null
        val record = fresh(source) ?: return null
        return RecordDeletionTarget(source.account, record)
    }

    fun delete(target: RecordDeletionTarget) {
        if (deleteJob?.isActive == true) return
        val source = RecordReplaySource(target.account, target.record.key, target.record.recordedAtWallMs)
        if (!matches(source)) return
        deleteAccount = target.account
        deleteJob = viewModelScope.launch {
            _deleteResult.value = null
            val result = runCatchingCancellable { deletion.delete(target) }
                .onFailure { Timber.w(it, "Could not remove the flight record") }
            if (matches(source)) _deleteResult.value = when {
                result.isFailure -> false
                result.getOrNull() == RecordDeletionResult.OBSOLETE -> null
                else -> result.getOrNull() == RecordDeletionResult.DELETED
            }
        }
    }

    fun consumeDeleteResult() {
        _deleteResult.value = null
        deleteAccount = null
    }

    fun replay() {
        if (replayJob?.isActive == true || _replayIntent.value != null) return
        val displayed = currentContent()?.takeIf { it.detail.canReplay } ?: return
        replayJob = viewModelScope.launch {
            yield()
            val record = fresh(displayed.source) ?: return@launch
            val detail = project(record)
            if (!detail.canReplay) return@launch
            val plan = (record as? RecordedFlight.Countdown)?.record
            val category = plan?.categoryId?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
            _replayIntent.value = ReplayIntentBuilder.build(detail, record.focusMinutes, plan?.durationMinutes, category)
                .copy(source = displayed.source)
        }
    }

    fun consumeReplayIntent() { _replayIntent.value = null }

    fun cancelReplay() {
        replayJob?.cancel()
        consumeReplayIntent()
    }

    suspend fun prepareShare(detail: RecordDetail): SharePayload? {
        val displayed = currentContent()?.takeIf { it.detail == detail } ?: return null
        val record = fresh(displayed.source) ?: return null
        val updated = project(record)
        val pass = ShareCardMapper.build(record, updated, zone) ?: return null
        return SharePayload(record, updated, pass, zone, displayed.source)
    }

    private fun project(record: RecordedFlight) = RecordDetailBuilder.build(record, zone, clock.millis())

    private suspend fun fresh(source: RecordReplaySource): RecordedFlight? {
        if (!matches(source)) return null
        return runCatchingCancellable { history.record(source.account, source.key).first() }
            .onFailure { Timber.w(it, "Could not refresh the flight record") }
            .getOrNull()?.takeIf { it.recordedAtWallMs == source.startedAtWallMs && matches(source) }
    }

    private fun matches(source: RecordReplaySource): Boolean {
        val request = target.value ?: return false
        return request.key == source.key && request.scope == source.account.scope &&
            (request.start == null || request.start == source.startedAtWallMs) && source.account.isCurrent()
    }

    data class SharePayload(
        val record: RecordedFlight,
        val detail: RecordDetail,
        val pass: BoardingPassData,
        val zone: ZoneId,
        val source: RecordReplaySource,
    )

    private companion object {
        const val KEY_TARGET = "record_detail_target"
    }
}
