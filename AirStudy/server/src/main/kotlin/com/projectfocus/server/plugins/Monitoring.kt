package com.projectfocus.server.plugins

import com.projectfocus.server.config.ServerConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import org.slf4j.event.Level















fun Application.configureMonitoring(config: ServerConfig) {
    install(CallLogging) {
        level = if (config.isProduction) Level.INFO else Level.DEBUG


        //   而邀请码是"能进入私人房间的凭证" —— 记进日志等于泄漏。
        format { call ->
            val status = call.response.status()?.value ?: "-"
            val ms = call.attributes.getOrNull(CallLoggingTimeKey)?.let {
                System.currentTimeMillis() - it
            } ?: 0
            "${call.request.httpMethod.value} ${call.request.path().redactPath()} -> $status (${ms}ms)"
        }
        // 不记录健康检查，否则 K8s 每 10 秒一次探测会淹没日志
        filter { call -> !call.request.path().startsWith("/health") }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val traceId = newTraceId()

            //   堆栈里可能有 SQL、文件路径、内部类名 —— 都是攻击者的地图。
            SERVER_LOG.error(
                "请求失败 traceId={} {} {}",
                traceId,
                call.request.httpMethod.value,
                call.request.uri.redactPath(),
                cause,
            )

            val (code, message, httpStatus) = classifyForUser(cause)

            call.respond(
                httpStatus,
                ApiErrorEnvelope(
                    error = ApiErrorBody(code = code, message = message, traceId = traceId),
                ),
            )
        }

        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                ApiErrorEnvelope(
                    error = ApiErrorBody(code = 404, message = "接口不存在：${call.request.path().redactPath()}"),
                ),
            )
        }

        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(
                status,
                ApiErrorEnvelope(
                    error = ApiErrorBody(code = 405, message = "该接口不支持 ${call.request.httpMethod.value}"),
                ),
            )
        }
    }
}

/** 与客户端 `ApiEnvelope` 同形状 —— 客户端无需区分"成功响应"与"错误响应"的结构 */
@kotlinx.serialization.Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)

@kotlinx.serialization.Serializable
data class ApiErrorBody(
    val code: Int,
    val message: String,
    val traceId: String? = null,
)








private fun classifyForUser(cause: Throwable): Triple<Int, String, HttpStatusCode> = when (cause) {
    is com.projectfocus.server.domain.DomainException ->
        Triple(cause.code, cause.userMessage, cause.httpStatus)

    is org.jetbrains.exposed.exceptions.ExposedSQLException ->
        Triple(5001, "服务繁忙，请稍后再试", HttpStatusCode.ServiceUnavailable)

    is io.ktor.server.plugins.BadRequestException ->
        // ContentNegotiation wraps invalid JSON/DTO input in this request
        // exception. It must stay a client error rather than becoming HTTP500.
        Triple(4001, "请求格式有误", HttpStatusCode.BadRequest)

    is kotlinx.serialization.SerializationException ->

        Triple(4001, "请求格式有误", HttpStatusCode.BadRequest)

    is java.util.concurrent.TimeoutException ->
        Triple(5002, "服务响应超时，请稍后再试", HttpStatusCode.GatewayTimeout)

    else ->
        Triple(5000, "服务器内部错误", HttpStatusCode.InternalServerError)
}













internal fun String.redactPath(): String {
    var s = this
    s = s.replace(Regex("""/code/[A-Za-z0-9]{4,8}"""), "/code/[redacted]")
    s = s.replace(
        Regex("""/room/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"""),
        "/room/[redacted]",
    )
    s = s.replace(Regex("""/session/[0-9a-fA-F-]{16,36}"""), "/session/[redacted]")
    return s
}

private val traceCounter = java.util.concurrent.atomic.AtomicLong(0)









internal fun newTraceId(): String =
    "%013d-%04d".format(
        System.currentTimeMillis(),
        traceCounter.incrementAndGet() % 10_000,
    )

/** CallLogging 里记录请求开始时间的 key */
internal val CallLoggingTimeKey = io.ktor.util.AttributeKey<Long>("pf:start")

/** 服务端自身的日志器（不用 `call.application.log` —— 它在 Ktor 3.6 里改名了） */
internal val SERVER_LOG = org.slf4j.LoggerFactory.getLogger("com.projectfocus.server.Request")
