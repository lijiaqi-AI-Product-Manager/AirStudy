package com.projectfocus.core.network.mock

import com.projectfocus.core.network.api.FocusApi
import com.projectfocus.core.network.dto.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random























class MockFocusApi(
    /** 每次调用的模拟网络延迟区间（毫秒） */
    private val latencyRange: LongRange = 80L..400L,
    /** 随机失败率，0 = 永不失败。默认 0：UI 开发时别自己找麻烦 */
    private val failureRate: Double = 0.0,
    /** 强制下一次调用返回这个错误（用于手动验证错误态） */
    var forceError: ApiErrorDto? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : FocusApi {

    // ═══════════════════════════════════════════════════════
    // 内部状态
    // ═══════════════════════════════════════════════════════






    private val mutex = Mutex()

    private var nextId = 1
    private fun id(prefix: String): String = "$prefix-${nextId++}"

    private val rooms = mutableMapOf<String, RoomResponseDto>()
    private val roomsByCode = mutableMapOf<String, String>()
    private val chatLog = mutableMapOf<String, MutableList<ChatMessage>>()
    private val sessions = mutableListOf<FlightHistoryDto>()
    private var nextServerId = 1

    private val _me = MutableStateFlow(
        UserProfileDto(
            id = "u-self",
            username = "高斯",
            avatarId = 2,
            xp = 4_280,
            streak = 12,
            totalFocusedMinutes = 8_640,
            countriesVisited = 7,
            rank = 1_024,
        ),
    )
    val me: StateFlow<UserProfileDto> = _me.asStateFlow()

    private var currentRoomId: String? = null

    // ═══════════════════════════════════════════════════════
    // 认证与用户
    // ═══════════════════════════════════════════════════════

    override suspend fun verifyToken(): ApiEnvelope<AuthUserDto> = respond {
        AuthUserDto(id = _me.value.id, email = "mock@projectfocus.app", token = "mock-token")
    }

    override suspend fun getMe(): ApiEnvelope<UserProfileDto> = respond { _me.value }

    override suspend fun updateUser(body: UpdateUserRequest): ApiEnvelope<UserProfileDto> = respond {
        val current = _me.value
        val updated = current.copy(
            username = body.username ?: current.username,
            avatarId = body.avatarId ?: current.avatarId,
        )

        //   不做这一步，UI 会以为"随便输什么都行"。
        if (updated.username.isBlank()) throw MockBusinessError(1002, "昵称不能为空")
        if (updated.username.length > 20) throw MockBusinessError(1003, "昵称最多 20 个字符")
        _me.value = updated
        updated
    }

    // ═══════════════════════════════════════════════════════
    // 飞行记录
    // ═══════════════════════════════════════════════════════

    override suspend fun syncSessions(body: BulkSyncRequest): ApiEnvelope<SyncResultDto> = respond {
        mutex.withLock {
            val serverIds = body.sessions.map {
                sessions += it
                "sv-${nextServerId++}"
            }
            SyncResultDto(accepted = serverIds.size, rejected = 0, serverIds = serverIds)
        }
    }

    override suspend fun getSessionHistory(page: Int, size: Int): ApiEnvelope<SessionHistoryDto> =
        respond {
            val sorted = sessions.sortedByDescending { it.startTimeMillis }
            val from = (page * size).coerceAtMost(sorted.size)
            val to = (from + size).coerceAtMost(sorted.size)
            SessionHistoryDto(
                sessions = sorted.subList(from, to).toList(),
                total = sorted.size,
                page = page,
                hasMore = to < sorted.size,
            )
        }

    override suspend fun deleteSession(id: String): ApiEnvelope<Unit> = respond {
        val removed = sessions.removeAll { it.id == id }
        if (!removed) throw MockBusinessError(4040, "记录不存在")
        Unit
    }

    override suspend fun startSession(body: StartSessionRequest): ApiEnvelope<SessionDetails> =
        respond {
            SessionDetails(
                id = id("sess"),
                startTimeMillis = now(),
                durationMinutes = body.durationMinutes,
                distanceKm = body.durationMinutes * (800.0 / 60.0),
            )
        }

    // ═══════════════════════════════════════════════════════
    // 组队房间
    // ═══════════════════════════════════════════════════════

    override suspend fun createRoom(body: CreateRoomRequestDto): ApiEnvelope<RoomResponseDto> =
        respond {
            if (body.durationMinutes !in 5..180) throw MockBusinessError(2001, "时长需在 5~180 分钟之间")
            mutex.withLock {
                val code = uniqueInviteCode()
                val room = RoomResponseDto(
                    id = id("room"),
                    creator = RoomCreatorDto(id = _me.value.id, username = _me.value.username, avatarId = _me.value.avatarId),
                    inviteCode = code,
                    durationMinutes = body.durationMinutes,
                    chatEnabledDuringSession = body.chatEnabledDuringSession,
                    state = if (body.willStartAt != null) "SCHEDULED" else "WAITING",
                    createdAt = now(),
                    willStartAt = body.willStartAt,
                    isPrivate = body.isPrivate,
                    participants = listOf(
                        ParticipantDto(
                            id = id("p"),
                            userId = _me.value.id,
                            username = _me.value.username,
                            avatarId = _me.value.avatarId,
                            joinedAt = now(),
                        ),
                    ),
                    destinationAirportCode = body.destinationAirportCode,
                    destinationAirportCountry = body.destinationAirportCountry,
                    destinationAirportName = body.destinationAirportName,
                    destinationAirportState = body.destinationAirportState,
                    destinationAirportLatitude = body.destinationAirportLatitude,
                    destinationAirportLongitude = body.destinationAirportLongitude,
                    departureAirportCode = body.departureAirportCode,
                    departureAirportCountry = body.departureAirportCountry,
                    departureAirportName = body.departureAirportName,
                    departureAirportState = body.departureAirportState,
                    departureAirportLatitude = body.departureAirportLatitude,
                    departureAirportLongitude = body.departureAirportLongitude,
                )
                rooms[room.id] = room
                roomsByCode[code] = room.id
                currentRoomId = room.id
                room
            }
        }

    override suspend fun validateInviteCode(code: String): ApiEnvelope<RoomResponseDto> = respond {
        mutex.withLock {
            val roomId = roomsByCode[code.uppercase()] ?: throw MockBusinessError(1001, "邀请码已失效")
            rooms[roomId] ?: throw MockBusinessError(1001, "邀请码已失效")
        }
    }

    override suspend fun joinRoom(body: JoinRoomRequestDto): ApiEnvelope<RoomResponseDto> = respond {
        mutex.withLock {
            val roomId = roomsByCode[body.inviteCode.uppercase()] ?: throw MockBusinessError(1001, "邀请码已失效")
            val room = rooms[roomId] ?: throw MockBusinessError(1001, "邀请码已失效")

            // 真实的状态机校验：房间已经开始了就不能再加入
            if (room.state == "COMPLETED" || room.state == "CANCELLED") {
                throw MockBusinessError(2002, "该航班已结束")
            }
            if (room.participants.size >= MAX_PARTICIPANTS) {
                throw MockBusinessError(2003, "房间已满")
            }
            if (room.participants.any { it.userId == _me.value.id }) {

                // 用户看了会以为出 bug。
                return@respond room
            }

            val joined = room.copy(
                participants = room.participants + ParticipantDto(
                    id = id("p"),
                    userId = _me.value.id,
                    username = _me.value.username,
                    avatarId = _me.value.avatarId,
                    joinedAt = now(),
                    seatNumber = parseSeat(body.selectedSeat),
                    seatColumn = parseColumn(body.selectedSeat),
                ),
            )
            rooms[roomId] = joined
            currentRoomId = roomId
            joined
        }
    }

    override suspend fun leaveRoom(): ApiEnvelope<Unit> = respond {
        mutex.withLock {
            val roomId = currentRoomId ?: return@withLock Unit
            val room = rooms[roomId]
            if (room != null) {
                rooms[roomId] = room.copy(
                    participants = room.participants.filterNot { it.userId == _me.value.id },
                )
            }
            currentRoomId = null
            Unit
        }
    }

    override suspend fun getRoom(id: String): ApiEnvelope<RoomResponseDto> = respond {
        mutex.withLock { rooms[id] ?: throw MockBusinessError(1004, "房间不存在") }
    }

    override suspend fun getMyRoom(): ApiEnvelope<RoomResponseDto?> = respond {
        mutex.withLock { currentRoomId?.let { rooms[it] } }
    }

    override suspend fun getPublicRooms(page: Int, size: Int): ApiEnvelope<List<RoomResponseDto>> =
        respond {
            mutex.withLock {
                rooms.values
                    .filter { !it.isPrivate && it.state in setOf("WAITING", "SCHEDULED") }
                    .sortedByDescending { it.createdAt }
                    .drop(page * size)
                    .take(size)
            }
        }

    override suspend fun scheduleRoom(body: UpdateRoomStartTime): ApiEnvelope<RoomResponseDto> =
        respond {
            mutex.withLock {
                val room = rooms[body.roomId] ?: throw MockBusinessError(1004, "房间不存在")
                if (room.state != "WAITING" && room.state != "SCHEDULED") {
                    throw MockBusinessError(2004, "该航班已开始，无法改期")
                }
                // 改期必须改到未来
                if (body.willStartAt <= now()) throw MockBusinessError(2005, "起飞时间必须晚于当前时间")
                val updated = room.copy(state = "SCHEDULED", willStartAt = body.willStartAt)
                rooms[body.roomId] = updated
                updated
            }
        }

    override suspend fun sendChat(body: SendChatRequest): ApiEnvelope<ChatMessage> = respond {
        mutex.withLock {
            val room = rooms[body.roomId] ?: throw MockBusinessError(1004, "房间不存在")
            if (body.message.isBlank()) throw MockBusinessError(2006, "消息不能为空")
            if (body.message.length > MAX_CHAT_LENGTH) throw MockBusinessError(2007, "消息过长（上限 $MAX_CHAT_LENGTH 字）")

            if (room.state == "STARTED" && !room.chatEnabledDuringSession) {
                throw MockBusinessError(2008, "本次航班已关闭机上聊天")
            }
            val msg = ChatMessage(
                id = id("msg"),
                sender = _me.value.username,
                message = body.message,
                createdAt = now(),
            )
            chatLog.getOrPut(body.roomId) { mutableListOf() } += msg
            msg
        }
    }

    // ═══════════════════════════════════════════════════════
    // 测试观察点
    // ═══════════════════════════════════════════════════════

    /** 直接读某个房间的聊天记录（测试用，不经过网络） */
    fun chatHistory(roomId: String): List<ChatMessage> = chatLog[roomId].orEmpty()

    /** 强制把房间推进到某个状态（测试"开始/结束"分支用） */
    suspend fun forceRoomState(roomId: String, state: String) {
        mutex.withLock {
            rooms[roomId]?.let { rooms[roomId] = it.copy(state = state) }
        }
    }

    /** 清空全部内存状态（每个测试之间调用） */
    suspend fun reset() {
        mutex.withLock {
            rooms.clear()
            roomsByCode.clear()
            chatLog.clear()
            sessions.clear()
            currentRoomId = null
            nextId = 1
            nextServerId = 1
            forceError = null
        }
    }

    // ═══════════════════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════════════════








    private suspend fun <T> respond(block: suspend () -> T): ApiEnvelope<T> {
        delay(Random.nextLong(latencyRange.first, latencyRange.last + 1))

        forceError?.let {
            val e = it
            forceError = null
            return ApiEnvelope(data = null, error = e)
        }

        if (failureRate > 0 && Random.nextDouble() < failureRate) {
            return ApiEnvelope(
                data = null,
                error = ApiErrorDto(code = 5000, message = "模拟的网络抖动（failureRate=$failureRate）"),
            )
        }

        return try {
            ApiEnvelope(data = block(), error = null)
        } catch (e: MockBusinessError) {
            ApiEnvelope(data = null, error = ApiErrorDto(code = e.code, message = e.message))
        }
    }

    private fun uniqueInviteCode(): String {

        // 用户报过"输入 0 说无效，其实是 O"。
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        repeat(50) {
            val code = (1..6).map { alphabet.random() }.joinToString("")
            if (code !in roomsByCode) return code
        }
        error("邀请码空间耗尽（只有测试里才可能出现）")
    }

    /** "12A" → 12；解析不出就 null（UI 显示"待安排"） */
    private fun parseSeat(seat: String?): Int? =
        seat?.takeWhile { it.isDigit() }?.toIntOrNull()

    /** "12A" → "A" */
    private fun parseColumn(seat: String?): String? =
        seat?.dropWhile { it.isDigit() }?.takeIf { it.isNotBlank() }

    private class MockBusinessError(val code: Int, override val message: String) : Exception(message)

    companion object {

        const val MAX_PARTICIPANTS = 8
        const val MAX_CHAT_LENGTH = 280
    }
}
