package com.projectfocus.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.database.entity.FreeFlightArchiveEntity
import com.projectfocus.core.database.entity.FlightRecordSceneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryReadDao {
    @Query("SELECT * FROM flight_history WHERE ownerScope = :owner OR (:owner = 'local' AND ownerScope IS NULL) ORDER BY createdAt DESC")
    fun countdowns(owner: String): Flow<List<FlightHistoryEntity>>

    @Query("SELECT * FROM free_flight_archive WHERE ownerScope = :owner ORDER BY updatedAtWallMs DESC")
    fun countUps(owner: String): Flow<List<FreeFlightArchiveEntity>>

    @Query("SELECT * FROM flight_history WHERE id = :id AND (ownerScope = :owner OR (:owner = 'local' AND ownerScope IS NULL))")
    fun countdown(owner: String, id: String): Flow<FlightHistoryEntity?>

    @Query("SELECT * FROM free_flight_archive WHERE sessionId = :id AND ownerScope = :owner")
    fun countUp(owner: String, id: String): Flow<FreeFlightArchiveEntity?>

    @Query("SELECT * FROM flight_record_scene WHERE ownerScope = :owner")
    fun scenes(owner: String): Flow<List<FlightRecordSceneEntity>>

    @Query("SELECT * FROM flight_record_scene WHERE ownerScope = :owner AND recordKind = :kind AND recordId = :id")
    fun scene(owner: String, kind: String, id: String): Flow<FlightRecordSceneEntity?>

    @Query("SELECT country, CASE WHEN iataCode != '' THEN iataCode ELSE icaoCode END AS code FROM airports WHERE country != '' AND (iataCode != '' OR icaoCode != '')")
    fun airportDirectory(): Flow<List<HistoryAirportReference>>
}

data class HistoryAirportReference(val country: String, val code: String)
