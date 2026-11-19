package com.projectfocus.feature.achievement.catalog

import androidx.annotation.StringRes


data class AchievementDefinition(
    val id: String,
    @StringRes val tagRes: Int,
    @StringRes val descRes: Int,
    val rule: AchievementRule,
)

sealed interface AchievementRule {
    /** 完成飞行次数 >= count。 */
    data class Completed(val count: Int) : AchievementRule

    /** 累计里程 >= km。 */
    data class DistanceKm(val km: Double) : AchievementRule


    data class Cities(val count: Int) : AchievementRule

    /** 累计专注分钟 > minutes。 */
    data class TotalFocusMinutes(val minutes: Int) : AchievementRule

    /** 平均单次飞行分钟 > minutes。 */
    data class AverageFocusMinutes(val minutes: Int) : AchievementRule

    /** 平均单次飞行分钟 < minutes。 */
    data class AverageFocusMinutesMax(val minutes: Int) : AchievementRule

    /** 需要的数据尚未采集；永不判为解锁。 */
    data object Unsupported : AchievementRule
}
