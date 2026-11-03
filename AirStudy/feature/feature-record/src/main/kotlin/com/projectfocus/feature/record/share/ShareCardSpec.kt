package com.projectfocus.feature.record.share

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
















































data class ShareCardSpec(
    /** 展示模型（提供城市、三字码、日期、里程、专注时长） */
    val detail: ShareDetail,
    /** 航班号，如 `PF2645`。与登机牌上的号码**必须是同一个** */
    val flightNo: String,
    /** 起飞墙钟毫秒。用于生成文件名里的日期 */
    val startWallMs: Long,

    val zone: ZoneId,
    /** 分享文本模板（由界面层传入 `getString(...)` 的结果，本层不碰资源） */
    val templates: Templates,
) {





    val text: String
        get() = templates.text(
            from = cityOrFallback(detail.fromCity, detail.fromCode),
            to = cityOrFallback(detail.toCity, detail.toCode),
            distance = detail.distanceText,
            focus = detail.actualText,
        )

    /** 邮件主题（部分邮件客户端直接用 `EXTRA_SUBJECT`） */
    val subject: String
        get() = templates.subject(
            from = cityOrFallback(detail.fromCity, detail.fromCode),
            to = cityOrFallback(detail.toCity, detail.toCode),
        )

    /** 卡片图的无障碍描述 */
    val imageDescription: String
        get() = templates.cardDesc(
            from = cityOrFallback(detail.fromCity, detail.fromCode),
            to = cityOrFallback(detail.toCity, detail.toCode),
            flightNo = flightNo,
        )














    val fileName: String
        get() {
            val date = FILE_DATE_FMT.format(Instant.ofEpochMilli(startWallMs).atZone(zone))
            val safeNo = flightNo.filter { it.isLetterOrDigit() }.ifEmpty { "PF0000" }
            return "boarding-pass-$date-$safeNo.png"
        }









    private fun cityOrFallback(city: String, code: String): String = when {
        city.isNotBlank() -> city
        code.isNotBlank() && code != UNKNOWN_CODE -> code
        else -> templates.unknownPlace
    }











    interface Templates {
        fun text(from: String, to: String, distance: String, focus: String): String
        fun subject(from: String, to: String): String
        fun cardDesc(from: String, to: String, flightNo: String): String
        /** 城市与三字码都缺失时的说法 */
        val unknownPlace: String
    }

    companion object {
        /** 与 `SessionRepository.UNKNOWN_AIRPORT` / `ReplayIntentBuilder.UNKNOWN_CODE` 同值 */
        const val UNKNOWN_CODE: String = "---"

        private val FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
    }
}


















data class ShareDetail(
    val fromCity: String,
    val toCity: String,
    val fromCode: String,
    val toCode: String,
    /** 已格式化的里程，如 `600.0`（不含单位） */
    val distanceText: String,
    /** 已格式化的专注时长，如 `45 分钟` */
    val actualText: String,
)
