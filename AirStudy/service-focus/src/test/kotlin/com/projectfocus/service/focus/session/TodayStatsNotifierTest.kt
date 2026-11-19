package com.projectfocus.service.focus.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test























@OptIn(ExperimentalCoroutinesApi::class)
class TodayStatsNotifierTest {

    @Test
    fun `没有订阅者时发信号不抛异常`() {

        val notifier = TodayStatsNotifier()
        repeat(20) { notifier.notifyChanged() }
    }

    @Test
    fun `有订阅者时能收到信号`() = runTest {
        val notifier = TodayStatsNotifier()
        val got = CompletableDeferred<Unit>()


        //   用默认的 StandardTestDispatcher 的话，collect 要等到调度器跑起来，
        //   测试得在 launch 之后手动 advanceUntilIdle —— 少写一句就变成
        //   "信号发出去了但没人收"，而测试会误报成"实现有问题"。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            notifier.changes.collect { got.complete(Unit) }
        }

        notifier.notifyChanged()
        withTimeout(1_000) { got.await() }
    }

    @Test
    fun `不重放历史信号`() = runTest {

        //   这里模拟"先发再订阅"，断言订阅者**什么都不该收到**。
        val notifier = TodayStatsNotifier()
        notifier.notifyChanged()

        var count = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            notifier.changes.collect { count++ }
        }

        // 给重放留出机会：如果真有 replay，Unconfined 下 collect 一进去就会拿到
        assertThat(count).isEqualTo(0)

        // 真正的信号仍然要能收到 —— 否则这条测试会因为"什么都没接上"而假绿
        notifier.notifyChanged()
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `多个订阅者都能收到`() = runTest {
        val notifier = TodayStatsNotifier()
        var a = 0
        var b = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            notifier.changes.collect { a++ }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            notifier.changes.collect { b++ }
        }

        notifier.notifyChanged()

        //   SharedFlow 是广播语义，写成 Channel 就会变成"谁抢到算谁的" ——
        //   而那样的话，第二个消费者（通知栏）会随机收不到。
        assertThat(a).isEqualTo(1)
        assertThat(b).isEqualTo(1)
    }

    @Test
    fun `连续发 20 次不会让发送方挂起`() = runTest {

        //   实现用的是 tryEmit + DROP_OLDEST，所以 20 次立刻返回。
        val notifier = TodayStatsNotifier()
        val done = async {
            repeat(20) { notifier.notifyChanged() }
            true
        }
        withTimeout(1_000) { done.await() }
    }

    @Test
    fun `没有订阅者时信号被丢弃而不是堆积`() = runTest {
        val notifier = TodayStatsNotifier()
        repeat(100) { notifier.notifyChanged() }

        // 订阅之后，**不应该**收到之前那 100 次中的任何一个
        var count = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            notifier.changes.collect { count++ }
        }
        assertThat(count).isEqualTo(0)
    }
}
