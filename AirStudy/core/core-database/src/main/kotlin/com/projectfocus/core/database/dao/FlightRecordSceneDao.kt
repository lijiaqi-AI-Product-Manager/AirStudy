package com.projectfocus.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.projectfocus.core.database.entity.FlightRecordSceneEntity

@Dao
interface FlightRecordSceneDao {
    @Query("SELECT * FROM flight_record_scene WHERE recordKind = :kind AND recordId = :id LIMIT 1")
    suspend fun find(kind: String, id: String): FlightRecordSceneEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: FlightRecordSceneEntity)

    @Query("""
        UPDATE flight_record_scene SET sceneJson = :sceneJson, revision = :revision,
          updatedAtWallMs = :updatedAt
        WHERE recordKind = :kind AND recordId = :id AND ownerScope = :owner
          AND startedAtWallMs = :startedAt AND revision = :expected
    """)
    suspend fun update(kind: String, id: String, owner: String, startedAt: Long,
        expected: Long, revision: Long, sceneJson: String, updatedAt: Long): Int
}
