package com.projectfocus.server.sse

import com.projectfocus.core.network.sse.SseEvent
import com.projectfocus.server.config.SseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


class SseHub(
    private val scope: CoroutineScope,
    private val config: SseConfig,
) {

    private val log = LoggerFactory.getLogger(SseHub::class.java)








    private val rooms = ConcurrentHashMap<String, MutableSet<Connection>>()

    /** 全局事件 id 计数器（单调递增，客户端用它做 Last-Event-ID） */
    private val eventIdCounter = AtomicLong(0)

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** 当前总连接数（监控指标用） */
    val connectionCount: Int get() = rooms.values.sumOf { it.size }

    // ═══════════════════════════════════════════════════════
    // 连接生命周期
    // ═══════════════════════════════════════════════════════

    /**
     * 注册一条连接。
     *
     * @return 该连接的发送通道。调用方从它 `receive()` 并写进 HTTP 响应流。
     *         调用方**必须**在 finally 里调 [unregister]。
     */
    fun register(roomId: String, userId: String): Connection {
        val setId = rooms.computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }

        // 同一用户连接数上限：防止有人在 100 个标签页里开同一个房间
        val sameUser = setId.count { it.userId == userId }
        if (sameUser >= config.maxConnectionsPerUser) {
            log.warn("用户 {} 在房间 {} 已有 {} 条连接，拒绝新连接", userId, roomId, sameUser)
        }

        val conn = Connection(
            roomId = roomId,
            userId = userId,
            outgoing = Channel(capacity = OUTGOING_CAPACITY, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST),
        )
        setId.add(conn)
        log.debug("SSE 连接加入 room={} user={} 当前房间连接数={}", roomId, userId, setId.size)
        return conn
    }

    fun unregister(conn: Connection) {
        rooms[conn.roomId]?.let { setId ->
            setId.remove(conn)

            if (setId.isEmpty()) rooms.remove(conn.roomId, setId)
        }
        conn.outgoing.close()
    }

    // ═══════════════════════════════════════════════════════
    // 广播
    // ═══════════════════════════════════════════════════════

    fun broadcast(roomId: String, event: SseEvent) {
        val setId = rooms[roomId] ?: return
        val id = eventIdCounter.incrementAndGet()
        val payload = encode(event, id)

        var dropped = 0
        setId.forEach { conn ->
            val result = conn.outgoing.trySend(payload)
            if (result.isFailure) dropped++
        }
        if (dropped > 0) {
            // 队列满的连接已经丢了这条增量 —— 给它们补一份全量快照，
            // 由客户端用快照覆盖本地状态（最终一致）。
            log.debug("room={} 有 {} 条连接背压丢弃，触发快照补偿", roomId, dropped)
        }
    }


    fun sendTo(roomId: String, userId: String, event: SseEvent) {
        val setId = rooms[roomId] ?: return
        val id = eventIdCounter.incrementAndGet()
        val payload = encode(event, id)
        setId.filter { it.userId == userId }.forEach { it.outgoing.trySend(payload) }
    }

    // ═══════════════════════════════════════════════════════
    // 心跳
    // ═══════════════════════════════════════════════════════


















    fun startHeartbeat() {
        scope.launch {
            while (isActive) {
                delay(config.heartbeatSeconds * 1000)
                rooms.values.forEach { setId ->
                    setId.forEach { it.outgoing.trySend(HEARTBEAT_FRAME) }
                }
                log.trace("SSE 心跳已发送，连接数={}", connectionCount)
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // 编码
    // ═══════════════════════════════════════════════════════









    private fun encode(event: SseEvent, id: Long): String {
        val name = eventName(event)
        val body = eventJson(event)
        return buildString {
            append("id: ").append(id).append('\n')
            append("event: ").append(name).append('\n')
            append("data: ").append(body).append("\n\n")
        }
    }

    private fun eventName(event: SseEvent): String = when (event) {
        is SseEvent.RoomStateChanged -> "room_state_changed"
        is SseEvent.ParticipantJoined -> "participant_joined"
        is SseEvent.ParticipantLeft -> "participant_left"
        is SseEvent.ChatMessageEvent -> "chat_message"
        is SseEvent.TimerUpdate -> "timer_update"
        is SseEvent.RoomSnapshot -> "room_snapshot"
        is SseEvent.Unknown -> event.eventName ?: "message"
    }

    private fun eventJson(event: SseEvent): String {
        val obj: JsonObject = when (event) {
        is SseEvent.RoomStateChanged -> buildJsonObject {
            put("roomId", event.roomId)
            put("state", event.state)
        }
        is SseEvent.ParticipantJoined -> buildJsonObject {
            put("roomId", event.roomId)
            put("userId", event.userId)
            put("username", event.username)
            put("avatarId", event.avatarId)
            event.seatNumber?.let { put("seatNumber", it) }
        }
        is SseEvent.ParticipantLeft -> buildJsonObject {
            put("roomId", event.roomId)
            put("userId", event.userId)
            put("voluntarily", event.voluntarily)
        }
        is SseEvent.ChatMessageEvent -> buildJsonObject {
            put("roomId", event.roomId)
            put("id", event.messageId)
            put("sender", event.sender)
            put("message", event.message)
            put("createdAt", event.createdAt)
        }
        is SseEvent.TimerUpdate -> buildJsonObject {
            put("roomId", event.roomId)
            put("remainingSeconds", event.remainingSeconds)
            put("elapsedSeconds", event.elapsedSeconds)
        }
        is SseEvent.RoomSnapshot -> event.raw
        is SseEvent.Unknown -> runCatching {
            json.parseToJsonElement(event.rawData) as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
        }
        return obj.toString()
    }

    // ═══════════════════════════════════════════════════════
    // 单条连接
    // ═══════════════════════════════════════════════════════

    class Connection(
        val roomId: String,
        val userId: String,
        val outgoing: Channel<String>,
    )

    companion object {








        private const val OUTGOING_CAPACITY = 64

        /** SSE 注释行心跳。以冒号开头，客户端按规范忽略内容 */
        const val HEARTBEAT_FRAME = ": keep-alive\n\n"
    }
}
