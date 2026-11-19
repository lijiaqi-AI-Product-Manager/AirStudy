package com.projectfocus.server.route

import com.projectfocus.core.network.dto.ApiEnvelope
import com.projectfocus.server.domain.SubscriptionStatusService
import com.projectfocus.server.plugins.AUTH_JWT
import com.projectfocus.server.plugins.principalOf
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database

/** This project's JWT/ApiEnvelope endpoint; original envelope/remote policy remain unknown. */
fun Application.registerSubscriptionStatusRoutes(database: Database, clock: Clock = Clock.systemUTC()) {
    val service = SubscriptionStatusService(database, clock)
    routing {
        authenticate(AUTH_JWT) {
            get("/subscription/status") {
                val owner = call.principalOf().userId
                val status = withContext(Dispatchers.IO) { service.status(owner) }
                call.response.headers.append("Cache-Control", "private, no-store")
                // JsonObject preserves the fourth explicit null key even though
                // the project's general serializer uses explicitNulls=false.
                call.respond(ApiEnvelope(data = buildJsonObject {
                    put("isActive", status.isActive)
                    put("isPremium", status.isPremium)
                    put("remainingDays", status.remainingDays)
                    put("expiresAt", status.expiresAt?.let(::JsonPrimitive) ?: JsonNull)
                }, success = true))
            }
        }
    }
}
