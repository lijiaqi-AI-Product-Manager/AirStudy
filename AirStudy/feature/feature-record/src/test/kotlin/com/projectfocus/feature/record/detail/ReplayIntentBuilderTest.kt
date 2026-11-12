package com.projectfocus.feature.record.detail

import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.record.list.FlightStatus
import com.projectfocus.service.focus.session.SessionRepository
import org.junit.Test

















class ReplayIntentBuilderTest {

    private fun detail(
        fromCode: String = "PKX",
        toCode: String = "KIX",
        completionPercent: Int? = 80,
    ) = RecordDetail(
        id = "r1",
        fromCode = fromCode,
        toCode = toCode,
        fromCity = "北京",
        toCity = "大阪",
        fromAirportName = "大兴",
        toAirportName = "关西",
        fromCountry = "中国",
        toCountry = "日本",
        dateText = "10月26日 周日",
        timeRangeText = "08:30 — 10:45",
        statusText = "已完成",
        status = FlightStatus.COMPLETED,
        plannedText = "2 小时 15 分",
        actualText = "2 小时 05 分",
        diffText = "少 10 分钟",
        completionPercent = completionPercent,
        distanceText = "1800.0",
        cruiseEquivalentText = "相当于巡航 2 小时 15 分",
        routeEquivalentText = "约 1.7 段京沪航线",
        seat = null,
        quote = null,
        quoteAuthor = null,
        isGroupFlight = false,
        participantCount = null,
        payloadJson = null,
    )

    // ==================================================================
    // 时长：优先实际值
    // ==================================================================

    @Test
    fun `优先用实际专注时长而不是计划时长`() {

        //   计划 45 分钟、实际只做到 30 分钟的人，重飞时想重复的是 30。
        //   用计划值时，他会再次中途退出 → "永远完不成"的挫败循环。
        val intent = ReplayIntentBuilder.build(
            detail = detail(),
            actualFocusMinutes = 30,
            plannedMinutes = 45,
            categoryId = 3,
        )
        assertThat(intent.durationMinutes).isEqualTo(30)
    }

    @Test
    fun `实际时长缺失时退回计划时长`() {
        val intent = ReplayIntentBuilder.build(
            detail = detail(),
            actualFocusMinutes = null,
            plannedMinutes = 45,
            categoryId = null,
        )
        assertThat(intent.durationMinutes).isEqualTo(45)
    }

    @Test
    fun `两者都缺失时退化到下限而不是 0`() {

        //   看起来像 App 崩了。必须 clamp 到 1 分钟。
        val intent = ReplayIntentBuilder.build(
            detail = detail(completionPercent = null),
            actualFocusMinutes = null,
            plannedMinutes = null,
            categoryId = null,
        )
        assertThat(intent.durationMinutes).isEqualTo(ReplayIntent.MIN_MINUTES)
        assertThat(intent.durationMinutes).isAtLeast(1)
    }

    @Test
    fun `负数时长被夹到下限`() {
        // 数据异常（比如某次迁移把分钟数算成负数）不能让用户进一个负时长的飞行。
        val intent = ReplayIntentBuilder.build(
            detail = detail(),
            actualFocusMinutes = -30,
            plannedMinutes = null,
            categoryId = null,
        )
        assertThat(intent.durationMinutes).isEqualTo(ReplayIntent.MIN_MINUTES)
    }

    @Test
    fun `超长时长被夹到上限`() {

        //   进度条永远停在 0.0%，用户无法结束。这比"时长不准"严重得多。
        val intent = ReplayIntentBuilder.build(
            detail = detail(),
            actualFocusMinutes = 100_000,
            plannedMinutes = null,
            categoryId = null,
        )
        assertThat(intent.durationMinutes).isEqualTo(ReplayIntent.MAX_MINUTES)
    }

    @Test
    fun `边界值本身不被改动`() {
        // 1 分钟和 12 小时这两个端点必须原样保留（clamp 是闭区间）
        assertThat(
            ReplayIntentBuilder.build(detail(), 1, null, null).durationMinutes,
        ).isEqualTo(1)
        assertThat(
            ReplayIntentBuilder.build(detail(), 12 * 60, null, null).durationMinutes,
        ).isEqualTo(12 * 60)
    }

    // ==================================================================
    // 三字码：占位值必须被过滤
    // ==================================================================

    @Test
    fun `占位三字码不会被当成真实目的地`() {

        //   若把 "---" 传给飞行页，它会去查这个机场、查不到、
        //   然后显示一片空白的航线信息 —— 而用户以为是自己选错了。
        val intent = ReplayIntentBuilder.build(
            detail = detail(fromCode = "---", toCode = "---"),
            actualFocusMinutes = 30,
            plannedMinutes = null,
            categoryId = null,
        )
        assertThat(intent.destinationCode).isNull()
        assertThat(intent.departureCode).isNull()
    }

    @Test
    fun `空白三字码不会被当成真实目的地`() {

        //   `""` 传给飞行页同样会查不到机场。
        val intent = ReplayIntentBuilder.build(
            detail = detail(fromCode = "", toCode = "   "),
            actualFocusMinutes = 30,
            plannedMinutes = null,
            categoryId = null,
        )
        assertThat(intent.destinationCode).isNull()
        assertThat(intent.departureCode).isNull()
    }

    @Test
    fun `占位码常量与写库时的常量逐字一致`() {

        //   两处各定义一份字符串（`SessionRepository.UNKNOWN_AIRPORT` 与本常量）。

        //   而它同样是个合法字符串。表现是"自由模式的记录重飞后航线空白"，
        //   只有真机上从自由模式重飞一次才能发现。
        assertThat(ReplayIntentBuilder.UNKNOWN_CODE)
            .isEqualTo(SessionRepository.UNKNOWN_AIRPORT)
    }

    @Test
    fun `真实三字码原样保留`() {
        val intent = ReplayIntentBuilder.build(
            detail = detail(fromCode = "PKX", toCode = "KIX"),
            actualFocusMinutes = 30,
            plannedMinutes = null,
            categoryId = null,
        )
        assertThat(intent.destinationCode).isEqualTo("KIX")
        assertThat(intent.departureCode).isEqualTo("PKX")
    }

    @Test
    fun `分类 id 原样透传，缺失时是 null 而不是 0`() {

        //   id=0 的分类（SQLite AUTOINCREMENT 可能真的产生 0），
        //   查不到 → 飞行页显示"未知分类"。
        assertThat(
            ReplayIntentBuilder.build(detail(), 30, null, categoryId = 7).categoryId,
        ).isEqualTo(7)
        assertThat(
            ReplayIntentBuilder.build(detail(), 30, null, categoryId = null).categoryId,
        ).isNull()
    }
}
