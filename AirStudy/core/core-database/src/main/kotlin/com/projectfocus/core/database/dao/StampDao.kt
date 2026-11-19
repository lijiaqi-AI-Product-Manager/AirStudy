package com.projectfocus.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.projectfocus.core.database.entity.StampEntity
import kotlinx.coroutines.flow.Flow


@Dao
abstract class StampDao {


    @Query("SELECT * FROM stamps")
    abstract fun observeAll(): Flow<List<StampEntity>>

    @Query("SELECT * FROM stamps")
    abstract suspend fun getAll(): List<StampEntity>

    @Query("SELECT * FROM stamps WHERE id = :id LIMIT 1")
    abstract suspend fun findById(id: String): StampEntity?


    @Query("SELECT * FROM stamps WHERE countryCode = :countryCode LIMIT 1")
    abstract suspend fun findByCountryCode(countryCode: String): StampEntity?

    /** 章总数（护照封面显示 "12 countries"） */
    @Query("SELECT COUNT(*) FROM stamps")
    abstract fun observeCount(): Flow<Int>

    /** 累计专注小时（护照摘要） */
    @Query("SELECT COALESCE(SUM(focusHours), 0) FROM stamps")
    abstract fun observeTotalFocusHours(): Flow<Double>

    /** 累计到访次数 */
    @Query("SELECT COALESCE(SUM(visitCount), 0) FROM stamps")
    abstract fun observeTotalVisits(): Flow<Int>

    // ── 写入 ───────────────────────────────────────────────


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: StampEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entities: List<StampEntity>)

    @Query("DELETE FROM stamps WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM stamps")
    abstract suspend fun deleteAll()
}
