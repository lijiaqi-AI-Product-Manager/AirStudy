package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import com.projectfocus.server.config.ServerConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** Real module/Netty/JDK HTTP; configured .test URLs are metadata, never fetched by the server. */
class AuthPromoImageRoutesTest {
    @Test fun anonymousMetadataPreservesTheConfiguredUrlAndNeverGrantsLoginOrMembership() {
        val configured = "https://images.projectfocus.test/daily.jpg?variant=night&size=1200"
        RunningServer { configured }.use { server ->
            val response = server.get()
            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store")
            val root = Json.parseToJsonElement(response.body()).jsonObject
            assertThat(root["success"]!!.jsonPrimitive.booleanOrNull).isTrue()
            assertThat(root["data"]!!.jsonObject["url"]!!.jsonPrimitive.content).isEqualTo(configured)
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty()
            assertThat(response.body()).doesNotContain("token")
            assertThat(response.body()).doesNotContain("isPremium")
        }
    }

    @Test fun unavailableInvalidAndThrowingProvidersReturnFailureWithoutLeakingConfiguration() {
        val value = AtomicReference<String?>(null)
        RunningServer { if (value.get() == "throw") throw IllegalStateException("provider-internal-detail") else value.get() }.use { server ->
            for (candidate in listOf(null, "", "  ", "relative.jpg", "file:///private/photo.jpg", "https://user:secret@private.test/a.jpg", "throw")) {
                value.set(candidate)
                val response = server.get()
                assertThat(response.statusCode()).isEqualTo(503)
                val body = Json.parseToJsonElement(response.body()).jsonObject
                assertThat(body["success"]!!.jsonPrimitive.booleanOrNull).isFalse()
                assertThat(body["data"]).isNull()
                assertThat(response.body()).doesNotContain("secret")
                assertThat(response.body()).doesNotContain("provider-internal-detail")
            }
        }
    }

    @Test fun metadataReadsTheCurrentOwnedProviderRatherThanFreezingTheFirstUrl() {
        val value = AtomicReference("https://images.projectfocus.test/one.png")
        RunningServer { value.get() }.use { server ->
            assertThat(server.get().body()).contains("one.png")
            value.set("  https://images.projectfocus.test/two.jpg  ")
            val second = Json.parseToJsonElement(server.get().body()).jsonObject
            assertThat(second["data"]!!.jsonObject["url"]!!.jsonPrimitive.content).isEqualTo("https://images.projectfocus.test/two.jpg")
        }
    }

    private class RunningServer(provider: () -> String?) : AutoCloseable {
        private val port = ServerSocket(0).use { it.localPort }
        private val config = ServerConfig.fromEnv { key -> when (key) {
            "PF_ENV" -> "dev"
            "PF_DB_URL" -> "jdbc:h2:mem:promo_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            "PF_DB_USER" -> "sa"; "PF_DB_PASSWORD" -> ""
            "PF_JWT_SECRET" -> "promo-test-only-secret-at-least-32-bytes"
            "PF_RL_GENERAL", "PF_RL_JOIN", "PF_RL_CHAT" -> "100000"
            else -> null
        } }
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val engine = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            module(config, authPromoImageUrl = provider)
        }.start(wait = false)
        fun get(): HttpResponse<String> = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/auth/promo-image"))
            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
        override fun close() { engine.stop(0, 5_000) }
    }
}
