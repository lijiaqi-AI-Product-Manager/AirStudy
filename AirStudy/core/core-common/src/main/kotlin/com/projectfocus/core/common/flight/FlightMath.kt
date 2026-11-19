package com.projectfocus.core.common.flight

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt


object FlightMath {


    const val SPEED_KMH: Double = 800.0


    const val KM_PER_DEGREE: Double = 111.32


    const val MIN_COS_LAT: Double = 1.0E-6

    /** 球面半径；原包ag.e/ak1.h0的直径常数为12742km。 */
    const val EARTH_RADIUS_KM: Double = 6371.0

    // ─────────────────────────────────────────────────────────
    // 1. 时长 ⇄ 距离
    // ─────────────────────────────────────────────────────────


    fun distanceKmForMinutes(minutes: Int): Double {
        require(minutes >= 0) { "minutes must be >= 0, was $minutes" }
        return (minutes / 60.0) * SPEED_KMH
    }

    /** 上式的浮点重载，用于连续时长（如从记录里恢复的长整数秒） */
    fun distanceKmForMinutes(minutes: Double): Double {
        require(minutes >= 0.0) { "minutes must be >= 0, was $minutes" }
        return (minutes / 60.0) * SPEED_KMH
    }

    /**
     * 距离 → 所需分钟数（反函数）。
     * 用于「用户在机场列表里点一个机场，显示要飞多久」。
     *
     * 原包先将公里数保存为Float，再由ak1.O → y56.L四舍五入；不能直接截断。
     */
    fun minutesForDistanceKm(distanceKm: Double): Int =
        if (distanceKm <= 0.0) 0 else (distanceKm.toFloat().toDouble() / SPEED_KMH * 60.0).roundToInt()

    /** R156原包ak1.h0(DDDD)D → jt2的Float转换；用于所选航段，非检索半径。 */
    fun airportDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)
        val a = sin(deltaLon / 2.0).pow(2) * cos(Math.toRadians(lat2)) *
            cos(Math.toRadians(lat1)) + sin(deltaLat / 2.0).pow(2)
        return (atan2(sqrt(a), sqrt(1.0 - a)) * (2.0 * EARTH_RADIUS_KM)).toFloat()
    }

    // ─────────────────────────────────────────────────────────
    // 2. 进度
    // ─────────────────────────────────────────────────────────


    fun progress(elapsedSeconds: Long, totalMinutes: Int): Float {
        val totalSeconds = totalMinutes.toLong() * 60L
        if (totalSeconds <= 0L) return 0f
        val raw = elapsedSeconds.toFloat() / totalSeconds.toFloat()
        return raw.coerceIn(0f, 1f)
    }

    /** ir3的剩余距离字段：先Float减法，再Double乘路线距离，最后保存为Float。 */
    fun remainingKm(totalKm: Double, progress: Float): Double =
        ((1f - progress).toDouble() * totalKm).toFloat().toDouble()

    /** 已飞距离 = 总距离 - 剩余距离 */
    fun traveledKm(totalKm: Double, progress: Float): Double =
        (totalKm - remainingKm(totalKm, progress)).coerceAtLeast(0.0)


    fun remainingSeconds(elapsedSeconds: Long, totalMinutes: Int): Long =
        max(0L, totalMinutes.toLong() * 60L - elapsedSeconds)


    fun remainingMinutesCeil(elapsedSeconds: Long, totalMinutes: Int): Int {
        val remain = remainingSeconds(elapsedSeconds, totalMinutes)
        return ((remain + 59L) / 60L).toInt()
    }

    // ─────────────────────────────────────────────────────────
    // 3. 视野适配（地图 boundingBox）
    // ─────────────────────────────────────────────────────────


    fun viewportDeltaDegrees(totalKm: Double, centerLat: Double): Pair<Double, Double> {
        val deltaLat = totalKm / KM_PER_DEGREE
        var cosLat = cos(Math.toRadians(centerLat))
        if (cosLat < MIN_COS_LAT) cosLat = MIN_COS_LAT
        val deltaLng = totalKm / (cosLat * KM_PER_DEGREE)
        return deltaLat to deltaLng
    }

    /** 由中心点与跨度算出 bbox：返回 [westLng, southLat, eastLng, northLat]（Mapbox 顺序） */
    fun boundingBox(
        centerLat: Double,
        centerLng: Double,
        totalKm: Double,
    ): DoubleArray {
        val (dLat, dLng) = viewportDeltaDegrees(totalKm, centerLat)
        return doubleArrayOf(
            centerLng - dLng,
            centerLat - dLat,
            centerLng + dLng,
            centerLat + dLat,
        )
    }

    // ─────────────────────────────────────────────────────────
    // 4. 大圆距离与插值（仅用于显示与航线绘制）
    // ─────────────────────────────────────────────────────────

    /**
     * Haversine 大圆距离。
     *
     * 本品进度函数按时间计算；本方法提供机场间的几何距离。
     * 原包ag.e/ak1.h0使用atan2形式；本品保留asin与越界保护，数值边界仍待对账。
     */
    fun greatCircleKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }

    /**
     * 球面线性插值（slerp），用于在地图上按进度放置飞机图标。
     *
     * @param t 0.0 = 起点，1.0 = 终点
     */
    fun interpolate(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        t: Double,
    ): Pair<Double, Double> {
        val tt = t.coerceIn(0.0, 1.0)
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(lon2 - lon1)

        val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        val d = 2 * asin(min(1.0, sqrt(a)))

        // 两点几乎重合：直接线性兜底，避免 0/0
        if (d < 1e-12) return (lat1 + (lat2 - lat1) * tt) to normalizeLng(lon1 + (lon2 - lon1) * tt)

        val aCoef = sin((1 - tt) * d) / sin(d)
        val bCoef = sin(tt * d) / sin(d)

        val x = aCoef * cos(phi1) * cos(Math.toRadians(lon1)) + bCoef * cos(phi2) * cos(Math.toRadians(lon2))
        val y = aCoef * cos(phi1) * sin(Math.toRadians(lon1)) + bCoef * cos(phi2) * sin(Math.toRadians(lon2))
        val z = aCoef * sin(phi1) + bCoef * sin(phi2)

        val lat = Math.toDegrees(kotlin.math.atan2(z, sqrt(x * x + y * y)))
        val lng = Math.toDegrees(kotlin.math.atan2(y, x))
        return lat to normalizeLng(lng)
    }

    private fun normalizeLng(lng: Double): Double {
        var v = lng
        while (v > 180.0) v -= 360.0
        while (v < -180.0) v += 360.0
        return v
    }

    // ─────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────


    fun formatDuration(seconds: Long): String {
        val s = max(0L, seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return "%02d:%02d:%02d".format(h, m, sec)
    }


    fun formatMinutes(totalMinutes: Int): String {
        if (totalMinutes < 60) return "${totalMinutes}m"
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (m == 0) "${h}h" else "${h}h ${m}m"
    }
}
