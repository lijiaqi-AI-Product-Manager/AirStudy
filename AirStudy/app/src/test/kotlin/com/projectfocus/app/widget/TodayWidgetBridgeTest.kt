package com.projectfocus.app.widget

import com.google.common.truth.Truth.assertThat
import com.projectfocus.service.focus.session.TodayStatsNotifier
import com.projectfocus.service.focus.session.WidgetMirror
import com.projectfocus.service.focus.session.WidgetPush
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test



































@OptIn(ExperimentalCoroutinesApi::class)
class TodayWidgetBridgeTest {

    /**
     * 记录刷新次数；可以用 [gate] 卡住某一次刷新，用来制造并发。
     */
    private class FakePush : WidgetPush {
        var todayCount = 0
            private set
        var maxConcurrent = 0
            private set
        private var concurrent = 0

        /** 非 null 时，刷新会在这里挂住，直到测试主动 complete */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun push(mirror: WidgetMirror) = Unit

        override suspend fun onTodayChanged() {
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            try {
                gate?.await()
            } finally {
                concurrent--
            }
            todayCount++
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ==================================================================

    @Test
    fun `start 之后立即请求一次刷新`() = runTest {

        //   用户昨天飞完关机、今天早上打开 App —— 库里数据没变，
        //   但桌面组件上的"今日 3 趟"必须变成 0。
        val push = FakePush()
        TodayWidgetBridge(TodayStatsNotifier(), push, scope).start()
        assertThat(push.todayCount).isEqualTo(1)
    }

    @Test
    fun `收到信号后刷新一次`() = runTest {
        val notifier = TodayStatsNotifier()
        val push = FakePush()
        val b = TodayWidgetBridge(notifier, push, scope)
        b.start()
        val baseline = push.todayCount      // start 自带的那一次

        notifier.notifyChanged()
        assertThat(push.todayCount).isEqualTo(baseline + 1)
    }

    @Test
    fun `两次刷新不会并发`() = runTest {

        //   并发时"后推的可能是先查的"，桌面数字会跳回旧值。
        val notifier = TodayStatsNotifier()
        val push = FakePush()
        val b = TodayWidgetBridge(notifier, push, scope)

        // 先让 start 自带的那次刷新通过（不设 gate）
        b.start()
        val baseline = push.todayCount

        // 卡住下一次
        val gate = CompletableDeferred<Unit>()
        push.gate = gate
        b.requestRefresh()

        // 刷新生效了但没完成 —— 此时再入队两个请求
        b.requestRefresh()
        b.requestRefresh()
        assertThat(push.maxConcurrent).isEqualTo(1)

        push.gate = null
        gate.complete(Unit)


        //   合并策略下第二、三个请求可能被合成一次，也可能是两次 ——
        //   那是实现细节，不该钉死。真正要守住的是**不并发**。
        assertThat(push.maxConcurrent).isEqualTo(1)
        assertThat(push.todayCount).isAtLeast(baseline + 1)
    }

    @Test
    fun `连续信号会被合并而不是逐个刷`() = runTest {
        val notifier = TodayStatsNotifier()
        val push = FakePush()
        val b = TodayWidgetBridge(notifier, push, scope)
        b.start()
        val baseline = push.todayCount

        // 卡住第一次，然后在它没完成时连发 5 个信号
        val gate = CompletableDeferred<Unit>()
        push.gate = gate
        b.requestRefresh()          // 占用消费者

        repeat(5) { notifier.notifyChanged() }
        push.gate = null
        gate.complete(Unit)


        //   缓冲区只有 1，中间的都被 DROP_OLDEST 掉了。

        //     钉死数字会让这条测试变成"实现细节的照相机"。
        //     要守的是"不会每个信号刷一次"，即 count < 6。
        assertThat(push.todayCount - baseline).isLessThan(6)
    }

    @Test
    fun `start 是幂等的，重复调用不会重复订阅`() = runTest {
        val notifier = TodayStatsNotifier()
        val push = FakePush()
        val b = TodayWidgetBridge(notifier, push, scope)

        b.start()
        val afterFirst = push.todayCount
        b.start()
        b.start()


        //   这里断言的是"没有多刷"。
        assertThat(push.todayCount).isEqualTo(afterFirst)
    }

    @Test
    fun `刷新抛异常不会中断后续刷新`() = runTest {

        //   实现里用 `runCatching` 包住单单一次刷新，collect 继续。
        val notifier = TodayStatsNotifier()
        var calls = 0
        val push = object : WidgetPush {
            override suspend fun push(mirror: WidgetMirror) = Unit
            override suspend fun onTodayChanged() {
                calls++
                if (calls == 1) error("模拟第一次推送失败")
            }
        }
        val b = TodayWidgetBridge(notifier, push, scope)
        b.start()                       // 第一次，会抛
        notifier.notifyChanged()        // 第二次，必须还能跑

        withTimeout(1_000) {
            while (calls < 2) kotlinx.coroutines.yield()
        }
        assertThat(calls).isAtLeast(2)
    }
}
