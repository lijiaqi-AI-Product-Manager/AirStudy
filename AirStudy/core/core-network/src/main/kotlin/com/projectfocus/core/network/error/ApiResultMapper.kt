package com.projectfocus.core.network.error

import com.projectfocus.core.network.dto.ApiEnvelope
import com.projectfocus.core.network.dto.ApiErrorDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException



































object ApiResultMapper {

    // ═══════════════════════════════════════════════════════
    // 入口 1：把一次接口调用的结果收敛成 Kotlin Result
    // ═══════════════════════════════════════════════════════






















    suspend inline fun <T> call(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(ApiException(t, classifyThrowable(t)))
        }

    // ═══════════════════════════════════════════════════════
    // 入口 2：信封解包 —— HTTP 200 也可能是失败
    // ═══════════════════════════════════════════════════════













    fun <T> ApiEnvelope<T>.requireData(): T {
        if (error != null) throw ApiException(error = error.toApiError())
        if (success == false) {
            throw ApiException(
                error = ApiError.Business(
                    code = -1,
                    serverMessage = message ?: "服务端未提供原因",
                    traceId = null,
                ),
            )
        }
        return data ?: throw ApiException(
            error = ApiError.Serialization("服务端返回 200 但 data 为空（可能是端点实现有误）"),
        )
    }

    /** 允许 `data` 为 null 的接口用这个（如 `my-room`） */
    fun <T> ApiEnvelope<T>.dataOrNull(): T? {
        if (error != null) throw ApiException(error = error.toApiError())
        if (success == false) {
            throw ApiException(
                error = ApiError.Business(
                    code = -1,
                    serverMessage = message ?: "服务端未提供原因",
                    traceId = null,
                ),
            )
        }
        return data
    }

    fun ApiErrorDto.toApiError(): ApiError = ApiError.Business(
        // code 缺失时用 -1 而不是 0 —— 0 是"成功"的常见约定值，
        // 用 0 表示未知错误在埋点里会与成功混淆。
        code = code ?: -1,
        serverMessage = message?.takeIf { it.isNotBlank() } ?: "服务端未提供错误信息",
        traceId = traceId,
    )

    // ═══════════════════════════════════════════════════════
    // 分类核心
    // ═══════════════════════════════════════════════════════

    fun classifyThrowable(t: Throwable): ApiError = when (t) {

        // ── 0. 包装层：ApiException 已经分类过，直接透出 ────────────
        //   避免 Result.failure 被再次 map 时把已有分类覆盖成 Unknown。
        is ApiException -> t.error

        // ── 1. HTTP 错误（Retrofit 把非 2xx 包成 HttpException）────
        is HttpException -> classifyHttp(t.code(), t.response()?.errorBody()?.string())

        // ── 2. 序列化：协议不匹配 ────────────────────────────────
        is SerializationException -> ApiError.Serialization(
            debugMessage = "响应解析失败：${t.message}",
            cause = t,
        )

        // ── 3. IO 家族（顺序见类注释的继承树，**不要重排**）────────
        // 3.1 TLS —— 必须早于 SocketException（它是 SocketException 子类）
        is SSLPeerUnverifiedException -> ApiError.Unknown(
            debugMessage = "TLS 对端证书校验失败，疑似中间人攻击或域名不匹配",
            cause = t,
        )
        is SSLHandshakeException -> ApiError.Unknown(
            debugMessage = "TLS 握手失败：${t.message}",
            cause = t,
        )
        is SSLException -> ApiError.Unknown(
            debugMessage = "TLS 层错误：${t.message}",
            cause = t,
        )

        // 3.2 超时 —— 必须早于 InterruptedIOException / IOException
        is SocketTimeoutException -> ApiError.Timeout(cause = t)

        // 3.3 连接层
        is UnknownHostException -> ApiError.NoNetwork(
            debugMessage = "DNS 解析失败：${t.message}",
            cause = t,
        )
        is ConnectException -> ApiError.NoNetwork(
            debugMessage = "连接被拒绝/不可达：${t.message}",
            cause = t,
        )
        is NoRouteToHostException -> ApiError.NoNetwork(
            debugMessage = "无路由到主机：${t.message}",
            cause = t,
        )
        is PortUnreachableException -> ApiError.NoNetwork(
            debugMessage = "目标端口不可达：${t.message}",
            cause = t,
        )
        // 3.4 泛 Socket 异常（落在这条的多是 "Socket closed" / "broken pipe"）
        is SocketException -> ApiError.NoNetwork(
            debugMessage = "连接中断：${t.message}",
            cause = t,
        )

        // 3.5 协议层：服务端返回了非预期协议（如 HTTP 端点返回了明文）
        is UnknownServiceException -> ApiError.Serialization(
            debugMessage = "服务协议异常（可能命中了错误端口）：${t.message}",
            cause = t,
        )

        // 3.6 读流提前结束 —— 服务端断流/代理截断
        is EOFException -> ApiError.Timeout(
            debugMessage = "响应流提前结束（服务端断流或超时被截断）",
            cause = t,
        )
        is InterruptedIOException -> ApiError.Timeout(
            debugMessage = "IO 被中断：${t.message}",
            cause = t,
        )

        // 3.7 兜底 IO
        is IOException -> ApiError.NoNetwork(
            debugMessage = "IO 错误：${t.message}",
            cause = t,
        )

        // ── 4. 其它 ───────────────────────────────────────────
        else -> ApiError.Unknown(
            debugMessage = "${t::class.java.simpleName}: ${t.message}",
            cause = t,
        )
    }














    fun classifyHttp(code: Int, rawBody: String? = null): ApiError {
        val serverMessage = extractMessage(rawBody)
        val traceId = extractTraceId(rawBody)

        return when (code) {
            // 401 未认证 / 403 无权限 —— 两者对用户是同一个动作：重新登录
            401, 403 -> ApiError.Unauthorized(httpCode = code)

            // 限流
            429 -> ApiError.RateLimited(
                retryAfterSeconds = null, // 由拦截器从 Retry-After 头填充
                debugMessage = "请求过于频繁",
            )


            400, 404, 409, 410, 412, 422 -> ApiError.Business(
                code = code,
                serverMessage = serverMessage ?: defaultBusinessMessage(code),
                traceId = traceId,
            )

            // 5xx
            in 500..599 -> ApiError.ServerError(
                httpCode = code,
                debugMessage = serverMessage ?: "服务端错误 HTTP $code",
                traceId = traceId,
            )

            // 其它（3xx 未被 follow、1xx 等）—— 不该发生，但要有归宿
            else -> ApiError.Unknown(debugMessage = "未预期 HTTP 状态码 $code")
        }
    }

    private fun defaultBusinessMessage(code: Int): String = when (code) {
        400 -> "请求参数有误"
        404 -> "请求的资源不存在"
        409 -> "操作冲突，请刷新后重试"
        410 -> "该内容已被删除"
        412 -> "前置条件不满足，请刷新后重试"
        422 -> "提交的内容不符合要求"
        else -> "请求失败（HTTP $code）"
    }












    private fun extractMessage(rawBody: String?): String? {
        val body = rawBody?.trim().orEmpty()
        if (body.isEmpty()) return null
        // 截断超长 body：错误体里塞了整个 Android 堆栈见过，别把它存进埋点。
        val scoped = if (body.length > 4096) body.substring(0, 4096) else body

        val key = "\"message\""
        val idx = scoped.indexOf(key)
        if (idx < 0) return null

        var i = idx + key.length
        while (i < scoped.length && (scoped[i] == ' ' || scoped[i] == '\t' || scoped[i] == '\n' || scoped[i] == '\r' || scoped[i] == ':')) i++
        if (i >= scoped.length || scoped[i] != '"') return null

        i++ // 跳过开引号
        val sb = StringBuilder()
        while (i < scoped.length) {
            val c = scoped[i]
            when (c) {
                '\\' -> {
                    // 转义序列：只处理最常见的 \" \\ \n \uXXXX 的简化版
                    if (i + 1 < scoped.length) {
                        when (val n = scoped[i + 1]) {
                            '"', '\\', '/' -> sb.append(n)
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'u' -> {
                                // \uXXXX —— 4 位十六进制；解不出来就原样跳过
                                if (i + 5 < scoped.length) {
                                    val hex = scoped.substring(i + 2, i + 6)
                                    val codeUnit = hex.toIntOrNull(16)
                                    if (codeUnit != null) {
                                        sb.append(codeUnit.toChar())
                                        i += 4
                                    }
                                }
                            }
                            else -> sb.append(n)
                        }
                        i += 2
                        continue
                    }
                    i++
                }
                '"' -> return sb.toString().takeIf { it.isNotBlank() }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        // 没闭合的引号 —— 说明 body 被截断了，已收到的部分仍然有价值
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun extractTraceId(rawBody: String?): String? {
        val body = rawBody ?: return null
        val key = "\"traceId\""
        val idx = body.indexOf(key)
        if (idx < 0) return null
        var i = idx + key.length
        while (i < body.length && body[i] != '"') i++
        val start = i + 1
        val end = body.indexOf('"', start)
        if (end < 0) return null
        return body.substring(start, end).takeIf { it.isNotBlank() }
    }
}










class ApiException(
    val error: ApiError,
    cause: Throwable? = null,
) : Exception(error.debugMessage, cause) {
    constructor(cause: Throwable, error: ApiError) : this(error, cause)

    override fun toString(): String = "ApiException(${error::class.java.simpleName}: ${error.debugMessage})"
}
