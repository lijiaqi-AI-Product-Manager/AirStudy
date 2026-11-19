package com.projectfocus.core.network.freeflight

import com.projectfocus.core.network.dto.AirportLocationRequest
import com.projectfocus.core.network.dto.NearbyAirportDto
import com.projectfocus.core.network.dto.ApiEnvelope
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

fun interface AirportLocationGateway {
    suspend fun search(request: AirportLocationRequest): List<NearbyAirportDto>
}

/** Same source POST/radius/limit50/offset0, but always the configured OWN host. */
class HttpAirportLocationGateway(client: OkHttpClient, baseUrl: String) : AirportLocationGateway {
    private val endpoint = (baseUrl.trimEnd('/') + "/").toHttpUrl().resolve("v2/airports/search/location")!!
        .newBuilder().addQueryParameter("limit", "50").addQueryParameter("offset", "0").build()
    private val http = client.newBuilder().apply {
        interceptors().clear(); networkInterceptors().clear()
        authenticator(Authenticator.NONE); cookieJar(CookieJar.NO_COOKIES)
        followRedirects(false); followSslRedirects(false); retryOnConnectionFailure(false); cache(null)
    }.build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = false; encodeDefaults = true }

    override suspend fun search(request: AirportLocationRequest): List<NearbyAirportDto> {
        require(request.latitude.isFinite() && request.latitude in -90.0..90.0 &&
            request.longitude.isFinite() && request.longitude in -180.0..180.0 && request.radius in 1..3200)
        val body = json.encodeToString(AirportLocationRequest.serializer(), request).toRequestBody("application/json".toMediaType())
        val call = http.newCall(Request.Builder().url(endpoint).post(body).header("Cache-Control", "no-store").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            check(it.isSuccessful) { "Airport service returned HTTP ${it.code}" }
                            val envelope = json.decodeFromString(ApiEnvelope.serializer(kotlinx.serialization.builtins.ListSerializer(NearbyAirportDto.serializer())),
                                requireNotNull(it.body).string())
                            check(envelope.success == true && envelope.error == null) { "Airport service did not return candidates" }
                            requireNotNull(envelope.data).also { values ->
                                check(values.size <= 50)
                                values.forEach { airport ->
                                    check(airport.code.isNotBlank() && airport.latitude.isFinite() && airport.latitude in -90.0..90.0 &&
                                        airport.longitude.isFinite() && airport.longitude in -180.0..180.0)
                                }
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }
}
