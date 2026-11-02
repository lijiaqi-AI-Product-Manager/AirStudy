package com.projectfocus.core.model.flight

import kotlinx.serialization.Serializable

/** An independent scene snapshot; an icon key is not a focus-category primary key. */
@Serializable
data class PlannedScene(
    val optionId: String,
    val name: String,
    val iconKey: String,
    val color: Int,
    val isCustom: Boolean,
    val cloudId: Long = -1L,
    val clientUuid: String? = null,
    val displayName: String? = null,
) {
    init {
        require(optionId.isNotBlank()) { "Scene identity is missing" }
    }
}
