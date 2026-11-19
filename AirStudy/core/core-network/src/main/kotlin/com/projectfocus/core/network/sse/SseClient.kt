package com.projectfocus.core.network.sse

import com.projectfocus.core.network.error.ApiError
import com.projectfocus.core.network.error.ApiResultMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong


class SseClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
    private val scope: CoroutineScope,
) {

    // ═══════════════════════════════════════════════════════
    // 对外暴露的状态与事件
    // ═══════════════════════════════════════════════════════

    private val _state = MutableStateFlow(SseConnectionState.Idle)
    val state: StateFlow<SseConnectionState> = _state.asStateFlow()












    private val _events = MutableSharedFlow<SseEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SseEvent> = _events.asSharedFlow()

    /** 最近一次致命错误（`isRetryable=false` 或重连耗尽） */
    private val _fatalError = MutableStateFlow<ApiError?>(null)
    val fatalError: StateFlow<ApiError?> = _fatalError.asStateFlow()

    // ═══════════════════════════════════════════════════════
    // 内部状态
    // ═══════════════════════════════════════════════════════

    private var connectJob: Job? = null






    private val lastEventId = AtomicLong(-1)


    private var serverRetryMillis: Long? = null

    /** 连续失败次数，用于算退避 */
    private var consecutiveFailures = 0

    // ═══════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════

    /**
     * 开始（或重连）监听。
     *
     * 幂等：已在连接中时重复调用不产生第二条连接。
     */
    fun connect(authToken: String?) {
        if (connectJob?.isActive == true) {
            Timber.d("SSE 已在连接中，忽略重复 connect")
            return
        }
        _fatalError.value = null
        consecutiveFailures = 0
        connectJob = scope.launch { runLoop(authToken) }
    }







    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        _state.value = SseConnectionState.Idle
    }

    // ═══════════════════════════════════════════════════════
    // 主循环
    // ═══════════════════════════════════════════════════════

    private suspend fun runLoop(authToken: String?) {
        while (scope.isActive) {
            _state.value = if (consecutiveFailures == 0) {
                SseConnectionState.Connecting
            } else {
                SseConnectionState.Reconnecting
            }

            val outcome = try {
                readOnce(authToken)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Outcome.Crashed(ApiResultMapper.classifyThrowable(t))
            }

            when (outcome) {
                // ── 正常结束（服务端关了连接）────────────────
                //   服务端关流通常是部署重启或代理超时。
                //   这类**应该快速重连**，不要退避太久 —— 用户等着看房间呢。
                is Outcome.Closed -> {
                    Timber.i("SSE 连接被服务端关闭（%s），2s 后重连", outcome.reason)
                    consecutiveFailures = 0
                    _state.value = SseConnectionState.Reconnecting
                    delay(RECONNECT_AFTER_SERVER_CLOSE_MS)
                }

                // ── 网络错误 ──────────────────────────────
                is Outcome.Failed -> {
                    consecutiveFailures++
                    Timber.w("SSE 第 %d 次失败：%s", consecutiveFailures, outcome.error.debugMessage)

                    // 401：token 失效。重连一万次也没用。
                    if (outcome.error is ApiError.Unauthorized) {
                        _fatalError.value = outcome.error
                        _state.value = SseConnectionState.Idle
                        return
                    }

                    if (consecutiveFailures >= MAX_AUTO_RETRIES) {
                        Timber.e("SSE 连续失败 %d 次，停止自动重连（等用户手动重连）", consecutiveFailures)
                        _fatalError.value = outcome.error
                        _state.value = SseConnectionState.Idle
                        return
                    }

                    val backoff = computeBackoff(consecutiveFailures)
                    Timber.i("SSE %.1fs 后重连", backoff / 1000.0)
                    _state.value = SseConnectionState.Reconnecting
                    delay(backoff)
                }

                // ── 解析/编码等非 IO 异常 ──────────────────

                //     但也不该直接停止 —— 可能只是某一帧坏了。

                //     避免一次偶发的坏帧让用户永久失去推送。
                is Outcome.Crashed -> {
                    consecutiveFailures++
                    Timber.w(
                        "SSE 读取崩溃（第 %d 次，非 IO）：%s",
                        consecutiveFailures,
                        outcome.error.debugMessage,
                    )
                    _state.value = SseConnectionState.Reconnecting
                    delay(computeBackoff(consecutiveFailures))
                }
            }
        }
    }









    private fun computeBackoff(attempt: Int): Long {
        serverRetryMillis?.let { return it }
        val base = BASE_BACKOFF_MS shl (attempt - 1).coerceIn(0, 5) // 1,2,4,8,16,32
        val capped = base.coerceAtMost(MAX_BACKOFF_MS)
        // 抖动：base * [0.8, 1.2]
        val jitter = (capped * (Math.random() * 0.4 - 0.2)).toLong()
        return (capped + jitter).coerceAtLeast(500L)
    }

    // ═══════════════════════════════════════════════════════
    // 单次连接
    // ═══════════════════════════════════════════════════════

    private sealed interface Outcome {
        data class Closed(val reason: String) : Outcome
        data class Failed(val error: ApiError) : Outcome
        data class Crashed(val error: ApiError) : Outcome
    }

    private suspend fun readOnce(authToken: String?): Outcome = withContext(Dispatchers.IO) {
        val request = buildRequest(authToken)

        val response: Response = try {

            //   execute 的阻塞是可控的（配合 call.cancel 在取消时打断）。
            okHttpClient.newCall(request).execute()
        } catch (c: CancellationException) {
            throw c
        } catch (e: IOException) {
            return@withContext Outcome.Failed(ApiResultMapper.classifyThrowable(e))
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body.string()
                return@withContext Outcome.Failed(
                    ApiResultMapper.classifyHttp(resp.code, body),
                )
            }

            val contentType = resp.header("Content-Type").orEmpty()

            //   代理/网关出错时会返回 200 + HTML 错误页。

            //   因为它不会有空行结束，最后以心跳超时告终（看起来像网络卡）。
            if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                return@withContext Outcome.Failed(
                    ApiError.Serialization(
                        "SSE 响应的 Content-Type 不是 text/event-stream，实际是：${contentType.ifEmpty { "(缺失)" }}",
                    ),
                )
            }

            _state.value = SseConnectionState.Connected
            consecutiveFailures = 0
            Timber.i("SSE 已连接：%s", request.url)

            readStream(resp.body)
        }
    }

    private fun buildRequest(authToken: String?): Request {
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + EVENTS_PATH)
            .header("Accept", "text/event-stream")

            .header("Cache-Control", "no-cache")
            .header("Connection", "keep-alive")
            .get()

        authToken?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }


        //   服务端据此补发断线期间的事件。没有它 = 消息永久丢失。
        val lastId = lastEventId.get()
        if (lastId >= 0) {
            builder.header("Last-Event-ID", lastId.toString())
            Timber.d("SSE 重连带 Last-Event-ID=%d", lastId)
        }

        return builder.build()
    }









    private suspend fun readStream(body: ResponseBody?): Outcome {
        if (body == null) return Outcome.Failed(ApiError.Serialization("SSE 响应体为空"))

        val source = body.source()
        val parser = SseFrameParser()

        while (true) {

            //   半死的 TCP（比如移动网络切换）不会抛异常，只会永远阻塞。
            //   没有这个超时，客户端会挂在"已连接"状态直到天荒地老。
            val line = withTimeoutOrNull(HEARTBEAT_TIMEOUT_MS) {
                source.readUtf8Line()
            } ?: return Outcome.Closed("心跳超时 ${HEARTBEAT_TIMEOUT_MS / 1000}s，无任何数据")

            // EOF
            if (line.isEmpty() && source.exhausted()) {
                return Outcome.Closed("EOF")
            }

            val event = try {
                parser.feed(line)
            } catch (t: Throwable) {
                // 单帧解析异常不能断流
                Timber.w(t, "SSE 帧解析异常，跳过该行：%s", line)
                null
            }

            parser.serverRetryMillis?.let { serverRetryMillis = it }

            if (event != null) {

                if (event !is SseEvent.Unknown || event.eventName != null) {
                    event.eventId?.toLongOrNull()?.let { lastEventId.set(it) }
                }
                // 用 tryEmit 而不是 emit：emit 会挂起等待订阅者，

                _events.tryEmit(event)
            }
        }
    }

    companion object {




        const val EVENTS_PATH = "events"

        private const val BASE_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val RECONNECT_AFTER_SERVER_CLOSE_MS = 2_000L

        /** 连续失败这么多次后不再自动重连 */
        private const val MAX_AUTO_RETRIES = 6








        private const val HEARTBEAT_TIMEOUT_MS = 45_000L
    }
}
