package com.projectfocus.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Editable history labels are independent of the original flight and recovery inputs. */
@Entity(
    tableName = "flight_record_scene",
    primaryKeys = ["recordKind", "recordId"],
    foreignKeys = [
        ForeignKey(entity = FlightHistoryEntity::class, parentColumns = ["id"],
            childColumns = ["countdownId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FreeFlightArchiveEntity::class, parentColumns = ["sessionId"],
            childColumns = ["countUpId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("ownerScope"), Index("countdownId"), Index("countUpId")],
)
data class FlightRecordSceneEntity(
    val recordKind: String,
    val recordId: String,
    val ownerScope: String,
    val countdownId: String?,
    val countUpId: String?,
    val startedAtWallMs: Long,
    val revision: Long,
    val sceneJson: String,
    val updatedAtWallMs: Long,
) {
    init {
        require(recordId.isNotBlank() && ownerScope.isNotBlank() && sceneJson.isNotBlank())
        require(revision > 0L)
        require(when (recordKind) {
            "COUNTDOWN" -> countdownId == recordId && countUpId == null
            "COUNT_UP" -> countUpId == recordId && countdownId == null
            else -> false
        })
    }
}
