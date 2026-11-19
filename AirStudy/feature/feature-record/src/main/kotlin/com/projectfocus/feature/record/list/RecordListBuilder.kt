package com.projectfocus.feature.record.list

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.service.focus.history.FlightRecordKey
import com.projectfocus.service.focus.history.FlightRecordKind
import com.projectfocus.service.focus.history.RecordedFlight
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs


/** 一行历史记录的展示数据 */
data class RecordItem(
    val id: String,
    /** 出发三字码 */
    val fromCode: String,
    /** 到达三字码 */
    val toCode: String,
    val fromCity: String,
    val toCity: String,
    /** 起飞时刻 `HH:mm` */
    val startTime: String,
    /** 计划时长 `45 分钟` / `1 小时 30 分` */
    val durationText: String,
    /** 里程 `600.0 km` */
    val distanceText: String,
    /** 实际专注 `42 分钟` */
    val focusText: String,
    /** 状态：已完成 / 已取消 / 进行中 */
    val status: FlightStatus,
    /** 完成度 0f~1f，用于卡片左侧的进度条 */
    val completionRatio: Float,
    val kind: FlightRecordKind = FlightRecordKind.COUNTDOWN,
    val dateText: String? = null,
    val flightNumber: String? = null,
    val seat: String? = null,
    val sceneTitle: String? = null,
    val sceneIconKey: String? = null,
    val sceneType: Int? = null,
    val distanceKm: Double? = null,
    val focusMinutes: Int? = null,
    val arrivalSelected: Boolean = true,
    val recordedAtWallMs: Long? = null,
    val sceneRevision: Long = 0L,
    val sceneOptionId: String? = null,
    val cloudSummary: com.projectfocus.core.network.dto.FlightRecordSummary? = null,
) {
    val key: FlightRecordKey get() = FlightRecordKey(kind, id)
    val displayKey get() = Triple(kind, id, cloudSummary != null)
    /** 供无障碍整条朗读 */
    val spokenSummary: String
        get() = if (cloudSummary != null) "$fromCity 到 $toCity，${status.label}，历史资料已保留，完整飞行资料待恢复" else
            "$fromCity 到 $toCity，$startTime 起飞，${status.label}，" +
            (if (kind == FlightRecordKind.COUNT_UP) "正计时，实际专注 $focusText"
            else "倒计时，计划 $durationText，实际专注 $focusText") +
                sceneTitle?.takeIf { it.isNotBlank() }?.let { "，场景 $it" }.orEmpty()
}

/** 飞行状态。与数据库 `status` 列的三个取值一一对应 */
enum class FlightStatus(val dbValue: String, val label: String) {
    ONGOING("ONGOING", "进行中"),
    COMPLETED("COMPLETED", "已完成"),
    CANCELLED("CANCELLED", "已取消"),
    UNKNOWN("UNKNOWN", "资料待恢复"),
    ;

    companion object {









        fun fromDb(value: String?): FlightStatus =
            entries.firstOrNull { it.dbValue == value } ?: CANCELLED
    }
}

/** 一天的记录 */
data class DayGroup(
    /** 本地日期，用于排序与稳定 key */
    val date: LocalDate,
    /** 标题：`今天` / `昨天` / `10月26日` / `2025年12月31日` */
    val title: String,
    /** 副标题：`3 趟 · 2 小时 15 分` */
    val summary: String,
    val items: List<RecordItem>,
)

/** 列表顶部的汇总 */
data class RecordSummary(
    val flightCount: Int,
    val totalHoursText: String,
    val totalDistanceText: String,
    val countryCount: Int,
) {
    val isEmpty: Boolean get() = flightCount == 0

    companion object {
        val EMPTY = RecordSummary(0, "0", "0", 0)
    }
}










sealed interface RecordListState {
    data object Loading : RecordListState

    /** 完全没有记录 */
    data object Empty : RecordListState

    /** 有记录，但当前筛选条件下没有 */
    data class NoResult(val keyword: String) : RecordListState

    data class Content(
        val summary: RecordSummary,
        val groups: List<DayGroup>,
    ) : RecordListState
}

/**
 * 历史记录 → 列表状态的纯函数。
 *
 * 全部逻辑集中在这里，所以它可以被完全单测覆盖（本文件配套 20+ 条）。
 * ViewModel 只负责"取数据 + 调这里 + 转发事件"。
 */
object RecordListBuilder {

    private val TIME_FMT = java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)










    fun build(
        entities: List<FlightHistoryEntity>,
        zone: ZoneId,
        nowMillis: Long,
        keyword: String = "",
    ): RecordListState {
        if (entities.isEmpty()) return RecordListState.Empty

        val kw = keyword.trim()
        val filtered = if (kw.isEmpty()) entities else entities.filter { matches(it, kw) }

        if (filtered.isEmpty()) return RecordListState.NoResult(kw)

        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        val groups = filtered


            //   用户在历史页想按时间线看，补录的旧飞行突然出现在最前面会让人困惑。

            .sortedByDescending { it.startTimeMillis }
            .groupBy { Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalDate() }
            .toSortedMap(compareByDescending { it })
            .map { (date, rows) -> buildGroup(date, today, zone, rows) }

        return RecordListState.Content(
            summary = buildSummary(filtered),
            groups = groups,
        )
    }

    private fun matches(e: FlightHistoryEntity, kw: String): Boolean {
        val k = kw.lowercase(Locale.ROOT)
        return e.departureCity.lowercase(Locale.ROOT).contains(k) ||
            e.destinationCity.lowercase(Locale.ROOT).contains(k) ||
            e.departureAirportCode.lowercase(Locale.ROOT).contains(k) ||
            e.destinationAirportCode.lowercase(Locale.ROOT).contains(k) ||
            // 机场英文名也参与匹配：有人会搜 "Tokyo"
            e.departureAirportName.lowercase(Locale.ROOT).contains(k) ||
            e.destinationAirportName.lowercase(Locale.ROOT).contains(k)
    }

    private fun buildGroup(
        date: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        rows: List<FlightHistoryEntity>,
    ): DayGroup {
        val totalMinutes = rows.sumOf { it.actualFocusMinutes }
        val summaryParts = buildList {
            add("${rows.size} 趟")
            if (totalMinutes > 0) add(formatMinutes(totalMinutes))
        }
        return DayGroup(
            date = date,
            title = relativeDayTitle(date, today),
            summary = summaryParts.joinToString(" · "),
            items = rows.map { toItem(it, zone) },
        )
    }












    fun relativeDayTitle(date: LocalDate, today: LocalDate): String {
        val diff = java.time.temporal.ChronoUnit.DAYS.between(date, today)
        return when {
            diff == 0L -> "今天"
            diff == 1L -> "昨天"
            // 未来日期（改系统时间、跨时区）当作普通日期显示，不写"明天"——
            // 历史列表里出现"明天"是荒谬的。
            date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
            else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }
    }

    private fun toItem(e: FlightHistoryEntity, zone: ZoneId): RecordItem =
        RecordItemMapping.item(RecordedFlight.Countdown(e), zone)

    private fun buildSummary(rows: List<FlightHistoryEntity>): RecordSummary {

        //   这一点必须和 `FlightHistoryDao.observeTotalDistanceKm` 的口径一致，
        //   否则列表顶部的数字和统计页的数字会不一样 —— 用户会认为其中一个错了。
        val done = rows.filter { it.status == FlightStatus.COMPLETED.dbValue }
        val totalMinutes = done.sumOf { it.actualFocusMinutes }
        val totalKm = done.sumOf { it.distanceKm }
        val countries = done.map { it.destinationCountry }.filter { it.isNotBlank() }.distinct().size

        return RecordSummary(
            flightCount = done.size,
            totalHoursText = if (totalMinutes < 60) {
                "$totalMinutes 分钟"
            } else {
                val h = totalMinutes / 60
                val m = totalMinutes % 60
                if (m == 0) "$h 小时" else "$h 小时 $m 分"
            },
            totalDistanceText = km(totalKm),
            countryCount = countries,
        )
    }









    fun formatMinutes(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m < 60 -> "$m 分钟"
            m % 60 == 0 -> "${m / 60} 小时"
            else -> "${m / 60} 小时 ${m % 60} 分"
        }
    }

    /** 里程：1 位小数，归一化负零。与 `HudFmt.km` 同口径 */
    fun km(v: Double): String {
        val x = if (abs(v) < 1e-9) 0.0 else v
        return String.format(Locale.ROOT, "%.1f", x)
    }

    /** 月份键，供"按月折叠"使用 */
    fun monthOf(date: LocalDate): YearMonth = YearMonth.from(date)
}
