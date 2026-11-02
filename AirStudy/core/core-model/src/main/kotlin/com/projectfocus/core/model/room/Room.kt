package com.projectfocus.core.model.room


data class Room(
    val id: String,
    val creator: RoomCreator,
    val inviteCode: String,
    val durationMinutes: Int,
    val chatEnabledDuringSession: Boolean,
    val state: RoomState,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val willStartAt: Long? = null,
    val participants: List<Participant> = emptyList(),
    val isPrivate: Boolean = true,
    val destinationAirportCode: String? = null,
    val destinationAirportName: String? = null,
)

data class RoomCreator(
    val id: String,
    val username: String,
    val avatarId: String?,
)

data class Participant(
    val id: String,
    val userId: String,
    val username: String,
    val avatarId: String?,
    val seatNumber: Int,
    val seatColumn: String?,
    val joinedAt: Long,
    val leftAt: Long? = null,
    val completed: Boolean = false,
)

enum class RoomState {
    WAITING,
    SCHEDULED,
    IN_FLIGHT,
    COMPLETED,
    CANCELLED,
}
