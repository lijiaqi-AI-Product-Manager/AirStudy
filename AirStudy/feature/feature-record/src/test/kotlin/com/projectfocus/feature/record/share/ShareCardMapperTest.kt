package com.projectfocus.feature.record.share

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.feature.completion.pass.BoardingPassData
import com.projectfocus.feature.record.detail.RecordDetailBuilder
import org.junit.Test
import java.time.ZoneId


















class ShareCardMapperTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val startMs = 1792972800_000L // 2026-10-26 08:00 CST

    private object FakeTemplates : ShareCardSpec.Templates {
        override fun text(from: String, to: String, distance: String, focus: String): String =
            "我完成了一趟 $from → $to 的专注飞行，飞行 $distance km，专注 $focus。"

        override fun subject(from: String, to: String): String = "$from-$to"

        override fun cardDesc(from: String, to: String, flightNo: String): String =
            "$from-$to-$flightNo"

        override val unknownPlace: String = "未知地点"
    }

    private fun entity(
        id: String = "rec-1",
        status: String = "COMPLETED",
        durationMinutes: Int = 45,
        actualFocusMinutes: Int = 45,
        endTimeMillis: Long? = startMs + 45 * 60_000L,
        seat: String? = "12A",
        startTimeMillis: Long = startMs,
    ) = FlightHistoryEntity(
        id = id,
        roomId = null,
        focusType = "SOLO",
        roomParticipantCount = null,
        departureAirportCode = "PEK",
        departureAirportName = "北京首都国际机场",
        departureCity = "北京",
        departureCountry = "中国",
        departureLatitude = 40.08,
        departureLongitude = 116.58,
        destinationAirportCode = "KIX",
        destinationAirportName = "关西国际机场",
        destinationCity = "大阪",
        destinationCountry = "日本",
        destinationLatitude = 34.43,
        destinationLongitude = 135.24,
        startTimeMillis = startTimeMillis,
        endTimeMillis = endTimeMillis,
        durationMinutes = durationMinutes,
        actualFocusMinutes = actualFocusMinutes,
        distanceKm = durationMinutes / 60.0 * 800.0,
        status = status,
        categoryId = 3L,
        seat = seat,
        hasAnnouncedDeparture = true,
        hasAnnouncedDescent = true,
        hasAnnouncedArrival = true,
        logJson = null,
        isSynced = false,
        createdAt = startTimeMillis,
        updatedAt = startTimeMillis,
    )

    private fun detailOf(e: FlightHistoryEntity) =
        RecordDetailBuilder.build(e, zone, nowMillis = startMs + 86_400_000L)

    // ── build() ────────────────────────────────────────────

    @Test
    fun `完成的记录能构造出登机牌数据`() {
        val e = entity()
        val pass = ShareCardMapper.build(e, detailOf(e), zone)
        assertThat(pass).isNotNull()
        assertThat(pass!!.departureCode).isEqualTo("PEK")
        assertThat(pass.arrivalCode).isEqualTo("KIX")
        assertThat(pass.seat).isEqualTo("12A")
        assertThat(pass.abandoned).isFalse()
    }

    @Test
    fun `mapper 优先使用 entity 的座位`() {
        val e = entity(seat = "30F")
        val stale = detailOf(e).copy(seat = "12A")
        assertThat(ShareCardMapper.build(e, stale, zone)!!.seat).isEqualTo("30F")
    }

    @Test
    fun `entity 无座位时不回退 detail`() {
        for (seat in listOf(null, "", "  ", BoardingPassData.NO_SEAT)) {
            val e = entity(seat = seat)
            val stale = detailOf(e).copy(seat = "12A")
            assertThat(ShareCardMapper.build(e, stale, zone)!!.seat)
                .isEqualTo(BoardingPassData.NO_SEAT)
        }
    }

    @Test
    fun `票面和分享正文使用持久化路线里程而不是分钟重算或旧详情`() {
        val stale = detailOf(entity().copy(distanceKm = 600.0))
        for ((distance, text) in listOf(1234.5 to "1234.5", 1234.567 to "1234.6", 0.0 to "0.0")) {
            val current = entity().copy(distanceKm = distance)
            val pass = ShareCardMapper.build(current, stale, zone)!!
            val spec = ShareCardMapper.spec(pass, stale, current, zone, FakeTemplates)

            assertThat(pass.distanceText).isEqualTo("$text km")
            assertThat(spec.text).contains("飞行 $text km")
            assertThat(spec.text).doesNotContain("600.0 km")
        }
    }

    @Test
    fun `损坏里程不构造看似有效的分享票面`() {
        for (distance in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            val current = entity().copy(distanceKm = distance)
            assertThat(ShareCardMapper.build(current, detailOf(current), zone)).isNull()
        }
    }

    @Test
    fun `取消的记录不产出登机牌数据`() {

        //   调用方必须显式处理，不能因为"反正有数据"就把取消的也分享出去。
        val e = entity(status = "CANCELLED")
        assertThat(ShareCardMapper.build(e, detailOf(e), zone)).isNull()
    }

    @Test
    fun `原记录已取消时不使用陈旧完成详情准许分享`() {
        val staleCompleted = detailOf(entity())
        val cancelled = entity(status = "CANCELLED").copy(distanceKm = 1234.5)
        assertThat(ShareCardMapper.build(cancelled, staleCompleted, zone)).isNull()
    }

    @Test
    fun `状态异常的记录不产出登机牌数据`() {

        //   而 `RecordDetailBuilder` 会给它加上「（状态异常）」后缀。
        //   这类记录**看起来**是 CANCELLED，但 `canShare` 为 false ——
        //   这条测试就是钉住"看起来像取消"与"能不能分享"不是同一件事。
        val e = entity(status = "SOMETHING_WEIRD")
        assertThat(ShareCardMapper.build(e, detailOf(e), zone)).isNull()
    }

    @Test
    fun `航班号与登机牌口径一致（用计划时长而不是实际）`() {

        //   如果这边用 actualFocusMinutes 算，会得到 PF2630，
        //   而到站页的登机牌是 PF2645 —— 两处不一致且只在提前下机时出现。
        val e = entity(durationMinutes = 45, actualFocusMinutes = 30)
        val pass = ShareCardMapper.build(e, detailOf(e), zone)!!
        assertThat(pass.flightNo).isEqualTo("PF2645")
    }

    @Test
    fun `endTimeMillis 缺失时用计划时长推算落地时刻`() {
        val e = entity(endTimeMillis = null, durationMinutes = 45)
        val pass = ShareCardMapper.build(e, detailOf(e), zone)!!
        // 08:00 起飞 + 45 分钟 = 08:45
        assertThat(pass.departureTime).isEqualTo("08:00")
        assertThat(pass.arrivalTime).isEqualTo("08:45")
    }

    @Test
    fun `endTimeMillis 与计划时长都缺时退化为零长度而不是崩溃`() {

        //   `BoardingPassData.from` 会得到相同的起降时间 —— 难看，但不崩。
        //   这条测试的价值是"确认它确实不崩"，而不是"这个结果是对的"。
        val e = entity(endTimeMillis = null, durationMinutes = 0, actualFocusMinutes = 0)
        val pass = ShareCardMapper.build(e, detailOf(e), zone)
        // duration=0 的记录 detail.canShare 仍为 true（COMPLETED），
        // 所以这里应该有数据，只是时间零长度。
        assertThat(pass).isNotNull()
        assertThat(pass!!.departureTime).isEqualTo(pass.arrivalTime)
    }

    // ── spec() ─────────────────────────────────────────────

    private fun specOf(e: FlightHistoryEntity): ShareCardSpec {
        val d = detailOf(e)
        val pass = ShareCardMapper.build(e, d, zone)!!
        return ShareCardMapper.spec(pass, d, e, zone, FakeTemplates)
    }

    @Test
    fun `正文里里程只有一个 km 单位`() {

        //   data.distanceText 是 "600.0 km"，模板里已经写了 " km"。
        val s = specOf(entity(durationMinutes = 45))
        assertThat(s.text).doesNotContain("km km")
        assertThat(s.text).contains("飞行 600.0 km")
    }

    @Test
    fun `正文里的专注时长用中文写法`() {

        val s = specOf(entity(durationMinutes = 45, actualFocusMinutes = 30))
        assertThat(s.text).contains("专注 30 分钟")
    }

    @Test
    fun `正文里出现真实城市名而不是三字码`() {
        val s = specOf(entity())
        assertThat(s.text).contains("北京 → 大阪")
        assertThat(s.text).doesNotContain("PEK → KIX")
    }

    @Test
    fun `分享规格的航班号与登机牌一致`() {
        val e = entity(durationMinutes = 90, actualFocusMinutes = 90)
        val d = detailOf(e)
        val pass = ShareCardMapper.build(e, d, zone)!!
        val s = ShareCardMapper.spec(pass, d, e, zone, FakeTemplates)
        assertThat(s.flightNo).isEqualTo(pass.flightNo)
    }

    @Test
    fun `文件名日期取自记录起飞时刻`() {
        val s = specOf(entity())
        assertThat(s.fileName).isEqualTo("boarding-pass-20261026-PF2645.png")
    }

    @Test
    fun `城市缺失时正文用三字码兜底而不是留空`() {
        val e = entity().copy(departureCity = "", destinationCity = "")
        val s = specOf(e)
        // RecordDetailBuilder 会把空城市兜成三字码，所以这里是 PEK/KIX
        assertThat(s.text).contains("PEK → KIX")
    }
}
