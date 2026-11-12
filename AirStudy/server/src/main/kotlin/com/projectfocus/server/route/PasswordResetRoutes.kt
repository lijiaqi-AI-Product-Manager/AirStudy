package com.projectfocus.server.route

import com.projectfocus.core.network.dto.ApiEnvelope
import com.projectfocus.server.domain.PasswordResetService
import com.projectfocus.server.domain.RegistrationEmailDelivery
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database

/** Own anonymous routes/envelope; original backend address/envelope/policies are unproved. */
fun Application.registerPasswordResetRoutes(database: Database, delivery: RegistrationEmailDelivery,
                                           secret: String, clock: Clock) {
    val service = PasswordResetService(database, delivery, secret, clock)
    routing {
        route("/auth/password-reset") {
            post("code") {
                val body = call.receive<PasswordResetCodeRequest>()
                withContext(Dispatchers.IO) { service.sendCode(body.email) }
                call.respond(ApiEnvelope<Unit>(success = true))
            }
            post {
                val body = call.receive<PasswordResetRequest>()
                withContext(Dispatchers.IO) { service.reset(body.email, body.code, body.newPassword) }
                // Source accepts200 and finishes, never signs in or sets an Activity result.
                call.respond(ApiEnvelope<Unit>(success = true))
            }
        }
    }
}

@Serializable
data class PasswordResetCodeRequest(@SerialName("email") val email: String = "")

@Serializable
data class PasswordResetRequest(
    @SerialName("email") val email: String = "",
    @SerialName("code") val code: String = "",
    @SerialName("new_password") val newPassword: String = "",
)
