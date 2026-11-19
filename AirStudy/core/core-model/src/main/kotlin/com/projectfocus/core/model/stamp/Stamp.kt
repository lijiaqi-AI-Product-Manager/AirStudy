package com.projectfocus.core.model.stamp


data class Stamp(

    val id: String,
    val countryName: String,
    /** ISO 3166-1 alpha-2，如 "JP" */
    val countryCode: String,
    /** 首次抵达时间戳，Unix 毫秒 */
    val firstVisitDate: Long,
    /** 最近一次抵达时间戳，Unix 毫秒 */
    val latestVisitDate: Long,
    /** 累计到访次数 */
    val visitCount: Int,
    /** 在该国累计专注小时数（浮点，允许 0.5 小时） */
    val focusHours: Double,


    val illustrationKey: String?,
    /** 到访过的机场三字码 */
    val airportCodes: List<String>,
    /** 已解锁地标 ID。⚠️ 地标全集见规格书待确认 #7 */
    val unlockedLandmarkIds: List<String>,
) {
    /** 是否已有任何到访（极少数情况下章可能是预置的未解锁态） */
    val isUnlocked: Boolean get() = visitCount > 0

    /** 专注小时展示口径：保留一位小数，整数则不带 .0 */
    fun focusHoursLabel(): String =
        if (focusHours == focusHours.toLong().toDouble()) {
            "${focusHours.toLong()}h"
        } else {
            "%.1fh".format(focusHours)
        }
}
