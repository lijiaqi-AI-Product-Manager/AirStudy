package com.projectfocus.feature.record.detail

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.feature.record.list.FlightStatus
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime






















class RecordDetailBuilderTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-10-26 15:00 北京时间 */
    private val nowMs = ZonedDateTime.of(2026, 10, 26, 15, 0, 0, 0, zone)
        .toInstant().toEpochMilli()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun entity(
        id: String = "rec-1",
        startMs: Long = at(2026, 10, 26, 8, 30),
        planned: Int = 135,
        actual: Int = 125,
        status: String = "COMPLETED",
        km: Double = 1800.0,
        endMs: Long? = null,
        seat: String? = null,
        quote: String? = null,
        quoteAuthor: String? = null,
        focusType: String = "SOLO",
        participants: Int? = null,
        logJson: String? = null,
    ) = FlightHistoryEntity(
        id = id,
        roomId = null,
        focusType = focusType,
        roomParticipantCount = participants,
        departureAirportCode = "PEK",
        departureAirportName = "北京首都国际机场",
        departureCity = "北京",
        departureCountry = "中国",
        departureLatitude = 40.08,
        departureLongitude = 116.58,
        destinationAirportCode = "PVG",
        destinationAirportName = "上海浦东国际机场",
        destinationCity = "上海",
        destinationCountry = "中国",
        destinationLatitude = 31.14,
        destinationLongitude = 121.81,
        startTimeMillis = startMs,
        endTimeMillis = endMs ?: if (planned > 0) startMs + planned * 60_000L else null,
        durationMinutes = planned,
        actualFocusMinutes = actual,
        distanceKm = km,
        status = status,
        categoryId = null,
        seat = seat,
        hasAnnouncedDeparture = true,
        hasAnnouncedDescent = true,
        hasAnnouncedArrival = true,
        logJson = logJson,
        isSynced = true,
        createdAt = startMs,
        updatedAt = startMs,
        quote = quote,
        quoteAuthor = quoteAuthor,
    )

    private fun build(e: FlightHistoryEntity) = RecordDetailBuilder.build(e, zone, nowMs)

    // ══════════════════════════════════════════════════════
    // 差额：三种情况 + 数据缺失
    // ══════════════════════════════════════════════════════

    @Test
    fun `实际少于计划时说少多少`() {
        assertThat(RecordDetailBuilder.diffText(135, 125)).isEqualTo("少 10 分钟")
    }

    @Test
    fun `实际多于计划时说多多少`() {
        assertThat(RecordDetailBuilder.diffText(60, 75)).isEqualTo("多 15 分钟")
    }

    @Test
    fun `刚好相等时说刚好而不是少 0 分钟`() {

        assertThat(RecordDetailBuilder.diffText(90, 90)).isEqualTo("刚好")
    }

    @Test
    fun `差额跨小时时用小时表达`() {

        assertThat(RecordDetailBuilder.diffText(180, 90)).isEqualTo("少 1 小时 30 分")
    }

    @Test
    fun `计划时长为零时差额显示破折号而不是编一个数`() {

        assertThat(RecordDetailBuilder.diffText(0, 10)).isEqualTo("—")
        assertThat(RecordDetailBuilder.diffText(-5, 10)).isEqualTo("—")
    }

    // ══════════════════════════════════════════════════════
    // 百分比
    // ══════════════════════════════════════════════════════

    @Test
    fun `完成度百分比四舍五入到整数`() {
        assertThat(RecordDetailBuilder.completionPercent(135, 125)).isEqualTo(93)
    }

    @Test
    fun `超额完成时百分比封顶一百`() {

        //   超时的情况由 diffText 用"多 X 分钟"表达，这一处只表示完成程度。
        assertThat(RecordDetailBuilder.completionPercent(60, 90)).isEqualTo(100)
    }

    @Test
    fun `实际为零时百分比是零而不是隐藏`() {

        //   0% 是**事实**，必须如实显示。
        assertThat(RecordDetailBuilder.completionPercent(60, 0)).isEqualTo(0)
    }

    @Test
    fun `计划为零时百分比为空而不是零`() {

        //   给 0 会被读成"一分钟都没专注"，给 100 会被读成"完美完成"。
        assertThat(RecordDetailBuilder.completionPercent(0, 10)).isNull()
        assertThat(RecordDetailBuilder.completionPercent(-1, 10)).isNull()
    }

    @Test
    fun `构建出的详情在计划为零时不带百分比`() {
        val d = build(entity(planned = 0, actual = 0, endMs = at(2026, 10, 26, 8, 30)))
        assertThat(d.completionPercent).isNull()
        assertThat(d.diffText).isEqualTo("—")
    }

    // ══════════════════════════════════════════════════════
    // 状态
    // ══════════════════════════════════════════════════════

    @Test
    fun `已完成的状态文案`() {
        assertThat(build(entity(status = "COMPLETED")).statusText).isEqualTo("已完成")
    }

    @Test
    fun `已取消的状态文案`() {
        assertThat(build(entity(status = "CANCELLED")).statusText).isEqualTo("已取消")
    }

    @Test
    fun `进行中不能重飞也不能分享`() {
        val d = build(entity(status = "ONGOING"))
        assertThat(d.status).isEqualTo(FlightStatus.ONGOING)

        //   分享一张"进行中"的卡片更奇怪 —— 它随时会变。
        assertThat(d.canReplay).isFalse()
        assertThat(d.canShare).isFalse()
    }

    @Test
    fun `未知状态要标出异常而不是冒充已取消`() {

        //   但**显示**不能跟着降级 —— 否则用户以为是真取消了。
        val d = build(entity(status = "WEIRD_VALUE_FROM_SYNC"))
        assertThat(d.status).isEqualTo(FlightStatus.CANCELLED)
        assertThat(d.statusText).isEqualTo("已取消（状态异常）")

        assertThat(d.canReplay).isFalse()
    }

    @Test
    fun `已完成且状态正常时才给重飞和分享`() {
        val d = build(entity(status = "COMPLETED"))
        assertThat(d.canReplay).isTrue()
        assertThat(d.canShare).isTrue()
    }

    // ══════════════════════════════════════════════════════
    // 时间区间
    // ══════════════════════════════════════════════════════

    @Test
    fun `时间区间用破折号连接起止时刻`() {
        val d = build(entity())
        assertThat(d.timeRangeText).isEqualTo("08:30 — 10:45")
    }

    @Test
    fun `结束时刻缺失时用计划时长反推`() {
        val zdt = java.time.ZonedDateTime.of(2026, 10, 26, 8, 30, 0, 0, zone)
        val text = RecordDetailBuilder.timeRangeText(zdt, null, 135)
        assertThat(text).isEqualTo("08:30 — 10:45")
    }

    @Test
    fun `结束时刻缺失且时长为零时只显示开始时刻`() {

        //   但更可能是数据缺失。只显示开始时刻是诚实的做法。
        val zdt = java.time.ZonedDateTime.of(2026, 10, 26, 8, 30, 0, 0, zone)
        assertThat(RecordDetailBuilder.timeRangeText(zdt, null, 0)).isEqualTo("08:30")
    }

    @Test
    fun `起止时刻相同时不显示零长度区间`() {
        val zdt = java.time.ZonedDateTime.of(2026, 10, 26, 8, 30, 0, 0, zone)
        assertThat(RecordDetailBuilder.timeRangeText(zdt, zdt, 60)).isEqualTo("08:30")
    }

    @Test
    fun `跨零点的时间区间照常显示两位时刻`() {

        //   跨日不能显示成 `23:30 — 01:00`？**可以**，这是最常见也最自然的写法。
        //   （机场大屏、登机牌都是这么写的。）真正要防的是"归到前一天"那种分组错误。
        val zdt = java.time.ZonedDateTime.of(2026, 10, 26, 23, 30, 0, 0, zone)
        val text = RecordDetailBuilder.timeRangeText(zdt, zdt.plusMinutes(90), 90)
        assertThat(text).isEqualTo("23:30 — 01:00")
    }

    // ══════════════════════════════════════════════════════
    // 日期：年份只在跨年时出现
    // ══════════════════════════════════════════════════════

    @Test
    fun `同一年内的日期不带年份`() {
        val start = ZonedDateTime.of(2026, 10, 26, 8, 30, 0, 0, zone)
        val now = ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, zone)
        assertThat(RecordDetailBuilder.dateText(start, now)).isEqualTo("10月26日 周一")
    }

    @Test
    fun `跨年的日期带年份`() {
        val start = ZonedDateTime.of(2025, 12, 31, 8, 30, 0, 0, zone)
        val now = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, zone)
        assertThat(RecordDetailBuilder.dateText(start, now)).isEqualTo("2025年12月31日 周三")
    }

    @Test
    fun `年份判据是同年而不是相差多少天`() {

        //   ① 相差 1 天，但跨年 → 必须带年份
        //   ② 相差 364 天，但同年 → 不带年份
        val dec31 = ZonedDateTime.of(2025, 12, 31, 20, 0, 0, 0, zone)
        val jan01 = ZonedDateTime.of(2026, 1, 1, 20, 0, 0, 0, zone)
        assertThat(RecordDetailBuilder.dateText(dec31, jan01)).contains("2025年")

        val jan02 = ZonedDateTime.of(2026, 1, 2, 8, 0, 0, 0, zone)
        val dec31sameYear = ZonedDateTime.of(2026, 12, 31, 8, 0, 0, 0, zone)
        assertThat(RecordDetailBuilder.dateText(jan02, dec31sameYear)).doesNotContain("2026年")
    }

    @Test
    fun `周日是第七天而不是第零天`() {

        //   用 `%7` 或 0-based 数组会整体错一位 —— 而错一位在周一~周六
        //   看起来"只是名字不对"，只有周日会数组越界。
        //   2026-10-25 是周日。
        val sunday = ZonedDateTime.of(2026, 10, 25, 8, 0, 0, 0, zone)
        val now = ZonedDateTime.of(2026, 10, 26, 8, 0, 0, 0, zone)
        assertThat(RecordDetailBuilder.dateText(sunday, now)).isEqualTo("10月25日 周日")
    }

    // ══════════════════════════════════════════════════════
    // 里程换算
    // ══════════════════════════════════════════════════════

    @Test
    fun `里程换算成巡航时长`() {
        // 1800 km / 800 km/h = 2.25 h = 135 分钟
        assertThat(RecordDetailBuilder.cruiseEquivalentText(1800.0))
            .isEqualTo("相当于巡航 2 小时 15 分")
    }

    @Test
    fun `零里程不给巡航时长`() {
        assertThat(RecordDetailBuilder.cruiseEquivalentText(0.0)).isEqualTo("—")
        assertThat(RecordDetailBuilder.cruiseEquivalentText(-5.0)).isEqualTo("—")
    }

    @Test
    fun `短途不换算成京沪航线段数`() {

        //   只会让页面显得"什么都想量化一下"。
        assertThat(RecordDetailBuilder.routeEquivalentText(30.0)).isEqualTo("—")
        assertThat(RecordDetailBuilder.routeEquivalentText(500.0)).isEqualTo("—")
    }

    @Test
    fun `长途换算成京沪航线段数`() {
        // 1800 / 1080 = 1.666... → 1.7
        assertThat(RecordDetailBuilder.routeEquivalentText(1800.0))
            .isEqualTo("约 1.7 段京沪航线")
    }

    @Test
    fun `正好半段时开始显示段数`() {

        assertThat(RecordDetailBuilder.routeEquivalentText(540.0))
            .isEqualTo("约 0.5 段京沪航线")
    }

    // ══════════════════════════════════════════════════════
    // 可选字段：空串必须变成 null
    // ══════════════════════════════════════════════════════

    @Test
    fun `座位为空串时变成 null`() {

        //   "有内容但看不见"的僵尸节点 —— TalkBack 仍会聚焦它并朗读空白。
        assertThat(build(entity(seat = "")).seat).isNull()
        assertThat(build(entity(seat = "   ")).seat).isNull()
        assertThat(build(entity(seat = "12A")).seat).isEqualTo("12A")
    }

    @Test
    fun `语录为空串时变成 null`() {
        assertThat(build(entity(quote = "", quoteAuthor = "")).quote).isNull()
        assertThat(build(entity(quote = "", quoteAuthor = "")).quoteAuthor).isNull()
    }

    @Test
    fun `语录与作者分别保留`() {
        val d = build(entity(quote = "路是自己走出来的", quoteAuthor = "鲁迅"))
        assertThat(d.quote).isEqualTo("路是自己走出来的")
        assertThat(d.quoteAuthor).isEqualTo("鲁迅")
    }

    @Test
    fun `座位两端空白被裁掉`() {
        assertThat(build(entity(seat = " 12A ")).seat).isEqualTo("12A")
    }

    // ══════════════════════════════════════════════════════
    // 联机飞行
    // ══════════════════════════════════════════════════════

    @Test
    fun `单人飞行不显示同行人数`() {
        val d = build(entity(focusType = "SOLO", participants = null))
        assertThat(d.isGroupFlight).isFalse()
        assertThat(d.participantCount).isNull()
    }

    @Test
    fun `联机飞行显示同行人数`() {
        val d = build(entity(focusType = "GROUP", participants = 4))
        assertThat(d.isGroupFlight).isTrue()
        assertThat(d.participantCount).isEqualTo(4)
    }

    @Test
    fun `联机飞行只有一人时不显示人数`() {


        val d = build(entity(focusType = "GROUP", participants = 1))
        assertThat(d.participantCount).isNull()
    }

    // ══════════════════════════════════════════════════════
    // 机场三字码缺失
    // ══════════════════════════════════════════════════════

    @Test
    fun `三字码为空时用占位符而不是空白`() {

        //   （`PEK ──✈── ` 变成 ` ──✈── PVG`，弧线跑到最左边）。
        val e = entity().copy(departureAirportCode = "", destinationAirportCode = "")
        val d = build(e)
        assertThat(d.fromCode).isEqualTo("---")
        assertThat(d.toCode).isEqualTo("---")
    }

    @Test
    fun `城市为空时退回三字码`() {
        val e = entity().copy(departureCity = "", destinationCity = "")
        val d = build(e)
        assertThat(d.fromCity).isEqualTo("PEK")
        assertThat(d.toCity).isEqualTo("PVG")
    }

    // ══════════════════════════════════════════════════════
    // 无障碍
    // ══════════════════════════════════════════════════════

    @Test
    fun `朗读头信息包含城市时间与状态`() {
        val d = build(entity())
        val spoken = d.spokenHeader
        assertThat(spoken).contains("北京")
        assertThat(spoken).contains("上海")
        assertThat(spoken).contains("10月26日")
        assertThat(spoken).contains("已完成")
    }
}
