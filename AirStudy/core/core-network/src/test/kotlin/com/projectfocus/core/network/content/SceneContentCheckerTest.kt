package com.projectfocus.core.network.content

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.projectfocus.core.network.api.SceneContentApi
import com.projectfocus.core.network.di.KotlinSerializationConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit

class SceneContentCheckerTest {
    private lateinit var server: MockWebServer
    private lateinit var checker: SceneContentChecker

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(KotlinSerializationConverterFactory.create(json, "application/json".toMediaType()))
            .build().create(SceneContentApi::class.java)
        checker = SceneContentChecker(api)
    }

    @After
    fun teardown() { server.close() }

    @Test
    fun `real request posts the supplied text to the own content check route`() = runBlocking {
        server.enqueue(MockResponse(body = """{"data":{"allowed":true}}"""))
        assertThat(checker.isAllowed("深度阅读")).isTrue()
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/user/content/check")
        assertThat(Json.parseToJsonElement(request.body!!.utf8()))
            .isEqualTo(Json.parseToJsonElement("""{"text":"深度阅读"}"""))
    }

    @Test
    fun `only a boolean true payload is accepted even with a lenient converter`() = runBlocking {
        for (body in listOf(
            """{"data":{"allowed":false}}""", """{"data":{"allowed":"true"}}""",
            """{"data":{"allowed":1}}""", """{"data":{"allowed":null}}""",
            """{"data":{"allowed":{}}}""", """{"data":{}}""", """{}""",
            """{"data":{"allowed":true},"success":false}""",
            """{"data":{"allowed":true},"error":{"code":4001}}""",
        )) {
            server.enqueue(MockResponse(body = body))
            assertWithMessage("payload %s", body).that(checker.isAllowed("工作")).isFalse()
        }
    }

    @Test
    fun `http failure is not turned into permission to save`() = runBlocking {
        server.enqueue(MockResponse(code = 503, body = "unavailable"))
        var failed = false
        try {
            checker.isAllowed("工作")
        } catch (_: HttpException) {
            failed = true
        }
        assertThat(failed).isTrue()
    }
}
