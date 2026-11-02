package com.projectfocus.feature.record.list

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.entity.FlightHistoryEntity
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime


















class RecordListBuilderTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-10-26 15:00 北京时间 */
    private val now = ZonedDateTime.of(2026, 10, 26, 15, 0, 0, 0, zone)
        .toInstant().toEpochMilli()

    private fun at(
        y: Int, mo: Int, d: Int, h: Int, mi: Int = 0,
    ): Long = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun entity(
        id: String,
        startMs: Long,
        fromCity: String = "北京",
        toCity: String = "大阪",
        fromCode: String = "PEK",
        toCode: String = "KIX",
        planned: Int = 45,
        actual: Int = 45,
        status: String = "COMPLETED",
        country: String = "日本",
        km: Double = 600.0,
        createdAt: Long = startMs,
    ) = FlightHistoryEntity(
        id = id,
        roomId = null,
        focusType = "SOLO",
        roomParticipantCount = null,
        departureAirportCode = fromCode,
        departureAirportName = "北京首都国际机场",
        departureCity = fromCity,
        departureCountry = "中国",
        departureLatitude = 40.08,
        departureLongitude = 116.58,
        destinationAirportCode = toCode,
        destinationAirportName = "关西国际机场",
        destinationCity = toCity,
        destinationCountry = country,
        destinationLatitude = 34.43,
        destinationLongitude = 135.24,
        startTimeMillis = startMs,
        endTimeMillis = startMs + planned * 60_000L,
        durationMinutes = planned,
        actualFocusMinutes = actual,
        distanceKm = km,
        status = status,
        categoryId = null,
        seat = null,
        hasAnnouncedDeparture = true,
        hasAnnouncedDescent = true,
        hasAnnouncedArrival = true,
        logJson = null,
        isSynced = true,
        createdAt = createdAt,
        updatedAt = createdAt,
        quote = null,
        quoteAuthor = null,
    )

    // ── 空态 ───────────────────────────────────────────────

    @Test
    fun `没有记录时是 Empty 而不是空白列表`() {
        val state = RecordListBuilder.build(emptyList(), zone, now)
        assertThat(state).isEqualTo(RecordListState.Empty)
    }

    @Test
    fun `空态与无结果态必须可区分`() {

        //   用一个 bool 表达就必然有一半场景是错的。
        val empty = RecordListBuilder.build(emptyList(), zone, now)
        val noResult = RecordListBuilder.build(
            listOf(entity("a", at(2026, 10, 20, 10))),
            zone, now, keyword = "不存在的城市",
        )
        assertThat(empty).isNotEqualTo(noResult)
        assertThat(noResult).isInstanceOf(RecordListState.NoResult::class.java)
    }

    @Test
    fun `搜索无结果时回带关键词供界面显示`() {
        val state = RecordListBuilder.build(
            listOf(entity("a", at(2026, 10, 20, 10))),
            zone, now, keyword = "火星",
        ) as RecordListState.NoResult
        assertThat(state.keyword).isEqualTo("火星")
    }

    // ── 分层：按天分组 ──────────────────────────────────────

    @Test
    fun `同一天的记录归到一组`() {
        val state = RecordListBuilder.build(
            listOf(
                entity("a", at(2026, 10, 26, 9)),
                entity("b", at(2026, 10, 26, 14)),
            ),
            zone, now,
        ) as RecordListState.Content

        assertThat(state.groups).hasSize(1)
        assertThat(state.groups[0].items).hasSize(2)
        assertThat(state.groups[0].title).isEqualTo("今天")
    }

    @Test
    fun `不同天的记录分成多组且按日期倒序`() {
        val state = RecordListBuilder.build(
            listOf(
                entity("a", at(2026, 10, 20, 9)),
                entity("b", at(2026, 10, 26, 9)),
                entity("c", at(2026, 10, 22, 9)),
            ),
            zone, now,
        ) as RecordListState.Content

        assertThat(state.groups.map { it.date }).containsExactly(
            LocalDate.of(2026, 10, 26),
            LocalDate.of(2026, 10, 22),
            LocalDate.of(2026, 10, 20),
        ).inOrder()
    }

    @Test
    fun `组内也按起飞时刻倒序`() {
        val state = RecordListBuilder.build(
            listOf(
                entity("early", at(2026, 10, 26, 8)),
                entity("late", at(2026, 10, 26, 20)),
                entity("mid", at(2026, 10, 26, 13)),
            ),
            zone, now,
        ) as RecordListState.Content

        assertThat(state.groups[0].items.map { it.id })
            .containsExactly("late", "mid", "early").inOrder()
    }

    @Test
    fun `组的副标题带趟数与总时长`() {
        val state = RecordListBuilder.build(
            listOf(
                entity("a", at(2026, 10, 26, 9), planned = 45, actual = 45),
                entity("b", at(2026, 10, 26, 14), planned = 90, actual = 90),
            ),
            zone, now,
        ) as RecordListState.Content

        // 45 + 90 = 135 分钟 = 2 小时 15 分
        assertThat(state.groups[0].summary).isEqualTo("2 趟 · 2 小时 15 分")
    }

    // ── 陷阱 ①：日界必须按本地时区 ──────────────────────────

    @Test
    fun `深夜起飞的航班归到当天而不是前一天`() {

        //   但如果是 10-26 07:30 起飞 → UTC 是 10-25 23:30，**前一天**。
        //   用 `millis / 86400000` 分组的话，这一趟会被分到 10-25。
        val lateNight = entity("late", at(2026, 10, 26, 23, 30))
        val earlyMorning = entity("early", at(2026, 10, 26, 7, 30))

        val state = RecordListBuilder.build(
            listOf(lateNight, earlyMorning),
            zone, now,
        ) as RecordListState.Content

        // 两趟都是 10-26，必须在同一组
        assertThat(state.groups).hasSize(1)
        assertThat(state.groups[0].date).isEqualTo(LocalDate.of(2026, 10, 26))
        assertThat(state.groups[0].items).hasSize(2)
    }

    @Test
    fun `换一个时区分组结果会变`() {
        // 10-26 07:30 北京时间 = 10-25 23:30 UTC
        val ms = at(2026, 10, 26, 7, 30)
        val cn = RecordListBuilder.build(listOf(entity("a", ms)), zone, now)
            as RecordListState.Content
        val utc = RecordListBuilder.build(listOf(entity("a", ms)), ZoneId.of("UTC"), now)
            as RecordListState.Content

        assertThat(cn.groups[0].date).isEqualTo(LocalDate.of(2026, 10, 26))

        //   如果两个时区结果一样，说明时区参数被忽略了。
        assertThat(utc.groups[0].date).isEqualTo(LocalDate.of(2026, 10, 25))
    }

    // ── 陷阱 ②：相对日期的措辞 ─────────────────────────────

    @Test
    fun `相对日期的边界`() {
        val today = LocalDate.of(2026, 10, 26)
        assertThat(RecordListBuilder.relativeDayTitle(today, today)).isEqualTo("今天")
        assertThat(
            RecordListBuilder.relativeDayTitle(today.minusDays(1), today),
        ).isEqualTo("昨天")
        assertThat(
            RecordListBuilder.relativeDayTitle(today.minusDays(2), today),
        ).isEqualTo("10月24日")
        // 跨年时带年份
        assertThat(
            RecordListBuilder.relativeDayTitle(LocalDate.of(2025, 12, 31), today),
        ).isEqualTo("2025年12月31日")
    }

    @Test
    fun `未来日期不显示明天`() {

        //   未来日期可能来自：改系统时间、跨时区、云端同步了别人设备的时钟。
        val today = LocalDate.of(2026, 10, 26)
        val future = RecordListBuilder.relativeDayTitle(today.plusDays(1), today)
        assertThat(future).isNotEqualTo("明天")
        assertThat(future).isEqualTo("10月27日")
    }

    // ── 陷阱 ③：汇总口径 ────────────────────────────────────

    @Test
    fun `汇总只统计已完成的航班`() {
        val state = RecordListBuilder.build(
            listOf(
                entity("ok", at(2026, 10, 26, 9), planned = 45, actual = 45, km = 600.0),
                entity("cx", at(2026, 10, 25, 9), planned = 45, actual = 5,
                    status = "CANCELLED", km = 600.0),
            ),
            zone, now,
        ) as RecordListState.Content


        //   取消意味着没飞成。
        assertThat(state.summary.flightCount).isEqualTo(1)
        assertThat(state.summary.totalDistanceText).isEqualTo("600.0")
        assertThat(state.summary.totalHoursText).isEqualTo("45 分钟")
    }

    @Test
    fun `进行中的航班也不计入汇总但会出现在列表里`() {
        val state = RecordListBuilder.build(
            listOf(
                entity("done", at(2026, 10, 26, 9), status = "COMPLETED"),
                entity("now", at(2026, 10, 26, 14), status = "ONGOING"),
            ),
            zone, now,
        ) as RecordListState.Content

        assertThat(state.summary.flightCount).isEqualTo(1)
        // 但列表里两条都在 —— 用户要能看到"正在飞"
        assertThat(state.groups[0].items).hasSize(2)
        assertThat(state.groups[0].items.count { it.status == FlightStatus.ONGOING })
            .isEqualTo(1)
    }

    @Test
    fun `国家数按目的地去重`() {
        val state = RecordListBuilder.build(
            listOf(
                entity("a", at(2026, 10, 26, 9), country = "日本"),
                entity("b", at(2026, 10, 25, 9), country = "日本"),
                entity("c", at(2026, 10, 24, 9), country = "韩国"),
            ),
            zone, now,
        ) as RecordListState.Content
        assertThat(state.summary.countryCount).isEqualTo(2)
    }

    // ── 陷阱 ④：排序键 ──────────────────────────────────────

    @Test
    fun `按起飞时刻排序而不是创建时刻`() {


        val state = RecordListBuilder.build(
            listOf(
                // 10-20 飞的，但 10-26 才补录（createdAt 很新）
                entity("backfilled", at(2026, 10, 20, 9), createdAt = now),
                // 10-26 正常飞的
                entity("normal", at(2026, 10, 26, 9)),
            ),
            zone, now,
        ) as RecordListState.Content

        // 时间线按起飞时刻，10-26 在前
        assertThat(state.groups.map { it.date }).containsExactly(
            LocalDate.of(2026, 10, 26),
            LocalDate.of(2026, 10, 20),
        ).inOrder()
    }

    // ── 搜索 ───────────────────────────────────────────────

    @Test
    fun `搜索匹配城市名三字码与机场名`() {
        val rows = listOf(
            entity("a", at(2026, 10, 26, 9), fromCity = "北京", toCity = "大阪",
                fromCode = "PEK", toCode = "KIX"),
            entity("b", at(2026, 10, 25, 9), fromCity = "上海", toCity = "首尔",
                fromCode = "PVG", toCode = "ICN"),
        )

        fun count(kw: String) = (
            RecordListBuilder.build(rows, zone, now, kw) as? RecordListState.Content
            )?.groups?.sumOf { it.items.size } ?: 0

        assertThat(count("大阪")).isEqualTo(1)
        assertThat(count("KIX")).isEqualTo(1)
        assertThat(count("kix")).isEqualTo(1) // 大小写不敏感
        assertThat(count("上海")).isEqualTo(1)
        assertThat(count("关西")).isEqualTo(2) // 两条的机场名都是"关西国际机场"
        assertThat(count("")).isEqualTo(2)
    }

    @Test
    fun `搜索关键词两端空格被忽略`() {
        val rows = listOf(entity("a", at(2026, 10, 26, 9), toCity = "大阪"))
        val state = RecordListBuilder.build(rows, zone, now, "  大阪  ")
        assertThat(state).isInstanceOf(RecordListState.Content::class.java)
    }

    // ── 未知状态降级 ────────────────────────────────────────

    @Test
    fun `未知状态值降级为已取消而不是崩溃`() {

        //   一条未知 status 让整个列表崩掉是不可接受的。
        assertThat(FlightStatus.fromDb("WHATEVER")).isEqualTo(FlightStatus.CANCELLED)
        assertThat(FlightStatus.fromDb(null)).isEqualTo(FlightStatus.CANCELLED)
        assertThat(FlightStatus.fromDb("COMPLETED")).isEqualTo(FlightStatus.COMPLETED)
    }

    @Test
    fun `未知状态的记录仍然出现在列表里`() {
        val state = RecordListBuilder.build(
            listOf(entity("x", at(2026, 10, 26, 9), status = "FUTURE_STATUS")),
            zone, now,
        ) as RecordListState.Content
        assertThat(state.groups[0].items).hasSize(1)
        assertThat(state.groups[0].items[0].status).isEqualTo(FlightStatus.CANCELLED)
    }

    // ── 格式化 ─────────────────────────────────────────────

    @Test
    fun `时长格式化的三条规则`() {
        assertThat(RecordListBuilder.formatMinutes(0)).isEqualTo("0 分钟")
        assertThat(RecordListBuilder.formatMinutes(59)).isEqualTo("59 分钟")
        assertThat(RecordListBuilder.formatMinutes(60)).isEqualTo("1 小时")
        assertThat(RecordListBuilder.formatMinutes(90)).isEqualTo("1 小时 30 分")
        assertThat(RecordListBuilder.formatMinutes(120)).isEqualTo("2 小时")
        // 负数不应该出现，但出现了也不能显示"-5 分钟"
        assertThat(RecordListBuilder.formatMinutes(-5)).isEqualTo("0 分钟")
    }

    @Test
    fun `里程格式化归一化负零`() {

        assertThat(RecordListBuilder.km(-0.0)).isEqualTo("0.0")
        assertThat(RecordListBuilder.km(0.0)).isEqualTo("0.0")
        assertThat(RecordListBuilder.km(600.04)).isEqualTo("600.0")
        assertThat(RecordListBuilder.km(1234.567)).isEqualTo("1234.6")
    }

    // ── 完成度 ─────────────────────────────────────────────

    @Test
    fun `完成度按实际除计划且裁剪到零到一`() {
        val state = RecordListBuilder.build(
            listOf(
                entity("full", at(2026, 10, 26, 9), planned = 45, actual = 45),
                entity("half", at(2026, 10, 26, 10), planned = 45, actual = 20),
                // 理论上不会出现（实际 > 计划），但必须裁到 1
                entity("over", at(2026, 10, 26, 11), planned = 45, actual = 60),
            ),
            zone, now,
        ) as RecordListState.Content

        val byId = state.groups[0].items.associateBy { it.id }
        assertThat(byId.getValue("full").completionRatio).isEqualTo(1f)
        assertThat(byId.getValue("half").completionRatio).isWithin(0.01f).of(0.444f)
        assertThat(byId.getValue("over").completionRatio).isEqualTo(1f)
    }

    @Test
    fun `计划时长为零时不除零`() {
        val state = RecordListBuilder.build(
            listOf(entity("zero", at(2026, 10, 26, 9), planned = 0, actual = 0)),
            zone, now,
        ) as RecordListState.Content

        //   但中间经过了 Infinity，NaN 的话 coerceIn 会返回 NaN 并让界面出错。
        assertThat(state.groups[0].items[0].completionRatio).isEqualTo(1f)
    }

    // ── 无障碍 ─────────────────────────────────────────────

    @Test
    fun `每条记录有可朗读的整句描述`() {
        val state = RecordListBuilder.build(
            listOf(entity("a", at(2026, 10, 26, 9), planned = 45, actual = 42)),
            zone, now,
        ) as RecordListState.Content

        val spoken = state.groups[0].items[0].spokenSummary

        //   必须提供一个整句，让用户一次听完。
        assertThat(spoken).contains("北京")
        assertThat(spoken).contains("大阪")
        assertThat(spoken).contains("09:00")
        assertThat(spoken).contains("已完成")
        assertThat(spoken).contains("45 分钟")
        assertThat(spoken).contains("42 分钟")
    }

    // ── 缺字段兜底 ─────────────────────────────────────────

    @Test
    fun `城市名为空时回退到三字码`() {
        val state = RecordListBuilder.build(
            listOf(
                entity("a", at(2026, 10, 26, 9), fromCity = "", toCity = ""),
            ),
            zone, now,
        ) as RecordListState.Content
        val item = state.groups[0].items[0]

        assertThat(item.fromCity).isEqualTo("PEK")
        assertThat(item.toCity).isEqualTo("KIX")
    }

    @Test
    fun `三字码为空时显示占位符`() {
        val state = RecordListBuilder.build(
            listOf(
                entity("a", at(2026, 10, 26, 9), fromCode = "", toCode = "", fromCity = "北京"),
            ),
            zone, now,
        ) as RecordListState.Content
        val item = state.groups[0].items[0]
        assertThat(item.fromCode).isEqualTo("---")
        assertThat(item.toCode).isEqualTo("---")
        // 城市名还在，所以不缺信息
        assertThat(item.fromCity).isEqualTo("北京")
    }
}
