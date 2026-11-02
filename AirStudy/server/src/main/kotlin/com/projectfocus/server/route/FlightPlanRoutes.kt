package com.projectfocus.server.route

import com.projectfocus.server.domain.FlightPlanService
import com.projectfocus.server.plugins.AUTH_JWT
import com.projectfocus.server.plugins.principalOf
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database

/** Project-owned authenticated endpoint; no original URL, credential or ApiEnvelope. */
fun Application.registerFlightPlanRoutes(database: Database, clock: Clock = Clock.systemUTC()) {
    install(WebSockets) {
        // Own transport memory bound, not an observed source daily leg-count cap.
        maxFrameSize = 16L * 1024L * 1024L
    }
    val service = FlightPlanService(database, clock)
    routing {
        authenticate(AUTH_JWT) {
            webSocket("/flight-plan") {
                val ownerId = call.principalOf().userId
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val response = withContext(Dispatchers.IO) { service.process(ownerId, frame.readText()) }
                    send(Frame.Text(response.toString()))
                }
            }
        }
    }
}
