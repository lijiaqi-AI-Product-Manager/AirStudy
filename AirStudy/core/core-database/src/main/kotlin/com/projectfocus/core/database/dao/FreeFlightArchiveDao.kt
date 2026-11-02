package com.projectfocus.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.projectfocus.core.database.entity.FreeFlightArchiveEntity
import com.projectfocus.core.database.entity.FreeFlightPositionEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class FreeFlightArchiveDao {
    @Query("SELECT * FROM free_flight_archive WHERE activeSlot = 1 LIMIT 1")
    abstract suspend fun getActive(): FreeFlightArchiveEntity?

    @Query("SELECT * FROM free_flight_archive WHERE sessionId = :id AND ownerScope = :owner LIMIT 1")
    abstract suspend fun get(id: String, owner: String): FreeFlightArchiveEntity?

    @Query("SELECT * FROM free_flight_archive WHERE ownerScope = :owner AND status != 'ONGOING' ORDER BY updatedAtWallMs DESC")
    abstract fun observeTerminal(owner: String): Flow<List<FreeFlightArchiveEntity>>

    @Query("SELECT p.* FROM free_flight_position p INNER JOIN free_flight_archive a ON a.sessionId = p.sessionId WHERE a.ownerScope = :owner AND a.sessionId = :id ORDER BY p.sequence")
    abstract suspend fun positions(id: String, owner: String): List<FreeFlightPositionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertArchive(row: FreeFlightArchiveEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPosition(row: FreeFlightPositionEntity)

    // UPDATE preserves the position FK children; INSERT OR REPLACE would delete them.
    @Query("""
        UPDATE free_flight_archive SET
          revision = :revision, status = :status, activeSlot = :activeSlot,
          updatedAtWallMs = :updatedAtWallMs, payloadJson = :payloadJson
        WHERE sessionId = :id AND ownerScope = :owner
          AND revision = :expected AND status = 'ONGOING'
    """)
    abstract suspend fun updateArchive(
        id: String, owner: String, expected: Long, revision: Long, status: String,
        activeSlot: Int?, updatedAtWallMs: Long, payloadJson: String,
    ): Int

    /** Compare-and-set and append a sample in one Room transaction. */
    @Transaction
    open suspend fun commit(
        expectedRevision: Long?,
        row: FreeFlightArchiveEntity,
        position: FreeFlightPositionEntity?,
    ): Boolean {
        require((row.status == "ONGOING") == (row.activeSlot == 1))
        require(row.activeSlot == null || row.activeSlot == 1)
        if (position != null) require(position.sessionId == row.sessionId && position.sequence > 0L)
        val accepted = if (expectedRevision == null) {
            require(row.revision == 0L && row.status == "ONGOING")
            insertArchive(row) != -1L
        } else {
            require(row.revision == Math.addExact(expectedRevision, 1L))
            updateArchive(
                row.sessionId, row.ownerScope, expectedRevision, row.revision, row.status,
                row.activeSlot, row.updatedAtWallMs, row.payloadJson,
            ) == 1
        }
        if (!accepted) return false
        if (position != null) insertPosition(position)
        return true
    }
}
