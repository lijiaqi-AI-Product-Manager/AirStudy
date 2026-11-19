package com.projectfocus.core.network.content

import com.projectfocus.core.network.api.SceneContentApi
import com.projectfocus.core.network.dto.SceneContentCheckRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Singleton
class SceneContentChecker @Inject constructor(private val api: SceneContentApi) {
    /** The form supplies source-trimmed text. Transport/cancellation errors propagate to its owner. */
    suspend fun isAllowed(text: String): Boolean {
        val response = api.checkContent(SceneContentCheckRequest(text))
        if (response.error != null || response.success == false) return false
        val allowed = response.data?.allowed as? JsonPrimitive ?: return false
        // Json may be lenient elsewhere; the original callback accepts Boolean.TRUE only.
        return !allowed.isString && allowed.booleanOrNull == true
    }
}
