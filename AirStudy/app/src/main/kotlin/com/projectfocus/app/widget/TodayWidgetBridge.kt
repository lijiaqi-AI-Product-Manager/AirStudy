package com.projectfocus.app.widget

import com.projectfocus.app.di.ApplicationScope
import com.projectfocus.service.focus.session.TodayStatsNotifier
import com.projectfocus.service.focus.session.WidgetPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

























































@Singleton
class TodayWidgetBridge @Inject constructor(
    private val notifier: TodayStatsNotifier,
    private val widgetPush: WidgetPush,






    @ApplicationScope private val appScope: CoroutineScope,
) {








    @Volatile
    private var started = false






















    private val refreshQueue = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 刷新次数，仅用于测试与日志（生产代码不要依赖它） */
    internal val refreshRequests: SharedFlow<Unit> get() = refreshQueue

    /**
     * 开始监听。**幂等**，可以在 Application.onCreate 里无脑调。
     */
    fun start() {
        if (started) return
        started = true

        appScope.launch {

            //   `collect` 的 lambda 是挂起的，所以下一个请求会等这一次结束 ——

            refreshQueue.collect {
                runCatching { widgetPush.onTodayChanged() }
                    .onFailure { Timber.w(it, "刷新今日小组件失败") }
            }
        }

        appScope.launch {

            //   直接调会让"结算"和"回前台"两个刷新并发，见上面 refreshQueue 的注释。
            notifier.changes.collect { refreshQueue.tryEmit(Unit) }
        }


        requestRefresh()
    }








    fun requestRefresh() {
        refreshQueue.tryEmit(Unit)
    }
}
