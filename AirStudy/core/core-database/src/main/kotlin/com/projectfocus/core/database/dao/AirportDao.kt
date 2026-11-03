package com.projectfocus.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.projectfocus.core.database.entity.AirportEntity
import kotlinx.coroutines.flow.Flow


@Dao
abstract class AirportDao {

    @Query("SELECT * FROM airports WHERE isPremiumOnly = 0 OR :hasPremium = 1")
    abstract fun observeAll(hasPremium: Boolean): Flow<List<AirportEntity>>

    @Query("SELECT * FROM airports WHERE isPremiumOnly = 0 OR :hasPremium = 1")
    abstract suspend fun getAll(hasPremium: Boolean): List<AirportEntity>

    @Query("SELECT * FROM airports WHERE iataCode = :code OR icaoCode = :code LIMIT 1")
    abstract suspend fun findByCode(code: String): AirportEntity?

    @Query("SELECT * FROM airports WHERE country = :country ORDER BY popularity DESC LIMIT 25")
    abstract suspend fun byCountry(country: String): List<AirportEntity>

    @Query("SELECT * FROM airports ORDER BY popularity DESC LIMIT :limit")
    abstract suspend fun topByPopularity(limit: Int): List<AirportEntity>

    @Query("SELECT * FROM airports ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun random(limit: Int): List<AirportEntity>

    @Query("SELECT COUNT(*) FROM airports WHERE isPremiumOnly = 0 OR :hasPremium = 1")
    abstract suspend fun count(hasPremium: Boolean): Int

    /** query 原样绑定；ICAO精确匹配没有独立排序优先级。 */
    @Query(
        "SELECT * FROM airports " +
            "WHERE (isPremiumOnly = 0 OR :hasPremium = 1) " +
            "AND (iataCode LIKE '%' || :query || '%' OR icaoCode LIKE '%' || :query || '%' " +
            "OR name LIKE '%' || :query || '%' OR city LIKE '%' || :query || '%') " +
            "ORDER BY CASE " +
            "WHEN iataCode = :query THEN 0 " +
            "WHEN iataCode LIKE :query || '%' THEN 1 " +
            "WHEN name LIKE :query || '%' THEN 2 " +
            "ELSE 3 END, popularity DESC LIMIT :limit",
    )
    abstract suspend fun search(query: String, limit: Int, hasPremium: Boolean): List<AirportEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(airports: List<AirportEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(airport: AirportEntity)

    @Query("DELETE FROM airports")
    abstract suspend fun deleteAll()
}
