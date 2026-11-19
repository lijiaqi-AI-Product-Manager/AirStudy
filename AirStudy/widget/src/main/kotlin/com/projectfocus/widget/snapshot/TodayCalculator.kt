package com.projectfocus.widget.snapshot

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId









































object TodayCalculator {

    /** 连续天数最多往回看多久。见 [streakDays] 的注释 */
    const val STREAK_SCAN_DAYS: Int = 400









    fun dayStartMillis(nowMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** 明日 00:00 的 Unix 毫秒。用于「左闭右开」区间 */
    fun nextDayStartMillis(nowMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()











    fun compute(
        sessionCount: Int,
        focusMinutes: Int,
        distanceKm: Double,
        recentStartTimes: List<Long>,
        nowMillis: Long,
        zone: ZoneId,
    ): TodayStats {
        val days = distinctDaysDescending(recentStartTimes, zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val streak = streakDays(days, today)

        return TodayStats(
            sessionCount = sessionCount.coerceAtLeast(0),
            focusMinutes = focusMinutes.coerceAtLeast(0),

            //   四舍五入而不是截断 —— 飞了 999.6 km 显示 999 会让人觉得"少了"。
            distanceKm = kotlin.math.round(distanceKm).toInt().coerceAtLeast(0),
            streakDays = streak,
            flewToday = days.firstOrNull() == today,
        )
    }











    fun distinctDaysDescending(times: List<Long>, zone: ZoneId): List<LocalDate> {
        if (times.isEmpty()) return emptyList()
        val set = java.util.TreeSet<LocalDate>(Comparator.reverseOrder())
        for (t in times) {

            //   1970-01-01 会变成一个"远古的连续天数"，把结果毁掉。
            if (t <= 0L) continue
            set.add(Instant.ofEpochMilli(t).atZone(zone).toLocalDate())
        }
        return set.toList()
    }
















    fun streakDays(days: List<LocalDate>, today: LocalDate): Int {
        if (days.isEmpty()) return 0


        //   注意这里**不能**写成 `days.contains(today)` 之后 else 0 ——
        //   那正是"早上打开归零"的错法。
        val cursor = when {
            days.binarySearchDescending(today) -> today
            days.binarySearchDescending(today.minusDays(1)) -> today.minusDays(1)
            else -> return 0
        }

        var count = 0
        var expected = cursor
        var index = 0
        // 跳过 cursor 之前（更新）的日期 —— 理论上不存在（today 是最大值），
        // 但「今天」与「未来时间戳」可能同时存在（用户改过系统时间）。
        while (index < days.size && days[index] > expected) index++

        while (index < days.size && days[index] == expected) {
            count++
            if (count >= STREAK_SCAN_DAYS) return STREAK_SCAN_DAYS

            expected = expected.minusDays(1)
            index++
        }
        return count
    }

    /** 降序列表里的二分查找。列表通常只有几十项，但二分零成本 */
    private fun List<LocalDate>.binarySearchDescending(target: LocalDate): Boolean {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = this[mid].compareTo(target)
            when {
                cmp == 0 -> return true
                // 降序：mid 比 target 大 → target 在右边
                cmp > 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return false
    }
}









data class TodayStats(
    val sessionCount: Int,
    val focusMinutes: Int,
    val distanceKm: Int,
    val streakDays: Int,

    val flewToday: Boolean,
) {
    /** 主标题：连续天数。口径 A（鼓励型） */
    val title: String
        get() = when {
            streakDays <= 0 -> "还没有开始"
            streakDays == 1 -> "连续 1 天"
            else -> "连续 $streakDays 天"
        }








    val subtitle: String
        get() = if (!flewToday && streakDays > 0) "今天还没飞"
        else if (sessionCount == 0) "点一下开始专注"
        else WidgetText.todaySubtitle(focusMinutes, distanceKm)

    /** 大字区：有飞行时显示分钟数，没有时显示 0 */
    val bigNumber: String get() = if (sessionCount == 0) "0" else focusMinutes.toString()


    val bigNumberUnit: String get() = "分钟"








    val spokenSummary: String
        get() = buildString {
            append(title).append("，")
            if (flewToday) {
                append("今天专注 ").append(focusMinutes).append(" 分钟，")
                append(sessionCount).append(" 趟，")
                append(WidgetText.formatDistance(distanceKm))
            } else {
                append("今天还没飞")
                if (streakDays > 0) append("，别断了")
            }
        }

    companion object {
        /** 空态。新装用户 / 一条记录都没有时用它，保证界面不出现 "0 天" */
        val EMPTY: TodayStats = TodayStats(
            sessionCount = 0,
            focusMinutes = 0,
            distanceKm = 0,
            streakDays = 0,
            flewToday = false,
        )
    }
}
