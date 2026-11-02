package com.projectfocus.feature.record.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.database.dao.FlightHistoryDao
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
) : ViewModel() {








    private val keywordFlow = MutableStateFlow("")

















    val state: StateFlow<RecordListState> = keywordFlow
        .flatMapLatest { kw ->
            dao.observeAll().map { rows ->
                runCatching { RecordListBuilder.build(rows, zone, clock.millis(), kw) }
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

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { dao.deleteById(id) }
                .onSuccess {

                    //   数据没变，刷了也是白刷 —— 更糟的是会把"连续天数"刷成
                    //   和库里吻合的值，反而让人以为删除成功了。

                    todayNotifier.notifyChanged()
                }
                .onFailure { Timber.w(it, "删除记录 %s 失败", id) }
        }
    }

    private companion object {







        const val MAX_KEYWORD_LEN = 32
    }
}
