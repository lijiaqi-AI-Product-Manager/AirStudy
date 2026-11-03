package com.projectfocus.core.database.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface HistoryDeletionDao {
    @Query("""
        DELETE FROM flight_history WHERE id = :id AND status IN ('COMPLETED', 'CANCELLED')
          AND (ownerScope = :owner OR (:owner = 'local' AND ownerScope IS NULL))
    """)
    suspend fun countdown(id: String, owner: String): Int

    @Query("""
        DELETE FROM free_flight_archive WHERE sessionId = :id AND ownerScope = :owner
          AND revision = :revision AND status IN ('COMPLETED', 'CANCELLED') AND activeSlot IS NULL
    """)
    suspend fun countUp(id: String, owner: String, revision: Long): Int
}
