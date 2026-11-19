package com.projectfocus.feature.achievement.catalog

import com.projectfocus.core.database.entity.FlightHistoryEntity


data class AchievementStats(
    val completedCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val visitedCityCount: Int = 0,
    val totalFocusMinutes: Int = 0,
    val averageFocusMinutes: Int = 0,
) {
    companion object {
        fun from(rows: List<FlightHistoryEntity>): AchievementStats {
            val completed = rows.filter { it.status == "COMPLETED" }
            val cities = completed
                .mapNotNull { it.destinationCity?.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val totalFocus = completed.sumOf { it.actualFocusMinutes }
            val average = if (completed.isEmpty()) 0 else totalFocus / completed.size
            return AchievementStats(
                completedCount = completed.size,
                totalDistanceKm = completed.sumOf { it.distanceKm },
                visitedCityCount = cities.size,
                totalFocusMinutes = totalFocus,
                averageFocusMinutes = average,
            )
        }
    }
}

object AchievementEvaluator {

    fun isUnlocked(definition: AchievementDefinition, stats: AchievementStats): Boolean =
        when (val rule = definition.rule) {
            is AchievementRule.Completed -> stats.completedCount >= rule.count
            is AchievementRule.DistanceKm -> stats.totalDistanceKm >= rule.km
            is AchievementRule.Cities -> stats.visitedCityCount > rule.count
            is AchievementRule.TotalFocusMinutes -> stats.totalFocusMinutes > rule.minutes
            is AchievementRule.AverageFocusMinutes -> stats.averageFocusMinutes > rule.minutes

            //       否则 0 次飞行的平均值 0 恒小于 X，新用户一打开成就页就"解锁"了它
            //       （AchievementEvaluatorTest.empty_stats_never_unlock_anything 抓到的真 bug）。
            is AchievementRule.AverageFocusMinutesMax ->
                stats.completedCount > 0 && stats.averageFocusMinutes < rule.minutes
            AchievementRule.Unsupported -> false
        }

    fun unlockedIds(stats: AchievementStats): Set<String> =
        AchievementCatalog.all.filter { isUnlocked(it, stats) }.map { it.id }.toSet()

    /** 当前有数据可判定的条目数；其余为 Unsupported。 */
    fun supportedCount(): Int =
        AchievementCatalog.all.count { it.rule != AchievementRule.Unsupported }
}
