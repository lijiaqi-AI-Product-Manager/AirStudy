package com.projectfocus.feature.record.list

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.dao.CountryTripCount
import com.projectfocus.core.database.dao.DailyFocusRow
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.service.focus.session.TodayStatsNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime


@OptIn(ExperimentalCoroutinesApi::class)
class RecordListViewModelTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-10-26 15:00 北京时间 */
    private val nowMs = ZonedDateTime.of(2026, 10, 26, 15, 0, 0, 0, zone)
        .toInstant().toEpochMilli()

    private val clock = Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneId.of("UTC"))

    private lateinit var dao: FakeDao
    private lateinit var vm: RecordListViewModel









    private val notifier = TodayStatsNotifier()

    @Before
    fun setUp() {

        //   和 Main 的 scheduler 变成两个（见类注释第三条教训）。
        //   Main 的绑定放在 [collectState] 里，用 `testScheduler` 绑。
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }


    private fun TestScope.newVm(rows: List<FlightHistoryEntity> = emptyList()): RecordListViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        dao = FakeDao(rows)
        return RecordListViewModel(dao, clock, zone, notifier, com.projectfocus.feature.record.testHistoryDeletion(dao, notifier)).also { vm = it }
    }


    private fun TestScope.startCollect(
        target: RecordListViewModel,
        sink: MutableList<RecordListState> = mutableListOf(),
    ): MutableList<RecordListState> {
        backgroundScope.launch(Dispatchers.Unconfined) {
            target.state.collect { sink += it }
        }
        return sink
    }

    /** [startCollect] + 推进调度器到空闲 —— 90% 的测试要的是这个 */
    private fun TestScope.collectState(
        target: RecordListViewModel,
        sink: MutableList<RecordListState> = mutableListOf(),
    ): MutableList<RecordListState> = startCollect(target, sink).also { advanceUntilIdle() }






    private fun TestScope.trace(target: RecordListViewModel): MutableList<RecordListState> =
        startCollect(target).also { advanceUntilIdle() }

    /** 取当前状态 —— 语义上比 `as Content` 强转安全：失败信息会带上实际值 */
    private fun RecordListViewModel.content(): RecordListState.Content {
        val s = state.value
        assertThat(s).isInstanceOf(RecordListState.Content::class.java)
        return s as RecordListState.Content
    }

    private fun RecordListState.Content.itemCount(): Int = groups.sumOf { it.items.size }

    private fun at(h: Int, mi: Int = 0, day: Int = 26): Long =
        ZonedDateTime.of(2026, 10, day, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun entity(
        id: String,
        startMs: Long,
        toCity: String = "大阪",
        actual: Int = 45,
        status: String = "COMPLETED",
    ) = FlightHistoryEntity(
        id = id, roomId = null, focusType = "SOLO", roomParticipantCount = null,
        departureAirportCode = "PEK", departureAirportName = "首都机场",
        departureCity = "北京", departureCountry = "中国",
        departureLatitude = 40.08, departureLongitude = 116.58,
        destinationAirportCode = "KIX", destinationAirportName = "关西国际机场",
        destinationCity = toCity, destinationCountry = "日本",
        destinationLatitude = 34.43, destinationLongitude = 135.24,
        startTimeMillis = startMs,
        endTimeMillis = startMs + 45 * 60_000L,
        durationMinutes = 45, actualFocusMinutes = actual,
        distanceKm = 600.0, status = status, categoryId = null, seat = null,
        hasAnnouncedDeparture = true, hasAnnouncedDescent = true,
        hasAnnouncedArrival = true, logJson = null, isSynced = true,
        createdAt = startMs, updatedAt = startMs, quote = null, quoteAuthor = null,
    )

    // ══════════════════════════════════════════════════════════
    // 初始态
    // ══════════════════════════════════════════════════════════

    @Test
    fun `起始状态是 Loading`() = runTest {
        newVm()


        //   然后才显示真实数据。
        assertThat(vm.state.value).isEqualTo(RecordListState.Loading)
    }

    @Test
    fun `有数据时从 Loading 变成 Content`() = runTest {
        newVm(listOf(entity("a", at(9))))

        //   必须在 assert 之前**先订阅、再推队列**（见类注释第四条教训）。
        val collected = trace(vm)


        //   如果实现里把 initialValue 改成 Empty，用户会看到"空列表 → 有数据"的闪烁。
        assertThat(collected.first()).isEqualTo(RecordListState.Loading)
        assertThat(collected.last()).isInstanceOf(RecordListState.Content::class.java)
    }

    @Test
    fun `没有数据时是 Empty`() = runTest {
        newVm(emptyList())
        val collected = collectState(vm)
        assertThat(collected.last()).isEqualTo(RecordListState.Empty)
    }

    // ══════════════════════════════════════════════════════════
    // 搜索
    // ══════════════════════════════════════════════════════════

    @Test
    fun `搜索后列表按关键词过滤`() = runTest {
        newVm(
            listOf(
                entity("a", at(9), toCity = "大阪"),
                entity("b", at(10), toCity = "首尔"),
            ),
        )
        collectState(vm)
        assertThat(vm.content().itemCount()).isEqualTo(2)

        vm.onSearch("大阪")
        advanceUntilIdle()

        val content = vm.content()
        assertThat(content.itemCount()).isEqualTo(1)
        assertThat(content.groups[0].items[0].id).isEqualTo("a")
    }

    @Test
    fun `清除搜索后恢复全部`() = runTest {
        newVm(listOf(entity("a", at(9)), entity("b", at(10))))
        collectState(vm)

        vm.onSearch("大阪")
        advanceUntilIdle()
        vm.clearSearch()
        advanceUntilIdle()

        assertThat(vm.content().itemCount()).isEqualTo(2)
    }

    @Test
    fun `搜索无结果时是 NoResult 而不是 Empty`() = runTest {
        newVm(listOf(entity("a", at(9))))
        collectState(vm)

        vm.onSearch("火星")
        advanceUntilIdle()


        //   Empty  → "去飞一趟吧"
        //   NoResult → "换个词试试" + 清除按钮
        //   两者界面上都是空白，用一个状态表达，必然有一半场景文案是错的。
        assertThat(vm.state.value).isInstanceOf(RecordListState.NoResult::class.java)
    }

    @Test
    fun `超长关键词被截断而不是拖慢列表`() = runTest {
        newVm(listOf(entity("a", at(9))))
        collectState(vm)

        // 粘一整段文章进搜索框
        vm.onSearch("北".repeat(500))
        advanceUntilIdle()

        // 不该崩，也不该匹配到任何东西（32 个连续"北"不在任何字段里）
        assertThat(vm.state.value).isInstanceOf(RecordListState.NoResult::class.java)
    }

    @Test
    fun `关键词首尾空白被忽略`() = runTest {
        newVm(listOf(entity("a", at(9), toCity = "大阪")))
        collectState(vm)


        vm.onSearch("  大阪  ")
        advanceUntilIdle()

        assertThat(vm.content().itemCount()).isEqualTo(1)
    }

    // ══════════════════════════════════════════════════════════
    // 数据变化实时反映
    // ══════════════════════════════════════════════════════════

    @Test
    fun `数据库新增记录时列表自动更新`() = runTest {
        newVm(listOf(entity("a", at(9))))
        collectState(vm)
        assertThat(vm.content().itemCount()).isEqualTo(1)


        dao.rows.value = dao.rows.value + entity("b", at(14))
        advanceUntilIdle()

        assertThat(vm.content().itemCount()).isEqualTo(2)
    }

    @Test
    fun `删除记录后列表自动更新`() = runTest {
        newVm(listOf(entity("a", at(9)), entity("b", at(10))))
        collectState(vm)

        launch { vm.prepareDelete("a")?.let { vm.delete(it) } }
        advanceUntilIdle()

        val content = vm.content()
        assertThat(content.itemCount()).isEqualTo(1)
        assertThat(content.groups[0].items[0].id).isEqualTo("b")
    }

    @Test
    fun `删除最后一条后变成 Empty`() = runTest {
        newVm(listOf(entity("a", at(9))))
        collectState(vm)

        launch { vm.prepareDelete("a")?.let { vm.delete(it) } }
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(RecordListState.Empty)
    }

    @Test
    fun `删除失败不崩溃也不清空列表`() = runTest {
        newVm(listOf(entity("a", at(9))))
        collectState(vm)

        dao.failDelete = true
        launch { vm.prepareDelete("a")?.let { vm.delete(it) } }
        advanceUntilIdle()


        //   如果实现里是「先本地移除再调 DAO」，失败后会留下一个"幽灵消失"
        //   —— 用户以为删了，重启又回来了。
        assertThat(vm.content().itemCount()).isEqualTo(1)
    }

    // ══════════════════════════════════════════════════════════
    // 时区与时钟注入
    // ══════════════════════════════════════════════════════════

    @Test
    fun `换一个时区后今天昨天的判定跟着变`() = runTest {
        // 10-26 07:30 北京时间 = 10-25 23:30 UTC
        val ms = at(7, 30)
        val rows = listOf(entity("a", ms))

        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val cnVm = FakeDao(rows).let { RecordListViewModel(it, clock, zone, notifier, com.projectfocus.feature.record.testHistoryDeletion(it, notifier)) }
        val cn = collectState(cnVm).last() as RecordListState.Content
        assertThat(cn.groups[0].title).isEqualTo("今天")


        //   如果两个时区结果一样，说明时区参数被忽略了（很可能实现里
        //   偷偷用了 ZoneId.systemDefault()）。
        val utcVm = FakeDao(rows).let { RecordListViewModel(it, clock, ZoneId.of("UTC"), notifier, com.projectfocus.feature.record.testHistoryDeletion(it, notifier)) }
        val utc = collectState(utcVm).last() as RecordListState.Content
        assertThat(utc.groups[0].title).isEqualTo("昨天")
    }

    @Test
    fun `时钟注入让今天昨天的判定可测`() = runTest {
        val rows = listOf(entity("a", at(9)))
        // 假定"现在"是 10-28
        val laterClock = Clock.fixed(
            Instant.ofEpochMilli(
                ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, zone).toInstant().toEpochMilli(),
            ),
            ZoneId.of("UTC"),
        )
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val laterVm = FakeDao(rows).let { RecordListViewModel(it, laterClock, zone, notifier, com.projectfocus.feature.record.testHistoryDeletion(it, notifier)) }

        val content = collectState(laterVm).last() as RecordListState.Content
        // 10-26 相对 10-28 是"前两天"，显示具体日期而不是"今天"
        assertThat(content.groups[0].title).isEqualTo("10月26日")
    }

    // ══════════════════════════════════════════════════════════
    // 手写内存 DAO
    // ══════════════════════════════════════════════════════════








    private class FakeDao(initial: List<FlightHistoryEntity>) : FlightHistoryDao() {
        val rows = MutableStateFlow(initial)

        /** 置 true 后 `deleteById` 抛异常，用于验证失败路径 */
        var failDelete: Boolean = false

        override fun observeAll(): Flow<List<FlightHistoryEntity>> =

            rows.map { it.sortedByDescending { r -> r.createdAt } }

        override suspend fun deleteById(id: String) {

            if (failDelete) error("模拟数据库删除失败（磁盘满 / 被其他进程锁定）")
            rows.value = rows.value.filterNot { it.id == id }
        }

        // ── 以下本测试用不到 ──────────────────────────────
        override suspend fun getAll(): List<FlightHistoryEntity> = rows.value
        override suspend fun findById(id: String): FlightHistoryEntity? =
            rows.value.firstOrNull { it.id == id }
        override fun observeById(id: String): Flow<FlightHistoryEntity?> =
            rows.map { list -> list.firstOrNull { it.id == id } }
        override suspend fun pendingSync(): List<FlightHistoryEntity> = TODO()
        override fun observePendingSyncCount(): Flow<Int> = TODO()
        override suspend fun latestCompleted(): FlightHistoryEntity? = TODO()
        override fun observeLatestCompleted(): Flow<FlightHistoryEntity?> = TODO()
        override suspend fun ongoing(): FlightHistoryEntity? = TODO()
        override fun observeOngoing(): Flow<FlightHistoryEntity?> = TODO()
        override suspend fun visitedCountries(): List<String> = TODO()
        override suspend fun visitedAirportCodesForCountry(country: String): List<String> = TODO()
        override suspend fun markSynced(id: String, synced: Boolean) = TODO()
        override fun observeCompletedCount(): Flow<Int> = TODO()
        override fun observeTotalDistanceKm(): Flow<Double> = TODO()
        override fun observeTotalFocusMinutes(): Flow<Int> = TODO()
        override fun observeVisitedCountryCount(): Flow<Int> = TODO()
        override fun observeVisitedAirportCount(): Flow<Int> = TODO()
        override fun observeCountryBreakdown(): Flow<List<CountryTripCount>> = TODO()
        override suspend fun dailyFocusMinutes(
            fromMillis: Long,
            tzOffsetMillis: Long,
        ): List<DailyFocusRow> = TODO()


        //   本类里其它成员都是 TODO()，意思是"本测试不应该走到这里"。
        //   保持一致 —— 一旦有人让列表逻辑意外调用今日汇总，测试会立刻红，
        //   而不是安静地返回 0。
        override suspend fun todaySummary(
            fromMillis: Long,
            toMillis: Long,
        ): com.projectfocus.core.database.dao.TodaySummaryRow? = TODO()

        override suspend fun completedStartTimesSince(fromMillis: Long): List<Long> = TODO()

        override suspend fun insert(entity: FlightHistoryEntity) = TODO()
        override suspend fun upsert(entity: FlightHistoryEntity) = TODO()
        override suspend fun update(entity: FlightHistoryEntity) = TODO()
        override suspend fun deleteAll() = TODO()
    }
}
