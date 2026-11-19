package com.projectfocus.server.domain

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class RegistrationEmail(
    val recipient: String,
    val code: String,
    val validForSeconds: Long,
    val purpose: EmailChallengePurpose = EmailChallengePurpose.REGISTER,
)

/** Own delivery port. An accepted relay request does not prove inbox delivery. */
fun interface RegistrationEmailDelivery {
    fun deliver(message: RegistrationEmail): Boolean
}

/**
 * Project-owned JSON mail-relay protocol. Configured only by the server owner;
 * no original email service, address, static header or credentials are reused.
 * No request is sent at startup. Missing/invalid configuration rejects delivery.
 */
class HttpRegistrationEmailDelivery internal constructor(
    private val endpoint: URI,
    private val token: String?,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
) : RegistrationEmailDelivery {
    override fun deliver(message: RegistrationEmail): Boolean {
        val body = buildJsonObject {
            put("purpose", message.purpose.wireValue)
            put("recipient", message.recipient)
            put("code", message.code)
            put("validForSeconds", message.validForSeconds)
        }.toString()
        return try {
            val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        } catch (_: Exception) { false }
    }

    companion object {
        fun fromEnv(getenv: (String) -> String? = System::getenv): RegistrationEmailDelivery {
            val unavailable = RegistrationEmailDelivery { false }
            return try {
                val raw = getenv("PF_REGISTRATION_MAIL_RELAY_URL")?.trim { it <= '\u0020' }
                    ?.takeIf { it.isNotEmpty() } ?: return unavailable
                val uri = URI(raw)
                val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
                if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
                    !(uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && loopback))) return unavailable
                val token = getenv("PF_REGISTRATION_MAIL_RELAY_TOKEN")?.takeIf { it.isNotEmpty() }
                HttpRegistrationEmailDelivery(uri, token)
            } catch (_: Exception) { unavailable }
        }
    }
}
