package com.projectfocus.server.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json




















@OptIn(ExperimentalSerializationApi::class)
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
                // 服务端不用 lenient：收到的必须是合法 JSON。
                // 宽松解析会让"客户端工具把 body 截断"这类错误静默通过。
                isLenient = false
                // @SerialName 重复时直接抛，不要静默取第一个
                coerceInputValues = false
            },
        )
    }
}
