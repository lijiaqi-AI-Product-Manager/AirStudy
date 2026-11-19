package com.projectfocus.feature.record.ui

import com.projectfocus.core.database.dao.CountryTripCount
import com.projectfocus.core.database.dao.DailyFocusRow
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.entity.FlightHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
























class FakeFlightHistoryDao(rows: List<FlightHistoryEntity> = emptyList()) : FlightHistoryDao() {

    val rows = MutableStateFlow(rows)

    var deleteCalls = 0
        private set

    /** 读取次数 —— 用来验证「进页面只查一次表」 */
    var observeAllCalls = 0
        private set







    var failNextDelete = false

    override fun observeAll(): Flow<List<FlightHistoryEntity>> {
        observeAllCalls++
        return rows.map { it }
    }

    override suspend fun getAll(): List<FlightHistoryEntity> = rows.value























    var findByIdDelayMs: Long = 0

    override suspend fun findById(id: String): FlightHistoryEntity? {
        if (findByIdDelayMs > 0) kotlinx.coroutines.delay(findByIdDelayMs)
        return rows.value.firstOrNull { it.id == id }
    }

    override fun observeById(id: String): Flow<FlightHistoryEntity?> =
        rows.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun pendingSync(): List<FlightHistoryEntity> =
        rows.value.filter { !it.isSynced && it.status == "COMPLETED" }

    override fun observePendingSyncCount(): Flow<Int> =
        rows.map { list -> list.count { !it.isSynced && it.status == "COMPLETED" } }

    override suspend fun latestCompleted(): FlightHistoryEntity? =
        rows.value.filter { it.status == "COMPLETED" }.maxByOrNull { it.endTimeMillis ?: 0L }

    override fun observeLatestCompleted(): Flow<FlightHistoryEntity?> =
        rows.map { list -> list.filter { it.status == "COMPLETED" }.maxByOrNull { it.endTimeMillis ?: 0L } }

    override suspend fun ongoing(): FlightHistoryEntity? = rows.value.firstOrNull { it.status == "ONGOING" }

    override fun observeOngoing(): Flow<FlightHistoryEntity?> =
        rows.map { list -> list.firstOrNull { it.status == "ONGOING" } }

    override suspend fun visitedCountries(): List<String> =
        rows.value.filter { it.status == "COMPLETED" }
            .map { it.destinationCountry }
            .filter { it.isNotBlank() }
            .distinct()


    override suspend fun visitedAirportCodesForCountry(country: String): List<String> =
        rows.value.filter { it.status == "COMPLETED" && it.destinationCountry == country }
            .map { it.destinationAirportCode }
            .filter { it.isNotEmpty() }
            .distinct()

    override suspend fun markSynced(id: String, synced: Boolean) {
        rows.value = rows.value.map { if (it.id == id) it.copy(isSynced = synced) else it }
    }

    override suspend fun deleteById(id: String) {
        deleteCalls++
        if (failNextDelete) {
            failNextDelete = false
            error("模拟数据库删除失败")
        }
        rows.value = rows.value.filterNot { it.id == id }
    }

    override fun observeCompletedCount(): Flow<Int> =
        rows.map { list -> list.count { it.status == "COMPLETED" } }

    override fun observeTotalDistanceKm(): Flow<Double> =
        rows.map { list -> list.filter { it.status == "COMPLETED" }.sumOf { it.distanceKm } }

    override fun observeTotalFocusMinutes(): Flow<Int> =
        rows.map { list -> list.filter { it.status == "COMPLETED" }.sumOf { it.actualFocusMinutes } }

    override fun observeVisitedCountryCount(): Flow<Int> =
        rows.map { list ->
            list.filter { it.status == "COMPLETED" }
                .map { it.destinationCountry }.filter { it.isNotBlank() }.distinct().size
        }

    override fun observeVisitedAirportCount(): Flow<Int> =
        rows.map { list ->
            list.filter { it.status == "COMPLETED" }
                .map { it.destinationAirportCode }.distinct().size
        }

    override fun observeCountryBreakdown(): Flow<List<CountryTripCount>> =
        rows.map { list ->
            list.filter { it.status == "COMPLETED" && it.destinationCountry.isNotBlank() }
                .groupingBy { it.destinationCountry }
                .eachCount()
                .map { (country, count) ->
                    CountryTripCount(
                        country = country,
                        tripCount = count,
                        visitedAirports = count,
                        totalAirports = 0,
                    )
                }
                .sortedWith(
                    compareByDescending<CountryTripCount> { it.tripCount }
                        .thenBy { it.country },
                )
        }

    override suspend fun dailyFocusMinutes(fromMillis: Long, tzOffsetMillis: Long): List<DailyFocusRow> =
        emptyList()

    /** 小组件用。记录列表页不关心今日合计 → 返回空，语义上"今天没有数据" */
    override suspend fun todaySummary(
        fromMillis: Long,
        toMillis: Long,
    ): com.projectfocus.core.database.dao.TodaySummaryRow? = null

    override suspend fun completedStartTimesSince(fromMillis: Long): List<Long> = emptyList()

    override suspend fun insert(entity: FlightHistoryEntity) {
        rows.value = rows.value + entity
    }

    override suspend fun upsert(entity: FlightHistoryEntity) {
        rows.value = rows.value.filterNot { it.id == entity.id } + entity
    }

    override suspend fun update(entity: FlightHistoryEntity) = upsert(entity)

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}
