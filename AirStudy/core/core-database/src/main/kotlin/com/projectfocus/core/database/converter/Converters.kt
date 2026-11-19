package com.projectfocus.core.database.converter

import androidx.room.TypeConverter
import com.projectfocus.core.model.flight.FlightLogEntry
import com.projectfocus.core.model.focus.BlockedAppType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject


class Converters {


    @TypeConverter
    fun blockedAppTypeToRaw(value: BlockedAppType): String = value.name


    @TypeConverter
    fun rawToBlockedAppType(value: String?): BlockedAppType = BlockedAppType.fromRaw(value)


    @TypeConverter
    fun stringListToJson(value: List<String>): String = toJsonArray(value)

    @TypeConverter
    fun jsonToStringList(value: String?): List<String> = parseJsonArray(value)

    // ── 航行日志（非转换器，供 EntityMappers 用）────────────────


    fun logToJson(value: List<FlightLogEntry>): String? {
        if (value.isEmpty()) return null
        val array = JSONArray()
        value.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put(FIELD_ID, entry.id)
                    put(FIELD_CONTENT, entry.content)
                    put(FIELD_TIMESTAMP, entry.timestampMillis)
                    put(FIELD_COLOR, entry.color ?: JSONObject.NULL)
                },
            )
        }
        return array.toString()
    }

    /**
     * `logJson` 列的值 → `List<FlightLogEntry>`。
     *
     * 逐条解析 + 单条失败跳过：一条坏记录不该让整趟飞行的日志都看不到。
     */
    fun logFromJson(raw: String?): List<FlightLogEntry> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "[]" || text == "null") return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString(FIELD_ID, "")
                val content = obj.optString(FIELD_CONTENT, "")
                if (id.isEmpty() && content.isEmpty()) return@mapNotNull null
                FlightLogEntry(
                    id = id,
                    content = content,
                    timestampMillis = obj.optLong(FIELD_TIMESTAMP, 0L),
                    color = obj.optString(FIELD_COLOR, "").takeIf { it.isNotEmpty() && it != "null" },
                )
            }
        }.getOrDefault(emptyList())
    }




























    fun parseJsonArray(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "[]" || text == "null") return emptyList()


        if (!looksLikeJsonStringArray(text)) return splitFallback(text)

        val array = try {
            JSONArray(text)
        } catch (e: JSONException) {
            return splitFallback(text)
        }
        // 只收字符串元素。`["A", 1, {}]` 里的 1 和 {} 一律丢掉，
        // 不要用 optString 强转 —— 强转出来的 "1" / "{}" 一样是脏数据。
        return (0 until array.length()).mapNotNull { i ->
            (array.opt(i) as? String)?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * 结构判定：这段文本是否"看起来像一个 JSON 字符串数组"。
     *
     * 只做前缀判定，不做完整语法校验 —— 目的是把 `{broken`、`PEK,PVG`
     * 这类明显不是数组的输入挡在 JSONArray 之外。
     * 合法字符串数组一定以 `[` 开头、紧跟一个 `"`（空数组 `[]` 上面已提前返回）。
     */
    private fun looksLikeJsonStringArray(text: String): Boolean {
        if (text.length < 2 || text[0] != '[') return false
        var i = 1
        while (i < text.length && text[i].isWhitespace()) i++
        return i < text.length && text[i] == '"'
    }










    private fun splitFallback(text: String): List<String> =
        text.removeSurrounding("[", "]")
            .split(',')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }

    companion object {
        private const val FIELD_ID = "id"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_TIMESTAMP = "timestampMillis"
        private const val FIELD_COLOR = "color"

        /** 统一编码出口，便于以后整体替换实现 */
        fun toJsonArray(value: List<String>): String {
            val array = JSONArray()
            value.forEach { array.put(it) }
            return array.toString()
        }
    }
}
