package com.projectfocus.feature.record.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.common.coroutines.runCatchingCancellable
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.feature.completion.pass.BoardingPassData
import com.projectfocus.feature.record.share.ShareCardMapper
import com.projectfocus.service.focus.session.TodayStatsNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

/** 详情页状态。 */
sealed interface RecordDetailState {
    data object Loading : RecordDetailState










    data object NotFound : RecordDetailState

    data class Content(val detail: RecordDetail) : RecordDetailState

    data class Error(val message: String) : RecordDetailState
}























































@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordDetailViewModel @Inject constructor(
    private val dao: FlightHistoryDao,
    private val clock: Clock,
    private val zone: ZoneId,
    private val savedState: SavedStateHandle,










    private val todayNotifier: TodayStatsNotifier,
) : ViewModel() {






    private val idFlow: StateFlow<String?> = savedState.getStateFlow(KEY_RECORD_ID, null)

    private val _state: StateFlow<RecordDetailState> = idFlow

        //   这是"忘了 start"的安全失败模式（转圈，而不是显示别人的数据）。
        .filterNotNull()
        .flatMapLatest { id ->
            dao.observeById(id)
                .map { entity ->
                    if (entity == null) {
                        RecordDetailState.NotFound
                    } else {
                        RecordDetailState.Content(
                            RecordDetailBuilder.build(entity, zone, clock.millis()),
                        )
                    }
                }
                .catch { e ->

                    //   不接的话 Flow 终止，stateIn 永远停在最后一个值 ——
                    //   界面白屏且**不会**报任何错。这是最难查的一类"卡住"。
                    Timber.w(e, "观察记录 %s 失败", id)
                    emit(RecordDetailState.Error("读取记录失败"))
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecordDetailState.Loading,
        )

    val state: StateFlow<RecordDetailState> = _state

    /** 删除结果。`null` = 无操作；`true` = 成功；`false` = 失败。 */
    private val _deleteResult = MutableStateFlow<Boolean?>(null)
    val deleteResult: StateFlow<Boolean?> = _deleteResult.asStateFlow()

    private var deleteJob: Job? = null


























    private val _replayIntent = MutableStateFlow<ReplayIntent?>(null)
    val replayIntent: StateFlow<ReplayIntent?> = _replayIntent.asStateFlow()

    private var replayJob: Job? = null









    fun start(id: String) {
        if (!isValidId(id)) {
            Timber.w("记录 id 无效，详情页将显示「不存在」")
            return
        }
        if (!looksLikeUuid(id)) {

            Timber.d("记录 id 不像 UUID（可能是新格式），仍按原值查询：%s", id)
        }
        savedState[KEY_RECORD_ID] = id
    }

















    fun delete() {
        if (deleteJob?.isActive == true) return
        val id = idFlow.value ?: return
        deleteJob = viewModelScope.launch {
            _deleteResult.value = null
            val ok = runCatchingCancellable { dao.deleteById(id) }
                .onSuccess { todayNotifier.notifyChanged() }
                .onFailure { Timber.w(it, "删除记录 %s 失败", id) }
                .isSuccess
            _deleteResult.value = ok
        }
    }

    /** 界面处理完删除结果后调用，避免旋转屏幕时重复弹 Toast / 重复 pop。 */
    fun consumeDeleteResult() {
        _deleteResult.value = null
    }




















    fun replay() {

        //
        //   `Job.isActive` 在协程**已经完成**之后是 false —— 也就是说
        //   `isActive == true` 只能挡住"正在跑"的那一次，挡不住"上一次已经跑完"。
        //   用户连点两次的间隔通常是 100~300ms，而 `dao.findById` 在内存库里
        //   只要几百微秒 —— 第一次早就跑完了，第二次照样进去。
        //   于是：**两次点击 = 两次意图 = 两次导航**，用户看到飞行页叠两层。
        //

        //   断言"连点只回调 1 次"直接红（实际回调 2 次）—— 这是测试抓到真 bug。
        //
        //   → `!isCompleted`：job 只要还没结束就不放行。语义上正确，
        //     但注意它**没有时间窗口**：如果某次 `findById` 挂了 3 秒，
        //     这 3 秒内的点击全部被吞。这是可接受的（重飞不是高频操作），
        //     而且比"重复导航"好得多。
        //
        //   ⚠️ `delete()` 里也用 `isActive`，同样是这个问题。
        //      但那边有 `deleteById` 的幂等性兜底（第二次影响 0 行），

        //      记在这里，等下次改删除流程时一起换。
        if (replayJob?.isCompleted == false) return
        val id = idFlow.value ?: return
        replayJob = viewModelScope.launch {
            val entity = runCatchingCancellable { dao.findById(id) }
                .onFailure { Timber.w(it, "重飞前读取记录 %s 失败", id) }
                .getOrNull()
                ?: return@launch


            val detail = RecordDetailBuilder.build(entity, zone, clock.millis())
            if (!detail.canReplay) {
                // 取消的、状态异常的记录不给重飞。按钮理论上已隐藏，
                // 但连点 / 无障碍服务触发仍可能走到这里。
                Timber.w("记录 %s 不可重飞（status=%s）", id, entity.status)
                return@launch
            }

            _replayIntent.value = ReplayIntentBuilder.build(
                detail = detail,
                actualFocusMinutes = entity.actualFocusMinutes,
                plannedMinutes = entity.durationMinutes,

                //   直接透传编译不过 —— 这个类型不一致本身就是个提醒：
                //   `Long` 转 `Int` 会截断，超过 Int.MAX 的 id 会变成负数。
                //   实际 id 是 AUTOINCREMENT 的小整数，但**不要靠"实际不会超"来写代码**：
                //   用 `takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }` 显式挡掉越界值，
                //   越界时退化成 null（= 沿用默认分类），而不是悄悄变成 -1。
                categoryId = entity.categoryId
                    ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
                    ?.toInt(),
            )
        }
    }

    /** 界面完成导航后调用。不清的话返回详情页会立刻又被弹去飞行页。 */
    fun consumeReplayIntent() {
        _replayIntent.value = null
    }

































    suspend fun prepareShare(detail: RecordDetail): SharePayload? {
        val id = idFlow.value ?: return null
        val entity = runCatchingCancellable { dao.findById(id) }
            .onFailure { Timber.w(it, "分享前读取记录 %s 失败", id) }
            .getOrNull()
            ?: return null

        // 座位可能刚被选座页改过；detail 是进入页面时的快照，entity 才是权威值。
        val freshDetail = if (detail.seat != entity.seat) {
            detail.copy(seat = entity.seat)
        } else {
            detail
        }

        val pass = ShareCardMapper.build(
            entity = entity,
            detail = freshDetail,
            zone = zone,
        ) ?: return null

        return SharePayload(entity = entity, detail = freshDetail, pass = pass, zone = zone)
    }










    data class SharePayload(
        val entity: FlightHistoryEntity,
        val detail: RecordDetail,
        val pass: BoardingPassData,
        val zone: ZoneId,
    )

    companion object {







        const val KEY_RECORD_ID = "record_detail_id"












        fun isValidId(id: String?): Boolean = !id.isNullOrBlank()

        /** id 看起来是否像 UUID。**只用于日志**，不参与任何判定。 */
        internal fun looksLikeUuid(id: String): Boolean = try {
            java.util.UUID.fromString(id)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
