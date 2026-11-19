package com.projectfocus.core.network.sse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull


sealed interface SseEvent {

    /** 服务端给的 id（对应 SSE 的 `id:` 行），重连时用 `Last-Event-ID` 回传 */
    val eventId: String?

    /** 房间整体状态变了（有人进出、房主改了时间、开始/结束） */
    data class RoomStateChanged(
        val roomId: String,
        /** WAITING | SCHEDULED | STARTED | COMPLETED | CANCELLED */
        val state: String,
        override val eventId: String? = null,
    ) : SseEvent

    data class ParticipantJoined(
        val roomId: String,
        val userId: String,
        val username: String,
        val avatarId: Int = 0,
        val seatNumber: Int? = null,
        override val eventId: String? = null,
    ) : SseEvent

    data class ParticipantLeft(
        val roomId: String,
        val userId: String,
        /** true = 主动退出；false = 超时/被移出（UI 文案不同） */
        val voluntarily: Boolean = true,
        override val eventId: String? = null,
    ) : SseEvent

    data class ChatMessageEvent(
        val roomId: String,
        val messageId: String,
        val sender: String,
        val message: String,
        val createdAt: Long,
        override val eventId: String? = null,
    ) : SseEvent









    data class TimerUpdate(
        val roomId: String,
        val remainingSeconds: Long,
        val elapsedSeconds: Long,
        override val eventId: String? = null,
    ) : SseEvent

    /** 初次连上、重连成功后服务端推的完整快照 —— 用来纠正错过的增量 */
    data class RoomSnapshot(
        val raw: JsonObject,
        override val eventId: String? = null,
    ) : SseEvent










    data class Unknown(
        val eventName: String?,
        val rawData: String,
        override val eventId: String? = null,
    ) : SseEvent
}


enum class SseConnectionState {
    /** 初始 / 已主动关闭 */
    Idle,

    /** 正在建立连接 */
    Connecting,

    /** 已连上，正在收事件 */
    Connected,

    /** 断线，正在退避等待重连 */
    Reconnecting,
}


class SseFrameParser(
    private val json: Json = DefaultJson,
) {

    // 当前正在累积的帧
    private val dataLines = StringBuilder()
    private var eventName: String? = null
    private var eventId: String? = null
    private var hasAnyField = false

    /** 服务端下发的重连间隔（毫秒），收到 `retry:` 行时更新 */
    var serverRetryMillis: Long? = null
        private set

    /**
     * 喂一行（**不含行尾换行符**）。
     *
     * @return 若这一行触发了帧结束，返回解析出的事件；否则 null
     */
    fun feed(line: String): SseEvent? {
        // ── 空行 = 帧结束 ──────────────────────────────────
        if (line.isEmpty()) {
            if (!hasAnyField) return null // 连续空行：忽略
            val event = buildEvent()
            reset()
            return event
        }

        // ── 注释行：冒号开头 ───────────────────────────────

        if (line.startsWith(":")) {
            return SseEvent.Unknown(eventName = null, rawData = line.removePrefix(":").trim(), eventId = null)
        }

        // ── 字段行：`field: value` 或 `field:value` ──────────
        //   注意规范允许**冒号后没有空格**，也可能**只有字段名没有冒号**。
        val colon = line.indexOf(':')
        val field: String
        val value: String
        if (colon < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colon)
            // 规范：冒号后若有一个空格，去掉它（且只去掉一个）
            value = line.substring(colon + 1).removePrefix(" ")
        }

        hasAnyField = true
        when (field) {
            "event" -> eventName = value
            "id" -> {
                // 规范：id 里不能含 NUL。含 NUL 时整行忽略。
                if ('\u0000' !in value) eventId = value
            }
            "data" -> {
                if (dataLines.isNotEmpty()) dataLines.append('\n')
                dataLines.append(value)
            }
            "retry" -> {
                // 规范：只接受纯数字。`retry: 3000ms` 是非法的，要忽略。
                value.toLongOrNull()?.takeIf { it >= 0 }?.let { serverRetryMillis = it }
            }
            else -> {
                // 未知字段：规范要求忽略
            }
        }
        return null
    }








    fun flush(): SseEvent? {
        if (!hasAnyField) return null
        val event = buildEvent()
        reset()
        return event
    }

    private fun reset() {
        dataLines.setLength(0)
        eventName = null
        eventId = null
        hasAnyField = false
    }

    private fun buildEvent(): SseEvent {
        val name = eventName
        val data = dataLines.toString()
        val id = eventId

        // 只有 id 没有 data 的帧（服务端只想推进 lastEventId）——
        // 规范说这种帧不触发 message 事件，但它对重连有意义。
        if (data.isEmpty()) {
            return SseEvent.Unknown(eventName = name, rawData = "", eventId = id)
        }

        val obj = parseObjectOrNull(data)
        return when (name) {
            "room_state_changed" -> SseEvent.RoomStateChanged(
                roomId = obj.str("roomId").orEmpty(),
                state = obj.str("state") ?: "WAITING",
                eventId = id,
            )
            "participant_joined" -> SseEvent.ParticipantJoined(
                roomId = obj.str("roomId").orEmpty(),
                userId = obj.str("userId").orEmpty(),
                username = obj.str("username").orEmpty(),
                avatarId = obj.int("avatarId") ?: 0,
                seatNumber = obj.int("seatNumber"),
                eventId = id,
            )
            "participant_left" -> SseEvent.ParticipantLeft(
                roomId = obj.str("roomId").orEmpty(),
                userId = obj.str("userId").orEmpty(),

                //   反过来（默认 false 显示"掉线"）会让正常退出的人显示成异常。
                voluntarily = obj.bool("voluntarily") ?: true,
                eventId = id,
            )
            "chat_message" -> SseEvent.ChatMessageEvent(
                roomId = obj.str("roomId").orEmpty(),
                messageId = obj.str("id") ?: obj.str("messageId").orEmpty(),
                sender = obj.str("sender").orEmpty(),
                message = obj.str("message").orEmpty(),
                createdAt = obj.long("createdAt") ?: 0L,
                eventId = id,
            )
            "timer_update" -> SseEvent.TimerUpdate(
                roomId = obj.str("roomId").orEmpty(),
                remainingSeconds = obj.long("remainingSeconds") ?: 0L,
                elapsedSeconds = obj.long("elapsedSeconds") ?: 0L,
                eventId = id,
            )
            "room_snapshot" -> SseEvent.RoomSnapshot(raw = obj, eventId = id)
            // event 名缺失 或 不认识的名字 —— 原样交出，由上层记日志
            else -> SseEvent.Unknown(eventName = name, rawData = data, eventId = id)
        }
    }

    private fun parseObjectOrNull(data: String): JsonObject =
        try {
            json.parseToJsonElement(data) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) {

            //   返回空对象 → 各字段取默认值 → 上层看到"空事件"。

            JsonObject(emptyMap())
        }

    // ── JsonObject 取值小工具（全部容错，取不到返回 null）────────
    private fun JsonObject.prim(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)

    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull
    private fun JsonObject.int(key: String): Int? = prim(key)?.longOrNull?.toInt()
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull

    companion object {


        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            explicitNulls = false
            encodeDefaults = true
        }
    }
}

/** 把 handler 里可能抛的异常收敛成"这一帧坏了"，不影响后续帧 */
inline fun <T> SseFrameParser.safeHandle(raw: String, block: (JsonElement) -> T): T? =
    try {
        @Suppress("UNCHECKED_CAST")
        block(Json.parseToJsonElement(raw))
    } catch (_: Exception) {
        null
    }
