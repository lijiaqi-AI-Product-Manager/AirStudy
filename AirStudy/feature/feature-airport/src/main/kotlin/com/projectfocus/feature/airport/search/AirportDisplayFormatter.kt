package com.projectfocus.feature.airport.search

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime


object AirportDisplayFormatter {











    fun primaryTitle(
        cityZh: String?,
        city: String?,
        nameZh: String?,
        name: String?,
    ): String {

        //   数据库里空城市是空串，但 OurAirports 里也有全是空格的行（导出工具有 bug）。
        //   空串检查会漏掉 " "，界面上就是一行空白标题 —— 比显示英文还糟。
        return firstNonBlank(cityZh, city, nameZh, name, "未知机场")
    }









    fun secondaryTitle(
        nameZh: String?,
        name: String?,
        iataCode: String?,
        icaoCode: String?,
        typeLabel: String? = null,
    ): String {
        val parts = buildList {
            firstNonBlankOrNull(nameZh, name)?.let(::add)

            //   同时显示 `PEK · ZBAA` 对乘客是噪音（他们只认三字码）。
            //   而 ICAO 对飞行模拟爱好者有价值，所以在"没有 IATA"时补上，
            //   而不是彻底丢掉。
            firstNonBlankOrNull(iataCode, icaoCode)?.let(::add)
            firstNonBlankOrNull(typeLabel)?.let(::add)
        }
        return parts.joinToString(SEP)
    }


    fun duration(minutes: Int): String {

        //   为一条坏数据让整个列表崩掉不值得。
        val m = minutes.coerceAtLeast(0)
        val h = m / 60
        val rest = m % 60
        return when {
            h == 0 -> "${rest}m"
            rest == 0 -> "${h}h"
            else -> "${h}h ${rest}m"
        }
    }











    fun distance(km: Double): String {

        //   km 来自 `FlightMath.greatCircleKm`，理论上不会 NaN，
        //   但如果坐标是 (0,0)（海洋里的虚拟机场）与 (0,0) 相比就是 0，
        //   而 (NaN) 入库过一次就会一直传下来。展示层必须挡住。
        if (km.isNaN() || km.isInfinite()) return "—"
        val v = km.coerceAtLeast(0.0)
        return if (v < 10.0) {
            "${v.toLong()}km"
        } else {
            "${withThousands(v.toLong())}km"
        }
    }

    /**
     * 距离 + 时长合成一行：`1,800km · 2h 15m`。
     */
    fun routeLine(distanceKm: Double, minutes: Int): String =
        "${distance(distanceKm)}$SEP${duration(minutes)}"


    fun localTime(epochMillis: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return "%02d:%02d".format(java.util.Locale.ROOT, t.hour, t.minute)
    }










    fun isSelectable(type: String?): Boolean = type?.uppercase() != "CLOSED"

    /** 层级文案，用于搜索结果分组标题 */
    fun tierLabel(tier: AirportSearchIndex.Tier): String = when (tier) {
        AirportSearchIndex.Tier.CODE_EXACT -> "精确匹配"
        AirportSearchIndex.Tier.CODE_PREFIX -> "代码匹配"
        AirportSearchIndex.Tier.NAME_PREFIX -> "机场名匹配"
        AirportSearchIndex.Tier.CITY_PREFIX -> "城市匹配"
        AirportSearchIndex.Tier.CHINESE -> "中文匹配"
        AirportSearchIndex.Tier.PINYIN -> "拼音匹配"
        AirportSearchIndex.Tier.NAME_CONTAINS -> "机场名包含"
        AirportSearchIndex.Tier.CITY_CONTAINS -> "城市包含"
    }

    // ─────────────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────────────

    private const val SEP = " · "

    private fun firstNonBlank(vararg candidates: String?): String =
        firstNonBlankOrNull(*candidates) ?: "未知机场"

    private fun firstNonBlankOrNull(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }?.trim()








    private fun withThousands(v: Long): String {
        val s = v.toString()
        if (s.length <= 3) return s
        val sb = StringBuilder(s.length + s.length / 3)
        var count = 0
        for (i in s.indices.reversed()) {
            if (count == 3) {
                sb.append(',')
                count = 0
            }
            sb.append(s[i])
            count++
        }
        return sb.reverse().toString()
    }











    fun roughDaylight(epochMillis: Long, zone: ZoneId): Boolean {
        val t: ZonedDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)
        // 简化：6:00–18:00 视为白昼。不含纬度/季节修正。
        return t.hour in 6..17
    }
}
