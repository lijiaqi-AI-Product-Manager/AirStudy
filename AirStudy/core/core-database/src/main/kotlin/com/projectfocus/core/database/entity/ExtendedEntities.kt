package com.projectfocus.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey


@Entity(
    tableName = "room",
    indices = [
        Index(value = ["inviteCode"], name = "index_room_inviteCode"),
    ],
)
data class RoomEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "creatorId")
    val creatorId: String,

    @ColumnInfo(name = "creatorUsername")
    val creatorUsername: String,

    @ColumnInfo(name = "creatorAvatarId")
    val creatorAvatarId: String?,


    @ColumnInfo(name = "inviteCode")
    val inviteCode: String,

    @ColumnInfo(name = "durationMinutes")
    val durationMinutes: Int,

    @ColumnInfo(name = "chatEnabledDuringSession")
    val chatEnabledDuringSession: Boolean,







    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "startedAt")
    val startedAt: Long?,

    /** 房主设定的"开始时间"（未来时刻），用于倒计时 */
    @ColumnInfo(name = "willStartAt")
    val willStartAt: Long?,

    @ColumnInfo(name = "completedAt")
    val completedAt: Long?,

    @ColumnInfo(name = "isPrivate")
    val isPrivate: Boolean,

    @ColumnInfo(name = "destinationAirportCode")
    val destinationAirportCode: String?,

    @ColumnInfo(name = "destinationAirportName")
    val destinationAirportName: String?,


    @ColumnInfo(name = "cachedAt")
    val cachedAt: Long,
)








@Entity(
    tableName = "room_participant",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["roomId"], name = "index_room_participant_roomId"),
    ],
)
data class RoomParticipantEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "roomId")
    val roomId: String,

    @ColumnInfo(name = "userId")
    val userId: String,

    @ColumnInfo(name = "username")
    val username: String,

    @ColumnInfo(name = "avatarId")
    val avatarId: String?,

    /** 座号 1~N，决定机舱图上画在哪 */
    @ColumnInfo(name = "seatNumber")
    val seatNumber: Int,

    /** A / B / C / D / E / F（窄体机 3-3 布局） */
    @ColumnInfo(name = "seatColumn")
    val seatColumn: String?,

    @ColumnInfo(name = "joinedAt")
    val joinedAt: Long,

    @ColumnInfo(name = "leftAt")
    val leftAt: Long?,

    @ColumnInfo(name = "completed")
    val completed: Boolean,
)








@Entity(
    tableName = "room_message",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["roomId", "sentAt"], name = "index_room_message_roomId_sentAt"),
    ],
)
data class RoomMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "roomId")
    val roomId: String,

    @ColumnInfo(name = "senderId")
    val senderId: String,

    @ColumnInfo(name = "senderName")
    val senderName: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "sentAt")
    val sentAt: Long,

    @ColumnInfo(name = "isSystem")
    val isSystem: Boolean,
)


@Entity(tableName = "achievement")
data class AchievementEntity(
    /** 徽章 id，如 `first_flight` / `distance_10000` */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "progress")
    val progress: Int,

    @ColumnInfo(name = "target")
    val target: Int,

    /** null = 未解锁 */
    @ColumnInfo(name = "unlockedAt")
    val unlockedAt: Long?,

    @ColumnInfo(name = "isNew")
    val isNew: Boolean,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
) {
    val isUnlocked: Boolean get() = unlockedAt != null
    val ratio: Float get() = if (target <= 0) 1f else (progress.toFloat() / target).coerceIn(0f, 1f)
}


@Entity(tableName = "daily_stat")
data class DailyStatEntity(
    @PrimaryKey
    @ColumnInfo(name = "dayStart")
    val dayStart: Long,

    @ColumnInfo(name = "focusMinutes")
    val focusMinutes: Int,

    @ColumnInfo(name = "flightCount")
    val flightCount: Int,

    @ColumnInfo(name = "distanceKm")
    val distanceKm: Double,
)


@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    /** null 表示"删除标记"（同步时用，本地读到时视为删除） */
    @ColumnInfo(name = "value")
    val value: String?,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
)
