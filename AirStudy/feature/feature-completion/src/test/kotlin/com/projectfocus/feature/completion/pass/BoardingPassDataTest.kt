package com.projectfocus.feature.completion.pass

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime


class BoardingPassDataTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    /** 2026-10-26 14:00:00 上海时间 */
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, shanghai).toInstant().toEpochMilli()

    private fun pass(
        startMs: Long = ts(2026, 10, 26, 14, 0),
        planned: Int = 45,
        actual: Int = 45,
        distanceKm: Double = 600.0,
        abandoned: Boolean = false,
        seat: String? = null,
        passengerName: String = "",
        boardingTime: String? = null,
    ) = BoardingPassData.from(
        departureCode = "PEK", departureCity = "北京",
        arrivalCode = "KIX", arrivalCity = "大阪",
        startWallMs = startMs,
        endWallMs = startMs + planned * 60_000L,
        zone = shanghai,
        plannedMinutes = planned,
        actualFocusMinutes = actual,
        distanceKm = distanceKm,
        seat = seat,
        abandoned = abandoned,
        passengerName = passengerName,
        boardingTime = boardingTime,
    )

    // ── 1. 时间 ────────────────────────────────────────────

    @Test
    fun `出发与到达时间按本地时区格式化`() {
        val p = pass()
        assertThat(p.departureTime).isEqualTo("14:00")
        assertThat(p.arrivalTime).isEqualTo("14:45")
        assertThat(p.date).isEqualTo("2026-10-26")
    }

    @Test
    fun `跨零点航班的两侧时间正确且日期取起飞日`() {
        // 23:30 起飞，45 分钟后 00:15 降落 —— 日期跨了
        val p = pass(startMs = ts(2026, 10, 26, 23, 30))
        assertThat(p.departureTime).isEqualTo("23:30")
        assertThat(p.arrivalTime).isEqualTo("00:15")


        //   一晚跨零点的航班被归到第二天。
        assertThat(p.date).isEqualTo("2026-10-26")
    }

    @Test
    fun `时区不同则时间不同，证明没用 UTC`() {

        //   同一时刻，上海是 14:00，UTC 是 06:00。
        val start = ts(2026, 10, 26, 14, 0)
        val cn = BoardingPassData.from(
            departureCode = "PEK", departureCity = "北京",
            arrivalCode = "KIX", arrivalCity = "大阪",
            startWallMs = start, endWallMs = start + 45 * 60_000L,
            zone = shanghai, plannedMinutes = 45, actualFocusMinutes = 45,
            distanceKm = 600.0,
        )
        val utc = BoardingPassData.from(
            departureCode = "PEK", departureCity = "北京",
            arrivalCode = "KIX", arrivalCity = "大阪",
            startWallMs = start, endWallMs = start + 45 * 60_000L,
            zone = ZoneId.of("UTC"), plannedMinutes = 45, actualFocusMinutes = 45,
            distanceKm = 600.0,
        )
        assertThat(cn.departureTime).isEqualTo("14:00")
        assertThat(utc.departureTime).isEqualTo("06:00")
    }

    @Test
    fun `到达时间用真实降落时刻而不是起飞加计划时长`() {
        // 用户提前下机：飞了 30 分钟就落了（计划 45 分钟）
        val start = ts(2026, 10, 26, 14, 0)
        val p = BoardingPassData.from(
            departureCode = "PEK", departureCity = "北京",
            arrivalCode = "KIX", arrivalCity = "大阪",
            startWallMs = start,
            endWallMs = start + 30 * 60_000L,   // 真实 14:30 落地
            zone = shanghai,
            plannedMinutes = 45,
            actualFocusMinutes = 30,
            distanceKm = 600.0,
        )

        //   用户明明 14:30 就下机了。
        assertThat(p.arrivalTime).isEqualTo("14:30")
    }

    // ── 2. 航班号的唯一性 ──────────────────────────────────

    @Test
    fun `同样的输入永远产生同一个航班号`() {


        val a = pass()
        val b = pass()
        assertThat(a.flightNo).isEqualTo(b.flightNo)
    }

    @Test
    fun `航班号编码了日期与时长`() {
        val p = pass(startMs = ts(2026, 10, 26, 14, 0), planned = 45)
        assertThat(p.flightNo).isEqualTo("PF2645")
    }

    @Test
    fun `不同日期的航班号不同`() {
        val d26 = pass(startMs = ts(2026, 10, 26, 14, 0))
        val d27 = pass(startMs = ts(2026, 10, 27, 14, 0))
        assertThat(d26.flightNo).isNotEqualTo(d27.flightNo)
    }

    @Test
    fun `不同时长的航班号不同`() {
        val m45 = pass(planned = 45)
        val m60 = pass(planned = 60)
        assertThat(m45.flightNo).isNotEqualTo(m60.flightNo)
    }

    @Test
    fun `航班号里的日期与登机牌日期一致`() {

        //   航班号说 26 号，日期栏必须也是 26 号。
        for (day in 1..31) {
            val p = pass(startMs = ts(2026, 10, day, 12, 0))
            val dayFromNo = p.flightNo.removePrefix("PF").take(2).toInt()
            val dayFromDate = p.date.substringAfterLast("-").toInt()
            assertThat(dayFromNo).isEqualTo(dayFromDate)
        }
    }

    @Test
    fun `时长超过两位时被夹取而不是产生五位航班号`() {
        // 3 小时 = 180 分钟，两位数放不下
        val p = pass(planned = 180)
        assertThat(p.flightNo.length).isEqualTo(6)   // PF + 2 + 2
        assertThat(p.flightNo).isEqualTo("PF2699")
    }

    // ── 3. 里程与专注时长 ──────────────────────────────────

    @Test
    fun `里程显示记录快照而不是按计划时长重算`() {
        val p = pass(planned = 45, distanceKm = 1234.5)
        assertThat(p.distanceText).isEqualTo("1234.5 km")
    }

    @Test
    fun `提前下机不会让里程变短`() {

        val full = pass(planned = 45, actual = 45, distanceKm = 1234.5)
        val early = pass(planned = 45, actual = 20, distanceKm = 1234.5)
        assertThat(early.distanceText).isEqualTo(full.distanceText)
        // 但专注时长要如实变短
        assertThat(early.focusDurationText).isEqualTo("20m")
        assertThat(full.focusDurationText).isEqualTo("45m")
    }

    @Test
    fun `记录零里程和小数里程原样按显示精度格式化`() {
        assertThat(pass(planned = 45, distanceKm = 0.0).distanceText).isEqualTo("0.0 km")
        assertThat(pass(planned = 45, distanceKm = 1234.567).distanceText).isEqualTo("1234.6 km")
        assertThat(pass(planned = 90, actual = 5, abandoned = true, distanceKm = 1234.5).distanceText)
            .isEqualTo("1234.5 km")
    }

    @Test
    fun `不把损坏里程格式化成有效票面`() {
        for (distance in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                pass(distanceKm = distance)
            }
        }
    }

    @Test
    fun `专注时长格式在一小时内是纯分钟，超过一小时带小时`() {
        assertThat(pass(planned = 45, actual = 45).focusDurationText).isEqualTo("45m")
        assertThat(pass(planned = 90, actual = 90).focusDurationText).isEqualTo("1h 30m")
        assertThat(pass(planned = 60, actual = 60).focusDurationText).isEqualTo("1h")
    }

    @Test
    fun `完成度比例反映真实专注与计划之比`() {
        assertThat(pass(planned = 45, actual = 45).completionRatio).isEqualTo(1f)
        assertThat(pass(planned = 45, actual = 15).completionRatio).isWithin(1e-4f).of(1f / 3f)
    }

    @Test
    fun `实际专注超过计划时完成度被夹到 1`() {
        // 恢复会话后可能算多几分钟，UI 上不该出现 110% 的进度
        val p = pass(planned = 45, actual = 50)
        assertThat(p.completionRatio).isEqualTo(1f)
    }

    // ── 4. 印章与座位 ──────────────────────────────────────

    @Test
    fun `放弃与完成的印章文字不同`() {
        assertThat(pass(abandoned = false).stampText).isEqualTo("COMPLETED")
        assertThat(pass(abandoned = true).stampText).isEqualTo("CANCELLED")
    }

    @Test
    fun `无座位时用短横占位而不是空串`() {

        assertThat(pass(seat = null).seat).isEqualTo("-")
        assertThat(pass(seat = "").seat).isEqualTo("-")
        assertThat(pass(seat = "   ").seat).isEqualTo("-")
        assertThat(pass(seat = "3A").seat).isEqualTo("3A")
    }

    // ── 5. 缺码兜底 ────────────────────────────────────────

    @Test
    fun `缺三字码时用三个短横而不是空白`() {
        val p = BoardingPassData.from(
            departureCode = "", departureCity = "北京",
            arrivalCode = null, arrivalCity = null,
            startWallMs = ts(2026, 10, 26, 14, 0),
            endWallMs = ts(2026, 10, 26, 14, 45),
            zone = shanghai, plannedMinutes = 45, actualFocusMinutes = 45,
            distanceKm = 600.0,
        )
        assertThat(p.departureCode).isEqualTo("---")
        assertThat(p.arrivalCode).isEqualTo("---")
        // 城市为 null 时是空串（UI 直接 setText，不必判空）
        assertThat(p.arrivalCity).isEmpty()
    }


    @Test
    fun `登机时间默认等于起飞时间`() {
        val p = pass()
        assertThat(p.boardingTime).isEqualTo(p.departureTime)
        assertThat(p.boardingTime).isEqualTo("14:00")
    }

    @Test
    fun `显式登机时间与旅客姓名被保留`() {
        val p = pass(passengerName = "LI JIA", boardingTime = "08:20")
        assertThat(p.boardingTime).isEqualTo("08:20")
        assertThat(p.passengerName).isEqualTo("LI JIA")
    }

    @Test
    fun `旅客姓名前后空白被裁剪`() {
        val p = pass(passengerName = "  LI JIA  ")
        assertThat(p.passengerName).isEqualTo("LI JIA")
    }

    @Test
    fun `空登机时间回退到起飞时间`() {
        val p = pass(boardingTime = "   ")
        assertThat(p.boardingTime).isEqualTo("14:00")
    }
}
