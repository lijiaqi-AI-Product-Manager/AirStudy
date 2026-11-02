package com.projectfocus.feature.profile.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description












































@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
















suspend fun awaitReal(
    timeoutMs: Long = 5_000,
    intervalMs: Long = 2,
    condition: suspend () -> Boolean,
) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) {
            throw AssertionError("awaitReal 超时：${timeoutMs}ms 内条件始终不成立")
        }
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            Thread.sleep(intervalMs)
        }
    }
}
