package com.projectfocus.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


// ═══════════════════════════════════════════════════════════
// 统一响应封装
// ═══════════════════════════════════════════════════════════


@Serializable
data class ApiEnvelope<T>(
    @SerialName("data")
    val data: T? = null,
    @SerialName("error")
    val error: ApiErrorDto? = null,
    @SerialName("success")
    val success: Boolean? = null,
    @SerialName("message")
    val message: String? = null,
)

@Serializable
data class ApiErrorDto(
    @SerialName("code")
    val code: Int? = null,
    @SerialName("message")
    val message: String? = null,
    @SerialName("traceId")
    val traceId: String? = null,
)

// ═══════════════════════════════════════════════════════════
// 认证与用户
// ═══════════════════════════════════════════════════════════

@Serializable
data class AuthUserDto(
    @SerialName("id")
    val id: String,
    @SerialName("email")
    val email: String = "",
    @SerialName("token")
    val token: String,
)


@Serializable
data class UserProfileDto(
    @SerialName("id")
    val id: String,
    @SerialName("username")
    val username: String = "",
    @SerialName("avatarId")
    val avatarId: Int = 0,
    @SerialName("isPremium")
    val isPremium: Boolean = false,
    @SerialName("xp")
    val xp: Int = 0,
    @SerialName("streak")
    val streak: Int = 0,
    @SerialName("totalGroupFlight")
    val totalGroupFlight: Int = 0,
    @SerialName("totalHostedFlight")
    val totalHostedFlight: Int = 0,
    @SerialName("challengesCompleted")
    val challengesCompleted: Int = 0,
    @SerialName("totalFocusedMinutes")
    val totalFocusedMinutes: Int = 0,
    @SerialName("countriesVisited")
    val countriesVisited: Int = 0,
    @SerialName("perfectWeeks")
    val perfectWeeks: Int = 0,
    @SerialName("rank")
    val rank: Int = 0,
)

@Serializable
data class UpdateUserRequest(
    @SerialName("username")
    val username: String? = null,
    @SerialName("avatarId")
    val avatarId: Int? = null,
)

// ═══════════════════════════════════════════════════════════
// 飞行记录同步
// ═══════════════════════════════════════════════════════════

@Serializable
data class BulkSyncRequest(
    @SerialName("sessions")
    val sessions: List<FlightHistoryDto>,
)

@Serializable
data class SyncResultDto(
    @SerialName("accepted")
    val accepted: Int = 0,
    @SerialName("rejected")
    val rejected: Int = 0,
    @SerialName("serverIds")
    val serverIds: List<String> = emptyList(),
)

















@Serializable
data class FlightHistoryDto(
    @SerialName("id")
    val id: String,
    @SerialName("roomId")
    val roomId: String? = null,
    @SerialName("focusType")
    val focusType: String = "SOLO",
    @SerialName("departureAirportCode")
    val departureAirportCode: String,
    @SerialName("destinationAirportCode")
    val destinationAirportCode: String,
    @SerialName("startTimeMillis")
    val startTimeMillis: Long,
    @SerialName("endTimeMillis")
    val endTimeMillis: Long? = null,
    @SerialName("durationMinutes")
    val durationMinutes: Int,
    @SerialName("actualFocusMinutes")
    val actualFocusMinutes: Int,
    @SerialName("distanceKm")
    val distanceKm: Double,
    @SerialName("status")
    val status: String = "COMPLETED",
    @SerialName("categoryId")
    val categoryId: Long? = null,
    @SerialName("seat")
    val seat: String? = null,
    @SerialName("logJson")
    val logJson: String? = null,
    @SerialName("createdAt")
    val createdAt: Long,
    @SerialName("updatedAt")
    val updatedAt: Long,

    @SerialName("quote")
    val quote: String? = null,
    @SerialName("quoteAuthor")
    val quoteAuthor: String? = null,
)

@Serializable
data class SessionHistoryDto(
    @SerialName("sessions")
    val sessions: List<FlightHistoryDto> = emptyList(),
    @SerialName("total")
    val total: Int = 0,
    @SerialName("page")
    val page: Int = 0,
    @SerialName("hasMore")
    val hasMore: Boolean = false,
)

@Serializable
data class StartSessionRequest(
    @SerialName("durationMinutes")
    val durationMinutes: Int,
    @SerialName("departureAirportCode")
    val departureAirportCode: String,
    @SerialName("destinationAirportCode")
    val destinationAirportCode: String,
    @SerialName("categoryId")
    val categoryId: Long? = null,
)

@Serializable
data class SessionDetails(
    @SerialName("id")
    val id: String,
    @SerialName("startTimeMillis")
    val startTimeMillis: Long,
    @SerialName("durationMinutes")
    val durationMinutes: Int,
    @SerialName("distanceKm")
    val distanceKm: Double = 0.0,
)

// ═══════════════════════════════════════════════════════════
// 组队房间
// ═══════════════════════════════════════════════════════════








@Serializable
data class RoomResponseDto(
    @SerialName("id")
    val id: String,
    @SerialName("creator")
    val creator: RoomCreatorDto? = null,
    @SerialName("inviteCode")
    val inviteCode: String = "",
    @SerialName("durationMinutes")
    val durationMinutes: Int = 0,
    @SerialName("chatEnabledDuringSession")
    val chatEnabledDuringSession: Boolean = false,
    /** WAITING | SCHEDULED | STARTED | COMPLETED | CANCELLED */
    @SerialName("state")
    val state: String = "WAITING",
    @SerialName("startedAt")
    val startedAt: Long? = null,
    @SerialName("completedAt")
    val completedAt: Long? = null,
    @SerialName("createdAt")
    val createdAt: Long = 0L,
    @SerialName("willStartAt")
    val willStartAt: Long? = null,
    @SerialName("participants")
    val participants: List<ParticipantDto> = emptyList(),
    @SerialName("isPrivate")
    val isPrivate: Boolean = false,

    @SerialName("destinationAirportCode")
    val destinationAirportCode: String = "",
    @SerialName("destinationAirportCountry")
    val destinationAirportCountry: String = "",
    @SerialName("destinationAirportName")
    val destinationAirportName: String = "",
    @SerialName("destinationAirportState")
    val destinationAirportState: String = "",
    @SerialName("destinationAirportLatitude")
    val destinationAirportLatitude: Double = 0.0,
    @SerialName("destinationAirportLongitude")
    val destinationAirportLongitude: Double = 0.0,

    @SerialName("departureAirportCode")
    val departureAirportCode: String = "",
    @SerialName("departureAirportCountry")
    val departureAirportCountry: String = "",
    @SerialName("departureAirportName")
    val departureAirportName: String = "",
    @SerialName("departureAirportState")
    val departureAirportState: String = "",
    @SerialName("departureAirportLatitude")
    val departureAirportLatitude: Double = 0.0,
    @SerialName("departureAirportLongitude")
    val departureAirportLongitude: Double = 0.0,
)

@Serializable
data class RoomCreatorDto(
    @SerialName("id")
    val id: String,
    @SerialName("username")
    val username: String = "",
    @SerialName("avatarId")
    val avatarId: Int = 0,
)

@Serializable
data class ParticipantDto(
    @SerialName("id")
    val id: String,
    @SerialName("userId")
    val userId: String = "",
    @SerialName("username")
    val username: String = "",
    @SerialName("avatarId")
    val avatarId: Int = 0,
    @SerialName("joinedAt")
    val joinedAt: Long = 0L,
    @SerialName("leftAt")
    val leftAt: Long? = null,
    @SerialName("completed")
    val completed: Boolean = false,

    @SerialName("seatNumber")
    val seatNumber: Int? = null,
    @SerialName("seatColumn")
    val seatColumn: String? = null,
)

@Serializable
data class CreateRoomRequestDto(
    @SerialName("durationMinutes")
    val durationMinutes: Int,
    @SerialName("chatEnabledDuringSession")
    val chatEnabledDuringSession: Boolean,

    @SerialName("private")
    val isPrivate: Boolean,
    @SerialName("destinationAirportCode")
    val destinationAirportCode: String,
    @SerialName("destinationAirportState")
    val destinationAirportState: String = "",
    @SerialName("destinationAirportCountry")
    val destinationAirportCountry: String = "",
    @SerialName("destinationAirportName")
    val destinationAirportName: String = "",
    @SerialName("destinationAirportLatitude")
    val destinationAirportLatitude: Double = 0.0,
    @SerialName("destinationAirportLongitude")
    val destinationAirportLongitude: Double = 0.0,
    @SerialName("departureAirportCode")
    val departureAirportCode: String,
    @SerialName("departureAirportCountry")
    val departureAirportCountry: String = "",
    @SerialName("departureAirportName")
    val departureAirportName: String = "",
    @SerialName("departureAirportState")
    val departureAirportState: String = "",
    @SerialName("departureAirportLatitude")
    val departureAirportLatitude: Double = 0.0,
    @SerialName("departureAirportLongitude")
    val departureAirportLongitude: Double = 0.0,
    @SerialName("creatorSeat")
    val creatorSeat: String? = null,
    @SerialName("willStartAt")
    val willStartAt: Long? = null,
)

@Serializable
data class JoinRoomRequestDto(
    @SerialName("selectedSeat")
    val selectedSeat: String? = null,
    @SerialName("inviteCode")
    val inviteCode: String,
)

@Serializable
data class UpdateRoomStartTime(
    @SerialName("roomId")
    val roomId: String,
    @SerialName("willStartAt")
    val willStartAt: Long,
)

@Serializable
data class SendChatRequest(
    @SerialName("roomId")
    val roomId: String,
    @SerialName("message")
    val message: String,
)

@Serializable
data class ChatMessage(
    @SerialName("id")
    val id: String,
    @SerialName("sender")
    val sender: String = "",
    @SerialName("message")
    val message: String = "",
    @SerialName("createdAt")
    val createdAt: Long = 0L,
)
