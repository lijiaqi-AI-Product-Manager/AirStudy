package com.projectfocus.server.route

import com.projectfocus.core.network.dto.ApiEnvelope
import com.projectfocus.core.network.dto.ApiErrorDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Own public image metadata adapter. No original image service or credentials are used. */
fun Application.registerAuthPromoImageRoutes(imageUrl: () -> String? = { System.getenv("AIRSTUDY_AUTH_PROMO_URL") }) {
    routing {
        get("/auth/promo-image") {
            call.response.headers.append("Cache-Control", "no-store")
            val url = try {
                imageUrl()?.trim { it <= '\u0020' }?.takeIf { value ->
                    val uri = URI(value)
                    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                        !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
                }
            } catch (_: Exception) { null }
            if (url == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiEnvelope<JsonObject>(success = false,
                    error = ApiErrorDto(code = 5032, message = "Promo image is unavailable")))
            } else {
                // Fetching/Calendar-day bitmap caching happens on the client.
                // This configured provider policy is not an original backend claim.
                call.respond(ApiEnvelope(data = buildJsonObject { put("url", url) }, success = true))
            }
        }
    }
}
