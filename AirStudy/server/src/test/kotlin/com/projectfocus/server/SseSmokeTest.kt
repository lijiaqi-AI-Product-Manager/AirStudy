package com.projectfocus.server

import com.projectfocus.server.config.ServerConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test














class SseSmokeTest {

    @Test
    fun `真实 Netty 下 SSE 能收到快照帧`() {
        val port = 18080 + (System.nanoTime() % 1000).toInt()
        val cfg = ServerConfig.fromEnv { k ->
            when (k) {
                "PF_ENV" -> "dev"
                "PF_DB_URL" -> "jdbc:h2:mem:pf_smoke$port;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
                "PF_DB_USER" -> "sa"
                "PF_JWT_SECRET" -> "smoke-secret-at-least-32-bytes-long-for-hs256"
                "PF_PORT" -> port.toString()
                "PF_HOST" -> "127.0.0.1"
                "PF_RL_GENERAL" -> "100000"
                "PF_RL_JOIN" -> "100000"
                "PF_RL_CHAT" -> "100000"
                else -> null
            }
        }
        val mailbox = FixtureRegistrationMailbox()
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            module(cfg, registrationEmailDelivery = mailbox)
        }.start(wait = false)
        try {
            val base = "http://127.0.0.1:$port"
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

            fun post(path: String, body: String, token: String? = null): String {
                val b = HttpRequest.newBuilder(URI("$base$path"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                token?.let { b.header("Authorization", "Bearer $it") }
                return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body()
            }

            val login = registerAndLoginOverHttp(http, base, mailbox, email = "smoke@pf.dev", nickname = "冒烟")
            val token = login["token"]!!.jsonPrimitive.content

            post(
                "/room/create",
                """{"durationMinutes":45,"chatEnabledDuringSession":true,"private":true,
                   "departureAirportCode":"PEK","destinationAirportCode":"SHA"}""",
                token,
            )

            val sseReq = HttpRequest.newBuilder(URI("$base/events"))
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .GET().build()

            val resp = http.send(sseReq, HttpResponse.BodyHandlers.ofLines())
            check(resp.statusCode() == 200) { "SSE 状态码 = ${resp.statusCode()}" }
            check(resp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
                "Content-Type = ${resp.headers().firstValue("Content-Type").orElse("(缺失)")}"
            }

            val firstLines = resp.body().iterator()
            val collected = mutableListOf<String>()
            var guard = 0
            while (firstLines.hasNext() && guard++ < 10) {
                val line = firstLines.next()
                collected += line
                if (line.isEmpty()) break
            }
            val joined = collected.joinToString("\n")
            println("SMOKE-SSE >>>\n$joined\n<<<")
            check(joined.contains("event: room_snapshot")) { "首帧不是 room_snapshot：$joined" }
            check(joined.contains("\"roomId\"")) { "快照里没有 roomId：$joined" }


            // 先查自己的房间 id
            val myRoom = Json.parseToJsonElement(
                http.send(
                    HttpRequest.newBuilder(URI("$base/my-room"))
                        .header("Authorization", "Bearer $token").GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body(),
            ).jsonObject["data"]!!.jsonObject
            val roomId = myRoom["id"]!!.jsonPrimitive.content
            val invite = myRoom["inviteCode"]!!.jsonPrimitive.content

            // 第二个用户加入并发消息
            val login2 = registerAndLoginOverHttp(http, base, mailbox, email = "smoke2@pf.dev", nickname = "乘客")
            val token2 = login2["token"]!!.jsonPrimitive.content
            post("/join", """{"inviteCode":"$invite"}""", token2)


            val it2 = firstLines
            var got = false
            var guard2 = 0
            // 边读边发：先发消息再继续读，避免"先发后读"错过帧的时序问题
            post("/chat", """{"roomId":"$roomId","message":"起飞了"}""", token2)
            val buf = StringBuilder()
            while (it2.hasNext() && guard2++ < 60) {
                val l = it2.next()
                buf.append(l).append('\n')
                if (l == "event: chat_message") { got = true }
                if (got && l.isEmpty()) break
            }
            println("SMOKE-SSE-2 >>>\n$buf\n<<<")
            check(buf.contains("event: participant_joined")) { "没收到 participant_joined：$buf" }
            check(buf.contains("event: chat_message")) { "没收到 chat_message：$buf" }
            check(buf.contains("起飞了")) { "消息内容缺失：$buf" }
        } finally {
            server.stop(200, 500)
        }
    }
}
