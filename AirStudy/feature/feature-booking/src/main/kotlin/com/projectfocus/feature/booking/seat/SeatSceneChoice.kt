package com.projectfocus.feature.booking.seat

import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import java.io.Serializable

/** A caller-supplied choice. This UI layer owns no scene catalog or booking API. */
data class SeatSceneChoice(
    /** Stable caller/model identity; iconKey is not unique for custom choices. */
    val optionId: String,
    val iconKey: String?,
    val title: String,
    val type: Int,
    @DrawableRes val iconRes: Int = 0,
    @ColorInt val color: Int = 0,
) : Serializable {
    private companion object { private const val serialVersionUID: Long = 1L }
}

/** MainActivity can adapt its real scene catalog without a feature-to-feature dependency. */
interface SeatSceneChoicesHost {
    fun seatSceneChoices(arguments: Bundle): SeatSceneChoices
    fun presetSeatScene(arguments: Bundle): SeatSceneChoice? = null
    fun createSeatSceneEditor(arguments: Bundle): SeatSceneEditorSession? = null
}

/** One picker owns its child dialogs and pending work; closing it releases both. */
interface SeatSceneEditorSession {
    fun add(onChanged: () -> Unit)
    fun remove(optionId: String, onChanged: () -> Unit)
    fun close()
}

/** Ready(emptyList()) is a legitimate user catalog, never a load error/default request. */
sealed interface SeatSceneChoices {
    data class Ready(val values: List<SeatSceneChoice>) : SeatSceneChoices
    data object Loading : SeatSceneChoices
    data object Unavailable : SeatSceneChoices
}
