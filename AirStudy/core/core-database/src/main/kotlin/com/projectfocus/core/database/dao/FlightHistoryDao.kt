package com.projectfocus.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.projectfocus.core.database.entity.FlightHistoryEntity
import kotlinx.coroutines.flow.Flow


@Dao
abstract class FlightHistoryDao {


    @Query("SELECT * FROM flight_history ORDER BY createdAt DESC")
    abstract fun observeAll(): Flow<List<FlightHistoryEntity>>

    /** 同上，一次性读取 */
    @Query("SELECT * FROM flight_history ORDER BY createdAt DESC")
    abstract suspend fun getAll(): List<FlightHistoryEntity>

    /** 单条详情 */
    @Query("SELECT * FROM flight_history WHERE id = :id LIMIT 1")
    abstract suspend fun findById(id: String): FlightHistoryEntity?

    /** 单条详情（可观察） */
    @Query("SELECT * FROM flight_history WHERE id = :id LIMIT 1")
    abstract fun observeById(id: String): Flow<FlightHistoryEntity?>

    /**
     * 待同步队列。
     * `isSynced = 0 AND status = 'COMPLETED'` —— 只同步已完成的，不同步进行中的。
     */
    @Query("SELECT * FROM flight_history WHERE isSynced = 0 AND status = 'COMPLETED'")
    abstract suspend fun pendingSync(): List<FlightHistoryEntity>

    /** 待同步条数（设置页显示「N 条待同步」） */
    @Query("SELECT COUNT(*) FROM flight_history WHERE isSynced = 0 AND status = 'COMPLETED'")
    abstract fun observePendingSyncCount(): Flow<Int>

    /** 最近一次完成的航班 */
    @Query(
        "SELECT * FROM flight_history WHERE status = 'COMPLETED' " +
            "ORDER BY endTimeMillis DESC LIMIT 1",
    )
    abstract suspend fun latestCompleted(): FlightHistoryEntity?

    /** 最近一次完成的航班（可观察，首页「上次飞的是哪条线」） */
    @Query(
        "SELECT * FROM flight_history WHERE status = 'COMPLETED' " +
            "ORDER BY endTimeMillis DESC LIMIT 1",
    )
    abstract fun observeLatestCompleted(): Flow<FlightHistoryEntity?>

    /** 当前进行中的航班。单例飞行约束：业务上最多一条 */
    @Query("SELECT * FROM flight_history WHERE status = 'ONGOING' LIMIT 1")
    abstract suspend fun ongoing(): FlightHistoryEntity?

    @Query("SELECT * FROM flight_history WHERE status = 'ONGOING' LIMIT 1")
    abstract fun observeOngoing(): Flow<FlightHistoryEntity?>

    /** 去过的国家（印章系统数据源） */
    @Query(
        "SELECT DISTINCT destinationCountry FROM flight_history " +
            "WHERE status = 'COMPLETED' " +
            "AND destinationCountry IS NOT NULL AND destinationCountry != ''",
    )
    abstract suspend fun visitedCountries(): List<String>

    /** 某国已访问的机场码（统计页国家下钻用） */
    @Query(
        "SELECT DISTINCT destinationAirportCode FROM flight_history " +
            "WHERE status = 'COMPLETED' " +
            "AND destinationCountry = :country " +
            "AND destinationAirportCode IS NOT NULL AND destinationAirportCode != ''",
    )
    abstract suspend fun visitedAirportCodesForCountry(country: String): List<String>

    /** 标记已同步 */
    @Query("UPDATE flight_history SET isSynced = :synced WHERE id = :id")
    abstract suspend fun markSynced(id: String, synced: Boolean)

    /** 删除单条 */
    @Query("DELETE FROM flight_history WHERE id = :id")
    abstract suspend fun deleteById(id: String)





    /** 累计飞行次数（COMPLETED 才算） */
    @Query("SELECT COUNT(*) FROM flight_history WHERE status = 'COMPLETED'")
    abstract fun observeCompletedCount(): Flow<Int>

    /** 累计飞行距离（km） */
    @Query("SELECT COALESCE(SUM(distanceKm), 0) FROM flight_history WHERE status = 'COMPLETED'")
    abstract fun observeTotalDistanceKm(): Flow<Double>

    /** 累计专注分钟 */
    @Query(
        "SELECT COALESCE(SUM(actualFocusMinutes), 0) FROM flight_history " +
            "WHERE status = 'COMPLETED'",
    )
    abstract fun observeTotalFocusMinutes(): Flow<Int>

    /** 去过的国家数 */
    @Query(
        "SELECT COUNT(DISTINCT destinationCountry) FROM flight_history " +
            "WHERE status = 'COMPLETED' " +
            "AND destinationCountry IS NOT NULL AND destinationCountry != ''",
    )
    abstract fun observeVisitedCountryCount(): Flow<Int>

    /** 去过的机场数（按目的地 IATA 去重；空 IATA 不计入） */
    @Query(
        "SELECT COUNT(DISTINCT destinationAirportCode) FROM flight_history " +
            "WHERE status = 'COMPLETED' " +
            "AND destinationAirportCode IS NOT NULL AND destinationAirportCode != ''",
    )
    abstract fun observeVisitedAirportCount(): Flow<Int>

    /**
     * 按天聚合的专注分钟（统计页热力图 / 周报）。
     *
     * 返回 (dayStartMillis, minutes)。SQLite 的 strftime 按本地时区之外的
     * UTC 处理时，日界会偏 —— 所以这里交给"调用方传入时区偏移"更稳妥：
     * 用 `startTimeMillis + :tzOffsetMillis` 换到本地日，再取整到天。
     */
    @Query(
        "SELECT (startTimeMillis + :tzOffsetMillis) / 86400000 * 86400000 - :tzOffsetMillis AS dayStart, " +
            "SUM(actualFocusMinutes) AS minutes " +
            "FROM flight_history " +
            "WHERE status = 'COMPLETED' AND startTimeMillis >= :fromMillis " +
            "GROUP BY dayStart ORDER BY dayStart ASC",
    )
    abstract suspend fun dailyFocusMinutes(
        fromMillis: Long,
        tzOffsetMillis: Long,
    ): List<DailyFocusRow>

    // ── 小组件：今日概览 / 连续天数 ──────────────────────────
    //

    //
    //   ① **数据量会随时间线性增长**
    //      一天飞 4 次，一年 1,400 条；三年 4,000 条。全量读出来在
    //      小组件刷新路径上（桌面随时可能请求）是不可接受的开销 ——
    //      小组件的 ANR 阈值同样是 5 秒，而它跑在**桌面进程的请求里**。
    //
    //   ② **"连续天数"不能用 group by day 再在内存里找断点**
    //      `dailyFocusMinutes` 只返回**有记录的那些天**。2 月 1 日和 2 月 5 日
    //      各有一天，中间那三天在结果里根本不存在。在 Kotlin 里"从今天往回数"，
    //      遇到缺失就停 —— 这要求结果集里每一天都在，而它不在。
    //      所以要么补全整段日期（要在 Kotlin 里构造日期序列），要么直接拿原始
    //      start 时刻在 SQL 里排。后者更省。
    //
    //   → 折中：只取"最近 N 天"的原始 start 时刻列表（N 足够大，见下），
    //     在 Kotlin 里用 LocalDate 归一后找断点。这样 SQL 只负责"缩小范围"，
    //     日界与时区这类"容易算错"的逻辑留在可单测的纯函数里。

















    @Query(
        "SELECT COUNT(*) AS sessionCount, " +
            "COALESCE(SUM(actualFocusMinutes), 0) AS focusMinutes, " +
            "COALESCE(SUM(distanceKm), 0) AS distanceKm " +
            "FROM flight_history " +
            "WHERE status = 'COMPLETED' AND startTimeMillis >= :fromMillis AND startTimeMillis < :toMillis",
    )
    abstract suspend fun todaySummary(fromMillis: Long, toMillis: Long): TodaySummaryRow?











    @Query(
        "SELECT startTimeMillis FROM flight_history " +
            "WHERE status = 'COMPLETED' AND startTimeMillis >= :fromMillis " +
            "ORDER BY startTimeMillis ASC",
    )
    abstract suspend fun completedStartTimesSince(fromMillis: Long): List<Long>

    // ── 写入 ───────────────────────────────────────────────

    /**
     * 按目的国家分组，统计已完成的飞行次数。
     *
     * - 只算 COMPLETED；
     * - 空国家不计；
     * - 次数降序，同次数按国家名升序，保证展示稳定。
     */
    @Query(
        "SELECT destinationCountry AS country, COUNT(*) AS tripCount, " +
            "COUNT(DISTINCT destinationAirportCode) AS visitedAirports, " +
            "(SELECT COUNT(*) FROM airports a " +
            " WHERE a.country = flight_history.destinationCountry) AS totalAirports " +
            "FROM flight_history WHERE status = 'COMPLETED' " +
            "AND destinationCountry IS NOT NULL AND destinationCountry != '' " +
            "GROUP BY destinationCountry ORDER BY tripCount DESC, country ASC",
    )
    abstract fun observeCountryBreakdown(): Flow<List<CountryTripCount>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(entity: FlightHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: FlightHistoryEntity)

    @Update
    abstract suspend fun update(entity: FlightHistoryEntity)

    /** 保存单人整分钟检查点；事务中读取最新座位等字段，避免旧快照覆盖用户修改。 */
    @Transaction
    open suspend fun saveSessionCheckpoint(
        fallback: FlightHistoryEntity,
        actualMinutes: Int,
        updatedAt: Long,
    ): FlightHistoryEntity {
        require(actualMinutes >= 0)
        val existing = findById(fallback.id)
        val current = existing ?: fallback
        if (current.status != "ONGOING") return current
        val saved = current.copy(
            actualFocusMinutes = maxOf(current.actualFocusMinutes, actualMinutes),
            updatedAt = updatedAt,
        )
        if (existing == null) insert(saved) else update(saved)
        return saved
    }

    /** 先写终态，界面下机只确认已提交记录；重复事件保留第一次终态。 */
    @Transaction
    open suspend fun saveSessionTerminal(fallback: FlightHistoryEntity): FlightHistoryEntity {
        require(fallback.status == "COMPLETED" || fallback.status == "CANCELLED")
        val existing = findById(fallback.id)
        val current = existing ?: fallback
        if (existing != null && current.status in setOf("COMPLETED", "CANCELLED")) return current
        val saved = current.copy(
            status = fallback.status,
            actualFocusMinutes = if (fallback.status == "COMPLETED") current.durationMinutes
                else current.actualFocusMinutes,
            endTimeMillis = if (fallback.status == "COMPLETED") fallback.endTimeMillis
                else current.endTimeMillis,
            isSynced = false,
            updatedAt = fallback.updatedAt,
        )
        if (existing == null) insert(saved) else update(saved)
        return saved
    }

    @Query("DELETE FROM flight_history")
    abstract suspend fun deleteAll()
}

/** 按天聚合的投影行 */
data class DailyFocusRow(
    val dayStart: Long,
    val minutes: Int,
)












data class TodaySummaryRow(
    val sessionCount: Int,
    val focusMinutes: Int,
    val distanceKm: Double,
)

/** 按目的国家聚合的一行；`tripCount` 只统计 COMPLETED。 */
data class CountryTripCount(
    val country: String,
    val tripCount: Int,
    val visitedAirports: Int,
    val totalAirports: Int,
)
