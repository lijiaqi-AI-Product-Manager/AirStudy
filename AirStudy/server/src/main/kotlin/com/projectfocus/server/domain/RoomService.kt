package com.projectfocus.server.domain

import com.projectfocus.core.network.dto.ChatMessage
import com.projectfocus.core.network.dto.CreateRoomRequestDto
import com.projectfocus.core.network.dto.ParticipantDto
import com.projectfocus.core.network.dto.RoomCreatorDto
import com.projectfocus.core.network.dto.RoomResponseDto
import com.projectfocus.core.network.sse.SseEvent
import com.projectfocus.server.db.Tables.RoomMessages
import com.projectfocus.server.db.Tables.RoomParticipants
import com.projectfocus.server.db.Tables.Rooms
import com.projectfocus.server.db.Tables.Users
import com.projectfocus.server.sse.SseHub
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID


class RoomService(private val sse: SseHub) {

    // ═══════════════════════════════════════════════════════
    // 创建
    // ═══════════════════════════════════════════════════════


    fun create(userId: String, req: CreateRoomRequestDto, now: Long = System.currentTimeMillis()): RoomResponseDto {
        validateDuration(req.durationMinutes)
        requireAirport(req.departureAirportCode, "出发")
        requireAirport(req.destinationAirportCode, "到达")
        if (req.departureAirportCode.equals(req.destinationAirportCode, ignoreCase = true)) {
            throw DomainException(2020, "出发地与目的地不能相同", io.ktor.http.HttpStatusCode.BadRequest)
        }
        if (req.willStartAt != null && req.willStartAt < now) throw ScheduleInPastException()

        return transaction {
            val roomId = UUID.randomUUID().toString()
            val code = generateUniqueInviteCode()
            val state = if (req.willStartAt != null) STATE_SCHEDULED else STATE_WAITING

            Rooms.insert {
                it[id] = roomId
                it[creatorId] = userId
                it[inviteCode] = code
                it[durationMinutes] = req.durationMinutes
                it[chatEnabledDuringSession] = req.chatEnabledDuringSession
                it[Rooms.state] = state
                it[isPrivate] = req.isPrivate
                it[createdAt] = now
                it[willStartAt] = req.willStartAt

                it[depCode] = req.departureAirportCode.uppercase()
                it[depCountry] = req.departureAirportCountry
                it[depName] = req.departureAirportName
                it[depState] = req.departureAirportState
                it[depLat] = req.departureAirportLatitude
                it[depLng] = req.departureAirportLongitude

                it[dstCode] = req.destinationAirportCode.uppercase()
                it[dstCountry] = req.destinationAirportCountry
                it[dstName] = req.destinationAirportName
                it[dstState] = req.destinationAirportState
                it[dstLat] = req.destinationAirportLatitude
                it[dstLng] = req.destinationAirportLongitude
            }



            //   打开一看自己是旁观者，这个交互被差评过。
            insertParticipant(roomId, userId, SeatMath.Seat(1, "A"), now)

            Users.update({ Users.id eq userId }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it.update(Users.totalHostedFlight, Users.totalHostedFlight + 1)
                }
            }

            // 创建者座位若被显式指定且不是 1A，按他的选择来
            req.creatorSeat?.let { raw ->
                SeatMath.Seat.parse(raw)?.let { seat ->
                    RoomParticipants.update({ (RoomParticipants.roomId eq roomId) and (RoomParticipants.userId eq userId) }) {
                        it[seatNumber] = seat.number
                        it[seatColumn] = seat.column
                    }
                }
            }

            loadRoom(roomId)!!
        }
    }

    // ═══════════════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════════════

    fun getRoom(roomId: String): RoomResponseDto = transaction {
        loadRoom(roomId) ?: throw RoomNotFoundException()
    }


    fun getRoomByInviteCode(rawCode: String): RoomResponseDto = transaction {
        val code = InviteCodes.normalize(rawCode)
        if (!InviteCodes.isValid(code)) throw InviteCodeInvalidException()
        val roomId = Rooms.selectAll().where { Rooms.inviteCode eq code }.limit(1)
            .firstOrNull()?.get(Rooms.id)

            ?: throw InviteCodeInvalidException()
        loadRoom(roomId) ?: throw InviteCodeInvalidException()
    }


    fun myRoom(userId: String): RoomResponseDto? = transaction {
        val roomId = RoomParticipants.selectAll()
            .where { (RoomParticipants.userId eq userId) and RoomParticipants.leftAt.isNull() }
            .orderBy(RoomParticipants.joinedAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()?.get(RoomParticipants.roomId)
            ?: return@transaction null
        loadRoom(roomId)
    }


    fun publicRooms(limit: Int = 20): List<RoomResponseDto> = transaction {
        val ids = Rooms.selectAll()
            .where {
                (Rooms.isPrivate eq false) and
                    (Rooms.state inList listOf(STATE_WAITING, STATE_SCHEDULED)) and
                    (Rooms.willStartAt.isNull() or (Rooms.willStartAt greaterEq System.currentTimeMillis()))
            }
            .orderBy(Rooms.createdAt to SortOrder.DESC)
            .limit(limit.coerceIn(1, 50))
            .map { it[Rooms.id] }
        ids.mapNotNull { loadRoom(it) }
    }

    // ═══════════════════════════════════════════════════════
    // 加入 / 离开
    // ═══════════════════════════════════════════════════════


    fun join(userId: String, inviteCode: String, selectedSeat: String?): RoomResponseDto {
        val normalized = InviteCodes.normalize(inviteCode)
        if (!InviteCodes.isValid(normalized)) throw InviteCodeInvalidException()

        val roomId = transaction {
            Rooms.selectAll().where { Rooms.inviteCode eq normalized }.limit(1)
                .firstOrNull()?.get(Rooms.id)
                ?: throw InviteCodeInvalidException()
        }

        val snapshot = transaction {
            val room = Rooms.selectAll().where { Rooms.id eq roomId }.forUpdate().firstOrNull()
                ?: throw InviteCodeInvalidException()

            // 状态校验：已结束/已取消不接受新加入
            val state = room[Rooms.state]
            if (state == STATE_COMPLETED) throw RoomAlreadyStartedException()
            if (state == STATE_CANCELLED) throw DomainException(2011, "该航班已被取消", io.ktor.http.HttpStatusCode.Conflict)

            // 幂等：已参与且未离开
            val existing = RoomParticipants.selectAll()
                .where { (RoomParticipants.roomId eq roomId) and (RoomParticipants.userId eq userId) and RoomParticipants.leftAt.isNull() }
                .limit(1).firstOrNull()
            if (existing != null) return@transaction loadRoom(roomId)!!

            val taken = occupiedSeats(roomId)
            if (taken.size >= SeatMath.MAX_SEATS) throw RoomFullException(SeatMath.MAX_SEATS)

            val preferred = SeatMath.Seat.parse(selectedSeat)
            val seat = if (preferred != null) {

                SeatMath.requireExact(preferred, taken) ?: throw SeatTakenException(selectedSeat!!)
            } else {
                // 未指定 → 顺序分配，占满即满员
                SeatMath.pick(null, taken) ?: throw RoomFullException(SeatMath.MAX_SEATS)
            }

            insertParticipant(roomId, userId, seat, System.currentTimeMillis())

            Users.update({ Users.id eq userId }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it.update(Users.totalGroupFlight, Users.totalGroupFlight + 1)
                }
            }

            val nickname = Users.selectAll().where { Users.id eq userId }.limit(1).firstOrNull()?.get(Users.username).orEmpty()
            val avatarId = Users.selectAll().where { Users.id eq userId }.limit(1).firstOrNull()?.get(Users.avatarId) ?: 0

            // SSE 在事务内只收集事件，事务成功提交后再发（见方法尾部的 flushEvents）
            pendingEvents.get().add {
                sse.broadcast(roomId, SseEvent.ParticipantJoined(roomId, userId, nickname, avatarId, seat.number))
                sse.broadcast(roomId, SseEvent.RoomStateChanged(roomId, state))
            }
            loadRoom(roomId)!!
        }

        flushEvents()
        return snapshot
    }









    fun leave(userId: String, roomId: String, voluntarily: Boolean = true) {
        transaction {
            val row = RoomParticipants.selectAll()
                .where {
                    (RoomParticipants.roomId eq roomId) and
                        (RoomParticipants.userId eq userId) and
                        RoomParticipants.leftAt.isNull()
                }
                .forUpdate().limit(1).firstOrNull() ?: throw NotInRoomException()


            val room = Rooms.selectAll().where { Rooms.id eq roomId }.limit(1).firstOrNull()
            val started = room?.get(Rooms.startedAt)
            val dur = room?.get(Rooms.durationMinutes) ?: 0
            val now = System.currentTimeMillis()
            val finished = started != null && now >= started + dur * 60_000L

            RoomParticipants.update({ RoomParticipants.id eq row[RoomParticipants.id] }) {
                it[leftAt] = now
                if (finished) it[completed] = true
            }

            pendingEvents.get().add {
                sse.broadcast(roomId, SseEvent.ParticipantLeft(roomId, userId, voluntarily))
            }
            Unit
        }
        flushEvents()
    }

    // ═══════════════════════════════════════════════════════
    // 改期 / 开始 / 结束
    // ═══════════════════════════════════════════════════════

    fun reschedule(userId: String, roomId: String, willStartAt: Long, now: Long = System.currentTimeMillis()): RoomResponseDto {
        if (willStartAt < now) throw ScheduleInPastException()

        val result = transaction {
            val room = Rooms.selectAll().where { Rooms.id eq roomId }.forUpdate().firstOrNull()
                ?: throw RoomNotFoundException()
            if (room[Rooms.creatorId] != userId) throw NotRoomHostException()


            val state = room[Rooms.state]
            if (state == STATE_STARTED || state == STATE_COMPLETED) throw CannotRescheduleException()

            Rooms.update({ Rooms.id eq roomId }) {
                it[Rooms.willStartAt] = willStartAt
                it[Rooms.state] = STATE_SCHEDULED
            }
            pendingEvents.get().add { sse.broadcast(roomId, SseEvent.RoomStateChanged(roomId, STATE_SCHEDULED)) }
            loadRoom(roomId)!!
        }
        flushEvents()
        return result
    }

    /** 立即开始（房主操作，或到点自动开始） */
    fun start(roomId: String, now: Long = System.currentTimeMillis()): RoomResponseDto {
        val result = transaction {
            val room = Rooms.selectAll().where { Rooms.id eq roomId }.forUpdate().firstOrNull()
                ?: throw RoomNotFoundException()
            val state = room[Rooms.state]
            if (state == STATE_STARTED) return@transaction loadRoom(roomId)!!
            if (state == STATE_COMPLETED) throw RoomAlreadyStartedException()
            if (state == STATE_CANCELLED) throw DomainException(2011, "该航班已被取消", io.ktor.http.HttpStatusCode.Conflict)

            Rooms.update({ Rooms.id eq roomId }) {
                it[Rooms.state] = STATE_STARTED
                it[startedAt] = now
            }
            pendingEvents.get().add { sse.broadcast(roomId, SseEvent.RoomStateChanged(roomId, STATE_STARTED)) }
            loadRoom(roomId)!!
        }
        flushEvents()
        return result
    }

    fun complete(roomId: String, now: Long = System.currentTimeMillis()): RoomResponseDto {
        val result = transaction {
            val room = Rooms.selectAll().where { Rooms.id eq roomId }.firstOrNull()
                ?: throw RoomNotFoundException()
            if (room[Rooms.state] == STATE_COMPLETED) return@transaction loadRoom(roomId)!!

            Rooms.update({ Rooms.id eq roomId }) {
                it[state] = STATE_COMPLETED
                it[completedAt] = now
            }
            // 全员标记完成：房间自然结束时，仍在座的人视为飞完
            RoomParticipants.update({ (RoomParticipants.roomId eq roomId) and RoomParticipants.leftAt.isNull() }) {
                it[completed] = true
            }
            pendingEvents.get().add { sse.broadcast(roomId, SseEvent.RoomStateChanged(roomId, STATE_COMPLETED)) }
            loadRoom(roomId)!!
        }
        flushEvents()
        return result
    }

    fun cancel(userId: String, roomId: String): RoomResponseDto {
        val result = transaction {
            val room = Rooms.selectAll().where { Rooms.id eq roomId }.firstOrNull() ?: throw RoomNotFoundException()
            if (room[Rooms.creatorId] != userId) throw NotRoomHostException()
            if (room[Rooms.state] == STATE_STARTED) throw DomainException(2012, "航班已起飞，无法取消", io.ktor.http.HttpStatusCode.Conflict)
            Rooms.update({ Rooms.id eq roomId }) { it[state] = STATE_CANCELLED }
            pendingEvents.get().add { sse.broadcast(roomId, SseEvent.RoomStateChanged(roomId, STATE_CANCELLED)) }
            loadRoom(roomId)!!
        }
        flushEvents()
        return result
    }

    // ═══════════════════════════════════════════════════════
    // 聊天
    // ═══════════════════════════════════════════════════════

    fun sendChat(userId: String, roomId: String, rawMessage: String, now: Long = System.currentTimeMillis()): ChatMessage {
        val text = rawMessage.trim()
        if (text.isEmpty()) throw DomainException(2030, "消息不能为空", io.ktor.http.HttpStatusCode.BadRequest)

        if (text.codePointCount(0, text.length) > MAX_CHAT_LENGTH) {
            throw DomainException(2031, "消息最多 $MAX_CHAT_LENGTH 字", io.ktor.http.HttpStatusCode.BadRequest)
        }
        if (text.any { it.isISOControl() && it != '\n' }) {
            throw DomainException(2032, "消息包含非法字符", io.ktor.http.HttpStatusCode.BadRequest)
        }

        val msg = transaction {
            val room = Rooms.selectAll().where { Rooms.id eq roomId }.firstOrNull() ?: throw RoomNotFoundException()
            val inRoom = RoomParticipants.selectAll()
                .where { (RoomParticipants.roomId eq roomId) and (RoomParticipants.userId eq userId) and RoomParticipants.leftAt.isNull() }
                .limit(1).any()
            if (!inRoom) throw NotInRoomException()




            if (room[Rooms.state] == STATE_STARTED && !room[Rooms.chatEnabledDuringSession]) {
                throw ChatDisabledException()
            }

            val sender = Users.selectAll().where { Users.id eq userId }.limit(1).firstOrNull()?.get(Users.username).orEmpty()
            val messageId = UUID.randomUUID().toString()
            RoomMessages.insert {
                it[id] = messageId
                it[RoomMessages.roomId] = roomId
                it[RoomMessages.userId] = userId
                it[message] = text
                it[createdAt] = now
            }
            val dto = ChatMessage(id = messageId, sender = sender, message = text, createdAt = now)
            pendingEvents.get().add {
                sse.broadcast(roomId, SseEvent.ChatMessageEvent(roomId, messageId, sender, text, now))
            }
            dto
        }
        flushEvents()
        return msg
    }

    fun chatHistory(roomId: String, limit: Int = 100): List<ChatMessage> = transaction {
        RoomMessages.selectAll().where { RoomMessages.roomId eq roomId }
            .orderBy(RoomMessages.createdAt to SortOrder.ASC)
            .limit(limit.coerceIn(1, 500))
            .map {
                val uid = it[RoomMessages.userId]
                val nick = Users.selectAll().where { Users.id eq uid }.limit(1).firstOrNull()?.get(Users.username).orEmpty()
                ChatMessage(
                    id = it[RoomMessages.id],
                    sender = nick,
                    message = it[RoomMessages.message],
                    createdAt = it[RoomMessages.createdAt],
                )
            }
    }

    // ═══════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════


    private val pendingEvents = ThreadLocal.withInitial { mutableListOf<() -> Unit>() }

    private fun flushEvents() {
        val list = pendingEvents.get()
        if (list.isEmpty()) return
        val copy = list.toList()
        list.clear()
        copy.forEach { runCatching(it).onFailure { e -> org.slf4j.LoggerFactory.getLogger(RoomService::class.java).warn("SSE 广播失败", e) } }
    }

    private fun validateDuration(minutes: Int) {
        if (minutes !in FlightMath.MIN_DURATION_MINUTES..FlightMath.MAX_DURATION_MINUTES) {
            throw DomainException(
                2021,
                "时长必须在 ${FlightMath.MIN_DURATION_MINUTES}~${FlightMath.MAX_DURATION_MINUTES} 分钟之间",
                io.ktor.http.HttpStatusCode.BadRequest,
            )
        }
    }

    private fun requireAirport(code: String, label: String) {
        val c = code.trim()
        if (c.length != 3 || !c.all { it.isLetter() }) {
            throw DomainException(2022, "$label 机场三字码不正确", io.ktor.http.HttpStatusCode.BadRequest)
        }
    }

    /** 邀请码撞码重试。31^6 空间下撞码概率极低，但仍然要有兜底 */
    private fun generateUniqueInviteCode(): String {
        repeat(8) {
            val code = InviteCodes.generate()
            val exists = Rooms.selectAll().where { Rooms.inviteCode eq code }.limit(1).any()
            if (!exists) return code
        }
        throw DomainException(2023, "创建房间失败，请重试", io.ktor.http.HttpStatusCode.ServiceUnavailable)
    }

    private fun insertParticipant(roomId: String, userId: String, seat: SeatMath.Seat, now: Long) {
        RoomParticipants.insert {
            it[id] = UUID.randomUUID().toString()
            it[RoomParticipants.roomId] = roomId
            it[RoomParticipants.userId] = userId
            it[joinedAt] = now
            it[seatNumber] = seat.number
            it[seatColumn] = seat.column
        }
    }

    private fun occupiedSeats(roomId: String): Set<SeatMath.Seat> =
        RoomParticipants.selectAll()
            .where { (RoomParticipants.roomId eq roomId) and RoomParticipants.leftAt.isNull() }
            .mapNotNull { row ->
                val n = row[RoomParticipants.seatNumber]
                val c = row[RoomParticipants.seatColumn]
                if (n == null || c == null) null else SeatMath.Seat(n, c)
            }.toSet()









    internal fun loadRoom(roomId: String): RoomResponseDto? {
        val room = Rooms.selectAll().where { Rooms.id eq roomId }.limit(1).firstOrNull() ?: return null
        val creatorRow = Users.selectAll().where { Users.id eq room[Rooms.creatorId] }.limit(1).firstOrNull()

        val participants = RoomParticipants.selectAll()
            .where { RoomParticipants.roomId eq roomId }
            .orderBy(RoomParticipants.joinedAt to SortOrder.ASC)
            .map { p ->
                val u = Users.selectAll().where { Users.id eq p[RoomParticipants.userId] }.limit(1).firstOrNull()
                ParticipantDto(
                    id = p[RoomParticipants.id],
                    userId = p[RoomParticipants.userId],
                    username = u?.get(Users.username).orEmpty(),
                    avatarId = u?.get(Users.avatarId) ?: 0,
                    joinedAt = p[RoomParticipants.joinedAt],
                    leftAt = p[RoomParticipants.leftAt],
                    completed = p[RoomParticipants.completed],
                    seatNumber = p[RoomParticipants.seatNumber],
                    seatColumn = p[RoomParticipants.seatColumn],
                )
            }

        return room.toDto(creatorRow, participants)
    }

    companion object {
        const val STATE_WAITING = "WAITING"
        const val STATE_SCHEDULED = "SCHEDULED"
        const val STATE_STARTED = "STARTED"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_CANCELLED = "CANCELLED"

        const val MAX_CHAT_LENGTH = 280
    }
}

internal fun ResultRow.toDto(creator: ResultRow?, participants: List<ParticipantDto>): RoomResponseDto = RoomResponseDto(
    id = this[Rooms.id],
    creator = creator?.let {
        RoomCreatorDto(id = it[Users.id], username = it[Users.username], avatarId = it[Users.avatarId])
    },
    inviteCode = this[Rooms.inviteCode],
    durationMinutes = this[Rooms.durationMinutes],
    chatEnabledDuringSession = this[Rooms.chatEnabledDuringSession],
    state = this[Rooms.state],
    startedAt = this[Rooms.startedAt],
    completedAt = this[Rooms.completedAt],
    createdAt = this[Rooms.createdAt],
    willStartAt = this[Rooms.willStartAt],
    participants = participants,
    isPrivate = this[Rooms.isPrivate],
    destinationAirportCode = this[Rooms.dstCode],
    destinationAirportCountry = this[Rooms.dstCountry],
    destinationAirportName = this[Rooms.dstName],
    destinationAirportState = this[Rooms.dstState],
    destinationAirportLatitude = this[Rooms.dstLat],
    destinationAirportLongitude = this[Rooms.dstLng],
    departureAirportCode = this[Rooms.depCode],
    departureAirportCountry = this[Rooms.depCountry],
    departureAirportName = this[Rooms.depName],
    departureAirportState = this[Rooms.depState],
    departureAirportLatitude = this[Rooms.depLat],
    departureAirportLongitude = this[Rooms.depLng],
)
