package com.projectfocus.feature.scene.catalog

import androidx.annotation.StringRes
import com.projectfocus.feature.scene.R


data class FocusScene(
    val id: String,
    val colorHex: String,
    @StringRes val labelRes: Int?,
)

object ScenePalette {

    val all: List<FocusScene> = listOf(
        FocusScene("creative_pink", "#E73F77", R.string.scene_label_creative),
        FocusScene("driving_blue", "#3B82F6", null),
        FocusScene("earth_orange", "#D87E63", null),
        FocusScene("exercise_peach", "#FFAB91", R.string.scene_label_exercise),
        FocusScene("gaming_purple", "#8B5CF6", R.string.scene_label_gaming),
        FocusScene("growth_green", "#3CC612", null),
        FocusScene("lime_yellow", "#C8C803", null),
        FocusScene("meditation_purple", "#A827BE", R.string.scene_label_meditation),
        FocusScene("music_yellow", "#FFF59D", R.string.scene_label_music),
        FocusScene("reading_orange", "#FDAA0F", R.string.scene_label_reading),
        FocusScene("relax_cyan", "#80DEEA", R.string.scene_label_relax),
        FocusScene("social_pink", "#EC4899", null),
        FocusScene("study_green", "#74EB50", R.string.scene_label_study),
        FocusScene("teal_blue", "#5EB0BA", null),
        FocusScene("work_blue", "#2B71C2", R.string.scene_label_work),
    )

    val byId: Map<String, FocusScene> = all.associateBy { it.id }

    /** 0xAARRGGBB；6 位 hex 视作不透明。 */
    fun argb(scene: FocusScene): Int {
        val rgb = scene.colorHex.removePrefix("#").toLong(16)
        return (0xFF000000L or rgb).toInt()
    }
}
