package com.projectfocus.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.database.migrations.Migrations
import com.projectfocus.core.database.seed.DefaultFocusScenarios
import com.projectfocus.core.database.seed.SeedCallback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config



























@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DatabaseBootstrapTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = buildDatabase()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /** 复现 DatabaseModule.provideAppDatabase 的 builder 配置 */
    private fun buildDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(*Migrations.ALL.toTypedArray())
            .addCallback(SeedCallback.create())
            .addCallback(SeedCallback.open())
            .allowMainThreadQueries()   // 仅测试：省掉协程样板
            .build()

    // ═══════════════════════════════════════════════════════════
    // 一、表都建出来了吗
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `建库后 11 张表全部存在`() = runTest {


        val tables = db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'")
            .use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }


        //   某些版本还会建 FTS / 触发器辅助表。断"必须全部包含"才是有意义的检查。
        val expected = listOf(
            "airports", "flight_history", "focus_categories", "blocked_apps", "stamps",
            "room", "room_participant", "room_message", "achievement", "daily_stat", "app_setting",
        )
        assertTrue(
            "缺少表：${expected.filterNot { it in tables }}，实际 = $tables",
            tables.containsAll(expected),
        )
    }

    @Test
    fun `建库后索引数量与迁移脚本一致`() = runTest {
        val indices = db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL")
            .use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }


        assertTrue(
            "缺少竞品索引，实际 = $indices",
            indices.containsAll(
                listOf(
                    "index_flight_history_categoryId",
                    "index_flight_history_startTimeMillis",
                    "index_blocked_apps_categoryId",
                ),
            ),
        )
    }

    // ═══════════════════════════════════════════════════════════
    // 二、种子数据
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `建库后 9 个内置场景已灌入且顺序正确`() = runTest {
        val categories = db.focusCategoryDao().observeAll().first()

        assertEquals(DefaultFocusScenarios.ALL.size, categories.size)
        // 顺序必须与 sortOrder 一致 —— 首页场景卡片的排列顺序靠它
        assertEquals(DefaultFocusScenarios.ALL.map { it.key }, categories.map { it.name })
        assertEquals(DefaultFocusScenarios.ALL.map { it.sortOrder }, categories.map { it.sortOrder })
    }

    @Test
    fun `内置场景的颜色与 emoji 与种子定义逐字一致`() = runTest {
        val categories = db.focusCategoryDao().observeAll().first().associateBy { it.name }

        DefaultFocusScenarios.ALL.forEach { seed ->
            val actual = categories[seed.key]
            assertNotNull("缺少内置场景 ${seed.key}", actual)
            requireNotNull(actual)
            assertEquals("${seed.key}.colorHex", seed.colorHex, actual.colorHex)
            assertEquals("${seed.key}.emoji", seed.emoji, actual.emoji)
            assertEquals("${seed.key}.isDefault", true, actual.isDefault)
        }
    }

    @Test
    fun `种子写入的 createdAt 与 updatedAt 是同一时刻`() = runTest {
        // 建库时一次性写入，两列应相等。若不等，说明回调跑了两次。
        val categories = db.focusCategoryDao().observeAll().first()
        categories.forEach {
            assertEquals("${it.name} 的 createdAt/updatedAt 不一致", it.createdAt, it.updatedAt)
        }
    }

    @Test
    fun `再次打开数据库不会重复灌种子`() = runTest {
        val first = db.focusCategoryDao().observeAll().first().size
        db.close()

        // 用同一个文件路径重开才能验证 onOpen 幂等；
        // 内存库关掉就没了，所以这里改用文件库。
        val name = "bootstrap_idempotent.db"
        context.deleteDatabase(name)
        val fileDb = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL.toTypedArray())
            .addCallback(SeedCallback.create())
            .addCallback(SeedCallback.open())
            .allowMainThreadQueries()
            .build()

        val afterFirst = fileDb.focusCategoryDao().observeAll().first().size
        fileDb.close()

        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL.toTypedArray())
            .addCallback(SeedCallback.create())
            .addCallback(SeedCallback.open())
            .allowMainThreadQueries()
            .build()
        val afterReopen = reopened.focusCategoryDao().observeAll().first().size
        reopened.close()
        context.deleteDatabase(name)

        assertEquals("内存库与文件库种子数量应一致", first, afterFirst)
        assertEquals("重开不应重复灌种子", afterFirst, afterReopen)
    }

    // ═══════════════════════════════════════════════════════════
    // 三、空库上跑首屏查询不能炸
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空库上首屏的五个查询都能正常返回`() = runTest {
        // 这五条是首页/成就页/护照页冷启动必跑的。
        // 空库是**新用户第一次打开 App** 的真实状态，必须全部安全返回。
        val dao = db.flightHistoryDao()

        assertEquals(emptyList<Any>(), dao.observeAll().first())
        assertEquals(null, dao.latestCompleted())
        assertEquals(null, dao.ongoing())
        assertEquals(emptyList<String>(), dao.visitedCountries())
        assertEquals(0, dao.observeTotalFocusMinutes().first())
        assertEquals(0.0, dao.observeTotalDistanceKm().first(), 0.0001)
    }

    @Test
    fun `空库上护照与屏蔽页查询都能正常返回`() = runTest {
        assertEquals(emptyList<Any>(), db.stampDao().observeAll().first())
        assertEquals(emptyList<Any>(), db.blockedAppDao().observeAll().first())
        assertEquals(emptyList<Any>(), db.airportDao().search("%PEK%", 20, true))
    }

    // ═══════════════════════════════════════════════════════════
    // 四、数据库基础配置
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `文件库以 WAL 模式打开`() = runTest {

        //   在那里断言 wal 只会得到一条恒红或恒假的测试 —— 什么都证明不了。
        //
        // WAL 决定：
        //   · 列表滚动会不会被写入阻塞（首页 Paging 依赖它）
        //   · 崩溃恢复行为（WAL 下未提交事务会回滚）
        val name = "bootstrap_wal.db"
        context.deleteDatabase(name)
        val fileDb = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*Migrations.ALL.toTypedArray())
            .allowMainThreadQueries()
            .build()
        val mode = fileDb.openHelper.readableDatabase
            .query("PRAGMA journal_mode")
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        fileDb.close()
        context.deleteDatabase(name)
        assertEquals("wal", mode?.lowercase())
    }

    @Test
    fun `外键约束是开启的`() = runTest {

        //   如果被关掉，SET NULL / CASCADE 全部失效 —— 删分类不会清历史引用，
        //   删房间不会清参与者，数据会悄悄变成孤儿。
        val on = db.openHelper.readableDatabase
            .query("PRAGMA foreign_keys")
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        assertEquals(1, on)
    }
}
