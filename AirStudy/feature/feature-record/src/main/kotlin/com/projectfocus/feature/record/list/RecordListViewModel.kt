package com.projectfocus.feature.record.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.RecordDeletionOperations
import com.projectfocus.service.focus.history.RecordDeletionTarget
import com.projectfocus.service.focus.history.RecordDeletionResult
import kotlinx.coroutines.CancellationException
import com.projectfocus.service.focus.session.TodayStatsNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject































@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordListViewModel @Inject constructor(
    private val dao: FlightHistoryDao,
    private val clock: Clock,
    private val zone: ZoneId,












    private val todayNotifier: TodayStatsNotifier,
    private val deletion: RecordDeletionOperations,
) : ViewModel() {








    private val keywordFlow = MutableStateFlow("")

















    val state: StateFlow<RecordListState> = combine(keywordFlow, TokenStore.identities) { kw, identity -> kw to identity }
        .flatMapLatest { (kw, identity) ->
            dao.observeAll().map { rows ->
                val scope = TokenStore.storageScope(identity)
                val owned = if (identity != TokenStore.snapshot()) emptyList() else rows.filter {
                    it.ownerScope == scope || (scope == "local" && it.ownerScope == null)
                }
                runCatching { RecordListBuilder.build(owned, zone, clock.millis(), kw) }
                    .onFailure { Timber.w(it, "构建历史列表失败，回退为空态") }


                    .getOrDefault(RecordListState.Empty)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecordListState.Loading,
        )









    fun onSearch(keyword: String) {
        keywordFlow.value = keyword.take(MAX_KEYWORD_LEN)
    }

    fun clearSearch() {
        keywordFlow.value = ""
    }

    suspend fun prepareDelete(id: String): RecordDeletionTarget? = deletion.captureCountdown(id)

    private var deleting = false
    fun delete(target: RecordDeletionTarget, completed: (Boolean) -> Unit = {}) {
        if (deleting || !target.account.isCurrent()) return
        deleting = true
        viewModelScope.launch {
            try {
                val result = deletion.delete(target)
                if (target.account.isCurrent() && result != RecordDeletionResult.OBSOLETE)
                    completed(result == RecordDeletionResult.DELETED)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                Timber.w(error, "删除记录失败")
                if (target.account.isCurrent()) completed(false)
            } finally { deleting = false }
        }
    }

    private companion object {







        const val MAX_KEYWORD_LEN = 32
    }
}
