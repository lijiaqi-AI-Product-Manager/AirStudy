package com.projectfocus.feature.stats

import android.content.Context
import android.content.res.Configuration
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import java.util.Locale

internal class StatsSceneLabels(private val context: Context) {
    private val resources = linkedMapOf(
        "study" to R.string.stats_scene_label_study, "work" to R.string.stats_scene_label_work,
        "reading" to R.string.stats_scene_label_reading, "meditation" to R.string.stats_scene_label_meditation,
        "creative" to R.string.stats_scene_label_creative, "relax" to R.string.stats_scene_label_relax,
        "exercise" to R.string.stats_scene_label_exercise, "music" to R.string.stats_scene_label_music,
        "programming" to R.string.stats_scene_label_programming, "gaming" to R.string.stats_scene_label_gaming,
        "starchasing" to R.string.stats_scene_label_starchasing,
    )
    private val aliases = buildMap<String, Int> {
        resources.forEach { (key, value) -> put(key, value) }
        for (language in listOf("zh-CN", "zh-TW", "en", "ja", "ko", "ru")) {
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(configuration)
            resources.values.forEach { id -> putIfAbsent(localized.getString(id).trim { it <= ' ' }.lowercase(Locale.ROOT), id) }
        }
    }
    fun label(key: String?, category: String? = null): String {
        val name = key.orEmpty().trim { it <= ' ' }
        if (name.isEmpty()) return name
        resources[name]?.let { return context.getString(it) }
        val translated = aliases[name.lowercase(Locale.ROOT)] ?: return name
        val categoryLabel = resources[category]
        return if (categoryLabel == null || categoryLabel == translated) context.getString(translated) else name
    }
    fun color(category: String) = SourceSceneCatalog.builtins.firstOrNull { it.iconKey == category }?.color
        ?: SourceSceneCatalog.builtins.first().color
    fun icon(category: String) = SourceSceneCatalog.icon(category)
    fun trendLabel(key: String, category: String): String =
        (resources[category] ?: resources[key])?.let(context::getString) ?: key.ifEmpty { category }
    fun category(state: StatsUiState): String =
        (state.periodScenes + state.allTimeScenes).firstOrNull { it.key == state.selectedSceneKey }?.iconKey
            ?: state.selectedSceneKey.takeIf { it in resources } ?: "study"
}
