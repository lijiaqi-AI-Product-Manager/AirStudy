package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import com.projectfocus.server.domain.HttpRegistrationEmailDelivery
import com.projectfocus.server.domain.RegistrationEmail
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** Actual local HTTP transport; no external SMTP/relay or real recipient is contacted. */
class RegistrationEmailRelayTest {
    private val message = RegistrationEmail("captain@projectfocus.test", "012345", 600)

    @Test fun missingInvalidAndUnencryptedRemoteConfigurationRejectsDelivery() {
        for (configured in listOf<String?>(null, "", "relative", "file:///mail", "http://mail.projectfocus.test/send", "https://user:secret@mail.projectfocus.test/send")) {
            val delivery = HttpRegistrationEmailDelivery.fromEnv { if (it == "PF_REGISTRATION_MAIL_RELAY_URL") configured else null }
            assertThat(delivery.deliver(message)).isFalse()
        }
    }

    @Test fun localRelayReceivesTheExactOwnedProtocolAndOptionalAuthorization() {
        Relay().use { relay ->
            val delivery = HttpRegistrationEmailDelivery.fromEnv { when (it) {
                "PF_REGISTRATION_MAIL_RELAY_URL" -> relay.url
                "PF_REGISTRATION_MAIL_RELAY_TOKEN" -> "owned-fixture-relay-token"
                else -> null
            } }
            assertThat(delivery.deliver(message)).isTrue()
            val request = relay.requests.single()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.authorization).isEqualTo("Bearer owned-fixture-relay-token")
            assertThat(request.contentType).isEqualTo("application/json; charset=utf-8")
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertThat(body.keys).containsExactly("purpose", "recipient", "code", "validForSeconds")
            assertThat(body["purpose"]!!.jsonPrimitive.content).isEqualTo("register")
            assertThat(body["recipient"]!!.jsonPrimitive.content).isEqualTo(message.recipient)
            assertThat(body["code"]!!.jsonPrimitive.content).isEqualTo("012345")
            assertThat(body["validForSeconds"]!!.jsonPrimitive.content).isEqualTo("600")
        }
    }

    @Test fun redirectsAndRejectedRelayStatusesNeverBecomeAcceptedDelivery() {
        Relay().use { relay ->
            val delivery = HttpRegistrationEmailDelivery.fromEnv { if (it == "PF_REGISTRATION_MAIL_RELAY_URL") relay.url else null }
            for (status in listOf(302, 400, 429, 500)) {
                relay.status.set(status)
                val before = relay.requests.size
                assertThat(delivery.deliver(message)).isFalse()
                assertThat(relay.requests).hasSize(before + 1)
                assertThat(relay.requests.last().path).isEqualTo("/send")
            }
        }
    }

    @Test fun anAcceptedRelayStatusIsOnlyAnAcknowledgementAndDoesNotRequireAResponseBody() {
        Relay().use { relay ->
            val delivery = HttpRegistrationEmailDelivery.fromEnv { if (it == "PF_REGISTRATION_MAIL_RELAY_URL") relay.url else null }
            for (status in listOf(200, 201, 202, 204)) {
                relay.status.set(status)
                assertThat(delivery.deliver(message)).isTrue()
                assertThat(relay.requests.last().authorization).isNull()
            }
        }
    }

    private data class Request(val method: String, val path: String, val authorization: String?, val contentType: String?, val body: String)
    private class Relay : AutoCloseable {
        val requests = ConcurrentLinkedQueue<Request>()
        val status = AtomicInteger(202)
        private val pool = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { exchange ->
                exchange.use {
                    requests += Request(exchange.requestMethod, exchange.requestURI.path,
                        exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("Content-Type"),
                        exchange.requestBody.readBytes().toString(UTF_8))
                    if (status.get() == 302) exchange.responseHeaders.add("Location", "/must-not-follow")
                    exchange.sendResponseHeaders(status.get(), -1)
                }
            }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}/send"
        override fun close() { server.stop(0); pool.shutdownNow() }
    }
}
