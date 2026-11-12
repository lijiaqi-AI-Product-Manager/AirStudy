package com.projectfocus.core.database

import androidx.room.migration.Migration
import com.projectfocus.core.database.migrations.Migrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File


class MigrationRegistryTest {

    private val migrations: List<Migration> = Migrations.ALL

    @Test
    fun `迁移数量与版本号匹配`() {
        // v1 起，每个版本一步，到 VERSION 一共需要 VERSION-1 个迁移
        assertEquals(
            "迁移数必须等于 VERSION - 1（VERSION=${AppDatabase.VERSION}）",
            AppDatabase.VERSION - 1,
            migrations.size,
        )
    }

    @Test
    fun `迁移必须从 1 连续覆盖到 VERSION`() {
        val sorted = migrations.sortedBy { it.startVersion }
        sorted.forEachIndexed { index, migration ->
            val expectedStart = index + 1
            assertEquals(
                "第 ${index + 1} 个迁移的 startVersion 应为 $expectedStart",
                expectedStart,
                migration.startVersion,
            )
            assertEquals(
                "迁移 ${migration.startVersion} 的 endVersion 应为 ${expectedStart + 1}",
                expectedStart + 1,
                migration.endVersion,
            )
        }
        assertEquals(
            "最后一个迁移的 endVersion 必须等于 VERSION",
            AppDatabase.VERSION,
            sorted.last().endVersion,
        )
    }

    @Test
    fun `不能有重复的起始版本`() {
        val starts = migrations.map { it.startVersion }
        assertEquals(
            "存在重复的 startVersion：$starts",
            starts.size,
            starts.toSet().size,
        )
    }

    @Test
    fun `版本号是 27`() {
        // 这个断言看起来多余，但它的作用是：改 VERSION 时强制改这里，
        // 从而强制去读一遍 AppDatabase 注释里的版本分配表，
        // 确认新加的版本真的在表里有说明（防止"顺手加个版本"）。


        assertEquals(27, AppDatabase.VERSION)
    }

    // ═══════════════════════════════════════════════════════════
    // schema 资产校验
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `当前版本 schema 已导出且提交在仓库里`() {
        val schemaFile = File(schemaDir(), "${AppDatabase.VERSION}.json")
        assertTrue(
            "找不到 schema 文件 ${schemaFile.absolutePath}。" +
                "exportSchema=true 时每加一个版本都必须把导出的 json 提交进 git，" +
                "否则 MigrationTestHelper 无法校验。先跑一次 assembleDebug。",
            schemaFile.exists(),
        )
        assertNotNull(schemaFile.readText().takeIf { it.isNotBlank() })
    }

    @Test
    fun `schema 的五张基线表包含本品增量列`() {
        val schemaFile = File(schemaDir(), "${AppDatabase.VERSION}.json")
        if (!schemaFile.exists()) return // 由上一个测试负责报错

        val text = schemaFile.readText()



        //             airports 的 nameZh / cityZh，stamps 的 assetVersion → 合计 74
        // 每一处都在 docs/ddl-alignment.md 里有理由，并由
        // SchemaDocParityTest 的「论断锚」逐条钉住。

        val expected = mapOf(
            "airports" to 15, // 13 + nameZh + cityZh（中文名，修「全英文界面」）
            "flight_history" to 38, // Includes nullable account ownership and assigned flight number.
            "focus_categories" to 8,
            "blocked_apps" to 6,
            "stamps" to 11, // 10 + assetVersion
        )

        expected.forEach { (table, expectedColumns) ->
            val actual = countColumns(text, table)
            assertEquals("表 $table 列数不符", expectedColumns, actual)
        }
    }














    private fun countColumns(schemaText: String, table: String): Int {
        val tableIdx = schemaText.indexOf("\"tableName\": \"$table\"")
        if (tableIdx < 0) return -1
        val fieldsIdx = schemaText.indexOf("\"fields\"", tableIdx)
        if (fieldsIdx < 0) return -1
        val arrayStart = schemaText.indexOf('[', fieldsIdx)
        if (arrayStart < 0) return -1

        // 找到与 arrayStart 配对的 ']'（字段数组里没有嵌套数组）
        var depth = 0
        var arrayEnd = -1
        for (i in arrayStart until schemaText.length) {
            when (schemaText[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        arrayEnd = i
                        break
                    }
                }
            }
        }
        if (arrayEnd < 0) return -1

        val arrayBody = schemaText.substring(arrayStart, arrayEnd)
        return Regex("\"columnName\"").findAll(arrayBody).count()
    }

    private fun schemaDir(): File {
        // 单测的 working dir 是模块根目录（core/core-database）
        return File("schemas/com.projectfocus.core.database.AppDatabase")
    }
}
