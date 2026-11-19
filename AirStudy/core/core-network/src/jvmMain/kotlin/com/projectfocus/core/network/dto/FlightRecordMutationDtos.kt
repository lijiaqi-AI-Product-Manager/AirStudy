package com.projectfocus.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FlightRecordMode(val path: String) {
    @SerialName("down") DOWN("down"),
    @SerialName("up") UP("up");
}

@Serializable
data class FlightRecordScenePatch(
    val focusIconType: Int,
    val focusScenario: String,
) {
    fun normalized(): FlightRecordScenePatch = copy(focusIconType = focusIconType.takeIf { it > 0 } ?: 1)
}

data class FlightRecordMutationOptions(val operationId: String? = null, val expectedRevision: Long? = null) {
    init {
        require(operationId == null || operationId.isNotBlank() && operationId.length <= 128 && operationId.none(Char::isISOControl))
        require(expectedRevision == null || expectedRevision >= 0)
    }
}
