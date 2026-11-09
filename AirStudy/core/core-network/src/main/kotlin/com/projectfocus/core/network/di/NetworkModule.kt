package com.projectfocus.core.network.di

import com.projectfocus.core.network.api.FocusApi
import com.projectfocus.core.network.api.SceneContentApi
import com.projectfocus.core.network.plan.FlightPlanGateway
import com.projectfocus.core.network.plan.SocketFlightPlanGateway
import com.projectfocus.core.network.record.RecordMutationGateway
import com.projectfocus.core.network.record.HttpRecordMutationGateway
import com.projectfocus.core.network.subscription.HttpSubscriptionStatusGateway
import com.projectfocus.core.network.subscription.SubscriptionStatusGateway
import com.projectfocus.core.network.sse.SseClient
import com.projectfocus.core.network.sse.SseFrameParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import okhttp3.MediaType.Companion.toMediaType
import javax.inject.Qualifier
import javax.inject.Singleton




@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideRecordArchiveGateway(core: NetworkCore): com.projectfocus.core.network.record.RecordArchiveGateway =
        com.projectfocus.core.network.record.HttpRecordArchiveGateway(core.baseClient, core.baseUrl)

    @Provides
    @Singleton
    fun provideRecordSummaryGateway(core: NetworkCore): com.projectfocus.core.network.record.RecordSummaryGateway =
        com.projectfocus.core.network.record.HttpRecordSummaryGateway(core.baseClient, core.baseUrl)


    /** REST 用这个 client（短连接、并发多） */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class RestClient

    /** SSE 用这个 client（长连接、独占） */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class SseOkHttpClient

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class FlightPlanOkHttpClient

    /** 应用级协程作用域（SSE 重连循环挂在这里，不随页面销毁） */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class NetworkScope

    // ═══════════════════════════════════════════════════════
    // JSON
    // ═══════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideJson(): Json = SseFrameParser.DefaultJson

    // ═══════════════════════════════════════════════════════
    // 拦截器
    // ═══════════════════════════════════════════════════════


    // ═══════════════════════════════════════════════════════
    // OkHttpClient
    // ═══════════════════════════════════════════════════════

    @Provides
    @Singleton
    @RestClient
    fun provideRestOkHttp(
        core: NetworkCore,
    ): OkHttpClient = core.baseClient.newBuilder()
        .addInterceptor(core.authInterceptor)
        .addInterceptor(RetryInterceptor())
        .addInterceptor(
            core.loggingInterceptor(includeBody = core.logBodies),
        )
        .build()

    @Provides
    @Singleton
    @SseOkHttpClient
    fun provideSseOkHttp(
        core: NetworkCore,
    ): OkHttpClient = core.baseClient.newBuilder()

        //   与 REST 共用池会挤掉正常的短请求。
        .connectionPool(okhttp3.ConnectionPool(maxIdleConnections = 4, keepAliveDuration = 5, timeUnit = java.util.concurrent.TimeUnit.MINUTES))

        //   OkHttp 层的重试是"立刻重发同一个请求"，对长连接毫无意义；
        //   而且会把 Last-Event-ID 的语义搞乱（重发时 header 可能是旧的）。
        //   重连由 SseClient 的重连循环负责，它知道退避与 lastEventId。
        .addInterceptor(core.authInterceptor)
        // SSE 的响应体是无限的，日志拦截器读 body 会卡死 —— 只记头部。
        .addInterceptor(core.loggingInterceptor(includeBody = false))

        //   OkHttp 默认 readTimeout 10s，45 秒才一次心跳，
        //   10 秒必断 —— 这是绝大多数 SSE 接入的第一个坑。
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        // 连接超时仍然要有：连不上就别干等
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @FlightPlanOkHttpClient
    fun provideFlightPlanOkHttp(core: NetworkCore): OkHttpClient = core.baseClient.newBuilder()
        // The gateway captures the JWT with an identity generation; no dynamic auth interceptor.
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    @Provides
    @Singleton
    fun provideFlightPlanGateway(core: NetworkCore, @FlightPlanOkHttpClient client: OkHttpClient): FlightPlanGateway =
        SocketFlightPlanGateway(client, core.baseUrl)

    @Provides
    @Singleton
    fun provideSubscriptionStatusGateway(core: NetworkCore, @FlightPlanOkHttpClient client: OkHttpClient): SubscriptionStatusGateway =
        HttpSubscriptionStatusGateway(client, core.baseUrl)

    @Provides
    @Singleton
    fun provideRecordMutationGateway(core: NetworkCore): RecordMutationGateway =
        HttpRecordMutationGateway(core.baseClient, core.baseUrl)

    // ═══════════════════════════════════════════════════════
    // Retrofit
    // ═══════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideRetrofit(
        core: NetworkCore,
        json: Json,
        @RestClient client: OkHttpClient,
    ): Retrofit = Retrofit.Builder()
        // baseUrl 必须以 `/` 结尾；Retrofit 会强制校验并抛
        // IllegalArgumentException: baseUrl must end in / —— 这个 panic 是对的，
        // 因为拼错的 URL 只会在运行期变成 404，更难查。
        .baseUrl(core.baseUrl.normalizeBaseUrl())
        .client(client)
        .addConverterFactory(
            KotlinSerializationConverterFactory.create(
                json,
                "application/json; charset=UTF-8".toMediaType(),
            ),
        )
        .build()


    @Provides
    @Singleton
    fun provideFocusApi(retrofit: Retrofit): FocusApi = retrofit.create(FocusApi::class.java)

    @Provides
    @Singleton
    fun provideSceneContentApi(retrofit: Retrofit): SceneContentApi =
        retrofit.create(SceneContentApi::class.java)

    // ═══════════════════════════════════════════════════════
    // SSE
    // ═══════════════════════════════════════════════════════

    @Provides
    @Singleton
    @NetworkScope
    fun provideNetworkScope(): CoroutineScope =
        // SupervisorJob：一条连接崩了不能连带取消别的房间连接。
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSseClient(
        core: NetworkCore,
        @SseOkHttpClient client: OkHttpClient,
        @NetworkScope scope: CoroutineScope,
    ): SseClient = SseClient(
        okHttpClient = client,
        baseUrl = core.baseUrl,
        scope = scope,
    )
}

/** baseUrl 归一化：补尾部斜杠。 */
internal fun String.normalizeBaseUrl(): String = if (endsWith("/")) this else "$this/"
