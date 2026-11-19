package com.projectfocus.core.common.nav

import java.io.Serializable


data class SeatSelectionResult @JvmOverloads constructor(
    val seat: String,
    val draftKey: String,
    val recordId: String,
    val scene: SeatSceneResult? = null,
) : Serializable {

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}


/** Optional scene data preserves existing three-field result producers and scope filtering. */
data class SeatSceneResult(
    val optionId: String,
    val iconKey: String?,
    val title: String,
    val type: Int,
) : Serializable {
    private companion object { private const val serialVersionUID: Long = 1L }
}
