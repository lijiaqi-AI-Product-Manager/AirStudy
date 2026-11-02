package com.projectfocus.core.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull




























class SchemaDocParityTest {

    private val schemaJson: File = File("schemas")
        .listFiles()
        ?.flatMap { dir -> dir.listFiles { f -> f.name.endsWith(".json") }?.toList() ?: emptyList() }
        ?.maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: 0 }
        ?: error("找不到导出的 Room schema。先跑 `gradle :core:core-database:kspDebugKotlin`")

    private val doc: File = sequenceOf(
        File("../../docs/ddl-alignment.md"),   // 模块工作目录
        File("../docs/ddl-alignment.md"),
    ).firstOrNull { it.isFile } ?: error("找不到 docs/ddl-alignment.md")

    // ==================================================================
    // 解析
    // ==================================================================

    /** 解析后的 schema 根节点，懒加载只读一次 */
    private val schemaRoot: JsonObject by lazy {
        Json.parseToJsonElement(schemaJson.readText()).jsonObject
    }

    private fun entities(): List<JsonObject> =
        schemaRoot["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }

    /** 真实 schema：表名 → 列数 */
    private fun actualColumnCounts(): Map<String, Int> = entities().associate { e ->
        e.str("tableName") to e["fields"]!!.jsonArray.size
    }






    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull
            ?: error("schema JSON 缺少字符串字段：$key（实际键：${keys}）")

    private fun JsonObject.array(key: String): JsonArray =
        this[key]?.jsonArray ?: error("schema JSON 缺少数组字段：$key")

    private fun entityOf(table: String): JsonObject =
        entities().first { it.str("tableName") == table }


    private fun docTable(): Map<String, Pair<String, String>> {
        val result = LinkedHashMap<String, Pair<String, String>>()
        val rowRe = Regex("""^\|\s*`([a-z_]+)`\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|""")
        doc.readLines().forEach { line ->
            val m = rowRe.find(line) ?: return@forEach
            val name = m.groupValues[1]

            val ours = m.groupValues[3].replace("*", "").trim()
            if (ours.toIntOrNull() == null) return@forEach
            if (name in result) return@forEach   // 只取第一次（§0 总览）
            result[name] = m.groupValues[2] to ours
        }
        return result
    }

    // ==================================================================
    // 断言
    // ==================================================================

    @Test
    fun `文档里的表名集合与 schema 完全一致`() {
        val actual = actualColumnCounts().keys
        val documented = docTable().keys

        assertThat(documented).containsExactlyElementsIn(actual)
    }

    @Test
    fun `文档里每张表的列数与 schema 逐一相等`() {
        val actual = actualColumnCounts()
        docTable().forEach { (table, counts) ->
            val documented = counts.second.toInt()
            assertThat(documented).isEqualTo(actual[table])
        }
    }


    @Test
    fun `flight_history 确实有 quote 与 quoteAuthor 两列`() {
        val columns = entityOf("flight_history").array("fields")
            .map { it.jsonObject.str("columnName") }
        assertThat(columns).contains("quote")
        assertThat(columns).contains("quoteAuthor")
    }

    @Test
    fun `airports 确实有 nameZh 与 cityZh 两列且都可空`() {

        //   如果哪天有人"清理无用字段"把这两列删了、同时把 §0 表格改成 13，
        //   `文档里每张表的列数与 schema 逐一相等` 会绿 —— 论断被静默抹掉。
        //   这条钉住「+2 列」这个事实本身。
        val cols = entityOf("airports").array("fields")
            .map { it.jsonObject }
            .filter { it.str("columnName") == "nameZh" || it.str("columnName") == "cityZh" }
        assertThat(cols.map { it.str("columnName") }).containsExactly("nameZh", "cityZh")


        //   83,799 条里绝大多数 small_airport 没有中文名。
        //   一旦有人把这两列改成 NOT NULL，插入就必须填假值（空串），
        //   而空串在列表页渲染成一行空白 —— 比显示英文更糟。

        //     可空列根本没有这个键（实测：nameZh 的字段全是
        //     `{fieldPath, columnName, affinity}`，没有 notNull）。
        //     所以「取不到 → 视为可空」，默认值必须是 **false**。

        cols.forEach { c ->
            val notNull = c["notNull"]?.jsonPrimitive?.booleanOrNull ?: false
            assertThat(notNull).isFalse()
        }
    }

    @Test
    fun `stamps_illustrationResId 是 TEXT 而不是竞品的 INTEGER`() {
        val col = entityOf("stamps").array("fields")
            .map { it.jsonObject }
            .first { it.str("columnName") == "illustrationResId" }

        assertThat(col.str("affinity")).isEqualTo("TEXT")
    }

    @Test
    fun `外键的删除行为与报告一致`() {
        fun onDeleteOf(table: String): List<String> {
            val e = entityOf(table)
            val fks = e["foreignKeys"]?.jsonArray ?: return emptyList()
            return fks.map { it.jsonObject.str("onDelete") }
        }

        // 删分类：历史保留（SET NULL），屏蔽规则一起删（CASCADE）—— 见报告 §3
        assertThat(onDeleteOf("flight_history")).containsExactly("SET NULL")
        assertThat(onDeleteOf("blocked_apps")).containsExactly("CASCADE")
        assertThat(onDeleteOf("room_participant")).containsExactly("CASCADE")
        assertThat(onDeleteOf("room_message")).containsExactly("CASCADE")
    }

    @Test
    fun `索引名与竞品逐字一致`() {

        //   在升级时会被判定为「schema 不匹配」而抛 IllegalStateException。
        val names = entityOf("flight_history").array("indices")
            .map { it.jsonObject.str("name") }
        assertThat(names).contains("index_flight_history_categoryId")
        assertThat(names).contains("index_flight_history_startTimeMillis")
    }
}
