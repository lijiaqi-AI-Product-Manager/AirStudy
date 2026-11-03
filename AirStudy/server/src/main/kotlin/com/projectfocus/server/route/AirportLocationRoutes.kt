package com.projectfocus.server.route

import com.projectfocus.core.network.dto.AirportLocationRequest
import com.projectfocus.core.network.dto.ApiEnvelope
import com.projectfocus.server.domain.AirportLocationCatalog
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


fun Application.registerAirportLocationRoutes(catalogue: AirportLocationCatalog = AirportLocationCatalog.bundled()) {
    routing {
        post("/v2/airports/search/location") {
            val request = call.receive<AirportLocationRequest>()
            val limitText = call.request.queryParameters["limit"]
            val offsetText = call.request.queryParameters["offset"]
            val limit = if (limitText == null) 50 else limitText.toIntOrNull() ?: -1
            val offset = if (offsetText == null) 0 else offsetText.toIntOrNull() ?: -1
            if (!request.latitude.isFinite() || request.latitude !in -90.0..90.0 ||
                !request.longitude.isFinite() || request.longitude !in -180.0..180.0 ||
                request.radius !in 1..3200 || limit !in 1..50 || offset < 0 || call.request.queryParameters["region"] != null) {
                call.respond(HttpStatusCode.BadRequest); return@post
            }
            val result = withContext(Dispatchers.Default) { catalogue.search(request, limit, offset) }
            call.response.headers.append("Cache-Control", "private, no-store")
            call.respond(ApiEnvelope(data = result, success = true))
        }
    }
}
