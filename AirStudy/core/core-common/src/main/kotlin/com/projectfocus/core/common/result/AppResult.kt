package com.projectfocus.core.common.result

/**
 * 统一结果封装。用于「用例层」向 ViewModel 返回数据，
 * 避免在各 feature 里重复定义 try/catch + sealed class。
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>

    /**
     * @param code 后端 ApiResponseErrorDto.status 的取值；本地错误为 null
     * @param retryable 是否值得重试（网络类 true，业务校验类 false）
     */
    data class Failure(
        val error: AppError,
        val code: Int? = null,
        val retryable: Boolean = false,
        val cause: Throwable? = null,
    ) : AppResult<Nothing>

    companion object {
        inline fun <T> runCatchingApp(block: () -> T): AppResult<T> =
            try {
                Success(block())
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Failure(AppError.Unhandled(t), cause = t)
            }
    }
}

sealed interface AppError {
    data object Network : AppError
    data object Unauthorized : AppError
    data object NotFound : AppError
    data class Server(val message: String?) : AppError
    data class Unhandled(val throwable: Throwable) : AppError
    data class Business(val reason: String) : AppError
}
