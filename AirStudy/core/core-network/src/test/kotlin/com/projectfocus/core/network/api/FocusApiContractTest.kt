package com.projectfocus.core.network.api

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.KotlinSerializationConverterFactory
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.BulkSyncRequest
import com.projectfocus.core.network.dto.CreateRoomRequestDto
import com.projectfocus.core.network.dto.FlightHistoryDto
import com.projectfocus.core.network.dto.JoinRoomRequestDto
import com.projectfocus.core.network.dto.UpdateUserRequest
import com.projectfocus.core.network.error.ApiError
import com.projectfocus.core.network.error.ApiException
import com.projectfocus.core.network.error.ApiResultMapper
import com.projectfocus.core.network.error.ApiResultMapper.dataOrNull
import com.projectfocus.core.network.error.ApiResultMapper.requireData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class FocusApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: FocusApi

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val client = OkHttpClient.Builder().build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(
                KotlinSerializationConverterFactory.create(
                    json,
                    "application/json; charset=UTF-8".toMediaType(),
                ),
            )
            .build()
            .create(FocusApi::class.java)

        TokenStore.clear()
    }

    @After
    fun tearDown() {
        server.close()
        TokenStore.clear()
    }

    // ═══════════════════════════════════════════════════════
    // 请求形状：path / method / body
    // ═══════════════════════════════════════════════════════

    @Test
    fun `verifyToken 发 GET auth-verify`() = runTest {
        server.enqueue(jsonResponse("""{"data":{"id":"u1","email":"a@b.c","token":"t"}}"""))

        val envelope = api.verifyToken()
        val user = envelope.requireData()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/auth/verify")
        assertThat(user.id).isEqualTo("u1")
        assertThat(user.token).isEqualTo("t")
    }

    @Test
    fun `updateUser 用 PATCH 而不是竞品的 DELETE`() = runTest {

        //   如果有人改回 DELETE，这条会红。
        server.enqueue(jsonResponse("""{"data":{"id":"u1","username":"新名字"}}"""))

        api.updateUser(UpdateUserRequest(username = "新名字", avatarId = 7)).requireData()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PATCH")
        assertThat(request.url.encodedPath).isEqualTo("/user/update")

        val body = json.parseToJsonElement(request.body!!.utf8()).toString()
        assertThat(body).contains("\"username\":\"新名字\"")
        // null 字段不出现在 JSON 里（explicitNulls=false）
        assertThat(body).doesNotContain("avatarId\":null")
        assertThat(body).contains("\"avatarId\":7")
    }

    @Test
    fun `getSessionHistory 的 page 与 size 拼进 query`() = runTest {
        server.enqueue(jsonResponse("""{"data":{"sessions":[],"total":0,"page":2,"hasMore":false}}"""))

        api.getSessionHistory(page = 2, size = 50).requireData()

        val request = server.takeRequest()
        val url = request.url
        assertThat(url.encodedPath).isEqualTo("/session/history")
        assertThat(url.queryParameter("page")).isEqualTo("2")
        assertThat(url.queryParameter("size")).isEqualTo("50")
    }

    @Test
    fun `deleteSession 的路径参数被正确替换`() = runTest {
        server.enqueue(jsonResponse("""{"data":{}}"""))

        api.deleteSession("fh-123").requireData()

        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/session/fh-123")
    }

    @Test
    fun `syncSessions 的 body 是包裹数组而不是裸数组`() = runTest {

        val record = FlightHistoryDto(
            id = "fh1",
            departureAirportCode = "PEK",
            destinationAirportCode = "SHA",
            startTimeMillis = 1_000L,
            endTimeMillis = 3_700_000L,
            durationMinutes = 45,
            actualFocusMinutes = 45,
            distanceKm = 600.0,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )
        server.enqueue(jsonResponse("""{"data":{"accepted":1,"rejected":0,"serverIds":["s1"]}}"""))

        val result = api.syncSessions(BulkSyncRequest(listOf(record))).requireData()

        val body = server.takeRequest().body!!.utf8()
        assertThat(body).startsWith("{\"sessions\":[")
        assertThat(body).contains("\"departureAirportCode\":\"PEK\"")

        assertThat(body).doesNotContain("isSynced")
        assertThat(body).doesNotContain("departureLatitude")
        assertThat(result.accepted).isEqualTo(1)
    }

    @Test
    fun `createRoom 的 private 字段名是关键字而不是 isPrivate`() = runTest {

        //   必须靠 @SerialName("private") 纠正。服务端只认 "private"。
        server.enqueue(jsonResponse("""{"data":{"id":"r1","inviteCode":"ABC123"}}"""))

        api.createRoom(
            CreateRoomRequestDto(
                durationMinutes = 45,
                chatEnabledDuringSession = true,
                isPrivate = true,
                destinationAirportCode = "SHA",
                departureAirportCode = "PEK",
            ),
        ).requireData()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/room/create")
        val body = request.body!!.utf8()
        assertThat(body).contains("\"private\":true")
        assertThat(body).doesNotContain("\"isPrivate\"")
    }

    @Test
    fun `joinRoom 用 POST join 且带邀请码`() = runTest {
        server.enqueue(jsonResponse("""{"data":{"id":"r1","state":"WAITING"}}"""))

        api.joinRoom(JoinRoomRequestDto(inviteCode = "ABC123", selectedSeat = "3A")).requireData()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/join")
        val body = request.body!!.utf8()
        assertThat(body).contains("\"inviteCode\":\"ABC123\"")
        assertThat(body).contains("\"selectedSeat\":\"3A\"")
    }

    @Test
    fun `scheduleRoom 用 PUT schedule`() = runTest {
        server.enqueue(jsonResponse("""{"data":{"id":"r1","willStartAt":999}}"""))

        api.scheduleRoom(
            com.projectfocus.core.network.dto.UpdateRoomStartTime(roomId = "r1", willStartAt = 999L),
        ).requireData()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.url.encodedPath).isEqualTo("/schedule")
    }

    @Test
    fun `validateInviteCode 的 code 在路径里`() = runTest {
        server.enqueue(jsonResponse("""{"data":{"id":"r1"}}"""))

        api.validateInviteCode("XYZ789").requireData()

        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/code/XYZ789")
    }

    // ═══════════════════════════════════════════════════════
    // 信封与错误
    // ═══════════════════════════════════════════════════════

    @Test
    fun `HTTP 200 但 error 非空时判为业务失败`() = runTest {

        server.enqueue(
            jsonResponse(
                """{"error":{"code":1001,"message":"邀请码已失效","traceId":"tr_9"}}""",
            ),
        )

        val e = runCatching { api.validateInviteCode("BAD").requireData() }.exceptionOrNull()

        assertThat(e).isInstanceOf(ApiException::class.java)
        val error = (e as ApiException).error
        assertThat(error).isInstanceOf(ApiError.Business::class.java)
        assertThat((error as ApiError.Business).serverMessage).isEqualTo("邀请码已失效")
        assertThat(error.code).isEqualTo(1001)
        assertThat(error.traceId).isEqualTo("tr_9")
    }

    @Test
    fun `success=false 也判为失败`() = runTest {
        server.enqueue(jsonResponse("""{"success":false,"message":"房间人数已满"}"""))

        val e = runCatching { api.getRoom("r1").requireData() }.exceptionOrNull()
        val error = (e as ApiException).error as ApiError.Business
        assertThat(error.serverMessage).isEqualTo("房间人数已满")
    }

    @Test
    fun `data 为空时判为失败而不是返回默认对象`() = runTest {

        //   房间"存在"，但 creator 是 null、participants 是空、state 是 "WAITING"。
        //   UI 表现为"进了房间，但所有人都消失了"。
        server.enqueue(jsonResponse("""{"success":true}"""))

        val e = runCatching { api.getRoom("r1").requireData() }.exceptionOrNull()
        assertThat((e as ApiException).error).isInstanceOf(ApiError.Serialization::class.java)
    }

    @Test
    fun `my-room 允许 data 为 null`() = runTest {
        // "不在任何房间"是正常状态，不是错误。
        server.enqueue(jsonResponse("""{"data":null}"""))

        val room = api.getMyRoom().dataOrNull()
        assertThat(room).isNull()
    }

    @Test
    fun `HTTP 401 归为 Unauthorized`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"message":"token expired"}"""))

        val e = runCatching { api.getMe().requireData() }.exceptionOrNull()
        val error = ApiResultMapper.classifyThrowable(e!!)
        assertThat(error).isInstanceOf(ApiError.Unauthorized::class.java)
        assertThat(error.isRetryable).isFalse()
    }

    @Test
    fun `HTTP 503 带 Retry-After 时归为可重试的服务端错误`() = runTest {
        server.enqueue(
            MockResponse(
                code = 503,
                headers = okhttp3.Headers.headersOf("Retry-After", "7"),
                body = """{"message":"维护中"}""",
            ),
        )

        val e = runCatching { api.getMe().requireData() }.exceptionOrNull()
        val error = ApiResultMapper.classifyThrowable(e!!)
        assertThat(error).isInstanceOf(ApiError.ServerError::class.java)
        assertThat(error.isRetryable).isTrue()
    }

    @Test
    fun `坏 JSON 归为 Serialization 而不是 Unknown`() = runTest {
        server.enqueue(jsonResponse("""{"data":{"id":}}"""))

        val e = runCatching { api.getRoom("r1").requireData() }.exceptionOrNull()
        val error = ApiResultMapper.classifyThrowable(e!!)
        assertThat(error).isInstanceOf(ApiError.Serialization::class.java)
    }

    @Test
    fun `空响应体归为 Serialization 而不是静默成功`() = runTest {

        server.enqueue(MockResponse(code = 200, body = ""))

        val e = runCatching { api.getMe().requireData() }.exceptionOrNull()
        assertThat(ApiResultMapper.classifyThrowable(e!!))
            .isInstanceOf(ApiError.Serialization::class.java)
    }

    @Test
    fun `服务端多返回未知字段不影响解析`() = runTest {
        // 服务端先行加字段是常态，客户端不能因此白屏。
        server.enqueue(jsonResponse("""{"data":{"id":"u1","brandNewField":123}}"""))

        val user = api.getMe().requireData()
        assertThat(user.id).isEqualTo("u1")
        // 缺失的字段用默认值
        assertThat(user.xp).isEqualTo(0)
    }

    // ═══════════════════════════════════════════════════════
    // 认证头注入（通过 OkHttp 拦截器）
    // ═══════════════════════════════════════════════════════

    @Test
    fun `TokenStore 有值时自动带 Authorization 头`() = runTest {
        TokenStore.set("jwt-abc")
        val authenticatedApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(
                        com.projectfocus.core.network.di.NetworkCoreTestFactory
                            .authInterceptor(server.url("/").toString()),
                    )
                    .build(),
            )
            .addConverterFactory(
                KotlinSerializationConverterFactory.create(
                    json,
                    "application/json; charset=UTF-8".toMediaType(),
                ),
            )
            .build()
            .create(FocusApi::class.java)

        server.enqueue(jsonResponse("""{"data":{"id":"u1"}}"""))
        authenticatedApi.getMe().requireData()

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer jwt-abc")
    }

    // ── helpers ──────────────────────────────────────────

    private fun jsonResponse(body: String): MockResponse = MockResponse(
        code = 200,
        headers = okhttp3.Headers.headersOf("Content-Type", "application/json; charset=utf-8"),
        body = body,
    )
}
