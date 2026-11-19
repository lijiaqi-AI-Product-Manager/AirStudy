package com.projectfocus.feature.completion.pass

import com.projectfocus.core.common.flight.FlightMath
import java.io.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs


data class BoardingPassData(
    // ── 主区 ────────────────────────────────────────────────
    /** 出发三字码，大字显示。缺码时为 `---` */
    val departureCode: String,
    val departureCity: String,
    /** 出发时间 `HH:mm`（本地时区） */
    val departureTime: String,

    val arrivalCode: String,
    val arrivalCity: String,
    /** 到达时间 `HH:mm`（本地时区） */
    val arrivalTime: String,

    // ── 信息条 ──────────────────────────────────────────────
    /** 航班号，如 `PF1026` */
    val flightNo: String,
    /** 座位，如 `3A`。solo 飞行时为 `-` */
    val seat: String,
    /** 日期 `2026-10-26`（本地时区） */
    val date: String,
    /** 里程文本 `600.0 km` */
    val distanceText: String,
    /** 专注时长文本 `45m` / `1h 30m` */
    val focusDurationText: String,
    /** 计划时长（分钟），用于「专注 45 / 计划 45 分钟」这种对照 */
    val plannedMinutes: Int,
    /** 实际专注分钟 */
    val actualFocusMinutes: Int,

    // ── 状态 ────────────────────────────────────────────────
    /** 是否是「放弃」的航班。登机牌上会盖 `CANCELLED` 章而不是 `COMPLETED` */
    val abandoned: Boolean,


    /**
     * 登机时间 `HH:mm`（本地时区）。
     *
     * 默认与起飞时间相同，但两者不是同一概念：真实排班接入后由调用方显式传入。
     * 放在构造参数末尾并带默认值，避免破坏既有 14 参调用点。
     */
    val boardingTime: String = departureTime,


    val passengerName: String = "",
) : Serializable {
    /** 完成度 0f~1f。UI 用它决定"完成"印章的不透明度 */
    val completionRatio: Float
        get() = if (plannedMinutes <= 0) 1f
        else (actualFocusMinutes.toFloat() / plannedMinutes).coerceIn(0f, 1f)


    val stampText: String get() = if (abandoned) "CANCELLED" else "COMPLETED"

    companion object {

        /** 序列化版本：R5 增加 boardingTime/passengerName，显式升到 2。 */
        private const val serialVersionUID: Long = 2L

        /** 缺码占位符。与 `SessionRepository.UNKNOWN_AIRPORT` 同值 */
        const val UNKNOWN_CODE = "---"

        /** 无座位占位符。用 `-` 而不是空串：空串会让「SEAT」标签后面空一片 */
        const val NO_SEAT = "-"

        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)














        fun from(
            departureCode: String?,
            departureCity: String?,
            arrivalCode: String?,
            arrivalCity: String?,
            startWallMs: Long,
            endWallMs: Long,
            zone: ZoneId,
            plannedMinutes: Int,
            actualFocusMinutes: Int,
            distanceKm: Double,
            seat: String? = null,
            abandoned: Boolean = false,
            passengerName: String = "",
            boardingTime: String? = null,
        ): BoardingPassData {
            require(distanceKm.isFinite() && distanceKm >= 0.0) { "distanceKm must be finite and non-negative" }
            val start = Instant.ofEpochMilli(startWallMs).atZone(zone)
            val end = Instant.ofEpochMilli(endWallMs).atZone(zone)

            return BoardingPassData(
                departureCode = departureCode?.takeIf { it.isNotBlank() } ?: UNKNOWN_CODE,
                departureCity = departureCity?.takeIf { it.isNotBlank() }.orEmpty(),
                departureTime = TIME_FMT.format(start),
                arrivalCode = arrivalCode?.takeIf { it.isNotBlank() } ?: UNKNOWN_CODE,
                arrivalCity = arrivalCity?.takeIf { it.isNotBlank() }.orEmpty(),

                //   用户提前下机时，真实时刻才是他想记住的。
                arrivalTime = TIME_FMT.format(end),
                flightNo = flightNo(start.dayOfMonth, plannedMinutes),
                seat = seat?.takeIf { it.isNotBlank() } ?: NO_SEAT,
                date = DATE_FMT.format(start),
                distanceText = "${HudFmt.km(distanceKm)} km",
                focusDurationText = FlightMath.formatMinutes(actualFocusMinutes),
                plannedMinutes = plannedMinutes,
                actualFocusMinutes = actualFocusMinutes,
                abandoned = abandoned,
                // 登机时间与起飞时间不是同一概念；默认取起飞时间，调用方可覆盖。
                boardingTime = boardingTime?.takeIf { it.isNotBlank() } ?: TIME_FMT.format(start),
                passengerName = passengerName.trim(),
            )
        }


        fun flightNo(dayOfMonth: Int, plannedMinutes: Int): String =
            "PF%02d%02d".format(Locale.ROOT, dayOfMonth.coerceIn(1, 31), plannedMinutes.coerceIn(0, 99))
    }
}

/**
 * HUD 与登机牌共用的格式化。抽出来是为了保证**同一组数字在飞行页与登机牌上
 * 一模一样** —— 两处各写一份 `String.format` 是这类不一致的常见来源。
 */
object HudFmt {
    /** 里程：1 位小数，归一化负零。与 `HudSnapshot.formatKm` 同口径 */
    fun km(v: Double): String {
        val x = if (abs(v) < 1e-9) 0.0 else v
        return String.format(Locale.ROOT, "%.1f", x)
    }
}
