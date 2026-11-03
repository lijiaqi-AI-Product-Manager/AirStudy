package com.projectfocus.feature.record.detail

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.feature.record.list.FlightStatus
import com.projectfocus.feature.record.list.RecordListBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale




































object RecordDetailBuilder {

    private val DATE_FMT = DateTimeFormatter.ofPattern("M月d日", Locale.ROOT)
    private val DATE_FMT_WITH_YEAR = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.ROOT)
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val WEEKDAY = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")





    private const val SPEED_KMH = 800.0

    /** 京沪航线（PEK↔SHA）约 1,080 km。用于把抽象里程换算成"飞了几段京沪" */
    private const val PEK_SHA_KM = 1080.0











    const val DIFF_EXACT: String = "刚好"

    /** 差额不可计算（计划时长缺失）时的占位符 */
    const val DIFF_UNKNOWN: String = "—"








    fun build(entity: FlightHistoryEntity, zone: ZoneId, nowMillis: Long): RecordDetail {
        val start = Instant.ofEpochMilli(entity.startTimeMillis).atZone(zone)
        val end = entity.endTimeMillis?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)

        val status = FlightStatus.fromDb(entity.status)
        val statusText = statusText(status, entity)

        return RecordDetail(
            id = entity.id,
            fromCode = entity.departureAirportCode.ifBlank { "---" },
            toCode = entity.destinationAirportCode.ifBlank { "---" },
            fromCity = entity.departureCity.ifBlank { entity.departureAirportCode },
            toCity = entity.destinationCity.ifBlank { entity.destinationAirportCode },
            fromAirportName = entity.departureAirportName,
            toAirportName = entity.destinationAirportName,
            fromCountry = entity.departureCountry,
            toCountry = entity.destinationCountry,
            dateText = dateText(start, now),
            timeRangeText = timeRangeText(start, end, entity.durationMinutes),
            statusText = statusText,
            status = status,
            plannedText = RecordListBuilder.formatMinutes(entity.durationMinutes),
            actualText = RecordListBuilder.formatMinutes(entity.actualFocusMinutes),
            diffText = diffText(entity.durationMinutes, entity.actualFocusMinutes),
            completionPercent = completionPercent(entity.durationMinutes, entity.actualFocusMinutes),
            distanceText = RecordListBuilder.km(entity.distanceKm),
            cruiseEquivalentText = cruiseEquivalentText(entity.distanceKm),
            routeEquivalentText = routeEquivalentText(entity.distanceKm),

            //   界面靠 `text.isNullOrBlank()` 决定显隐；给空串会让
            //   View 变成"有内容但看不见"的僵尸节点，TalkBack 仍会聚焦它。
            seat = entity.seat?.trim()?.takeIf { it.isNotEmpty() },
            quote = entity.quote?.trim()?.takeIf { it.isNotEmpty() },
            quoteAuthor = entity.quoteAuthor?.trim()?.takeIf { it.isNotEmpty() },
            isGroupFlight = entity.focusType == "GROUP",
            participantCount = entity.roomParticipantCount?.takeIf { it > 1 },
            payloadJson = entity.logJson,
        )
    }











    fun dateText(start: java.time.ZonedDateTime, now: java.time.ZonedDateTime): String {
        val d = start.toLocalDate()
        val fmt = if (d.year == now.year) DATE_FMT else DATE_FMT_WITH_YEAR
        return "${fmt.format(d)} ${WEEKDAY[d.dayOfWeek.value - 1]}"
    }











    fun timeRangeText(
        start: java.time.ZonedDateTime,
        end: java.time.ZonedDateTime?,
        durationMinutes: Int,
    ): String {
        val startText = TIME_FMT.format(start)
        val endZdt = end ?: if (durationMinutes > 0) start.plusMinutes(durationMinutes.toLong()) else null
        if (endZdt == null) return startText
        val endText = TIME_FMT.format(endZdt)

        //   宁可只显示开始时刻，也不显示一个"零长度区间"。
        if (endText == startText) return startText

        return "$startText — $endText"
    }










    fun statusText(status: FlightStatus, entity: FlightHistoryEntity): String {
        val known = FlightStatus.entries.any { it.dbValue == entity.status }
        val base = when (status) {
            FlightStatus.ONGOING -> "进行中"
            FlightStatus.COMPLETED -> "已完成"
            FlightStatus.CANCELLED -> "已取消"
        }
        return if (known) base else "$base（状态异常）"
    }














    fun diffText(plannedMinutes: Int, actualMinutes: Int): String {
        if (plannedMinutes <= 0) return DIFF_UNKNOWN
        val diff = actualMinutes - plannedMinutes
        if (diff == 0) return DIFF_EXACT
        val abs = kotlin.math.abs(diff)
        val unit = RecordListBuilder.formatMinutes(abs)
        return if (diff < 0) "少 $unit" else "多 $unit"
    }














    fun completionPercent(plannedMinutes: Int, actualMinutes: Int): Int? {
        if (plannedMinutes <= 0) return null
        val ratio = actualMinutes.toDouble() / plannedMinutes
        return kotlin.math.round(ratio.coerceIn(0.0, 1.0) * 100).toInt()
    }














    fun cruiseEquivalentText(distanceKm: Double): String {
        if (distanceKm <= 0.0) return "—"
        val minutes = kotlin.math.round(distanceKm / SPEED_KMH * 60).toInt()
        return "相当于巡航 ${RecordListBuilder.formatMinutes(minutes)}"
    }








    fun routeEquivalentText(distanceKm: Double): String {
        if (distanceKm <= 0.0) return "—"
        val segments = distanceKm / PEK_SHA_KM
        if (segments < 0.5) return "—"
        return "约 ${String.format(Locale.ROOT, "%.1f", segments)} 段京沪航线"
    }
}








data class RecordDetail(
    val id: String,
    val fromCode: String,
    val toCode: String,
    val fromCity: String,
    val toCity: String,
    val fromAirportName: String,
    val toAirportName: String,
    val fromCountry: String,
    val toCountry: String,
    /** `10月26日 周日` */
    val dateText: String,
    /** `08:30 — 10:45` */
    val timeRangeText: String,
    val statusText: String,
    val status: FlightStatus,
    /** 计划时长 `2 小时 15 分` */
    val plannedText: String,
    /** 实际专注 `2 小时 05 分` */
    val actualText: String,
    /** `少 10 分钟` */
    val diffText: String,
    /** 完成度整数百分比；`null` = 数据不足，界面隐藏 */
    val completionPercent: Int?,
    /** `1800.0`（不含单位，单位由界面加，便于对齐） */
    val distanceText: String,
    /** `相当于巡航 2 小时 15 分` */
    val cruiseEquivalentText: String,
    /** `约 1.7 段京沪航线` */
    val routeEquivalentText: String,
    val seat: String?,
    val quote: String?,
    val quoteAuthor: String?,
    val isGroupFlight: Boolean,
    val participantCount: Int?,
    /** 原始 logJson（导出用；界面不直接显示） */
    val payloadJson: String?,
) {
    /** 是否可以「重飞这趟」。取消与状态异常的记录不给重飞。 */
    val canReplay: Boolean
        get() = status == FlightStatus.COMPLETED && !statusText.contains("异常")

    /** 是否可以分享成卡片。与重飞同口径 —— 取消的飞行没有可分享的成就。 */
    val canShare: Boolean get() = canReplay

    /** 供 TalkBack 整段朗读的头信息 */
    val spokenHeader: String
        get() = "$fromCity 到 $toCity，$dateText，$timeRangeText 起飞，$statusText"
}
