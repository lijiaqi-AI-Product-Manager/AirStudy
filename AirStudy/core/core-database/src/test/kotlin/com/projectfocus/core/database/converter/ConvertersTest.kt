package com.projectfocus.core.database.converter

import com.projectfocus.core.model.flight.FlightLogEntry
import com.projectfocus.core.model.focus.BlockedAppType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config














@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26]) // minSdk 26，用最低版本跑，最能暴露兼容问题
class ConvertersTest {

    private val converters = Converters()

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    @Test
    fun `枚举落库存名字符串而不是 ordinal`() {

        // 若存 ordinal，枚举中间插新值时会全表错位 —— 这是必须守住的兼容点。
        assertEquals("APP", converters.blockedAppTypeToRaw(BlockedAppType.APP))
        assertEquals("CATEGORY", converters.blockedAppTypeToRaw(BlockedAppType.CATEGORY))
        assertEquals("WEBSITE", converters.blockedAppTypeToRaw(BlockedAppType.WEBSITE))
    }

    @Test
    fun `未知的屏蔽类型兜底为 APP 而不是抛异常`() {

        //   "Can't convert value to enum, unknown value: xxx"
        //   后果是整条查询挂掉 —— 一条脏数据毒死整个屏蔽页。
        assertEquals(BlockedAppType.APP, converters.rawToBlockedAppType("SOMETHING_NEW"))
        assertEquals(BlockedAppType.APP, converters.rawToBlockedAppType(null))
        assertEquals(BlockedAppType.APP, converters.rawToBlockedAppType(""))
    }

    @Test
    fun `已知的屏蔽类型正常往返`() {
        BlockedAppType.entries.forEach { type ->
            assertEquals(type, converters.rawToBlockedAppType(converters.blockedAppTypeToRaw(type)))
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    @Test
    fun `字符串列表落库是 JSON 数组格式`() {
        val raw = converters.stringListToJson(listOf("PEK", "PVG", "CAN"))
        assertEquals("""["PEK","PVG","CAN"]""", raw)
    }

    @Test
    fun `空列表落库是空数组而不是 null`() {
        assertEquals("[]", converters.stringListToJson(emptyList()))
    }

    @Test
    fun `JSON 数组往返一致`() {
        val original = listOf("JP", "KR", "SG")
        val roundTrip = converters.jsonToStringList(converters.stringListToJson(original))
        assertEquals(original, roundTrip)
    }

    @Test
    fun `解析 null 和空串得到空列表`() {
        assertEquals(emptyList<String>(), converters.jsonToStringList(null))
        assertEquals(emptyList<String>(), converters.jsonToStringList(""))
        assertEquals(emptyList<String>(), converters.jsonToStringList("[]"))
        assertEquals(emptyList<String>(), converters.jsonToStringList("null"))
    }

    @Test
    fun `解析脏数据不抛异常`() {
        // 转换器的职责是"不崩、不丢能捞的数据"，不做业务校验。
        // `{broken` 不是数组，会走逗号兜底原样返回；过滤非法机场码
        // 是 EntityMappers / 领域层的责任，不在这里。
        //

        //   以为解析逻辑没生效，查了一小时。真正的坑是调试打印
        //   `"[" + list + "]"` 把 list.toString() 的方括号吃掉，
        //   单元素结果 `[{broken]` 与空列表 `[]` 长得几乎一样。
        //   现在每个断言都用 listOf(...) 显式比对，不拼字符串。
        assertEquals(listOf("{broken"), converters.jsonToStringList("{broken"))


        assertEquals(listOf("PEK", "PVG"), converters.jsonToStringList("PEK,PVG"))

        assertEquals(listOf("PEK", "PVG"), converters.jsonToStringList("[PEK, PVG]"))
    }

    @Test
    fun `缺引号的 JSON 数组走逗号兜底而不是被宽松解析`() {
        // org.json 的 JSONArray("[PEK, PVG]") 不抛异常，会把裸标量当字符串收进来。

        // 用"非字符串元素"来区分两条路径：宽松解析会把它转成字符串。
        assertEquals(listOf("1", "2"), converters.jsonToStringList("[1, 2]"))
        assertEquals(listOf("true"), converters.jsonToStringList("[true]"))
    }

    @Test
    fun `JSON 数组里的非字符串元素被丢掉而不是强转`() {

        assertEquals(
            listOf("A"),
            converters.jsonToStringList("""["A", 1, {}, null]"""),
        )
    }

    @Test
    fun `含特殊字符的字符串能安全往返`() {
        // 机场名/地标名可能带引号、反斜杠、中文、emoji
        val tricky = listOf("""Say "hi"""", "back\\slash", "北京首都", "🇯🇵", "a,b")
        val roundTrip = converters.jsonToStringList(converters.stringListToJson(tricky))
        assertEquals(tricky, roundTrip)
    }

    // ═══════════════════════════════════════════════════════════
    // 航行日志（logJson）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空日志写成 null 而不是空数组`() {
        // 与竞品一致：logJson 可空，v1/v2 老记录是 NULL。
        // 写 "[]" 会让「没有日志」和「日志为空数组」两种状态混在一起。
        assertNull(converters.logToJson(emptyList()))
    }

    @Test
    fun `日志往返一致`() {
        val entries = listOf(
            FlightLogEntry(id = "e1", content = "Cabin doors closed", timestampMillis = 1_000L, color = "#FFB74D"),
            FlightLogEntry(id = "e2", content = "Cruising at 800 km/h", timestampMillis = 2_000L, color = null),
        )
        val json = converters.logToJson(entries)
        val restored = converters.logFromJson(json)
        assertEquals(entries, restored)
    }

    @Test
    fun `日志 JSON 字段名与竞品 LogEntry 一致`() {
        val json = converters.logToJson(
            listOf(FlightLogEntry("e1", "hi", 123L, "#FFF")),
        )!!
        // 竞品 LogEntry: id / content / timestampMillis / color
        assertTrue("缺 id 字段: $json", json.contains("\"id\""))
        assertTrue("缺 content 字段: $json", json.contains("\"content\""))
        assertTrue("缺 timestampMillis 字段: $json", json.contains("\"timestampMillis\""))
        assertTrue("缺 color 字段: $json", json.contains("\"color\""))
    }

    @Test
    fun `日志里单条坏数据被跳过而不是整段丢弃`() {
        // 手工构造：第 0 条正常，第 1 条是空对象（坏），第 2 条正常
        val json = """[{"id":"e1","content":"ok","timestampMillis":1},{"junk":1},{"id":"e3","content":"ok3","timestampMillis":3}]"""
        val entries = converters.logFromJson(json)
        assertEquals("坏的那条应被跳过，好的两条要保留", 2, entries.size)
        assertEquals("e1", entries[0].id)
        assertEquals("e3", entries[1].id)
    }

    @Test
    fun `日志解析兼容额外字段`() {

        val json = """[{"id":"e1","content":"ok","timestampMillis":1,"extraField":"x","another":123}]"""
        val entries = converters.logFromJson(json)
        assertEquals(1, entries.size)
        assertEquals("e1", entries[0].id)
    }

    @Test
    fun `日志解析对非 JSON 输入返回空列表`() {
        assertEquals(emptyList<FlightLogEntry>(), converters.logFromJson("not json at all"))
        assertEquals(emptyList<FlightLogEntry>(), converters.logFromJson(null))
        assertEquals(emptyList<FlightLogEntry>(), converters.logFromJson("[]"))
    }
}
