package com.projectfocus.core.common.dispatcher

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 调度器抽象。测试里替换为 StandardTestDispatcher，避免 runTest 与真实 IO 打架。
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}
