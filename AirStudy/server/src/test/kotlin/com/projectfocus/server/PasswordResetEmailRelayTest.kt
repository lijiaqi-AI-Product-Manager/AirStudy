package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import com.projectfocus.server.domain.EmailChallengePurpose
import com.projectfocus.server.domain.HttpRegistrationEmailDelivery
import com.projectfocus.server.domain.RegistrationEmail
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** Actual local relay protocol only; no external email transport is contacted. */
class PasswordResetEmailRelayTest {
    @Test fun resetUsesItsOwnPurposeWhileTheR167ThreeArgumentMessageRemainsRegister() {
        val bodies = ConcurrentLinkedQueue<String>()
        val status = AtomicInteger(202)
        val relayThreads = Executors.newCachedThreadPool()
        val relay = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = relayThreads
            createContext("/send") { exchange ->
                exchange.use {
                    assertThat(exchange.requestMethod).isEqualTo("POST")
                    assertThat(exchange.requestHeaders.getFirst("Authorization")).isNull()
                    bodies += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    exchange.sendResponseHeaders(status.get(), -1)
                }
            }
            start()
        }
        try {
            val delivery = HttpRegistrationEmailDelivery.fromEnv { key ->
                if (key == "PF_REGISTRATION_MAIL_RELAY_URL") "http://127.0.0.1:${relay.address.port}/send" else null
            }
            val register = RegistrationEmail("fixture@pf.test", "012345", 600)
            val reset = register.copy(purpose = EmailChallengePurpose.PASSWORD_RESET)
            assertThat(delivery.deliver(register)).isTrue()
            assertThat(delivery.deliver(reset)).isTrue()
            val messages = bodies.map { Json.parseToJsonElement(it).jsonObject }
            assertThat(messages[0]["purpose"]!!.jsonPrimitive.content).isEqualTo("register")
            assertThat(messages[1]["purpose"]!!.jsonPrimitive.content).isEqualTo("password_reset")
            for (message in messages) {
                assertThat(message.keys).containsExactly("purpose", "recipient", "code", "validForSeconds")
                assertThat(message["recipient"]!!.jsonPrimitive.content).isEqualTo(register.recipient)
                assertThat(message["code"]!!.jsonPrimitive.content).isEqualTo(register.code)
                assertThat(message["validForSeconds"]!!.jsonPrimitive.content).isEqualTo("600")
            }
            status.set(503)
            assertThat(delivery.deliver(reset)).isFalse()
            assertThat(bodies).hasSize(3)
        } finally { relay.stop(0); relayThreads.shutdownNow() }
    }
}
