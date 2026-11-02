package com.projectfocus.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey


@Entity(
    tableName = "free_flight_archive",
    indices = [
        Index(value = ["activeSlot"], unique = true),
        Index(value = ["ownerScope", "updatedAtWallMs"]),
    ],
)
data class FreeFlightArchiveEntity(
    @PrimaryKey val sessionId: String,
    val ownerScope: String,
    val revision: Long,
    val status: String,
    /** Only ONGOING has 1; nullable unique index admits multiple terminal rows. */
    val activeSlot: Int?,
    val updatedAtWallMs: Long,
    val payloadJson: String,
)

/** Append positions atomically with the checkpoint instead of rewriting all positions. */
@Entity(
    tableName = "free_flight_position",
    primaryKeys = ["sessionId", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = FreeFlightArchiveEntity::class,
            parentColumns = ["sessionId"], childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
data class FreeFlightPositionEntity(
    val sessionId: String,
    val sequence: Long,
    val activeElapsedMs: Long,
    val latitude: Double,
    val longitude: Double,
    val bearingDegrees: Double,
    val cumulativeDistanceKm: Double,
    val wallMs: Long,
)
