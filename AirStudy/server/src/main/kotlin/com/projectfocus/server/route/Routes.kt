package com.projectfocus.server.route

import com.projectfocus.core.network.dto.ApiEnvelope
import com.projectfocus.core.network.dto.SceneContentCheckPayload
import com.projectfocus.core.network.dto.SceneContentCheckRequest
import com.projectfocus.core.network.sse.SseEvent
import com.projectfocus.server.config.ServerConfig
import com.projectfocus.server.db.DatabaseFactory
import com.projectfocus.server.db.Tables.Users
import com.projectfocus.server.domain.AuthService
import com.projectfocus.server.domain.EmailRegistrationService
import com.projectfocus.server.domain.RegistrationEmailDelivery
import com.projectfocus.server.domain.BadRequestException
import com.projectfocus.server.domain.SceneContentChecker
import com.projectfocus.server.domain.DomainException
import com.projectfocus.server.domain.FlightService
import com.projectfocus.server.domain.RoomService
import com.projectfocus.server.domain.UnauthorizedException
import com.projectfocus.server.plugins.AUTH_JWT
import com.projectfocus.server.plugins.principalOf
import com.projectfocus.server.security.JwtService
import com.projectfocus.server.sse.SseHub
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.projectfocus.server.Routes")
















fun Application.registerRoutes(
    database: DatabaseFactory.Handle,
    sseHub: SseHub,
    config: ServerConfig,
    sceneContentChecker: SceneContentChecker,
    registrationEmailDelivery: RegistrationEmailDelivery,
    registrationClock: Clock,
) {
    val jwt = JwtService(config.jwt)
    val auth = AuthService(jwt, database.database)
    val registration = EmailRegistrationService(database.database, auth, registrationEmailDelivery, config.jwt.secret, registrationClock)
    val rooms = RoomService(sseHub)
    val flights = FlightService()

    sseHub.startHeartbeat()

    routing {
        // ═══════════════════════════════════════════════
        // 健康检查（K8s liveness / readiness 探针）
        // ═══════════════════════════════════════════════
        get("/health") {

            //   只返回 {"status":"ok"} 的探针在数据库挂掉时依然 OK，
            //   K8s 就不会重启/摘流量 —— 这是很多线上事故的起点。
            val dbOk = runCatching {
                transaction(database.database) {
                    Users.selectAll().limit(1).count()
                }
            }.isSuccess

            if (!dbOk) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiEnvelope<Unit>(error = com.projectfocus.core.network.dto.ApiErrorDto(5003, "数据库不可用")),
                )
                return@get
            }
            call.respondText(
                """{"status":"ok","db":"ok","sseConnections":${sseHub.connectionCount}}""",
                ContentType.Application.Json,
            )
        }

        // ═══════════════════════════════════════════════
        // 认证（3 + 2）
        // ═══════════════════════════════════════════════
        route("auth") {
            post("register/code") {
                val body = call.receive<RegistrationCodeRequest>()
                withContext(Dispatchers.IO) { registration.sendCode(body.email) }
                call.respond(ApiEnvelope<Unit>(success = true))
            }
            post("register") {
                val body = call.receive<EmailRegistrationRequest>()
                withContext(Dispatchers.IO) { registration.register(body.email, body.code, body.password) }
                // Source register accepts201 only, ignores body and returns to
                // login. Creating an account must not issue an access/refresh pair.
                call.respond(HttpStatusCode.Created, ApiEnvelope<Unit>(success = true))
            }
            post("login") {
                val body = call.receive<RegisterRequest>()
                val result = withContext(Dispatchers.IO) { auth.login(body.email, body.password) }
                call.respond(ApiEnvelope(data = result))
            }
            post("refresh") {
                val body = call.receive<RefreshRequest>()
                call.respond(ApiEnvelope(data = auth.refresh(body.refreshToken)))
            }
            post("logout") {
                val body = call.receive<RefreshRequest>()
                auth.logout(body.refreshToken)
                call.respond(ApiEnvelope<Unit>(data = Unit))
            }
          authenticated {
            get("verify") {
                val me = authenticatedUserId(call)


                //   导致启动时的"静默校验"要拉 1KB 数据。
                val row = transaction(database.database) {
                    Users.selectAll().where { Users.id eq me }.limit(1).firstOrNull()
                } ?: throw UnauthorizedException()
                call.respond(ApiEnvelope(data = com.projectfocus.core.network.dto.AuthUserDto(
                    id = me, email = row[Users.email], token = "",
                )))
            }
          }
        }

        // ═══════════════════════════════════════════════
        // 用户（2）
        // ═══════════════════════════════════════════════
        authenticated {
        route("user") {
            get("me") {
                val me = authenticatedUserId(call)
                call.respond(ApiEnvelope(data = auth.profile(me)))
            }
            patch("update") {
                val me = authenticatedUserId(call)
                val body = call.receive<com.projectfocus.core.network.dto.UpdateUserRequest>()
                call.respond(ApiEnvelope(data = auth.updateProfile(me, body.username, body.avatarId)))
            }
        }
        }

        // Project adapter: read-only local scene validation does not require an account.
        // This does not establish the original service's authentication or moderation policy.
        post("/user/content/check") {
            val body = receiveSceneContentCheckRequest(call)
            val allowed = sceneContentChecker.isAllowed(body.text)
            call.respond(ApiEnvelope(data = SceneContentCheckPayload(allowed = JsonPrimitive(allowed))))
        }

        // ═══════════════════════════════════════════════
        // 飞行记录（3）
        // ═══════════════════════════════════════════════
        authenticated {
        route("session") {
            post("sync") {
                val me = authenticatedUserId(call)
                val body = call.receive<com.projectfocus.core.network.dto.BulkSyncRequest>()
                call.respond(ApiEnvelope(data = flights.sync(me, body)))
            }
            get("history") {
                val me = authenticatedUserId(call)
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
                val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
                call.respond(ApiEnvelope(data = flights.history(me, page, size)))
            }
            delete("{id}") {
                val me = authenticatedUserId(call)
                val id = call.parameters["id"] ?: throw DomainException(3001, "记录不存在", HttpStatusCode.NotFound)
                flights.delete(me, id)
                call.respond(ApiEnvelope<Unit>(data = Unit))
            }
        }
        }

        authenticated {
        post("start") {
            val me = authenticatedUserId(call)
            val body = call.receive<com.projectfocus.core.network.dto.StartSessionRequest>()
            call.respond(ApiEnvelope(data = flights.start(me, body)))
        }
        }

        // ═══════════════════════════════════════════════
        // 房间（9）
        // ═══════════════════════════════════════════════
        authenticated {
        post("room/create") {
            val me = authenticatedUserId(call)
            val body = call.receive<com.projectfocus.core.network.dto.CreateRoomRequestDto>()
            call.respond(ApiEnvelope(data = rooms.create(me, body)))
        }
        }

        get("code/{code}") {

            //   但限流按 IP 拦（见 Security.kt）。
            val code = call.parameters["code"].orEmpty()
            call.respond(ApiEnvelope(data = rooms.getRoomByInviteCode(code)))
        }

        authenticated {
        post("join") {
            val me = authenticatedUserId(call)
            val body = call.receive<com.projectfocus.core.network.dto.JoinRoomRequestDto>()
            call.respond(ApiEnvelope(data = rooms.join(me, body.inviteCode, body.selectedSeat)))
        }
        }

        authenticated {
        post("leave") {
            val me = authenticatedUserId(call)

            //   这在"同时在两个房间"时会有歧义，但它的数据模型就不允许两个房间。

            val room = rooms.myRoom(me) ?: throw DomainException(2009, "你不在任何房间中", HttpStatusCode.Conflict)
            rooms.leave(me, room.id, voluntarily = true)
            call.respond(ApiEnvelope<Unit>(data = Unit))
        }
        }



        authenticated {
        get("room/{id}") {
            val id = call.parameters["id"].orEmpty()
            call.respond(ApiEnvelope(data = rooms.getRoom(id)))
        }
        }

        authenticated {
        get("my-room") {
            val me = authenticatedUserId(call)
            call.respond(ApiEnvelope(data = rooms.myRoom(me)))
        }
        }

        get("public") {
            val limit = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
            call.respond(ApiEnvelope(data = rooms.publicRooms(limit)))
        }

        authenticated {
        put("schedule") {
            val me = authenticatedUserId(call)
            val body = call.receive<com.projectfocus.core.network.dto.UpdateRoomStartTime>()
            call.respond(ApiEnvelope(data = rooms.reschedule(me, body.roomId, body.willStartAt)))
        }
        }

        authenticated {
        post("chat") {
            val me = authenticatedUserId(call)
            val body = call.receive<com.projectfocus.core.network.dto.SendChatRequest>()
            call.respond(ApiEnvelope(data = rooms.sendChat(me, body.roomId, body.message)))
        }
        }

        // ═══════════════════════════════════════════════
        // SSE 长连接
        // ═══════════════════════════════════════════════
        authenticated {
        get("events") {
            val me = authenticatedUserId(call)
            val room = rooms.myRoom(me) ?: throw DomainException(2009, "你不在任何房间中", HttpStatusCode.Conflict)


            call.response.header("X-Accel-Buffering", "no")
            call.response.header("Cache-Control", "no-cache, no-transform")

            call.respondTextWriter(ContentType.Text.EventStream) {
                val conn = sseHub.register(room.id, me)
                try {
                    // ① 连上先推一次全量快照 —— 客户端据此纠正本地状态
                    val snapshot = buildJsonObject {
                        put("roomId", room.id)
                        put("state", room.state)
                        put("inviteCode", room.inviteCode)
                        put("durationMinutes", room.durationMinutes)
                        put("startedAt", room.startedAt)
                        put("willStartAt", room.willStartAt)
                        put("participantCount", room.participants.count { it.leftAt == null })
                    }

                    write("event: room_snapshot\n")
                    write("data: ${snapshot}\n\n")
                    flush()

                    // ② 循环消费发送队列
                    while (true) {
                        // 用超时兜底：即使 hub 的心跳协程挂了，这里也会醒来检查
                        val frame = withTimeoutOrNull(FRAME_WAIT_TIMEOUT_MS) { conn.outgoing.receiveCatching().getOrNull() }
                        if (frame == null) {
                            // ⚠️ 这里**不能**简单 return —— 超时只是"暂时没有消息"，
                            //    连接依然有效。真正的结束信号是 channel 被 close（收到 null）。
                            if (conn.outgoing.isClosedForReceive) break
                            continue
                        }
                        write(frame)
                        flush()
                    }
                } finally {

                    sseHub.unregister(conn)
                }
            }
        }
        }
    }

    log.info("已注册业务端点（含内容检测）+ /health + /events")
}

// ═══════════════════════════════════════════════════════════
// 辅助
// ═══════════════════════════════════════════════════════════

/** 需要登录的端点统一从这里取 userId —— 全局唯一入口 */










private fun io.ktor.server.routing.Route.authenticated(
    build: io.ktor.server.routing.Route.() -> Unit,
) = authenticate(AUTH_JWT) { build() }

private fun authenticatedUserId(call: ApplicationCall): String =
    call.principalOf().userId

/** 用 Bearer token 换 userId（`/events` 的特殊场景：SSE 不能用 Authorization 头时） */
internal fun userIdFromToken(rawHeader: String?, jwt: JwtService): String? {
    val token = rawHeader?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return jwt.verifyOrNull(token)?.subject
}

/**
 * Validate JSON kind explicitly: a String DTO alone must not depend on coercion
 * settings. Malformed/missing/non-string text is a request error, not allowed=false.
 */
private suspend fun receiveSceneContentCheckRequest(call: ApplicationCall): SceneContentCheckRequest {
    val mediaType = call.request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
    if (!mediaType.equals(ContentType.Application.Json.toString(), ignoreCase = true)) {
        throw BadRequestException("请求格式有误")
    }
    val element = try {
        Json.parseToJsonElement(call.receiveText())
    } catch (_: SerializationException) {
        throw BadRequestException("请求格式有误")
    }
    val text = (element as? JsonObject)?.get("text") as? JsonPrimitive
    if (text == null || !text.isString) throw BadRequestException("请求格式有误")
    return SceneContentCheckRequest(text.content)
}

private const val FRAME_WAIT_TIMEOUT_MS = 20_000L

// ═══════════════════════════════════════════════════════════
// 请求体
// ═══════════════════════════════════════════════════════════

@kotlinx.serialization.Serializable
data class RegistrationCodeRequest(@kotlinx.serialization.SerialName("email") val email: String = "")

@kotlinx.serialization.Serializable
data class EmailRegistrationRequest(
    @kotlinx.serialization.SerialName("email") val email: String = "",
    @kotlinx.serialization.SerialName("code") val code: String = "",
    @kotlinx.serialization.SerialName("password") val password: String = "",
)

/** Historical login request type; username is ignored by login, never an OTP bypass. */
@kotlinx.serialization.Serializable
data class RegisterRequest(
    @kotlinx.serialization.SerialName("email") val email: String = "",
    @kotlinx.serialization.SerialName("password") val password: String = "",
    @kotlinx.serialization.SerialName("username") val username: String = "",
)

@kotlinx.serialization.Serializable
data class RefreshRequest(
    @kotlinx.serialization.SerialName("refreshToken") val refreshToken: String = "",
)
