package com.projectfocus.feature.airport.search

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.core.model.airport.AirportType
import org.junit.Test









class AirportNameFormatterTest {

    private fun ap(
        name: String,
        city: String? = null,
        country: String? = null,
        nameZh: String? = null,
        cityZh: String? = null,
        iata: String = "PEK",
    ) = Airport(
        id = 1, iataCode = iata, icaoCode = "ZBAA",
        name = name, city = city, country = country,
        latitude = 40.0, longitude = 116.0, elevation = 100,
        continent = "AS", type = AirportType.LARGE_AIRPORT,
        popularity = 100, isPremiumOnly = false,
        nameZh = nameZh, cityZh = cityZh,
    )

    private val full = ap(
        name = "Beijing Capital International Airport",
        city = "Beijing", country = "China",
        nameZh = "北京首都国际机场", cityZh = "北京",
    )

    private val noZh = ap(
        name = "Some Small Strip", city = "Nowhere", country = "Nowhereland",
        iata = "XXX",
    )

    // ── 1. 主标题 ──────────────────────────────────────────

    @Test
    fun `中文优先时有中文名就用中文名`() {
        assertThat(AirportNameFormatter.primary(full, preferChinese = true))
            .isEqualTo("北京首都国际机场")
    }

    @Test
    fun `中文优先但缺中文名时降级到英文名而不是空串`() {

        //   如果这里返回空串或抛异常，整个列表就是一片空白。
        assertThat(AirportNameFormatter.primary(noZh, preferChinese = true))
            .isEqualTo("Some Small Strip")
    }

    @Test
    fun `中文名为空白字符串时视为缺失`() {

        //   就是在防这个 —— 否则列表里会出现一行只有 IATA 码的机场。
        val blank = full.copy(nameZh = "   ")
        assertThat(AirportNameFormatter.primary(blank, preferChinese = true))
            .isEqualTo("Beijing Capital International Airport")
    }

    @Test
    fun `不优先中文时永远用英文名，即使有中文名`() {

        //   它会让中文界面里中英混排（有的行 5 个字，有的行 34 个字符），
        //   列表行高跳动，视觉上是明显的脏。
        assertThat(AirportNameFormatter.primary(full, preferChinese = false))
            .isEqualTo("Beijing Capital International Airport")
    }

    // ── 2. 城市与副标题 ─────────────────────────────────────

    @Test
    fun `城市名同样按偏好降级`() {
        assertThat(AirportNameFormatter.city(full, preferChinese = true)).isEqualTo("北京")
        assertThat(AirportNameFormatter.city(full, preferChinese = false)).isEqualTo("Beijing")
        assertThat(AirportNameFormatter.city(noZh, preferChinese = true)).isEqualTo("Nowhere")
    }

    @Test
    fun `城市为 null 时返回 null 而不是字符串 null`() {
        val noCity = ap(name = "Nowhere Airport", city = null)
        assertThat(AirportNameFormatter.city(noCity, preferChinese = true)).isNull()
        assertThat(AirportNameFormatter.city(noCity, preferChinese = false)).isNull()
    }

    @Test
    fun `副标题用中点分隔城市与国家`() {
        assertThat(AirportNameFormatter.subtitle(full, preferChinese = true))
            .isEqualTo("北京 · China")
    }

    @Test
    fun `副标题在只有城市时不含分隔符残留`() {

        val onlyCity = ap(name = "X", city = "Beijing", country = null)
        assertThat(AirportNameFormatter.subtitle(onlyCity, preferChinese = true)).isEqualTo("Beijing")
    }

    @Test
    fun `副标题在两个都缺失时是空串而不是空指针`() {
        val bare = ap(name = "X", city = null, country = null)
        assertThat(AirportNameFormatter.subtitle(bare, preferChinese = true)).isEmpty()
    }

    // ── 3. 完整标签 ─────────────────────────────────────────

    @Test
    fun `标签是码加中点加名字`() {
        assertThat(AirportNameFormatter.label(full, preferChinese = true))
            .isEqualTo("PEK · 北京首都国际机场")
    }

    @Test
    fun `缺 IATA 时标签用 ICAO 而不是空白码位`() {
        // Airport.displayCode 的三级降级：IATA → ICAO → name
        val icaoOnly = full.copy(iataCode = "", nameZh = "北京首都国际机场")
        assertThat(icaoOnly.displayCode).isEqualTo("ZBAA")
        assertThat(AirportNameFormatter.label(icaoOnly, preferChinese = true)).startsWith("ZBAA · ")
    }
}
