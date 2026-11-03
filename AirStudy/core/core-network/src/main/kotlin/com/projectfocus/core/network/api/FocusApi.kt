package com.projectfocus.core.network.api

import com.projectfocus.core.network.dto.ApiEnvelope
import com.projectfocus.core.network.dto.AuthUserDto
import com.projectfocus.core.network.dto.BulkSyncRequest
import com.projectfocus.core.network.dto.ChatMessage
import com.projectfocus.core.network.dto.CreateRoomRequestDto
import com.projectfocus.core.network.dto.FlightHistoryDto
import com.projectfocus.core.network.dto.JoinRoomRequestDto
import com.projectfocus.core.network.dto.RoomResponseDto
import com.projectfocus.core.network.dto.SendChatRequest
import com.projectfocus.core.network.dto.SessionDetails
import com.projectfocus.core.network.dto.SessionHistoryDto
import com.projectfocus.core.network.dto.StartSessionRequest
import com.projectfocus.core.network.dto.SyncResultDto
import com.projectfocus.core.network.dto.UpdateRoomStartTime
import com.projectfocus.core.network.dto.UpdateUserRequest
import com.projectfocus.core.network.dto.UserProfileDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query


interface FocusApi {

    // ═══════════════════════════════════════════════════════
    // 认证（1）
    // ═══════════════════════════════════════════════════════


    @GET("auth/verify")
    suspend fun verifyToken(): ApiEnvelope<AuthUserDto>

    // ═══════════════════════════════════════════════════════
    // 用户（2）
    // ═══════════════════════════════════════════════════════

    @GET("user/me")
    suspend fun getMe(): ApiEnvelope<UserProfileDto>


    @PATCH("user/update")
    suspend fun updateUser(@Body body: UpdateUserRequest): ApiEnvelope<UserProfileDto>

    // ═══════════════════════════════════════════════════════
    // 飞行记录（3）
    // ═══════════════════════════════════════════════════════


    @POST("session/sync")
    suspend fun syncSessions(@Body body: BulkSyncRequest): ApiEnvelope<SyncResultDto>

    @GET("session/history")
    suspend fun getSessionHistory(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): ApiEnvelope<SessionHistoryDto>

    @DELETE("session/{id}")
    suspend fun deleteSession(@Path("id") id: String): ApiEnvelope<Unit>

    // ═══════════════════════════════════════════════════════
    // 会话（1）
    // ═══════════════════════════════════════════════════════

    @POST("start")
    suspend fun startSession(@Body body: StartSessionRequest): ApiEnvelope<SessionDetails>

    // ═══════════════════════════════════════════════════════
    // 组队房间（8）
    // ═══════════════════════════════════════════════════════

    /** 凭 6 位邀请码查房间（加入前预览用：用户想知道"这是什么房间"） */
    @GET("code/{code}")
    suspend fun validateInviteCode(@Path("code") code: String): ApiEnvelope<RoomResponseDto>

    @POST("join")
    suspend fun joinRoom(@Body body: JoinRoomRequestDto): ApiEnvelope<RoomResponseDto>

    @POST("leave")
    suspend fun leaveRoom(): ApiEnvelope<Unit>

    @GET("room/{id}")
    suspend fun getRoom(@Path("id") id: String): ApiEnvelope<RoomResponseDto>

    /** 当前用户所在的房间（null = 不在任何房间） */
    @GET("my-room")
    suspend fun getMyRoom(): ApiEnvelope<RoomResponseDto?>

    @GET("public")
    suspend fun getPublicRooms(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): ApiEnvelope<List<RoomResponseDto>>

    @PUT("schedule")
    suspend fun scheduleRoom(@Body body: UpdateRoomStartTime): ApiEnvelope<RoomResponseDto>

    @POST("chat")
    suspend fun sendChat(@Body body: SendChatRequest): ApiEnvelope<ChatMessage>

    // ═══════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════


    @POST("room/create")
    suspend fun createRoom(@Body body: CreateRoomRequestDto): ApiEnvelope<RoomResponseDto>
}

/**
 * 便于测试与 Mock 实现的 DTO 类型别名。
 *
 * 只是为了让"上行 DTO"在别的模块里引用时更短，不改变任何语义。
 */
typealias UploadFlightDto = FlightHistoryDto
