package com.projectfocus.widget.snapshot

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime




















class TodayCalculatorTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")

    /** 构造某个本地时刻的毫秒 */
    private fun ms(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
        zone: ZoneId = shanghai,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    // ==================================================================
    // 日界
    // ==================================================================

    @Test
    fun `今日零点按本地时区算而不是 UTC`() {
        // 2026-10-26 23:30 上海 = 2026-10-26 15:30 UTC
        val now = ms(2026, 10, 26, 23, 30)
        val start = TodayCalculator.dayStartMillis(now, shanghai)
        assertThat(start).isEqualTo(ms(2026, 10, 26, 0, 0))
        // 同一个时刻在纽约是 11:30，日界 00:00 纽约
        val nyStart = TodayCalculator.dayStartMillis(now, newYork)
        assertThat(nyStart).isEqualTo(ms(2026, 10, 26, 0, 0, newYork))

        assertThat(start).isNotEqualTo(nyStart)
    }

    @Test
    fun `明日零点就是今日零点加一天`() {
        val now = ms(2026, 10, 26, 9, 15)
        val start = TodayCalculator.dayStartMillis(now, shanghai)
        val next = TodayCalculator.nextDayStartMillis(now, shanghai)
        assertThat(next).isEqualTo(ms(2026, 10, 27, 0, 0))
        assertThat(next - start).isEqualTo(86_400_000L)
    }









    @Test
    fun `夏令时那天的区间长度是 23 小时`() {
        val now = ms(2026, 3, 8, 12, 0, newYork)
        val start = TodayCalculator.dayStartMillis(now, newYork)
        val next = TodayCalculator.nextDayStartMillis(now, newYork)
        assertThat(next - start).isEqualTo(23 * 3_600_000L)
    }

    @Test
    fun `夏令时结束那天是 25 小时`() {
        // 2026-11-01 纽约回拨
        val now = ms(2026, 11, 1, 12, 0, newYork)
        val start = TodayCalculator.dayStartMillis(now, newYork)
        val next = TodayCalculator.nextDayStartMillis(now, newYork)
        assertThat(next - start).isEqualTo(25 * 3_600_000L)
    }

    // ==================================================================
    // 归一成日期
    // ==================================================================

    @Test
    fun `同一天飞两趟只算一天`() {
        val times = listOf(ms(2026, 10, 26, 8), ms(2026, 10, 26, 20))
        val days = TodayCalculator.distinctDaysDescending(times, shanghai)
        assertThat(days).containsExactly(LocalDate.of(2026, 10, 26))
    }

    @Test
    fun `降序排列`() {
        val times = listOf(
            ms(2026, 10, 24, 8),
            ms(2026, 10, 26, 8),
            ms(2026, 10, 25, 8),
        )
        val days = TodayCalculator.distinctDaysDescending(times, shanghai)
        assertThat(days).containsExactly(
            LocalDate.of(2026, 10, 26),
            LocalDate.of(2026, 10, 25),
            LocalDate.of(2026, 10, 24),
        ).inOrder()
    }

    @Test
    fun `跨零点的两趟属于不同天`() {
        val times = listOf(ms(2026, 10, 26, 23, 59), ms(2026, 10, 27, 0, 1))
        val days = TodayCalculator.distinctDaysDescending(times, shanghai)
        assertThat(days).containsExactly(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 26)).inOrder()
    }

    @Test
    fun `空列表返回空`() {
        assertThat(TodayCalculator.distinctDaysDescending(emptyList(), shanghai)).isEmpty()
    }





    @Test
    fun `非法的零与负数时间戳被丢弃`() {
        val times = listOf(0L, -1L, -86_400_000L, ms(2026, 10, 26, 8))
        val days = TodayCalculator.distinctDaysDescending(times, shanghai)
        assertThat(days).containsExactly(LocalDate.of(2026, 10, 26))
    }

    // ==================================================================
    // 连续天数 —— 核心
    // ==================================================================

    private val today = LocalDate.of(2026, 10, 26)

    @Test
    fun `今天飞了从今天开始数`() {
        val days = listOf(today, today.minusDays(1), today.minusDays(2))
        assertThat(TodayCalculator.streakDays(days, today)).isEqualTo(3)
    }







    @Test
    fun `今天没飞但昨天飞了时仍然显示连续`() {
        val days = listOf(today.minusDays(1), today.minusDays(2), today.minusDays(3))
        assertThat(TodayCalculator.streakDays(days, today)).isEqualTo(3)
    }

    @Test
    fun `昨天今天都没飞则归零`() {
        val days = listOf(today.minusDays(2), today.minusDays(3))
        assertThat(TodayCalculator.streakDays(days, today)).isEqualTo(0)
    }

    @Test
    fun `断掉的一天会截断连续`() {
        // 今天、昨天，然后缺 10-24，再有 10-23
        val days = listOf(today, today.minusDays(1), today.minusDays(3), today.minusDays(4))
        assertThat(TodayCalculator.streakDays(days, today)).isEqualTo(2)
    }

    @Test
    fun `空列表连续为零`() {
        assertThat(TodayCalculator.streakDays(emptyList(), today)).isEqualTo(0)
    }

    @Test
    fun `只有今天一天是连续一`() {
        assertThat(TodayCalculator.streakDays(listOf(today), today)).isEqualTo(1)
    }

    @Test
    fun `只有昨天一天是连续一`() {
        assertThat(TodayCalculator.streakDays(listOf(today.minusDays(1)), today)).isEqualTo(1)
    }

    @Test
    fun `四十九天不断就是四十九`() {
        val days = (0 until 49).map { today.minusDays(it.toLong()) }
        assertThat(TodayCalculator.streakDays(days, today)).isEqualTo(49)
    }





    @Test
    fun `未来的日期被跳过不影响计数`() {
        val days = listOf(today.plusDays(3), today, today.minusDays(1))
        assertThat(TodayCalculator.streakDays(days, today)).isEqualTo(2)
    }

    /** 跨月、跨年：靠 LocalDate.minusDays，不做手工日期运算 */
    @Test
    fun `跨年连续正确`() {
        val newYear = LocalDate.of(2027, 1, 1)
        val days = listOf(newYear, LocalDate.of(2026, 12, 31), LocalDate.of(2026, 12, 30))
        assertThat(TodayCalculator.streakDays(days, newYear)).isEqualTo(3)
    }







    @Test
    fun `跨夏令时的相邻两天算连续`() {
        val d = LocalDate.of(2026, 3, 9)
        val days = listOf(d, LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 7))
        assertThat(TodayCalculator.streakDays(days, d)).isEqualTo(3)
    }

    @Test
    fun `超过扫描上限时截到上限`() {
        val days = (0 until TodayCalculator.STREAK_SCAN_DAYS + 50)
            .map { today.minusDays(it.toLong()) }
        assertThat(TodayCalculator.streakDays(days, today)).isEqualTo(TodayCalculator.STREAK_SCAN_DAYS)
    }

    // ==================================================================
    // compute 组装
    // ==================================================================

    @Test
    fun `今日飞了三次组装正确`() {
        val now = ms(2026, 10, 26, 21)
        val stats = TodayCalculator.compute(
            sessionCount = 3,
            focusMinutes = 135,
            distanceKm = 1800.4,
            recentStartTimes = listOf(ms(2026, 10, 26, 8), ms(2026, 10, 26, 13), ms(2026, 10, 26, 19)),
            nowMillis = now,
            zone = shanghai,
        )
        assertThat(stats.sessionCount).isEqualTo(3)
        assertThat(stats.focusMinutes).isEqualTo(135)

        assertThat(stats.distanceKm).isEqualTo(1800)
        assertThat(stats.streakDays).isEqualTo(1)
        assertThat(stats.flewToday).isTrue()
    }

    @Test
    fun `里程四舍五入而不是截断`() {
        val now = ms(2026, 10, 26, 21)
        val s1 = TodayCalculator.compute(1, 45, 999.6, listOf(now), now, shanghai)
        assertThat(s1.distanceKm).isEqualTo(1000)
        val s2 = TodayCalculator.compute(1, 45, 999.4, listOf(now), now, shanghai)
        assertThat(s2.distanceKm).isEqualTo(999)
    }

    @Test
    fun `负的统计值被夹到零`() {
        val now = ms(2026, 10, 26, 21)

        val stats = TodayCalculator.compute(-2, -30, -100.0, emptyList(), now, shanghai)
        assertThat(stats.sessionCount).isEqualTo(0)
        assertThat(stats.focusMinutes).isEqualTo(0)
        assertThat(stats.distanceKm).isEqualTo(0)
        assertThat(stats.streakDays).isEqualTo(0)
        assertThat(stats.flewToday).isFalse()
    }

    @Test
    fun `昨天飞了今天没飞时 flewToday 为假但连续不为零`() {
        val now = ms(2026, 10, 26, 9)
        val stats = TodayCalculator.compute(
            sessionCount = 0,
            focusMinutes = 0,
            distanceKm = 0.0,
            recentStartTimes = listOf(ms(2026, 10, 25, 20), ms(2026, 10, 24, 20)),
            nowMillis = now,
            zone = shanghai,
        )
        assertThat(stats.flewToday).isFalse()
        assertThat(stats.streakDays).isEqualTo(2)
    }


    @Test
    fun `同一批时刻在东西十二区连续天数可能不同`() {
        // 2026-10-26 00:30 上海 = 2026-10-25 12:30 纽约
        val t = ms(2026, 10, 26, 0, 30, shanghai)
        val nowSh = ms(2026, 10, 26, 9, 0, shanghai)
        val statsSh = TodayCalculator.compute(1, 45, 600.0, listOf(t), nowSh, shanghai)
        assertThat(statsSh.flewToday).isTrue()

        val nowNy = ms(2026, 10, 26, 9, 0, newYork)
        val statsNy = TodayCalculator.compute(1, 45, 600.0, listOf(t), nowNy, newYork)
        // 在纽约，那趟是"昨天"飞的
        assertThat(statsNy.flewToday).isFalse()
        assertThat(statsNy.streakDays).isEqualTo(1)
    }

    // ==================================================================
    // 文案
    // ==================================================================

    @Test
    fun `空态文案不出现零天`() {
        assertThat(TodayStats.EMPTY.title).isEqualTo("还没有开始")
        assertThat(TodayStats.EMPTY.subtitle).isEqualTo("点一下开始专注")
        assertThat(TodayStats.EMPTY.bigNumber).isEqualTo("0")
        assertThat(TodayStats.EMPTY.spokenSummary).doesNotContain("0 天")
    }

    @Test
    fun `连续一天与多天文案不同`() {
        assertThat(TodayStats.EMPTY.copy(streakDays = 1).title).isEqualTo("连续 1 天")
        assertThat(TodayStats.EMPTY.copy(streakDays = 12).title).isEqualTo("连续 12 天")
    }




    @Test
    fun `今天没飞但连续不为零时副标题明说今天还没飞`() {
        val s = TodayStats(sessionCount = 0, focusMinutes = 0, distanceKm = 0, streakDays = 5, flewToday = false)
        assertThat(s.title).isEqualTo("连续 5 天")
        assertThat(s.subtitle).isEqualTo("今天还没飞")
        assertThat(s.spokenSummary).contains("今天还没飞")
        assertThat(s.spokenSummary).contains("别断了")
    }

    @Test
    fun `今天飞了副标题显示明细`() {
        val s = TodayStats(sessionCount = 2, focusMinutes = 90, distanceKm = 1200, streakDays = 6, flewToday = true)
        assertThat(s.subtitle).isEqualTo("90 分钟 · 1.2 千公里")
        assertThat(s.spokenSummary).contains("今天专注 90 分钟")
        assertThat(s.spokenSummary).contains("2 趟")
    }

    @Test
    fun `没有任何连续天数时副标题不催`() {
        val s = TodayStats(sessionCount = 0, focusMinutes = 0, distanceKm = 0, streakDays = 0, flewToday = false)
        assertThat(s.subtitle).isEqualTo("点一下开始专注")
    }

    @Test
    fun `大字与单位成对`() {
        val s = TodayStats(sessionCount = 1, focusMinutes = 45, distanceKm = 600, streakDays = 1, flewToday = true)
        assertThat(s.bigNumber).isEqualTo("45")
        assertThat(s.bigNumberUnit).isEqualTo("分钟")
    }
}
