package com.projectfocus.core.network.api

import com.projectfocus.core.network.dto.ApiEnvelope
import com.projectfocus.core.network.dto.SceneContentCheckPayload
import com.projectfocus.core.network.dto.SceneContentCheckRequest
import retrofit2.http.Body
import retrofit2.http.POST

/** Own-service envelope adapter for the original scene form's content-check callback. */
interface SceneContentApi {
    @POST("user/content/check")
    suspend fun checkContent(@Body body: SceneContentCheckRequest): ApiEnvelope<SceneContentCheckPayload>
}
