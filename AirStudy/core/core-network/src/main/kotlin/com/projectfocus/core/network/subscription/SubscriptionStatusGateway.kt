package com.projectfocus.core.network.subscription

import com.projectfocus.core.network.dto.SubscriptionStatusSnapshot
import java.io.IOException

interface SubscriptionStatusGateway {
    /** null is an explicit 2xx rejection/missing payload, never a fabricated premium. */
    suspend fun fetch(): SubscriptionStatusSnapshot?
}

open class SubscriptionStatusException(message: String, cause: Throwable? = null) : IOException(message, cause)
class SubscriptionStatusAuthenticationException : SubscriptionStatusException("Subscription authentication is required")
class SubscriptionStatusIdentityChangedException : SubscriptionStatusException("Subscription account changed during the request")
class SubscriptionStatusHttpException(val statusCode: Int) : SubscriptionStatusException("Subscription HTTP $statusCode")
class SubscriptionStatusNetworkException(cause: IOException) : SubscriptionStatusException("Subscription connection failed", cause)
class SubscriptionStatusTimeoutException(cause: Throwable? = null) : SubscriptionStatusException("Subscription request timed out", cause)
class SubscriptionStatusParseException(cause: Throwable? = null) : SubscriptionStatusException("Could not parse subscription status", cause)
