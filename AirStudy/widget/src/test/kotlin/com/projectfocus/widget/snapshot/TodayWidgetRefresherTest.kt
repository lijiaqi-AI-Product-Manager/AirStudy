package com.projectfocus.widget.snapshot

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.dao.CountryTripCount
import com.projectfocus.core.database.dao.DailyFocusRow
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.dao.TodaySummaryRow
import com.projectfocus.core.database.entity.FlightHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.ZoneId














class TodayWidgetRefresherTest {








    private class Sink {
        var stats: TodayStats? = null
        var epochDay: Long = -1
        val calls get() = if (stats == null) 0 else 1
    }

    /** 2026-10-27 14:30 北京时间。选下午，让它离两个日界都远 —— 边界用例单独写 */
    private val now = java.time.ZonedDateTime.of(2026, 10, 27, 14, 30, 0, 0, SHANGHAI)
        .toInstant().toEpochMilli()

    private class FakeDao(
        var summary: TodaySummaryRow? = TodaySummaryRow(sessionCount = 2, focusMinutes = 50, distanceKm = 1800.0),
        var recent: List<Long> = emptyList(),

        //   写成 `: FlightHistoryDao`（不加括号）会报
        //   "This type has a constructor, so it must be initialized here"，
        //   而这条报错的**行指向的是类声明那一行**，看起来像"某个属性没初始化"，

    ) : FlightHistoryDao() {
        var summaryCalls = 0
        var streakCalls = 0
        var lastSummaryFrom = -1L
        var lastSummaryTo = -1L
        var lastStreakFrom = -1L
        var throwOnSummary: Throwable? = null

        override suspend fun todaySummary(fromMillis: Long, toMillis: Long): TodaySummaryRow? {
            summaryCalls++
            lastSummaryFrom = fromMillis
            lastSummaryTo = toMillis
            throwOnSummary?.let { throw it }
            return summary
        }

        override suspend fun completedStartTimesSince(fromMillis: Long): List<Long> {
            streakCalls++
            lastStreakFrom = fromMillis
            return recent
        }

        // —— 以下为 FlightHistoryDao 的其它成员，本测试不涉及 ——
        //

        //   返回 `emptyList()` / `0` 会让"万一刷新逻辑走错查询"变成**静默的错数字**
        //   （今日 0 趟看起来完全正常）；TODO() 会立刻抛，错误一眼可见。

        override fun observeAll(): Flow<List<FlightHistoryEntity>> = TODO()
        override suspend fun getAll(): List<FlightHistoryEntity> = TODO()
        override suspend fun findById(id: String): FlightHistoryEntity? = TODO()
        override fun observeById(id: String): Flow<FlightHistoryEntity?> = TODO()
        override suspend fun pendingSync(): List<FlightHistoryEntity> = TODO()
        override fun observePendingSyncCount(): Flow<Int> = TODO()
        override suspend fun latestCompleted(): FlightHistoryEntity? = TODO()
        override fun observeLatestCompleted(): Flow<FlightHistoryEntity?> = TODO()
        override suspend fun ongoing(): FlightHistoryEntity? = TODO()
        override fun observeOngoing(): Flow<FlightHistoryEntity?> = TODO()
        override suspend fun visitedCountries(): List<String> = TODO()
        override suspend fun visitedAirportCodesForCountry(country: String): List<String> = TODO()
        override suspend fun markSynced(id: String, synced: Boolean) = TODO()
        override suspend fun deleteById(id: String) = TODO()
        override fun observeCompletedCount(): Flow<Int> = TODO()
        override fun observeTotalDistanceKm(): Flow<Double> = TODO()
        override fun observeTotalFocusMinutes(): Flow<Int> = TODO()
        override fun observeVisitedCountryCount(): Flow<Int> = TODO()
        override fun observeVisitedAirportCount(): Flow<Int> = TODO()
        override fun observeCountryBreakdown(): Flow<List<CountryTripCount>> = TODO()
        override suspend fun dailyFocusMinutes(fromMillis: Long, tzOffsetMillis: Long): List<DailyFocusRow> = TODO()
        override suspend fun insert(entity: FlightHistoryEntity) = TODO()
        override suspend fun upsert(entity: FlightHistoryEntity) = TODO()
        override suspend fun update(entity: FlightHistoryEntity) = TODO()
        override suspend fun deleteAll() = TODO()
    }


    //   不写具名是为了让"参数顺序"这件事在测试里也暴露出来 ——
    //   生产代码那边也是靠 trailing lambda 读最后一位的。
    private fun refresher(dao: FakeDao, sink: Sink = Sink()) = TodayWidgetRefresher(
        dao,
        { SHANGHAI },
        { now },
    ) { stats, day ->
        sink.stats = stats
        sink.epochDay = day
    }

    // ==================================================================
    // 日界
    // ==================================================================

    @Test
    fun `今日区间是今天零点到明天零点`() = runBlocking {
        val dao = FakeDao()
        refresher(dao).refresh()

        val expectFrom = java.time.ZonedDateTime.of(2026, 10, 27, 0, 0, 0, 0, SHANGHAI)
            .toInstant().toEpochMilli()
        val expectTo = java.time.ZonedDateTime.of(2026, 10, 28, 0, 0, 0, 0, SHANGHAI)
            .toInstant().toEpochMilli()
        assertThat(dao.lastSummaryFrom).isEqualTo(expectFrom)
        assertThat(dao.lastSummaryTo).isEqualTo(expectTo)

        assertThat(dao.lastSummaryTo - dao.lastSummaryFrom).isEqualTo(24 * 3600_000L)
    }

    @Test
    fun `连续天数的回溯下界正好是 400 天前`() = runBlocking {
        val dao = FakeDao()
        refresher(dao).refresh()

        val expect = java.time.ZonedDateTime.of(2026, 10, 27, 0, 0, 0, 0, SHANGHAI)
            .minusDays(TodayCalculator.STREAK_SCAN_DAYS.toLong())
            .toInstant().toEpochMilli()
        assertThat(dao.lastStreakFrom).isEqualTo(expect)
    }









    @Test
    fun `夏令时切换当天区间是 23 小时`() = runBlocking {
        val ny = ZoneId.of("America/New_York")
        val day = java.time.LocalDate.of(2026, 3, 8)
        val dao = FakeDao()
        TodayWidgetRefresher(
            dao,
            { ny },
            { day.atTime(12, 0).atZone(ny).toInstant().toEpochMilli() },
        ) { _, _ -> }.refresh()
        val span = dao.lastSummaryTo - dao.lastSummaryFrom
        assertThat(span).isEqualTo(23 * 3600_000L)
    }

    // ==================================================================
    // 落在哪一天
    // ==================================================================











    @Test
    fun `同一时刻在不同时区属于不同的今天`() = runBlocking {
        val instant = java.time.ZonedDateTime.of(2026, 10, 27, 23, 30, 0, 0, SHANGHAI)
            .toInstant().toEpochMilli()

        val sh = FakeDao()
        TodayWidgetRefresher(sh, { SHANGHAI }, { instant }) { _, _ -> }.refresh()

        val utc = FakeDao()
        TodayWidgetRefresher(utc, { ZoneId.of("UTC") }, { instant }) { _, _ -> }.refresh()

        assertThat(sh.lastSummaryFrom).isNotEqualTo(utc.lastSummaryFrom)
        // 东八区的"今天零点"比 UTC 的"今天零点"早 8 小时
        assertThat(utc.lastSummaryFrom - sh.lastSummaryFrom).isEqualTo(8 * 3600_000L)
    }

    // ==================================================================
    // 异常不能冒出去
    // ==================================================================











    @Test
    fun `查库抛异常时 refresh 不向外抛`() = runBlocking {

        //   于是这条"测异常处理"的用例会被日志自己搞红 —— 非常讽刺。
        //   把 LOGGER 换成空实现，才真正测到"异常被吞掉"这件事本身。
        silenceLogger()
        val dao = FakeDao().apply { throwOnSummary = IllegalStateException("database is locked") }
        refresher(dao).refresh()   // 不抛就是通过
        assertThat(dao.summaryCalls).isEqualTo(1)
    }








    @Test
    fun `协程取消时 refresh 必须重新抛出`() = runBlocking {
        silenceLogger()
        val dao = FakeDao().apply { throwOnSummary = kotlinx.coroutines.CancellationException("scope died") }
        val thrown = runCatching { refresher(dao).refresh() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

    // ==================================================================
    // 空数据
    // ==================================================================









    @Test
    fun `汇总为 null 时按全零处理`() = runBlocking {
        val dao = FakeDao(summary = null, recent = emptyList())
        refresher(dao).refresh()

        //   "没有崩溃"和"显示了正确的零"是两件完全不同的事。
        val sink = Sink()
        refresher(FakeDao(summary = null, recent = emptyList()), sink).refresh()
        assertThat(sink.calls).isEqualTo(1)
        assertThat(sink.stats!!.sessionCount).isEqualTo(0)
        assertThat(sink.stats!!.focusMinutes).isEqualTo(0)
        assertThat(sink.stats!!.distanceKm).isEqualTo(0)
        assertThat(sink.stats!!.flewToday).isFalse()
    }

    @Test
    fun `空行的替身是全零而不是空值`() {
        val zero = (null as TodaySummaryRow?).orZero()
        assertThat(zero.sessionCount).isEqualTo(0)
        assertThat(zero.focusMinutes).isEqualTo(0)
        assertThat(zero.distanceKm).isEqualTo(0.0)
    }

    // ==================================================================
    // 调用契约
    // ==================================================================










    @Test
    fun `一次刷新只查一次汇总和一次明细`() = runBlocking {
        val dao = FakeDao()
        refresher(dao).refresh()
        assertThat(dao.summaryCalls).isEqualTo(1)
        assertThat(dao.streakCalls).isEqualTo(1)
    }

    /** 把 widget 模块的日志出口换成空实现。只在需要"预期会打日志"的用例里调。 */
    private fun silenceLogger() {
        TodayWidgetRefresher.silenceLogForTest()
    }

    private companion object {
        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
