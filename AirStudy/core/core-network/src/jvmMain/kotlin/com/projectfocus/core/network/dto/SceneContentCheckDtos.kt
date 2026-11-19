package com.projectfocus.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Project server adapter; the original service's HTTP envelope is not established. */
@Serializable
data class SceneContentCheckRequest(
    @SerialName("text") val text: String,
)

/** Preserve JSON kind so even a lenient client cannot accept the string "true". */
@Serializable
data class SceneContentCheckPayload(
    @SerialName("allowed") val allowed: JsonElement? = null,
)
