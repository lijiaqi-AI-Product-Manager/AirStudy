package com.projectfocus.core.network.di

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException


internal class AuthInterceptor(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
) : Interceptor {

    private val sameOriginHost: String? = runCatching {
        java.net.URI(baseUrl).host?.lowercase()
    }.getOrNull()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val host = request.url.host.lowercase()
        val isSameOrigin = sameOriginHost != null && host == sameOriginHost

        // 已经是显式指定过 Authorization 的（比如测试）不覆盖
        if (!isSameOrigin || request.header("Authorization") != null) {
            return chain.proceed(request)
        }

        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            return chain.proceed(request)
        }

        val authorized = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(authorized)
    }
}


internal class RetryInterceptor(
    private val maxAttempts: Int = 2, // 总共 2 次 = 1 次重试
    private val baseDelayMillis: Long = 400L,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        var lastResponse: Response? = null
        var lastIo: IOException? = null

        while (attempt < maxAttempts) {
            attempt++
            lastResponse?.close()
            lastResponse = null

            try {
                val response = chain.proceed(request)
                if (!shouldRetry(request.method, response.code) || attempt >= maxAttempts) {
                    return response
                }
                lastResponse = response
                val waitMs = retryAfterMillis(response) ?: backoff(attempt)
                Timber.i(
                    "HTTP %d，%s %s 将在 %dms 后重试（第 %d 次）",
                    response.code, request.method, request.url.encodedPath, waitMs, attempt,
                )
                Thread.sleep(waitMs)
            } catch (e: IOException) {

                //   · SocketTimeoutException —— 请求可能已经到服务端并被执行了。
                //     在 POST 上重试会造成重复写入 → **不重试**
                //   · 其它（ConnectException 等）—— 连都没连上，肯定没执行 → 可重试
                val safeToRetryAgain = request.method in RETRYABLE_METHODS ||
                    (request.method == "POST" && e !is java.net.SocketTimeoutException &&
                        e !is java.io.InterruptedIOException)

                if (!safeToRetryAgain || attempt >= maxAttempts) {
                    lastIo = e
                    throw e
                }
                lastIo = e
                Timber.i("IO 异常（%s），%s %s 将重试", e.javaClass.simpleName, request.method, request.url.encodedPath)
                Thread.sleep(backoff(attempt))
            }
        }

        lastResponse?.let { return it }
        throw lastIo ?: IOException("重试耗尽但没有任何响应")
    }

    private fun shouldRetry(method: String, code: Int): Boolean = when {
        // 服务端明确"现在不行" → 幂等与否都可重试
        code == 503 || code == 429 -> true
        // 5xx 其它：只有幂等方法才重试（500 可能是服务端处理到一半崩了）
        code in 500..599 -> method in RETRYABLE_METHODS

        code == 408 -> method in RETRYABLE_METHODS
        else -> false
    }










    private fun retryAfterMillis(response: Response): Long? {
        val raw = response.header("Retry-After") ?: return null
        val seconds = raw.trim().toLongOrNull() ?: return null
        if (seconds <= 0) return null
        return (seconds * 1000).coerceAtMost(MAX_RETRY_AFTER_MILLIS)
    }

    private fun backoff(attempt: Int): Long =
        (baseDelayMillis shl (attempt - 1)).coerceAtMost(2_000L)

    companion object {
        /** HTTP 规范定义的幂等方法（外加 DELETE） */
        private val RETRYABLE_METHODS = setOf("GET", "HEAD", "OPTIONS", "DELETE", "PUT")


        private const val MAX_RETRY_AFTER_MILLIS = 30_000L
    }
}
