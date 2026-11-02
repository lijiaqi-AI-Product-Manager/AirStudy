package com.projectfocus.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.database.entity.FreeFlightArchiveEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryReadDao {
    @Query("SELECT * FROM flight_history WHERE ownerScope = :owner OR (:owner = 'local' AND ownerScope IS NULL) ORDER BY createdAt DESC")
    fun countdowns(owner: String): Flow<List<FlightHistoryEntity>>

    @Query("SELECT * FROM free_flight_archive WHERE ownerScope = :owner ORDER BY updatedAtWallMs DESC")
    fun countUps(owner: String): Flow<List<FreeFlightArchiveEntity>>

    @Query("SELECT country, CASE WHEN iataCode != '' THEN iataCode ELSE icaoCode END AS code FROM airports WHERE country != '' AND (iataCode != '' OR icaoCode != '')")
    fun airportDirectory(): Flow<List<HistoryAirportReference>>
}

data class HistoryAirportReference(val country: String, val code: String)
