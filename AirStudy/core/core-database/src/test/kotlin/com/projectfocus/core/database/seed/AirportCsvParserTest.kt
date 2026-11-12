package com.projectfocus.core.database.seed

import com.projectfocus.core.database.TestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * 机场 CSV 解析器的行为测试。
 *
 * 两类用例：
 *  1. **真实资产** —— 断言 9,068 行的关键事实（含逗号/引号的机场名、空 ICAO、
 *     热门排序），防止生成脚本或资产被悄悄改坏。
 *  2. **合成边界** —— 引号、转义、CRLF、空行、坏列数、未闭合引号，
 *     这些是 9 千行里只出现几十次、人工抽查看不见的形态。
 */
class AirportCsvParserTest {

    private val header: String = AirportCsvParser.HEADER.joinToString(",")

    private fun parse(text: String) = AirportCsvParser.parse(StringReader(text)).toList()

    // ═══════════════════════════════════════════════════════════
    // 真实资产
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `real asset has expected shape`() {
        val airports = AirportCsvParser.parse(TestAssets.airportCsv().bufferedReader()).toList()

        assertEquals(AirportSeeder.EXPECTED_ROWS, airports.size)
        assertEquals(airports.size, airports.map { it.id }.toSet().size)
        assertEquals(airports.size, airports.map { it.iataCode }.toSet().size)
        assertEquals(airports.size, airports.count { it.iataCode.length == 3 })
        assertTrue(airports.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
        assertTrue(airports.all { it.popularity >= 0 })
        assertTrue(airports.none { it.isPremiumOnly })
        assertTrue(airports.all { it.nameZh == null && it.cityZh == null })
    }

    @Test
    fun `real asset keeps names that contain commas and quotes`() {
        val airports = AirportCsvParser.parse(TestAssets.airportCsv().bufferedReader()).toList()

        assertEquals(39, airports.count { ',' in it.name })
        assertEquals(1, airports.count { '"' in it.name })
        assertTrue(airports.any { it.name.contains(",") })
    }

    @Test
    fun `real asset decodes the Beijing hub`() {
        val pek = AirportCsvParser.parse(TestAssets.airportCsv().bufferedReader())
            .first { it.iataCode == "PEK" }

        assertEquals(27188L, pek.id)
        assertEquals("Beijing Capital International Airport", pek.name)
        assertEquals("Beijing", pek.city)
        assertEquals("CN", pek.country)
        assertEquals("ZBAA", pek.icaoCode)
        assertEquals(116, pek.elevation)
        assertEquals("large_airport", pek.type)
        assertEquals(1_351_300, pek.popularity)
        assertTrue(pek.latitude > 40.0 && pek.latitude < 41.0)
        assertTrue(pek.longitude > 116.0 && pek.longitude < 117.0)
        assertNull(pek.nameZh)
        assertNull(pek.cityZh)
    }

    @Test
    fun `real asset sorts by id ascending`() {
        val ids = AirportCsvParser.parse(TestAssets.airportCsv().bufferedReader()).map { it.id }.toList()
        assertEquals(ids.sorted(), ids)
    }

    // ═══════════════════════════════════════════════════════════
    // 合成边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parses quoted comma and doubled quote`() {
        val csv = header + "\n" +
            "1,AAA,AAAA,\"Name, with \"\"quote\"\"\",City,CN,1.5,2.5,100,AS,large_airport,1000,0,北京,北京\n"

        val airport = parse(csv).single()

        assertEquals("Name, with \"quote\"", airport.name)
        assertEquals("北京", airport.nameZh)
        assertEquals("北京", airport.cityZh)
        assertEquals(1.5, airport.latitude, 0.0)
        assertEquals(2.5, airport.longitude, 0.0)
    }

    @Test
    fun `treats empty optional columns as null`() {
        val csv = header + "\n" +
            "1,AAA,AAAA,Name,City,CN,1.5,2.5,100,AS,large_airport,1000,0,,\n"

        val airport = parse(csv).single()

        assertNull(airport.nameZh)
        assertNull(airport.cityZh)
    }

    @Test
    fun `accepts crlf and skips blank lines`() {
        val csv = header + "\r\n\r\n" +
            "1,AAA,AAAA,A,City,CN,1.5,2.5,100,AS,large_airport,1000,0,,\r\n" +
            "\r\n" +
            "2,BBB,BBBB,B,City,CN,1.5,2.5,100,AS,large_airport,900,0,,\r\n"

        assertEquals(listOf("AAA", "BBB"), parse(csv).map { it.iataCode }.toList())
    }

    @Test
    fun `rejects wrong column count`() {
        val csv = header + "\n" + "1,AAA,AAAA,A,City,CN,1.5,2.5,100,AS,large_airport,1000\n"

        val error = assertThrows(IllegalArgumentException::class.java) { parse(csv) }

        assertTrue(error.message!!.contains("columns"))
    }

    @Test
    fun `rejects unterminated quote`() {
        val csv = header + "\n" + "1,AAA,AAAA,\"never closed,City,CN,1.5,2.5,100,AS,large_airport,1000,0,,\n"

        assertThrows(IllegalArgumentException::class.java) { parse(csv) }
    }

    @Test
    fun `rejects header drift`() {
        val csv = header.replaceFirst("id", "idx") + "\n" +
            "1,AAA,AAAA,A,City,CN,1.5,2.5,100,AS,large_airport,1000,0,,\n"

        assertThrows(IllegalArgumentException::class.java) { parse(csv) }
    }

    @Test
    fun `rejects out of range coordinate`() {
        val csv = header + "\n" +
            "1,AAA,AAAA,A,City,CN,91.0,2.5,100,AS,large_airport,1000,0,,\n"

        assertThrows(IllegalArgumentException::class.java) { parse(csv) }
    }

    @Test
    fun `rejects illegal premium flag`() {
        val csv = header + "\n" +
            "1,AAA,AAAA,A,City,CN,1.5,2.5,100,AS,large_airport,1000,2,,\n"

        assertThrows(IllegalArgumentException::class.java) { parse(csv) }
    }
}
