package com.projectfocus.core.common.coroutines

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` 的协程安全版本。
 *
 * Kotlin 的 `runCatching` 会捕获 `CancellationException`，把它当成普通失败；
 * 在 `viewModelScope`/`lifecycleScope` 里这会吞掉取消、继续跑后续逻辑。
 * 这里只把“非取消”的异常收进 [Result]，取消原样重抛。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { error ->
        if (error is CancellationException) throw error
    }
