package com.projectfocus.server.plugins

import com.projectfocus.server.config.ServerConfig
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

/**
 * HTTP 层的基础设施：压缩、默认头、请求计时。
 */
fun Application.configureHttp(config: ServerConfig) {

    install(Compression) {
        gzip {
            // 小于 1KB 的响应不压缩：gzip 头本身就有 20 字节，压缩反而更大
            minimumSize(1024)
        }
    }

    install(DefaultHeaders) {
        header(HttpHeaders.Server, "ProjectFocus")

        //   这是最低成本的攻击面收敛。


        //   （浏览器会记住 max-age），所以只在生产环境开。
        if (config.isProduction) {
            header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }


        //   /health 与未来的管理后台是浏览器访问，必须防住）
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
    }

    // 请求计时：给 CallLogging 用。

    //   `install(CallSetup) { call -> ... }` 的 lambda 参数**就是** `ApplicationCall`
    //   （javap 确认：`CallSetup.install(ApplicationCallPipeline, Function2<ApplicationCall,Continuation,...>)`）。
    //   而 `intercept` 的 lambda 参数是 `PipelineContext<Unit, PipelineCall>`，
    //   在 `configureHttp` 这个非 Application 接收者的上下文里容易解析错。
    //   `Hook.install(ApplicationCallPipeline, handler)` 是**普通成员方法**，
    //   不是 `Application.install(Plugin, block)`。写成 `with(this) { CallSetup.install(pipeline) {...} }`。
    val pipelineForHook = this
    io.ktor.server.application.hooks.CallSetup.install(pipelineForHook) { call ->
        call.attributes.put(CallLoggingTimeKey, System.currentTimeMillis())
    }
}
