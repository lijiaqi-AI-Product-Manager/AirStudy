package com.projectfocus.feature.record.detail








































data class ReplayIntent(
    /** 目的地 IATA 三字码；`null` = 自由模式（不选目的地） */
    val destinationCode: String?,
    /** 时长（分钟），已 clamp 到 [MIN_MINUTES]..[MAX_MINUTES] */
    val durationMinutes: Int,
    /** 分类 id；`null` = 沿用当前默认分类 */
    val categoryId: Int?,


    val departureCode: String?,
) {
    companion object {







        const val MIN_MINUTES: Int = 1







        const val MAX_MINUTES: Int = 12 * 60
    }
}

























object ReplayIntentBuilder {

    /**
     * @param detail 详情页展示模型（提供三字码）
     * @param actualFocusMinutes 实际专注分钟数（原始值）；`null` = 数据缺失
     * @param plannedMinutes 计划分钟数（原始值），用于 `actualFocusMinutes` 缺失时兜底
     * @param categoryId 原记录的分类 id；`null` = 沿用默认
     */
    fun build(
        detail: RecordDetail,
        actualFocusMinutes: Int?,
        plannedMinutes: Int?,
        categoryId: Int?,
    ): ReplayIntent {

        //   用户点"重飞这趟"，想重复的是**他真正做到的那个时长**，
        //   而不是当初计划的。计划 45 分钟、实际只专注了 30 分钟的人，
        //   重飞时想要的是 30（"上次做到 30，这次也做 30"），
        //   而不是 45（"上次没做到，这次还是做不到"）。

        //     再次中途退出，形成"永远完不成"的挫败循环。
        val raw = actualFocusMinutes
            ?: plannedMinutes
            ?: detail.completionPercent?.let { 0 }   // 都没有 → 0，会被 clamp 到下限
            ?: 0

        return ReplayIntent(
            destinationCode = detail.toCode.takeIf { it.isNotBlank() && it != UNKNOWN_CODE },
            durationMinutes = raw.coerceIn(ReplayIntent.MIN_MINUTES, ReplayIntent.MAX_MINUTES),
            categoryId = categoryId,
            departureCode = detail.fromCode.takeIf { it.isNotBlank() && it != UNKNOWN_CODE },
        )
    }


















    const val UNKNOWN_CODE: String = "---"
}
