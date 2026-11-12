package com.projectfocus.server.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt


object FlightMath {


    const val SPEED_KMH = 800.0


    const val KM_PER_DEGREE = 111.32


    const val MIN_COS_LAT = 1.0E-6

    const val EARTH_RADIUS_KM = 6371.0


    const val MIN_DURATION_MINUTES = 5
    const val MAX_DURATION_MINUTES = 180

    /**
     * 核心公式 —— **只由时长决定**。
     *
     * 注意这是**有意的**与"真实距离"脱钩，见类注释。
     */
    fun distanceKm(durationMinutes: Int): Double {
        val d = durationMinutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
        return d / 60.0 * SPEED_KMH
    }

    /**
     * 大圆距离（Haversine）。**不用于计费/记录**，只用于：
     *   · 校验客户端上传的机场坐标是否明显错误（比如把北京填到南美）
     *   · 未来 M3 的"真实航程"字段
     */
    fun greatCircleKm(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val dLat = (lat2 - lat1).toRadians()
        val dLng = (lng2 - lng1).toRadians()
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLng / 2).let { it * it }
        // atan2 版本比 asin 版本在极小距离上数值更稳（不受浮点误差导致 sqrt 负数）
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    private fun Double.toRadians(): Double = this / 180.0 * PI
}


object SeatMath {

    val COLUMNS = listOf("A", "B", "C", "D", "E", "F")
    const val MAX_SEATS = 8

    data class Seat(val number: Int, val column: String) {
        override fun toString(): String = "$number$column"

        companion object {
            /** 解析 `"3A"`。非法输入返回 null（不抛 —— 输入来自不可信客户端） */
            fun parse(raw: String?): Seat? {
                if (raw.isNullOrBlank()) return null
                val s = raw.trim().uppercase()
                if (s.length !in 2..3) return null
                val col = s.last()
                if (col.toString() !in COLUMNS) return null
                val num = s.dropLast(1).toIntOrNull() ?: return null
                if (num !in 1..9) return null   // 8 人房最多 2 排，9 是宽容量
                return Seat(num, col.toString())
            }
        }
    }

    /** 全部可用座位，按"排优先、列优先"顺序 —— 这是填座顺序 */
    val ALL: List<Seat> = buildList {
        for (row in 1..9) {
            for (col in COLUMNS) {
                add(Seat(row, col))
                if (this.size >= MAX_SEATS) return@buildList
            }
        }
    }


    fun pick(preferred: Seat?, taken: Set<Seat>): Seat? {
        if (preferred != null && preferred !in taken) return preferred
        return ALL.firstOrNull { it !in taken }
    }

    /** 严格模式：要求恰好是某个座位，被占就返回 null（由调用方抛 SeatTakenException） */
    fun requireExact(preferred: Seat, taken: Set<Seat>): Seat? =
        if (preferred in taken) null else preferred
}


object InviteCodes {

    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6

    private val RANDOM = java.security.SecureRandom()






    fun generate(): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]) }
    }

    /** 归一化：大小写不敏感 + 去空格。用户手输的码一定是脏的 */
    fun normalize(raw: String): String =
        raw.trim().uppercase().filter { it.isLetterOrDigit() }

    fun isValid(raw: String): Boolean {
        val n = normalize(raw)
        return n.length == LENGTH && n.all { it in ALPHABET }
    }
}
