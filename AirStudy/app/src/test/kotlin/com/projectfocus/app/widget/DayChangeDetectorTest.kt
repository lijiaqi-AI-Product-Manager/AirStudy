package com.projectfocus.app.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime



















class DayChangeDetectorTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    /** 可变的假时钟 */
    private class FakeClock(var nowMs: Long) {
        fun set(y: Int, m: Int, d: Int, h: Int, min: Int, zone: ZoneId) {
            nowMs = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
        }
    }

    private fun detector(clock: FakeClock, zone: ZoneId = ZoneId.of("Asia/Shanghai")) =
        DayChangeDetector(zoneProvider = { zone }, clock = { clock.nowMs })

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, shanghai).toInstant().toEpochMilli()

    // ==================================================================

    @Test
    fun `第一次调用必须返回 true`() {

        //   进程刚起来时，桌面上那个日期是**上一个进程**留下的，不可信 ——
        //   可能是昨天的。若初值设成"今天"，这条会返回 false，冷启动漏刷一次。
        val clock = FakeClock(at(2026, 10, 27, 9, 0))
        assertThat(detector(clock).shouldRefresh()).isTrue()
    }

    @Test
    fun `同一天内反复调用只返回一次 true`() {
        val clock = FakeClock(at(2026, 10, 27, 9, 0))
        val d = detector(clock)

        assertThat(d.shouldRefresh()).isTrue()      // 第一次
        clock.set(2026, 10, 27, 12, 0, shanghai)
        assertThat(d.shouldRefresh()).isFalse()
        clock.set(2026, 10, 27, 23, 59, shanghai)
        assertThat(d.shouldRefresh()).isFalse()


        //   从 09:00 到 23:59 已经 14 小时，这条就会红。
    }

    @Test
    fun `跨过零点返回 true`() {
        val clock = FakeClock(at(2026, 10, 27, 23, 50))
        val d = detector(clock)
        assertThat(d.shouldRefresh()).isTrue()

        // 10 分钟后 —— 只差 10 分钟，但日期变了
        clock.set(2026, 10, 28, 0, 0, shanghai)
        assertThat(d.shouldRefresh()).isTrue()

        //   10 分钟远小于任何合理阈值，但确实必须刷。
    }

    @Test
    fun `跨两次零点只返回两次 true`() {
        val clock = FakeClock(at(2026, 10, 27, 23, 59))
        val d = detector(clock)
        assertThat(d.shouldRefresh()).isTrue()

        clock.set(2026, 10, 28, 0, 1, shanghai)
        assertThat(d.shouldRefresh()).isTrue()

        clock.set(2026, 10, 29, 0, 1, shanghai)
        assertThat(d.shouldRefresh()).isTrue()

        // 共 3 次 true（含首次），中间没有多余的
        clock.set(2026, 10, 29, 8, 0, shanghai)
        assertThat(d.shouldRefresh()).isFalse()
    }

    @Test
    fun `同一时刻在不同时区可能属于不同的日期`() {
        // 2026-10-27 23:30 北京时间 = 同日 15:30 UTC
        val ms = at(2026, 10, 27, 23, 30)
        val clock = FakeClock(ms)

        // 东八区：10 月 27 日
        val cn = detector(clock, shanghai)
        assertThat(cn.shouldRefresh()).isTrue()
        // UTC：同一天（15:30），所以第二次不该刷
        clock.set(2026, 10, 27, 15, 30, ZoneId.of("UTC"))
        // 注意：上面的 set 用 UTC 时区构造，得到的毫秒值与 ms 相同
        assertThat(cn.shouldRefresh()).isFalse()

        // 而一个用 UTC 判定日期的检测器，在此刻仍是 27 日；
        // 30 分钟后东八区已经是 28 日，UTC 还是 27 日 —— 两者必须分歧。
        clock.nowMs = at(2026, 10, 28, 0, 10)   // 北京 28 日 00:10 = UTC 27 日 16:10
        val utc = detector(clock, ZoneId.of("UTC"))
        assertThat(utc.shouldRefresh()).isTrue()   // 首次，无参考价值
        assertThat(cn.shouldRefresh()).isTrue()     // 东八区：确实跨日了

        //   在 CI 机器是 UTC 的情况下，上面这条 cn 的断言就会红。
    }

    @Test
    fun `peekWouldRefresh 不影响内部状态`() {
        val clock = FakeClock(at(2026, 10, 27, 9, 0))
        val d = detector(clock)
        // 还没刷过 → 会刷
        assertThat(d.peekWouldRefresh()).isTrue()
        assertThat(d.peekWouldRefresh()).isTrue()       // 连看两次仍然 true
        assertThat(d.shouldRefresh()).isTrue()          // 真刷了
        assertThat(d.peekWouldRefresh()).isFalse()
    }

    @Test
    fun `markRefreshed 之后同一天不再触发`() {

        //   这里只需把状态对齐，不要再刷一遍。
        val clock = FakeClock(at(2026, 10, 27, 9, 0))
        val d = detector(clock)
        d.markRefreshed()
        assertThat(d.shouldRefresh()).isFalse()
    }

    @Test
    fun `跨年也按日期判断而不是月份`() {
        // 12-31 23:59 → 01-01 00:01。日期确实变了，必须刷。
        // 用"月份变了""年份变了"之类的粗糙判断会漏掉 10-27 → 10-28 这种。
        val clock = FakeClock(at(2026, 12, 31, 23, 59))
        val d = detector(clock)
        assertThat(d.shouldRefresh()).isTrue()

        clock.set(2027, 1, 1, 0, 1, shanghai)
        assertThat(d.shouldRefresh()).isTrue()

        assertThat(LocalDate.of(2027, 1, 1)).isGreaterThan(LocalDate.of(2026, 12, 31))
    }

    @Test
    fun `夏令时切换当天判断仍然正确`() {
        // 美东 2026-03-08 只有 23 小时。用"加 24 小时"推算明天的实现会在这里错。
        val ny = ZoneId.of("America/New_York")
        val clock = FakeClock(
            ZonedDateTime.of(2026, 3, 7, 23, 30, 0, 0, ny).toInstant().toEpochMilli(),
        )
        val d = detector(clock, ny)
        assertThat(d.shouldRefresh()).isTrue()

        // 3 月 8 日 00:30（这一天只有 23 小时，凌晨 2 点直接跳到 3 点）
        clock.nowMs = ZonedDateTime.of(2026, 3, 8, 0, 30, 0, 0, ny).toInstant().toEpochMilli()
        assertThat(d.shouldRefresh()).isTrue()
    }
}
