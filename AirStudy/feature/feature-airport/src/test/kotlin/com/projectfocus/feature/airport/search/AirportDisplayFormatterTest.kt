package com.projectfocus.feature.airport.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId


























class AirportDisplayFormatterTest {

    // ══════════════════════════════════════════════════════
    // 主标题
    // ══════════════════════════════════════════════════════

    @Test
    fun `主标题优先中文城市名`() {
        assertThat(
            AirportDisplayFormatter.primaryTitle(
                cityZh = "北京", city = "Beijing",
                nameZh = "北京首都国际机场", name = "Beijing Capital International Airport",
            ),
        ).isEqualTo("北京")
    }

    @Test
    fun `没有中文城市时退到英文城市`() {
        assertThat(
            AirportDisplayFormatter.primaryTitle(
                cityZh = null, city = "Tokyo",
                nameZh = null, name = "Haneda Airport",
            ),
        ).isEqualTo("Tokyo")
    }

    @Test
    fun `城市全空时退到机场名而不是显示空串`() {

        //   如果用 isNullOrEmpty 判断（而不是 isNullOrBlank），
        //   这里会返回空串，界面上就是一行什么都没有的标题。
        assertThat(
            AirportDisplayFormatter.primaryTitle(
                cityZh = "  ", city = "",
                nameZh = null, name = "Some Heliport",
            ),
        ).isEqualTo("Some Heliport")
    }

    @Test
    fun `全部为空时给占位符而不是空串`() {

        assertThat(
            AirportDisplayFormatter.primaryTitle(null, null, null, null),
        ).isEqualTo("未知机场")
    }

    // ══════════════════════════════════════════════════════
    // 副标题 —— 空 `·` 的排查重灾区
    // ══════════════════════════════════════════════════════

    @Test
    fun `副标题正常拼接`() {
        assertThat(
            AirportDisplayFormatter.secondaryTitle(
                nameZh = "首都国际机场", name = "Beijing Capital Intl",
                iataCode = "PEK", icaoCode = "ZBAA",
            ),
        ).isEqualTo("首都国际机场 · PEK")
    }

    @Test
    fun `没有 IATA 时用 ICAO 兜底`() {

        //   "看起来和别的不是一类"（少一段），用户会以为数据坏了。
        assertThat(
            AirportDisplayFormatter.secondaryTitle(
                nameZh = null, name = "Some Small Field",
                iataCode = null, icaoCode = "KXYZ",
            ),
        ).isEqualTo("Some Small Field · KXYZ")
    }

    @Test
    fun `同时有 IATA 与 ICAO 时只显示 IATA`() {

        val s = AirportDisplayFormatter.secondaryTitle(
            nameZh = "首都国际机场", name = null,
            iataCode = "PEK", icaoCode = "ZBAA",
        )
        assertThat(s).isEqualTo("首都国际机场 · PEK")
        assertThat(s).doesNotContain("ZBAA")
    }

    @Test
    fun `码全缺失时不留下悬空的间隔符`() {

        //   错误实现：`"$name · ${code}"` → `"某直升机场 · "`（末尾一个悬空点）
        //   或更糟：`"$name$SEP$code"` 在 code 为空时 → `"某直升机场 · "`
        val s = AirportDisplayFormatter.secondaryTitle(
            nameZh = "某直升机场", name = null,
            iataCode = null, icaoCode = null,
        )
        assertThat(s).isEqualTo("某直升机场")
        assertThat(s).doesNotContain("·")
        assertThat(s.endsWith(" ")).isFalse()
    }

    @Test
    fun `名称与码全缺失时返回空串供 UI 隐藏整行`() {

        //   返回 "" 时调用方可以 `isVisible = text.isNotEmpty()`。
        val s = AirportDisplayFormatter.secondaryTitle(
            nameZh = null, name = null, iataCode = null, icaoCode = null,
        )
        assertThat(s).isEmpty()
    }

    @Test
    fun `有类型标签时拼在最后`() {
        assertThat(
            AirportDisplayFormatter.secondaryTitle(
                nameZh = null, name = "Fictional Field",
                iataCode = null, icaoCode = null, typeLabel = "直升机场",
            ),
        ).isEqualTo("Fictional Field · 直升机场")
    }

    @Test
    fun `中文名首尾空白被去掉`() {

        //   不 trim 的话 `首都国际机场 · PEK` 会变成 `首都国际机场  · PEK`（双空格）。
        assertThat(
            AirportDisplayFormatter.secondaryTitle(
                nameZh = "  首都国际机场 ", name = null,
                iataCode = "PEK", icaoCode = null,
            ),
        ).isEqualTo("首都国际机场 · PEK")
    }

    // ══════════════════════════════════════════════════════
    // 时长
    // ══════════════════════════════════════════════════════

    @Test
    fun `时长小于一小时只显示分钟`() {
        assertThat(AirportDisplayFormatter.duration(45)).isEqualTo("45m")
        assertThat(AirportDisplayFormatter.duration(1)).isEqualTo("1m")
        assertThat(AirportDisplayFormatter.duration(0)).isEqualTo("0m")
    }

    @Test
    fun `整小时不写 0m`() {

        assertThat(AirportDisplayFormatter.duration(60)).isEqualTo("1h")
        assertThat(AirportDisplayFormatter.duration(120)).isEqualTo("2h")
        assertThat(AirportDisplayFormatter.duration(600)).isEqualTo("10h")
    }

    @Test
    fun `小时加分钟`() {
        assertThat(AirportDisplayFormatter.duration(135)).isEqualTo("2h 15m")
        assertThat(AirportDisplayFormatter.duration(61)).isEqualTo("1h 1m")
        assertThat(AirportDisplayFormatter.duration(1439)).isEqualTo("23h 59m")
    }

    @Test
    fun `负数时长为 0m 而不是负数文案`() {

        //   某些实现会算出 -0 或极小的负数。显示 `-1m` 是明确的 bug 信号。
        assertThat(AirportDisplayFormatter.duration(-1)).isEqualTo("0m")
        assertThat(AirportDisplayFormatter.duration(-999)).isEqualTo("0m")
    }

    @Test
    fun `超过 24 小时不换单位`() {

        //   地球上最长的商用直飞约 19h，出现 25h 一定是数据错误。
        //   显示 "25h 30m" 反而能让用户/测试立刻看出数据有问题。
        assertThat(AirportDisplayFormatter.duration(1530)).isEqualTo("25h 30m")
    }

    // ══════════════════════════════════════════════════════
    // 距离
    // ══════════════════════════════════════════════════════

    @Test
    fun `距离小于十公里不显示小数`() {
        assertThat(AirportDisplayFormatter.distance(3.2)).isEqualTo("3km")
        assertThat(AirportDisplayFormatter.distance(9.99)).isEqualTo("9km")
    }

    @Test
    fun `距离带千分位`() {
        assertThat(AirportDisplayFormatter.distance(820.4)).isEqualTo("820km")
        assertThat(AirportDisplayFormatter.distance(1800.6)).isEqualTo("1,800km")
        assertThat(AirportDisplayFormatter.distance(12345.0)).isEqualTo("12,345km")
    }

    @Test
    fun `千分位在四位数与七位数上都正确`() {

        assertThat(AirportDisplayFormatter.distance(1000.0)).isEqualTo("1,000km")
        assertThat(AirportDisplayFormatter.distance(999.0)).isEqualTo("999km")
        assertThat(AirportDisplayFormatter.distance(1000000.0)).isEqualTo("1,000,000km")
    }

    @Test
    fun `距离 NaN 显示占位符而不是 NaNkm`() {

        //   展示层必须挡住，否则用户在列表里看到 `NaNkm`。
        assertThat(AirportDisplayFormatter.distance(Double.NaN)).isEqualTo("—")
        assertThat(AirportDisplayFormatter.distance(Double.POSITIVE_INFINITY)).isEqualTo("—")
        assertThat(AirportDisplayFormatter.distance(Double.NEGATIVE_INFINITY)).isEqualTo("—")
    }

    @Test
    fun `负距离按零处理`() {
        assertThat(AirportDisplayFormatter.distance(-50.0)).isEqualTo("0km")
    }

    @Test
    fun `航线行把距离与时长合成一行`() {
        assertThat(AirportDisplayFormatter.routeLine(1800.6, 135)).isEqualTo("1,800km · 2h 15m")
    }

    // ══════════════════════════════════════════════════════
    // Locale
    // ══════════════════════════════════════════════════════

    @Test
    fun `数字用 Locale_ROOT 不受系统 locale 影响`() {
        val old = java.util.Locale.getDefault()
        try {
            // 阿拉伯语（埃及）：默认数字是阿拉伯-印度数字 ١٢٣
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertThat(AirportDisplayFormatter.distance(1800.0)).isEqualTo("1,800km")

            // 德语：小数点默认是逗号
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("de-DE"))
            assertThat(AirportDisplayFormatter.distance(1800.0)).isEqualTo("1,800km")
        } finally {
            java.util.Locale.setDefault(old)
        }
    }

    @Test
    fun `本地时间用 Locale_ROOT 不受系统 locale 影响`() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            // 2026-10-26 21:40 东京
            val ms = java.time.ZonedDateTime
                .of(2026, 10, 26, 21, 40, 0, 0, ZoneId.of("Asia/Tokyo"))
                .toInstant().toEpochMilli()
            val s = AirportDisplayFormatter.localTime(ms, ZoneId.of("Asia/Tokyo"))
            assertThat(s).isEqualTo("21:40")
        } finally {
            java.util.Locale.setDefault(old)
        }
    }

    // ══════════════════════════════════════════════════════
    // 本地时间 —— 时区必须由调用方给出
    // ══════════════════════════════════════════════════════

    @Test
    fun `同一时刻在不同时区显示不同本地时间`() {
        // 2026-10-26 12:00 UTC
        val ms = java.time.Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()
        assertThat(AirportDisplayFormatter.localTime(ms, ZoneId.of("Asia/Shanghai"))).isEqualTo("20:00")
        assertThat(AirportDisplayFormatter.localTime(ms, ZoneId.of("Asia/Tokyo"))).isEqualTo("21:00")
        assertThat(AirportDisplayFormatter.localTime(ms, ZoneId.of("UTC"))).isEqualTo("12:00")
        assertThat(AirportDisplayFormatter.localTime(ms, ZoneId.of("America/New_York"))).isEqualTo("08:00")
    }

    @Test
    fun `跨零点时本地时间与日期一致`() {
        // 2026-10-26 23:30 上海 = 15:30 UTC
        val ms = java.time.Instant.parse("2026-10-26T15:30:00Z").toEpochMilli()
        // 显示 23:30 而不是 15:30 —— 这是用户要的"当地时间"
        assertThat(AirportDisplayFormatter.localTime(ms, ZoneId.of("Asia/Shanghai"))).isEqualTo("23:30")
    }

    // ══════════════════════════════════════════════════════
    // 可选性
    // ══════════════════════════════════════════════════════

    @Test
    fun `已关闭的机场不可选`() {
        assertThat(AirportDisplayFormatter.isSelectable("closed")).isFalse()
        assertThat(AirportDisplayFormatter.isSelectable("CLOSED")).isFalse()
    }

    @Test
    fun `未知类型可选`() {

        //   OurAirports 里大量小机场 type 是空或无法识别。
        //   如果实现写成 `type in setOf("large_airport", ...)` 这种白名单，
        //   这些小机场就全被禁掉了 —— 小城市用户直接不可用。
        assertThat(AirportDisplayFormatter.isSelectable(null)).isTrue()
        assertThat(AirportDisplayFormatter.isSelectable("")).isTrue()
        assertThat(AirportDisplayFormatter.isSelectable("unknown")).isTrue()
        assertThat(AirportDisplayFormatter.isSelectable("heliport")).isTrue()
        assertThat(AirportDisplayFormatter.isSelectable("large_airport")).isTrue()
    }

    // ══════════════════════════════════════════════════════
    // 层级文案
    // ══════════════════════════════════════════════════════

    @Test
    fun `每个层级都有非空中文文案`() {

        //   （`when` 是穷尽的，所以是编译期就红；这条是防"补成 TODO"。）
        for (t in AirportSearchIndex.Tier.entries) {
            val label = AirportDisplayFormatter.tierLabel(t)
            assertThat(label).isNotEmpty()
            assertThat(label).doesNotContain("TODO")
        }
    }

    @Test
    fun `层级文案互不重复`() {

        val labels = AirportSearchIndex.Tier.entries.map { AirportDisplayFormatter.tierLabel(it) }
        assertThat(labels.toSet()).hasSize(labels.size)
    }
}
