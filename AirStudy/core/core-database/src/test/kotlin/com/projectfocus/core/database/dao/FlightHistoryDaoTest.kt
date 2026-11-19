package com.projectfocus.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.database.entity.BlockedAppEntity
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.database.entity.FocusCategoryEntity
import com.projectfocus.core.database.migrations.Migrations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class FlightHistoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FlightHistoryDao
    private lateinit var categoryDao: FocusCategoryDao
    private lateinit var airportDao: AirportDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            // 外键必须显式开：Room 默认关，不开关不掉 CASCADE / SET NULL 就测不出来
            .addMigrations(*Migrations.ALL.toTypedArray())
            .allowMainThreadQueries()
            .build()
        dao = db.flightHistoryDao()
        categoryDao = db.focusCategoryDao()
        airportDao = db.airportDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具
    // ═══════════════════════════════════════════════════════════

    private fun flight(
        id: String,
        startTimeMillis: Long = 1_700_000_000_000L,
        endTimeMillis: Long? = startTimeMillis + 45 * 60_000L,
        durationMinutes: Int = 45,
        actualFocusMinutes: Int = 45,
        status: String = "COMPLETED",
        createdAt: Long = startTimeMillis,
        isSynced: Boolean = false,
        categoryId: Long? = null,
        destinationCountry: String = "China",
        destinationAirportCode: String = "SHA",
        focusType: String = "SOLO",
        roomId: String? = null,
    ): FlightHistoryEntity {
        // 本地合成夹具；真实路线公里与分钟独立保存，不以该数值推导原包口径。
        val distanceKm = durationMinutes / 60.0 * 800.0
        return FlightHistoryEntity(
            id = id,
            roomId = roomId,
            focusType = focusType,
            roomParticipantCount = null,
            departureAirportCode = "PEK",
            departureAirportName = "Beijing Capital International",
            departureCity = "Beijing",
            departureCountry = "China",
            departureLatitude = 40.0799,
            departureLongitude = 116.6031,
            destinationAirportCode = destinationAirportCode,
            destinationAirportName = "Shanghai Hongqiao",
            destinationCity = "Shanghai",
            destinationCountry = destinationCountry,
            destinationLatitude = 31.1979,
            destinationLongitude = 121.3363,
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            durationMinutes = durationMinutes,
            actualFocusMinutes = actualFocusMinutes,
            distanceKm = distanceKm,
            status = status,
            categoryId = categoryId,
            seat = null,
            hasAnnouncedDeparture = false,
            hasAnnouncedDescent = false,
            hasAnnouncedArrival = false,
            logJson = null,
            isSynced = isSynced,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    private suspend fun newCategory(name: String, isDefault: Boolean = false): Long =
        categoryDao.insert(
            FocusCategoryEntity(
                name = name,
                colorHex = "#4CAF50",
                emoji = "💻",
                isDefault = isDefault,
                sortOrder = 0,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

    @Test
    fun `R158 checkpoint reads current row and preserves a newer seat and frozen route`() = runTest {
        val stale = flight("checkpoint", status = "ONGOING", actualFocusMinutes = 0,
            endTimeMillis = null).copy(distanceKm = 1234.5, seat = "12A")
        dao.insert(stale)
        dao.update(stale.copy(seat = "14C", hasAnnouncedDeparture = true))
        val saved = dao.saveSessionCheckpoint(stale, 2, 100L)
        assertEquals(2, saved.actualFocusMinutes)
        assertEquals("14C", saved.seat)
        assertEquals(1234.5, saved.distanceKm, 0.0)
        assertTrue(saved.hasAnnouncedDeparture)
        assertEquals(saved, dao.findById(stale.id))
    }

    @Test
    fun `R158 completion commits latest fields once and cancellation keeps saved checkpoint`() = runTest {
        val stale = flight("terminal", status = "ONGOING", actualFocusMinutes = 2,
            endTimeMillis = null).copy(distanceKm = 1234.5, seat = "12A")
        dao.insert(stale)
        dao.update(stale.copy(seat = null, hasAnnouncedArrival = true))
        val completed = dao.saveSessionTerminal(stale.copy(status = "COMPLETED",
            actualFocusMinutes = 99, endTimeMillis = 500L, updatedAt = 100L))
        assertEquals(45, completed.actualFocusMinutes)
        assertNull(completed.seat)
        assertTrue(completed.hasAnnouncedArrival)
        assertEquals(1234.5, completed.distanceKm, 0.0)
        assertEquals(completed, dao.saveSessionTerminal(stale.copy(status = "COMPLETED",
            seat = "22D", endTimeMillis = 700L, updatedAt = 200L)))

        val ongoing = stale.copy(id = "cancelled")
        dao.insert(ongoing)
        val cancelled = dao.saveSessionTerminal(ongoing.copy(status = "CANCELLED",
            actualFocusMinutes = 20, endTimeMillis = 999L, updatedAt = 200L))
        assertEquals(2, cancelled.actualFocusMinutes)
        assertNull(cancelled.endTimeMillis)
        assertEquals(1234.5, cancelled.distanceKm, 0.0)
        assertEquals(cancelled, dao.findById(cancelled.id))
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    @Test
    fun `列表按 createdAt 倒序而不是 startTimeMillis 倒序`() = runTest {

        //   不是 startTimeMillis（起飞时刻）。
        //   后果：补录一条"上个月的飞行"，它会排在最前面（因为刚创建）。

        val oldFlightRecordedNow = flight(
            id = "old-flight-new-record",
            startTimeMillis = 1_600_000_000_000L, // 很早起飞
            createdAt = 1_800_000_000_000L, // 但刚刚才录入
        )
        val recentFlightRecordedLongAgo = flight(
            id = "recent-flight-old-record",
            startTimeMillis = 1_790_000_000_000L, // 最近起飞
            createdAt = 1_700_000_000_000L,
        )
        dao.insert(oldFlightRecordedNow)
        dao.insert(recentFlightRecordedLongAgo)

        val list = dao.getAll()
        assertEquals(2, list.size)
        // createdAt 大的在前 → 补录那条排第一
        assertEquals("old-flight-new-record", list[0].id)
        assertEquals("recent-flight-old-record", list[1].id)
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    @Test
    fun `待同步队列只包含已完成且未同步的记录`() = runTest {
        dao.insert(flight("done-unsynced", status = "COMPLETED", isSynced = false))
        dao.insert(flight("done-synced", status = "COMPLETED", isSynced = true))
        dao.insert(flight("ongoing-unsynced", status = "ONGOING", isSynced = false, endTimeMillis = null))
        dao.insert(flight("cancelled-unsynced", status = "CANCELLED", isSynced = false))

        val pending = dao.pendingSync()

        assertEquals(1, pending.size)
        assertEquals("done-unsynced", pending[0].id)
    }

    @Test
    fun `标记已同步后从待同步队列消失`() = runTest {
        dao.insert(flight("f1", status = "COMPLETED", isSynced = false))
        assertEquals(1, dao.pendingSync().size)

        dao.markSynced("f1", true)
        assertEquals(0, dao.pendingSync().size)

        val updated = dao.findById("f1")
        assertNotNull(updated)
        assertTrue("isSynced 应已被置为 true", updated!!.isSynced)
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    @Test
    fun `最近完成的航班按 endTimeMillis 取而不是 createdAt`() = runTest {
        dao.insert(flight("a", endTimeMillis = 1_700_000_100_000L, createdAt = 1_700_000_500_000L))
        dao.insert(flight("b", endTimeMillis = 1_700_000_900_000L, createdAt = 1_700_000_100_000L))
        // b 落地更晚但创建更早 → latestCompleted 应返回 b
        assertEquals("b", dao.latestCompleted()?.id)
    }

    @Test
    fun `最近完成的航班只考虑 COMPLETED`() = runTest {
        dao.insert(flight("done", endTimeMillis = 100L))
        dao.insert(flight("cancelled", endTimeMillis = 999L, status = "CANCELLED"))
        dao.insert(flight("ongoing", endTimeMillis = 999L, status = "ONGOING"))
        assertEquals("done", dao.latestCompleted()?.id)
    }

    @Test
    fun `没有任何完成记录时最近完成为 null`() = runTest {
        assertNull(dao.latestCompleted())
        assertNull(dao.ongoing())
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    @Test
    fun `进行中的航班只返回一条`() = runTest {
        dao.insert(flight("o1", status = "ONGOING", endTimeMillis = null))
        dao.insert(flight("o2", status = "ONGOING", endTimeMillis = null))

        assertNotNull(dao.ongoing())
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    @Test
    fun `去过的国家去重且只算已完成`() = runTest {
        dao.insert(flight("1", destinationCountry = "Japan"))
        dao.insert(flight("2", destinationCountry = "Japan"))
        dao.insert(flight("3", destinationCountry = "Korea"))
        dao.insert(flight("4", destinationCountry = "Thailand", status = "CANCELLED"))
        dao.insert(flight("5", destinationCountry = "France", status = "ONGOING", endTimeMillis = null))
        dao.insert(flight("6", destinationCountry = ""))

        val countries = dao.visitedCountries().sorted()
        assertEquals(listOf("Japan", "Korea"), countries)
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    @Test
    fun `删除单条不影响其他记录`() = runTest {
        dao.insert(flight("keep"))
        dao.insert(flight("drop"))
        dao.deleteById("drop")
        assertEquals(1, dao.getAll().size)
        assertEquals("keep", dao.getAll()[0].id)
    }

    // ═══════════════════════════════════════════════════════════

    //     「规则可以删，历史不能删」
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `删除分类后飞行记录的 categoryId 被置空但记录还在`() = runTest {
        val catId = newCategory("work")
        dao.insert(flight("f1", categoryId = catId))
        assertEquals(catId, dao.findById("f1")?.categoryId)

        // 直接删分类（DAO 的 deleteCustomById 带 isDefault=0 条件，
        // 这里用底层 SQL 删，模拟"分类被删"的任意路径）
        db.openHelper.writableDatabase.execSQL("DELETE FROM focus_categories WHERE id = $catId")

        val after = dao.findById("f1")
        assertNotNull("★ 历史记录必须还在（SET NULL 不是 CASCADE）", after)
        assertNull("★ 外键必须被置空", after!!.categoryId)
    }

    @Test
    fun `删除分类后飞行记录的其他字段完全不受影响`() = runTest {
        val catId = newCategory("work")
        val original = flight("f1", categoryId = catId)
        dao.insert(original)
        db.openHelper.writableDatabase.execSQL("DELETE FROM focus_categories WHERE id = $catId")

        val after = dao.findById("f1")!!
        assertEquals(original.departureAirportCode, after.departureAirportCode)
        assertEquals(original.destinationAirportCode, after.destinationAirportCode)
        assertEquals(original.distanceKm, after.distanceKm, 0.0001)
        assertEquals(original.durationMinutes, after.durationMinutes)
        assertEquals(original.actualFocusMinutes, after.actualFocusMinutes)
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    @Test
    fun `删除分类时屏蔽规则被级联删除`() = runTest {
        val catId = newCategory("work")
        val blockedDao = db.blockedAppDao()
        blockedDao.insert(
            BlockedAppEntity(
                categoryId = catId,
                packageName = "com.example.weibo",
                appName = "Weibo",
                type = "APP",
                createdAt = 0L,
            ),
        )
        assertEquals(1, blockedDao.getAll().size)

        db.openHelper.writableDatabase.execSQL("DELETE FROM focus_categories WHERE id = $catId")

        assertEquals("★ 屏蔽规则必须被 CASCADE 删掉", 0, blockedDao.getAll().size)
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    @Test
    fun `内置分类删不掉`() = runTest {
        val defaultId = newCategory("work", isDefault = true)
        val customId = newCategory("my-study", isDefault = false)


        val deletedDefault = categoryDao.deleteCustomById(defaultId)
        val deletedCustom = categoryDao.deleteCustomById(customId)

        assertEquals("★ 内置分类必须删不掉（返回 0 行受影响）", 0, deletedDefault)
        assertEquals("用户自建分类应能删掉", 1, deletedCustom)
        assertNotNull(categoryDao.findById(defaultId))
        assertNull(categoryDao.findById(customId))
    }

    @Test
    fun `分类排序是 sortOrder 升序再 name 升序`() = runTest {
        newCategory("zzz").also { id -> categoryDao.updateSortOrder(id, 0, 0L) }
        newCategory("aaa").also { id -> categoryDao.updateSortOrder(id, 0, 0L) }
        newCategory("mmm").also { id -> categoryDao.updateSortOrder(id, -1, 0L) }

        val names = categoryDao.getAll().map { it.name }
        // sortOrder: mmm(-1) < aaa(0)/zzz(0) → mmm 最先；同为 0 的按 name 升序 aaa < zzz
        assertEquals(listOf("mmm", "aaa", "zzz"), names)
    }

    @Test
    fun `自建分类列表排除内置`() = runTest {
        newCategory("work", isDefault = true)
        newCategory("custom1", isDefault = false)
        newCategory("custom2", isDefault = false)

        val custom = categoryDao.customCategories()
        assertEquals(2, custom.size)
        assertTrue(custom.none { it.isDefault })
    }

    // ═══════════════════════════════════════════════════════════
    // 聚合查询（数值必须与"在 Kotlin 层算"一致）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `聚合只统计已完成记录`() = runTest {
        dao.insert(flight("a", durationMinutes = 45, actualFocusMinutes = 45))
        dao.insert(flight("b", durationMinutes = 180, actualFocusMinutes = 150))
        dao.insert(flight("c", durationMinutes = 60, actualFocusMinutes = 60, status = "CANCELLED"))
        dao.insert(
            flight(
                "d",
                durationMinutes = 30,
                actualFocusMinutes = 10,
                status = "ONGOING",
                endTimeMillis = null,
            ),
        )

        // 45 + 150 = 195 分钟专注（b 的计划 180 但实际只专注 150）




        assertEquals(195, dao.observeTotalFocusMinutes().first())
        // 距离仍按 durationMinutes 算（飞行距离只由计划时长决定）
        // (45/60*800) + (180/60*800) = 600 + 2400 = 3000 km
        assertEquals(3000.0, dao.observeTotalDistanceKm().first(), 0.0001)
        assertEquals(2, dao.observeCompletedCount().first())
    }

    @Test
    fun `专注分钟用 actualFocusMinutes 而不是 durationMinutes`() = runTest {

        //   累计专注必须是 80，不是 120。这是"双时长"设计的核心。
        dao.insert(flight("x", durationMinutes = 120, actualFocusMinutes = 80))
        assertEquals(80, dao.observeTotalFocusMinutes().first())
        // 但距离按计划 120 分钟算 = 1600 km（不是 80 分钟的 1066.67）
        assertEquals(1600.0, dao.observeTotalDistanceKm().first(), 0.0001)
    }

    @Test
    fun `去过的机场数按目的地 IATA 去重`() = runTest {
        dao.insert(flight("1", destinationAirportCode = "SHA"))
        dao.insert(flight("2", destinationAirportCode = "SHA"))
        dao.insert(flight("3", destinationAirportCode = "CAN"))
        assertEquals(2, dao.observeVisitedAirportCount().first())
    }

    @Test
    fun `空国家与空机场不计入到访数`() = runTest {

        dao.insert(flight("empty", destinationCountry = "", destinationAirportCode = ""))
        dao.insert(flight("real", destinationCountry = "Japan", destinationAirportCode = "NRT"))
        assertEquals(1, dao.observeVisitedCountryCount().first())
        assertEquals(1, dao.observeVisitedAirportCount().first())
    }

    // ═══════════════════════════════════════════════════════════
    // 关键业务口径：distanceKm 只由 durationMinutes 决定
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `同等时长飞不同距离的机场记录的距离相同`() = runTest {

        //   与 45 分钟飞 PEK→NRT（真实 2100 km），记录的 distanceKm 都是 600。
        //   这条行为如果复刻错，统计/印章/分享卡片全部会飘。
        dao.insert(flight("pek-sha", durationMinutes = 45, destinationAirportCode = "SHA"))
        dao.insert(flight("pek-nrt", durationMinutes = 45, destinationAirportCode = "NRT"))

        val sha = dao.findById("pek-sha")!!
        val nrt = dao.findById("pek-nrt")!!
        assertEquals(600.0, sha.distanceKm, 0.0001)
        assertEquals(600.0, nrt.distanceKm, 0.0001)
    }

    // ═══════════════════════════════════════════════════════════
    // 事务：批量插入要么全成要么全不成
    // ═══════════════════════════════════════════════════════════


    // ═══════════════════════════════════════════════════════════
    // 小组件：todaySummary / completedStartTimesSince
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `今日汇总只统计区间内的已完成飞行`() = runTest {
        val day = 1_790_000_000_000L          // 某个整点
        val dayEnd = day + 86_400_000L
        dao.insert(flight("in1", startTimeMillis = day + 1_000, actualFocusMinutes = 45))
        dao.insert(flight("in2", startTimeMillis = day + 3_600_000, actualFocusMinutes = 30))
        // 区间前 1 毫秒
        dao.insert(flight("before", startTimeMillis = day - 1, actualFocusMinutes = 60))
        // 区间末尾（左闭右开 → 不算今天）
        dao.insert(flight("after", startTimeMillis = dayEnd, actualFocusMinutes = 90))

        val row = dao.todaySummary(day, dayEnd)!!
        assertEquals(2, row.sessionCount)
        assertEquals(75, row.focusMinutes)
    }

    @Test
    fun `今日汇总之和不重复计算左侧边界`() = runTest {

        //   BETWEEN 的写法在这里看似正确，但右边界会多算 —— 见下一条。
        val day = 1_780_000_000_000L
        dao.insert(flight("edge", startTimeMillis = day, actualFocusMinutes = 25))
        val row = dao.todaySummary(day, day + 86_400_000L)!!
        assertEquals(1, row.sessionCount)
    }

    @Test
    fun `今日汇总排除进行中与已取消`() = runTest {
        val day = 1_770_000_000_000L
        dao.insert(flight("ok", startTimeMillis = day + 100, actualFocusMinutes = 45))
        dao.insert(flight("ongoing", startTimeMillis = day + 200, status = "ONGOING", endTimeMillis = null))
        dao.insert(flight("cancelled", startTimeMillis = day + 300, status = "CANCELLED"))

        val row = dao.todaySummary(day, day + 86_400_000L)!!
        assertEquals(1, row.sessionCount)
        assertEquals(45, row.focusMinutes)
    }

    @Test
    fun `今天一条都没有时返回零而不是 null`() = runTest {

        //   如果 SQL 里没写 COALESCE，Room 映射到非空 Int 会抛 NullPointerException，
        //   而这恰好发生在新装用户第一次放小组件的时候。
        val row = dao.todaySummary(1_760_000_000_000L, 1_760_086_400_000L)!!
        assertEquals(0, row.sessionCount)
        assertEquals(0, row.focusMinutes)
        assertEquals(0.0, row.distanceKm, 0.0001)
    }

    @Test
    fun `今日里程按计划时长累加而不是实际时长`() = runTest {
        val day = 1_750_000_000_000L
        // 计划 120 分钟、实际只专注 80 → 里程按 120 分钟 = 1600 km
        dao.insert(flight("a", startTimeMillis = day + 10, durationMinutes = 120, actualFocusMinutes = 80))
        val row = dao.todaySummary(day, day + 86_400_000L)!!
        assertEquals(80, row.focusMinutes)
        assertEquals(1600.0, row.distanceKm, 0.0001)
    }

    @Test
    fun `起飞时刻列表按时间升序且跳过进行中`() = runTest {
        val base = 1_740_000_000_000L
        dao.insert(flight("b", startTimeMillis = base + 3))
        dao.insert(flight("a", startTimeMillis = base + 1))
        dao.insert(flight("ongoing", startTimeMillis = base + 2, status = "ONGOING", endTimeMillis = null))
        dao.insert(flight("old", startTimeMillis = base - 1))

        val times = dao.completedStartTimesSince(base)
        assertEquals(listOf(base + 1, base + 3), times)
    }

    @Test
    fun `起飞时刻列表保留同一天的重复以便上层去重`() = runTest {

        //   这里显式钉住"会返回重复"这个契约，防止有人"优化"成 distinct。
        val base = 1_730_000_000_000L
        dao.insert(flight("m", startTimeMillis = base + 10))
        dao.insert(flight("n", startTimeMillis = base + 20))
        assertEquals(listOf(base + 10, base + 20), dao.completedStartTimesSince(base))
    }

    @Test
    fun `主键冲突时插入抛异常且不留下半条数据`() = runTest {
        dao.insert(flight("dup"))
        var threw = false
        try {
            dao.insert(flight("dup"))
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            threw = true
        }
        assertTrue("重复主键必须抛约束异常（ABORT 策略）", threw)
        assertEquals("不应产生第二条", 1, dao.getAll().size)
    }

    @Test
    fun `country breakdown counts completed trips and dedupes airports`() = runTest {
        dao.insert(flight("c1", destinationCountry = "China", destinationAirportCode = "SHA"))
        dao.insert(flight("c2", destinationCountry = "China", destinationAirportCode = "PEK"))
        dao.insert(flight("c3", destinationCountry = "China", destinationAirportCode = "SHA"))
        dao.insert(flight("j1", destinationCountry = "Japan", destinationAirportCode = "NRT"))
        dao.insert(
            flight(
                "ongoing",
                status = "ONGOING",
                endTimeMillis = null,
                destinationCountry = "China",
                destinationAirportCode = "CAN",
            ),
        )

        val rows = dao.observeCountryBreakdown().first()

        assertEquals(2, rows.size)
        assertEquals("China", rows[0].country)
        assertEquals(3, rows[0].tripCount)
        assertEquals(2, rows[0].visitedAirports)
        assertEquals(0, rows[0].totalAirports)
        assertEquals("Japan", rows[1].country)
        assertEquals(1, rows[1].tripCount)
    }

    @Test
    fun `country breakdown excludes blank country`() = runTest {
        dao.insert(flight("blank", destinationCountry = ""))
        dao.insert(flight("china", destinationCountry = "China"))

        val rows = dao.observeCountryBreakdown().first()

        assertEquals(listOf("China"), rows.map { it.country })
    }

    @Test
    fun `country breakdown counts airports in denominator`() = runTest {
        airportDao.upsertAll(
            listOf(
                airport(1, "SHA", "China"),
                airport(2, "PEK", "China"),
                airport(3, "NRT", "Japan"),
            ),
        )
        dao.insert(flight("c1", destinationCountry = "China", destinationAirportCode = "SHA"))
        dao.insert(flight("j1", destinationCountry = "Japan", destinationAirportCode = "NRT"))

        val rows = dao.observeCountryBreakdown().first()

        assertEquals(2, rows[0].totalAirports)
        assertEquals(1, rows[1].totalAirports)
    }

    @Test
    fun `visited airport codes are distinct and scoped to country`() = runTest {
        dao.insert(flight("a", destinationCountry = "China", destinationAirportCode = "SHA"))
        dao.insert(flight("b", destinationCountry = "China", destinationAirportCode = "SHA"))
        dao.insert(flight("c", destinationCountry = "China", destinationAirportCode = "PEK"))
        dao.insert(flight("j", destinationCountry = "Japan", destinationAirportCode = "NRT"))
        dao.insert(
            flight(
                "ongoing",
                status = "ONGOING",
                endTimeMillis = null,
                destinationCountry = "China",
                destinationAirportCode = "CAN",
            ),
        )
        dao.insert(flight("blank", destinationCountry = "China", destinationAirportCode = ""))

        assertEquals(
            setOf("SHA", "PEK"),
            dao.visitedAirportCodesForCountry("China").toSet(),
        )
        assertTrue(dao.visitedAirportCodesForCountry("France").isEmpty())
    }

    private fun airport(id: Long, iata: String, country: String) = AirportEntity(
        id = id,
        iataCode = iata,
        icaoCode = "X$iata",
        name = "$iata Airport",
        city = "City",
        country = country,
        latitude = 0.0,
        longitude = 0.0,
        elevation = 0,
        continent = "AS",
        type = "large_airport",
        popularity = 1,
        isPremiumOnly = false,
    )
}
