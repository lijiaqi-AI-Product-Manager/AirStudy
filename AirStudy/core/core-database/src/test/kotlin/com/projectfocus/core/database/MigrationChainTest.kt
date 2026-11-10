package com.projectfocus.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.database.migrations.Migrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MigrationChainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `v1 老库迁移到最新版本并通过 Room 校验`() {
        createRaw(version = 1, statements = listOf(LEGACY_V1, LEGACY_ROW))

        withRoom { db ->
            db.query(
                "SELECT status, departureAirportCode, actualFocusMinutes, syncedAt " +
                    "FROM flight_history WHERE id = 'legacy-1'",
            ).use { c ->
                assertTrue("旧数据应搬进 flight_history", c.moveToFirst())
                assertEquals("ONGOING", c.getString(0))
                assertEquals("PEK", c.getString(1))
                assertEquals(60, c.getInt(2))
                assertTrue("syncedAt 未同步应为 NULL", c.isNull(3))
            }
            assertEquals(0, count(db, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'flight_sessions'"))
        }
    }

    @Test
    fun `全新安装的 v21 库升级到 22，唯一索引去重时引用改指保留行`() {
        val schema = schemaJson(21)
        val statements = createSqlOf(schema) + listOf(
            // 两个同名内置分类（v21 新装库没有唯一索引，确实可能出现）
            insertSql(schema, "focus_categories", mapOf("id" to 1, "name" to "work", "isDefault" to 1)),
            insertSql(schema, "focus_categories", mapOf("id" to 2, "name" to "work", "isDefault" to 1)),
            // 历史记录挂在将被删掉的重复分类上
            insertSql(schema, "flight_history", mapOf("id" to "h-1", "categoryId" to 2)),
            // 同一 App 分别挂在两个重复分类上：改指后会变成重复行，必须再去重
            insertSql(schema, "blocked_apps", mapOf("id" to 1, "categoryId" to 2, "packageName" to "com.example")),
            insertSql(schema, "blocked_apps", mapOf("id" to 2, "categoryId" to 1, "packageName" to "com.example")),
        )
        createRaw(version = 21, statements = statements)

        withRoom { db ->
            assertEquals(1, count(db, "SELECT COUNT(*) FROM focus_categories WHERE name = 'work'"))
            assertEquals(1, count(db, "SELECT id FROM focus_categories WHERE name = 'work'"))
            assertEquals("历史记录应改指到保留的分类，而不是被置空", 1,
                count(db, "SELECT categoryId FROM flight_history WHERE id = 'h-1'"))
            assertEquals(1, count(db, "SELECT COUNT(*) FROM blocked_apps WHERE packageName = 'com.example'"))
            assertEquals(1, count(db, "SELECT categoryId FROM blocked_apps WHERE packageName = 'com.example'"))
            // 21→22 补的列（Robolectric 的 SQLite 不支持 pragma_table_info() 表值函数，用 PRAGMA 遍历）
            assertTrue("stamps 应补上 assetVersion", "assetVersion" in columnsOf(db, "stamps"))
            assertTrue("flight_history 应补上 syncedAt", "syncedAt" in columnsOf(db, "flight_history"))
            assertTrue("airports 列应保持 nameZh/cityZh",
                columnsOf(db, "airports").containsAll(listOf("nameZh", "cityZh")))
        }
    }

    @Test
    fun v25UpgradeAddsIndependentSceneLabelsWithoutRewritingEitherFlightArchive() {
        val schema = schemaJson(25)
        createRaw(25, createSqlOf(schema) + listOf(
            insertSql(schema, "flight_history", mapOf("id" to "same-id", "status" to "COMPLETED",
                "ownerScope" to "retained-owner", "sceneSnapshotJson" to "opaque countdown scene")),
            insertSql(schema, "free_flight_archive", mapOf("sessionId" to "same-id", "ownerScope" to "retained-owner",
                "revision" to 4, "status" to "COMPLETED", "payloadJson" to "opaque independent archive")),
            insertSql(schema, "free_flight_position", mapOf("sessionId" to "same-id", "sequence" to 1)),
        ))
        withRoom { db ->
            assertEquals(0, count(db, "SELECT COUNT(*) FROM flight_record_scene"))
            db.query("SELECT sceneSnapshotJson, ownerScope FROM flight_history WHERE id = 'same-id'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("opaque countdown scene", row.getString(0))
                assertEquals("retained-owner", row.getString(1))
            }
            db.query("SELECT payloadJson, revision FROM free_flight_archive WHERE sessionId = 'same-id'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("opaque independent archive", row.getString(0))
                assertEquals(4L, row.getLong(1))
            }
            assertEquals(1, count(db, "SELECT COUNT(*) FROM free_flight_position WHERE sessionId = 'same-id'"))
            db.query("PRAGMA foreign_key_check").use { assertTrue(!it.moveToFirst()) }
        }
    }

    // ── helpers ─────────────────────────────────────────────

    /** 用 Room 打开并强制建连（触发迁移与校验），回调里拿底层连接做断言。 */
    @Test
    fun `v22 rows migrate to nullable independent plan metadata without changing legacy values`() {
        val schema = schemaJson(22)
        val statements = createSqlOf(schema) + listOf(
            insertSql(schema, "focus_categories", mapOf("id" to 7, "name" to "legacy-category", "isDefault" to 0)),
            insertSql(schema, "flight_history", mapOf(
                "id" to "legacy-metadata", "categoryId" to 7, "seat" to "12A",
                "quote" to "旧金句", "quoteAuthor" to "旧作者", "syncedAt" to 123456789L,
                "status" to "ONGOING", "durationMinutes" to 12, "actualFocusMinutes" to 3,
            )),
        )
        createRaw(version = 22, statements = statements)
        withRoom { db ->
            assertEquals(38, columnsOf(db, "flight_history").size)
            db.query("SELECT categoryId, seat, quote, quoteAuthor, syncedAt, sceneSnapshotJson, " +
                "plannedDepartureJson, durationMinutes, actualFocusMinutes FROM flight_history WHERE id = 'legacy-metadata'")
                .use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(7, c.getInt(0))
                    assertEquals("12A", c.getString(1))
                    assertEquals("旧金句", c.getString(2))
                    assertEquals("旧作者", c.getString(3))
                    assertEquals(123456789L, c.getLong(4))
                    assertTrue(c.isNull(5))
                    assertTrue(c.isNull(6))
                    assertEquals(12, c.getInt(7))
                    assertEquals(3, c.getInt(8))
                }
            db.query("PRAGMA foreign_key_check").use { assertTrue(!it.moveToFirst()) }
            db.execSQL("UPDATE flight_history SET sceneSnapshotJson = ?, plannedDepartureJson = ? " +
                "WHERE id = 'legacy-metadata'", arrayOf("""{"futureScene":true}""", """{"futureDeparture":true}"""))
        }
        // A real close/reopen also verifies current Room schema validation.
        withRoom { db ->
            db.query("SELECT sceneSnapshotJson, plannedDepartureJson FROM flight_history WHERE id = 'legacy-metadata'")
                .use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("""{"futureScene":true}""", c.getString(0))
                    assertEquals("""{"futureDeparture":true}""", c.getString(1))
                }
        }
    }

    @Test
    fun v24UpgradePreservesEveryArchiveAndLeavesLegacyOwnershipUnassigned() {
        val schema = schemaJson(24)
        createRaw(24, createSqlOf(schema) + listOf(
            insertSql(schema, "flight_history", mapOf("id" to "unassigned-old-record", "status" to "COMPLETED",
                "durationMinutes" to 25, "actualFocusMinutes" to 23, "distanceKm" to 117.75,
                "quote" to "retained quote", "sceneSnapshotJson" to "{\"custom\":true}")),
            insertSql(schema, "free_flight_archive", mapOf("sessionId" to "independent-record", "ownerScope" to "retained-owner",
                "revision" to 4, "status" to "COMPLETED", "payloadJson" to "opaque preserved bytes")),
        ))
        withRoom { db ->
            db.query("SELECT durationMinutes, actualFocusMinutes, distanceKm, quote, sceneSnapshotJson, ownerScope FROM flight_history WHERE id = 'unassigned-old-record'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(25, row.getInt(0)); assertEquals(23, row.getInt(1))
                assertEquals(117.75, row.getDouble(2), 0.0)
                assertEquals("retained quote", row.getString(3))
                assertEquals("{\"custom\":true}", row.getString(4))
                assertTrue(row.isNull(5))
            }
            db.query("SELECT ownerScope, revision, payloadJson FROM free_flight_archive WHERE sessionId = 'independent-record'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("retained-owner", row.getString(0)); assertEquals(4, row.getInt(1))
                assertEquals("opaque preserved bytes", row.getString(2))
            }
            db.query("PRAGMA foreign_key_check").use { assertTrue(!it.moveToFirst()) }
        }
    }

    @Test
    fun v26UpgradeLeavesUnassignedFlightNumbersUnknownAndPreservesArchiveData() {
        val schema = schemaJson(26)
        createRaw(26, createSqlOf(schema) + listOf(
            insertSql(schema, "flight_history", mapOf("id" to "legacy-number", "status" to "COMPLETED",
                "ownerScope" to "retained-owner", "seat" to "04F", "actualFocusMinutes" to 23,
                "sceneSnapshotJson" to "opaque scene", "plannedDepartureJson" to "opaque clock")),
        ))
        withRoom { db ->
            db.query("SELECT flightNumber, ownerScope, seat, actualFocusMinutes, sceneSnapshotJson, plannedDepartureJson " +
                "FROM flight_history WHERE id = 'legacy-number'").use { row ->
                assertTrue(row.moveToFirst()); assertTrue(row.isNull(0))
                assertEquals("retained-owner", row.getString(1)); assertEquals("04F", row.getString(2))
                assertEquals(23, row.getInt(3)); assertEquals("opaque scene", row.getString(4))
                assertEquals("opaque clock", row.getString(5))
            }
            db.execSQL("UPDATE flight_history SET flightNumber = ? WHERE id = 'legacy-number'", arrayOf("AS2701"))
            db.query("PRAGMA foreign_key_check").use { assertTrue(!it.moveToFirst()) }
        }
        withRoom { db ->
            db.query("SELECT flightNumber FROM flight_history WHERE id = 'legacy-number'").use { row ->
                assertTrue(row.moveToFirst()); assertEquals("AS2701", row.getString(0))
            }
        }
    }

    private fun withRoom(block: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit) {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(*Migrations.ALL.toTypedArray())
            .allowMainThreadQueries()
            .build()
        try {
            block(room.openHelper.writableDatabase)
        } finally {
            room.close()
        }
    }

    /** 不经过 Room，直接建一个指定 user_version 的库。 */
    private fun createRaw(version: Int, statements: List<Pair<String, Array<Any?>>>) {
        context.deleteDatabase(DB_NAME)
        val file = context.getDatabasePath(DB_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            statements.forEach { (sql, args) -> db.execSQL(sql, args) }
            db.version = version
        }
    }

    private fun columnsOf(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIndex = c.getColumnIndexOrThrow("name")
            buildSet { while (c.moveToNext()) add(c.getString(nameIndex)) }
        }

    /** 取首行首列的整数（COUNT 或单个 id 值）。 */
    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

    /** 单测工作目录是模块根（同 MigrationRegistryTest）。 */
    private fun schemaJson(version: Int): JsonObject =
        Json.parseToJsonElement(
            File("schemas/com.projectfocus.core.database.AppDatabase/$version.json").readText(),
        ).jsonObject["database"]!!.jsonObject

    private fun entities(schema: JsonObject): List<JsonObject> =
        schema["entities"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun createSqlOf(schema: JsonObject): List<Pair<String, Array<Any?>>> =
        entities(schema).flatMap { e ->
            val table = e.str("tableName")
            val indices = e["indices"]?.jsonArray?.map { it.jsonObject.str("createSql") }.orEmpty()
            (listOf(e.str("createSql")) + indices).map { it.replace("\${TABLE_NAME}", table) to emptyArray() }
        }

    /** 按 schema 的字段表拼 INSERT：未指定的非空列按亲和类型给占位值，可空列给 NULL。 */
    private fun insertSql(
        schema: JsonObject,
        table: String,
        values: Map<String, Any?>,
    ): Pair<String, Array<Any?>> {
        val fields = entities(schema).first { it.str("tableName") == table }["fields"]!!.jsonArray
            .map { it.jsonObject }
        val columns = fields.map { it.str("columnName") }
        val args = fields.map { f ->
            val name = f.str("columnName")
            when {
                name in values -> values[name]
                f["notNull"]?.jsonPrimitive?.booleanOrNull == true -> when (f.str("affinity")) {
                    "INTEGER" -> 0
                    "REAL" -> 0.0
                    else -> "x"
                }
                else -> null
            }
        }
        val sql = "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) " +
            "VALUES (${columns.joinToString { "?" }})"
        return sql to args.toTypedArray()
    }

    private companion object {
        const val DB_NAME = "migration-chain-test.db"

        val LEGACY_V1: Pair<String, Array<Any?>> = """
            CREATE TABLE `flight_sessions` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `startAirportIata` TEXT, `startAirportName` TEXT, `startAirportCity` TEXT,
                `destinationAirportIata` TEXT, `destinationAirportName` TEXT,
                `destinationAirportCity` TEXT, `startTimeMillis` INTEGER, `endTimeMillis` INTEGER,
                `durationMinutes` INTEGER, `focusTimeMinutes` INTEGER, `distanceKm` REAL,
                `status` TEXT, `categoryId` INTEGER
            )
        """.trimIndent() to emptyArray()

        val LEGACY_ROW: Pair<String, Array<Any?>> = """
            INSERT INTO `flight_sessions` VALUES (
                'legacy-1', 'PEK', 'Beijing Capital', 'Beijing', 'SHA', 'Hongqiao', 'Shanghai',
                1700000000000, NULL, 120, 60, 1088.0, 'IN_PROGRESS', NULL)
        """.trimIndent() to emptyArray()
    }
}
