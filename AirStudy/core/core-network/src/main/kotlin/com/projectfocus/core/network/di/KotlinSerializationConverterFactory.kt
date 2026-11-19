package com.projectfocus.core.network.di

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.StringFormat
import kotlinx.serialization.serializer
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type



























class KotlinSerializationConverterFactory private constructor(
    private val format: StringFormat,
    private val contentType: MediaType?,
) : Converter.Factory() {

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *> {
        val serializer: KSerializer<Any> = serializerFor(type)
        return Converter<ResponseBody, Any> { body ->

            val text = body.string()
            if (text.isBlank()) {
                throw SerializationException(
                    "响应体为空（期望 ${typeNameOf(type)}）。" +
                        "服务端可能返回了 204 或漏写了 Content-Length。",
                )
            }
            try {
                @Suppress("UNCHECKED_CAST")
                format.decodeFromString(serializer, text) as Any
            } catch (e: SerializationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // kotlinx.serialization 对"字段类型完全不符"有时抛 IAE
                throw SerializationException("响应解析失败：${e.message}", e)
            }
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody> {
        val serializer: KSerializer<Any> = serializerFor(type)
        return Converter<Any, RequestBody> { value ->
            val json = format.encodeToString(serializer, value)
            json.toRequestBody(contentType)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(type: Type): KSerializer<Any> =
        try {
            serializer(type) as KSerializer<Any>
        } catch (e: Throwable) {
            // 典型原因：DTO 忘了加 `@Serializable`
            throw SerializationException(
                "无法为类型 ${typeNameOf(type)} 生成 KSerializer —— " +
                    "检查这个 DTO 是否漏了 @Serializable。",
                e,
            )
        }

    companion object {
        /**
         * 供 [NetworkModule] 使用。
         *
         * `contentType` 必须带 `charset=UTF-8`：kotlinx.serialization 输出
         * 总是 UTF-8，而服务端有些框架默认按 ISO-8859-1 解 ——
         * 中文昵称会变成 `??`。
         */
        fun create(format: StringFormat, contentType: MediaType?): KotlinSerializationConverterFactory =
            KotlinSerializationConverterFactory(format, contentType)
    }
}























private fun typeNameOf(type: Type): String =

    //   解析泛型签名的实现）。拼错误信息的地方绝不能再抛第二次。
    runCatching { type.toString() }.getOrDefault(type.javaClass.name)
