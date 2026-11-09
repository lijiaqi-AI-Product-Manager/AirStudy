package com.projectfocus.server.route

import com.projectfocus.core.network.dto.*
import com.projectfocus.server.domain.FlightRecordFailure
import com.projectfocus.server.domain.FlightRecordService
import com.projectfocus.server.plugins.AUTH_JWT
import com.projectfocus.server.plugins.principalOf
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.jetbrains.exposed.sql.Database
import java.net.URLDecoder

fun Application.registerFlightRecordRoutes(database: Database) {
    val service = FlightRecordService(database)
    suspend fun ApplicationCall.reply(block: suspend () -> Unit) {
        response.headers.append("Cache-Control", "private, no-store")
        try {
            block()
        } catch (failure: FlightRecordFailure) {
            respond(failure.status, FlightRecordResponse<Unit>(false, failure.message))
        } catch (_: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, FlightRecordResponse<Unit>(false, "记录请求无效"))
        } catch (_: SerializationException) {
            respond(HttpStatusCode.BadRequest, FlightRecordResponse<Unit>(false, "记录请求无效"))
        }
    }
    fun ApplicationCall.key(): Pair<FlightRecordMode, String> {
        val mode = FlightRecordMode.entries.singleOrNull { it.path == parameters["mode"] }
            ?: throw IllegalArgumentException("Unknown record mode")
        // The client form-encodes this single path segment. Decode it exactly once, including '+'.
        val encodedId = request.uri.substringBefore('?').split('/').getOrNull(4) ?: error("Missing record id")
        return mode to URLDecoder.decode(encodedId, "UTF-8")
    }
    fun ApplicationCall.options(): FlightRecordMutationOptions {
        val header = request.headers["If-Match"]
        val expected = header?.let {
            require(it.matches(Regex("\"[0-9]+\"")))
            requireNotNull(it.substring(1, it.length - 1).toLongOrNull())
        }
        return FlightRecordMutationOptions(request.headers["Idempotency-Key"], expected)
    }
    routing {
        authenticate(AUTH_JWT) {
            route("/v2/flight-records") {
                get {
                    call.reply {
                        val user = call.principalOf().userId
                        val after = call.request.queryParameters["after"]?.let { requireNotNull(it.toLongOrNull()) } ?: 0L
                        val limit = call.request.queryParameters["limit"]?.let { requireNotNull(it.toIntOrNull()) } ?: 50
                        call.respond(FlightRecordResponse(true, data = withContext(Dispatchers.IO) { service.changes(user, after, limit) }))
                    }
                }
                put("{mode}/{id}") {
                    call.reply {
                        val user = call.principalOf().userId; val (mode, id) = call.key(); val options = call.options()
                        val body = call.receive<FlightRecordArchiveUpload>()
                        call.respond(FlightRecordResponse(true, data = withContext(Dispatchers.IO) { service.upload(user, mode, id, body, options) }))
                    }
                }
                patch("{mode}/{id}/focus-scene") {
                    call.reply {
                        val user = call.principalOf().userId; val (mode, id) = call.key(); val options = call.options()
                        val patch = call.receive<FlightRecordScenePatch>()
                        call.respond(FlightRecordResponse(true, data = withContext(Dispatchers.IO) { service.changeScene(user, mode, id, patch, options) }))
                    }
                }
                delete("{mode}/{id}") {
                    call.reply {
                        val user = call.principalOf().userId; val (mode, id) = call.key(); val options = call.options()
                        call.respond(FlightRecordResponse(true, data = withContext(Dispatchers.IO) { service.delete(user, mode, id, options) }))
                    }
                }
            }
        }
    }
}
