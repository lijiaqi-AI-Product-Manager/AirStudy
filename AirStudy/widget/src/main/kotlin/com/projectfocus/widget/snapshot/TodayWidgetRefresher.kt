package com.projectfocus.widget.snapshot

import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.dao.TodaySummaryRow
import kotlinx.coroutines.CancellationException
import java.time.ZoneId


































class TodayWidgetRefresher(
    private val dao: FlightHistoryDao,
    /** 时区来源做成函数，测试里可以钉死 `Asia/Shanghai`，不受 CI 机器时区影响 */
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    /** 时钟也做成函数：连续天数对"现在"极其敏感，测试必须能控制它 */
    private val clock: () -> Long = { System.currentTimeMillis() },







































    private val sink: suspend (TodayStats, Long) -> Unit,
) {









    suspend fun refresh() {
        try {
            val now = clock()
            val zone = zoneProvider()
            val from = TodayCalculator.dayStartMillis(now, zone)
            val to = TodayCalculator.nextDayStartMillis(now, zone)

            val summary = dao.todaySummary(from, to)
            val recent = dao.completedStartTimesSince(streakFromMillis(now, zone))

            val stats = TodayCalculator.compute(
                sessionCount = summary?.sessionCount ?: 0,
                focusMinutes = summary?.focusMinutes ?: 0,
                distanceKm = summary?.distanceKm ?: 0.0,
                recentStartTimes = recent,
                nowMillis = now,
                zone = zone,
            )

            //   （2026-10-26 lint 抓到）
            //
            //   `LocalDate.ofInstant` 是 **API 34 / Java 9** 才有的静态方法：
            //     Error: Call requires API level 34, or core library desugaring
            //            (current min is 26): java.time.LocalDate#ofInstant [NewApi]
            //
            //   注意 `java.time` 在 API 26 就有了（minSdk=26 正好踩线），
            //   但**不是每个 java.time 方法都跟着 API 26 一起进来** ——
            //   Java 9+ 新增的那些（`ofInstant`、`LocalDate.datesUntil`、
            //   `Duration.toSeconds` 的部分重载）在 Android 上要么靠
            //   desugaring，要么根本没有。
            //

            //     用 IDE 补全出来的静态工厂最容易踩这个 —— 补全不区分 API level。
            //     `Instant.atZone(zone).toLocalDate()` 是 Java 8 就有的等价写法。
            val epochDay = java.time.Instant.ofEpochMilli(now)
                .atZone(zone)
                .toLocalDate()
                .toEpochDay()
            sink(stats, epochDay)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {

            //   核心动作触发的，那个动作不能因为桌面组件出问题而失败。
            LOGGER("刷新今日小组件失败", e)
        }
    }









    private fun streakFromMillis(nowMillis: Long, zone: ZoneId): Long =
        java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            .minusDays(TodayCalculator.STREAK_SCAN_DAYS.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()

    companion object {








        internal fun silenceLogForTest() {
            LOGGER = { _, _ -> }
        }












        internal var LOGGER: (String, Throwable) -> Unit = { msg, e ->

            //
            //   原因就是上面那段注释：本类的核心逻辑**要在纯 JVM 测试里跑**，
            //   而 `Timber.w()` 在测试里如果没有 `Tree`，会一声不响地什么都不做 ——
            //   「异常被吞掉且记录了日志」这条断言就永远是假绿。
            //   而 `android.util.Log` 在纯 JVM 测试里会抛 not-mocked，
            //   反倒**逼着**测试把 LOGGER 换掉，从而真的验证到替换生效。
            //

            //     确切地说，是 widget 进程里没有 Activity，Timber 不会被自动种树，
            //     所以这里用平台 Log 反而是**唯一可靠**的选择。
            @Suppress("LogNotTimber")
            android.util.Log.w("TodayWidgetRefresher", msg, e)
        }
    }
}









internal fun TodaySummaryRow?.orZero(): TodaySummaryRow =
    this ?: TodaySummaryRow(sessionCount = 0, focusMinutes = 0, distanceKm = 0.0)
