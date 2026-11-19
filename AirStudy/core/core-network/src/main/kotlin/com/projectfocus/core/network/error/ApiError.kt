package com.projectfocus.core.network.error


sealed interface ApiError {

    /** 人类可读的描述，用于日志与埋点，**不直接展示给用户** */
    val debugMessage: String

    /** 是否值得自动重试 */
    val isRetryable: Boolean

    /** 无网络 / DNS 失败 / 连接被拒 */
    data class NoNetwork(
        override val debugMessage: String,
        val cause: Throwable? = null,
    ) : ApiError {

        //   等系统网络回调（ConnectivityManager）通知恢复后再重试。
        override val isRetryable: Boolean get() = false
    }

    /** 连上了但超时 */
    data class Timeout(
        override val debugMessage: String = "请求超时",
        val cause: Throwable? = null,
    ) : ApiError {

        override val isRetryable: Boolean get() = true
    }

    /** 401 / 403 —— token 失效或无权限 */
    data class Unauthorized(
        override val debugMessage: String = "登录已过期",
        val httpCode: Int = 401,
    ) : ApiError {

        override val isRetryable: Boolean get() = false
    }

    /** 5xx */
    data class ServerError(
        val httpCode: Int,
        override val debugMessage: String,
        val traceId: String? = null,
    ) : ApiError {


        override val isRetryable: Boolean get() = httpCode != 501
    }

    /** 429 */
    data class RateLimited(
        /** 服务端要求的等待秒数（Retry-After），未知为 null */
        val retryAfterSeconds: Long? = null,
        override val debugMessage: String = "请求过于频繁",
    ) : ApiError {
        override val isRetryable: Boolean get() = true
    }


    data class Business(
        val code: Int,
        val serverMessage: String,
        val traceId: String? = null,
        override val debugMessage: String = "业务错误 $code: $serverMessage",
    ) : ApiError {
        override val isRetryable: Boolean get() = false
    }

    /** JSON 解析失败 / 字段类型不符 —— 协议不匹配 */
    data class Serialization(
        override val debugMessage: String,
        val cause: Throwable? = null,
    ) : ApiError {

        override val isRetryable: Boolean get() = false
    }

    /** 其它未分类 */
    data class Unknown(
        override val debugMessage: String,
        val cause: Throwable? = null,
    ) : ApiError {
        override val isRetryable: Boolean get() = false
    }
}
