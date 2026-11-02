package com.projectfocus.core.network.content

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.api.FocusApi
import com.projectfocus.core.network.di.KotlinSerializationConverterFactory
import com.projectfocus.core.network.di.TokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit

class SceneAccessResolverTest {
    private lateinit var server: MockWebServer
    private lateinit var resolver: SceneAccessResolver

    @Before
    fun setup() {
        TokenStore.clear()
        server = MockWebServer().apply { start() }
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(KotlinSerializationConverterFactory.create(Json { ignoreUnknownKeys = true }, "application/json".toMediaType()))
            .build().create(FocusApi::class.java)
        resolver = SceneAccessResolver(api)
    }

    @After
    fun teardown() { TokenStore.clear(); server.close() }

    @Test
    fun `an actual anonymous session uses five without fabricating a remote profile`() = runBlocking {
        assertThat(resolver.maximumEnabledScenes()).isEqualTo(5)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `authenticated limits come from the real profile response`() = runBlocking {
        TokenStore.set("own-test-session")
        server.enqueue(MockResponse(body = """{"data":{"id":"own-user","isPremium":false}}"""))
        assertThat(resolver.maximumEnabledScenes()).isEqualTo(5)
        server.enqueue(MockResponse(body = """{"data":{"id":"own-user","isPremium":true}}"""))
        assertThat(resolver.maximumEnabledScenes()).isEqualTo(127)
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/user/me")
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/user/me")
    }

    @Test
    fun `profile failure does not silently grant the larger limit`() = runBlocking {
        TokenStore.set("own-test-session")
        server.enqueue(MockResponse(code = 503))
        var failed = false
        try { resolver.maximumEnabledScenes() } catch (_: HttpException) { failed = true }
        assertThat(failed).isTrue()
    }
}
