package com.projectfocus.server.route

import com.projectfocus.core.network.dto.*
import com.projectfocus.server.config.JwtConfig
import com.projectfocus.server.domain.FreeFlightAdmissionService
import com.projectfocus.server.domain.FreeFlightDevicePolicy
import com.projectfocus.server.security.JwtService
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database

/** Own endpoints: valid membership first; invalid/missing JWT can only use proven device quota. */
fun Application.registerFreeFlightAdmissionRoutes(
    database: Database, jwtConfig: JwtConfig, policy: FreeFlightDevicePolicy,
    clock: Clock = Clock.systemUTC(),
) {
    val service = FreeFlightAdmissionService(database, policy, jwtConfig.secret, clock)
    val jwt = JwtService(jwtConfig)
    fun owner(call: ApplicationCall): String? {
        val header = call.request.headers[HttpHeaders.Authorization] ?: return null
        if (!header.startsWith("Bearer ")) return null
        return jwt.verifyOrNull(header.removePrefix("Bearer "))?.subject
    }
    routing {
        route("/freeflight/admission") {
            post("prepare") {
                val identity = owner(call); val body = call.receive<FreeFlightDeviceRequest>()
                val result = withContext(Dispatchers.IO) { service.prepare(identity, body) }
                call.response.headers.append("Cache-Control", "private, no-store")
                call.respond(ApiEnvelope(data = result, success = true))
            }
            post("formal") {
                val identity = owner(call); val body = call.receive<FreeFlightFormalRequest>()
                val result = withContext(Dispatchers.IO) { service.formal(identity, body) }
                call.response.headers.append("Cache-Control", "private, no-store")
                call.respond(ApiEnvelope(data = result, success = true))
            }
            // POST keeps the device credential out of URLs/access-log query strings.
            post("operation") {
                val identity = owner(call); val body = call.receive<FreeFlightOperationRequest>()
                val result = withContext(Dispatchers.IO) { service.operation(identity, body) }
                call.response.headers.append("Cache-Control", "private, no-store")
                call.respond(ApiEnvelope(data = result, success = true))
            }
            post("local-start-failed") {
                val identity = owner(call); val body = call.receive<FreeFlightLocalFailureRequest>()
                val result = withContext(Dispatchers.IO) { service.localStartFailed(identity, body) }
                call.response.headers.append("Cache-Control", "private, no-store")
                call.respond(ApiEnvelope(data = result, success = true))
            }
        }
    }
}
