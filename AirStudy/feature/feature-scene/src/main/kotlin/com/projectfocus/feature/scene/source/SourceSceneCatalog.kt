package com.projectfocus.feature.scene.source

import android.content.Context
import com.projectfocus.feature.scene.R


object SourceSceneCatalog {
    data class Builtin(
        val iconKey: String,
        val labelRes: Int,
        val iconRes: Int,
        val color: Int,
        val type: Int,
    ) {
        fun record() = SourceSceneRecord(name = "", iconKey = iconKey, color = color)
    }

    val builtins: List<Builtin> = listOf(
        Builtin("study", R.string.pf_source_scene_label_study, R.drawable.pf_source_scene_ic_study, 0xFF3CC612.toInt(), 1),
        Builtin("work", R.string.pf_source_scene_label_work, R.drawable.pf_source_scene_ic_work, 0xFF2B71C2.toInt(), 2),
        Builtin("reading", R.string.pf_source_scene_label_reading, R.drawable.pf_source_scene_ic_reading, 0xFFFDAA0F.toInt(), 3),
        Builtin("meditation", R.string.pf_source_scene_label_meditation, R.drawable.pf_source_scene_ic_meditation, 0xFFA827BE.toInt(), 4),
        Builtin("creative", R.string.pf_source_scene_label_creative, R.drawable.pf_source_scene_ic_creative, 0xFFE73F77.toInt(), 5),
        Builtin("relax", R.string.pf_source_scene_label_relax, R.drawable.pf_source_scene_ic_relax, 0xFF5EB0BA.toInt(), 6),
        Builtin("exercise", R.string.pf_source_scene_label_exercise, R.drawable.pf_source_scene_ic_exercise, 0xFFD87E63.toInt(), 7),
        Builtin("music", R.string.pf_source_scene_label_music, R.drawable.pf_source_scene_ic_music, 0xFFC8C803.toInt(), 8),
        Builtin("programming", R.string.pf_source_scene_label_programming, R.drawable.pf_source_scene_ic_programming, 0xFF3B82F6.toInt(), 9),
        Builtin("gaming", R.string.pf_source_scene_label_gaming, R.drawable.pf_source_scene_ic_gaming, 0xFF8B5CF6.toInt(), 10),
        Builtin("starchasing", R.string.pf_source_scene_label_starchasing, R.drawable.pf_source_scene_ic_starchasing, 0xFFEC4899.toInt(), 11),
    )

    fun initialEnabledRecords(): List<SourceSceneRecord> = builtins.take(2).map { it.record() }

    fun builtinSnapshot(scope: SourceSceneScope): SourceSceneSnapshot =
        SourceSceneOptionIds.snapshot(scope, SourceSceneCollection.BUILT_IN, builtins.map { it.record() })

    fun type(iconKey: String): Int = builtins.firstOrNull { it.iconKey == iconKey }?.type ?: 0

    fun icon(iconKey: String): Int =
        builtins.firstOrNull { it.iconKey == iconKey }?.iconRes ?: R.drawable.pf_source_scene_ic_study

    /** JSON/string-backed source objects resolve a builtin resource only when !isCustom. */
    fun label(context: Context, record: SourceSceneRecord): String {
        val builtin = if (record.isCustom) null else builtins.firstOrNull { it.iconKey == record.iconKey }
        return if (builtin == null) record.name else context.getString(builtin.labelRes)
    }

    fun present(context: Context, option: SourceSceneOption): SourceScenePresentation = SourceScenePresentation(
        optionId = option.optionId,
        iconKey = option.record.iconKey,
        title = label(context, option.record),
        type = type(option.record.iconKey),
        iconRes = icon(option.record.iconKey),
        color = option.record.color,
    )
}

/** App can adapt this to core-ui ScenePickerOption and booking SeatSceneChoice. */
data class SourceScenePresentation(
    val optionId: String,
    val iconKey: String,
    val title: String,
    val type: Int,
    val iconRes: Int,
    val color: Int,
)
