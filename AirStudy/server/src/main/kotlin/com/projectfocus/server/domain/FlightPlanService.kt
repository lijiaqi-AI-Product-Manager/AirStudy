package com.projectfocus.server.domain

import com.projectfocus.core.network.dto.FlightPlanActions
import com.projectfocus.core.network.dto.CancelBatchDeleteFlightPlanRequest
import com.projectfocus.core.common.plan.SourceFlightPlanStatus
import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import com.projectfocus.core.network.dto.UpdateFlightPlanLegStatusRequest
import com.projectfocus.server.db.FlightPlanDays
import com.projectfocus.server.db.Tables.Users
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * Own SQL adapter for the observed flat WS protocol, not the original backend.
 * No Flights mutation, entitlement inference, daily leg cap or inferred ETA.
 */
class FlightPlanService(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger(FlightPlanService::class.java)

    fun process(ownerId: String, message: String): JsonObject {
        var requestId = ""
        var action = ""
        return try {
            val root = json.parseToJsonElement(message) as? JsonObject
                ?: throw BadRequestException("消息必须是 JSON 对象")
            requestId = string(root, "request_id").also {
                if (it.isBlank()) throw BadRequestException("request_id 不能为空")
            }
            action = string(root, "action")
            val data = when (action) {
                FlightPlanActions.GET -> {
                    contextKinds(root)
                    val request = json.decodeFromJsonElement(GetFlightPlanRequest.serializer(), root)
                    val context = context(request.planDate, request.localPlanDate, request.timezone,
                        request.timezoneOffsetMinutes)
                    val snapshot = transaction(database) {
                        requireOwner(ownerId)
                        find(ownerId, context.localDate, context.timezone) ?: context.snapshot(JsonArray(emptyList()))
                    }
                    buildJsonObject { put("action", action); put("legs", snapshot.toJson()) }
                }
                FlightPlanActions.UPSERT -> {
                    contextKinds(root)
                    val legs = root["legs"] as? JsonArray ?: throw BadRequestException("legs 必须是完整数组")
                    validateLegs(legs)
                    // The DTO is shared with the project client. Persist validated raw
                    // JSON, so future fields and coordinate spellings are not discarded.
                    val request = json.decodeFromJsonElement(UpsertFlightPlanRequest.serializer(), root)
                    val context = context(request.planDate, request.localPlanDate, request.timezone,
                        request.timezoneOffsetMinutes)
                    val snapshot = transaction(database) {
                        requireOwner(ownerId)
                        val updated = FlightPlanDays.update({ key(ownerId, context.localDate, context.timezone) }) {
                            it[planDate] = context.planDate
                            it[timezoneOffsetMinutes] = context.offsetMinutes
                            it[legsJson] = legs.toString()
                            it[updatedAt] = clock.instant()
                        }
                        if (updated == 0) {
                            FlightPlanDays.insert {
                                it[FlightPlanDays.ownerId] = ownerId
                                it[localDate] = context.localDate
                                it[timezone] = context.timezone
                                it[planDate] = context.planDate
                                it[timezoneOffsetMinutes] = context.offsetMinutes
                                it[legsJson] = legs.toString()
                                it[updatedAt] = clock.instant()
                            }
                        }
                        checkNotNull(find(ownerId, context.localDate, context.timezone))
                    }
                    buildJsonObject { put("action", action); put("legs", snapshot.toJson()) }
                }
                FlightPlanActions.HISTORY -> {
                    string(root, "startDate"); string(root, "endDate"); string(root, "timezone")
                    if (root.containsKey("limit")) integer(root, "limit")
                    val request = json.decodeFromJsonElement(FlightPlanHistoryRequest.serializer(), root)
                    val start = canonicalDate(request.startDate)
                    val end = canonicalDate(request.endDate)
                    val zone = timezone(request.timezone)
                    if (end < start) throw BadRequestException("日期范围无效")
                    if (request.limit !in 1..HISTORY_LIMIT) throw BadRequestException("history limit 必须在 1..200")
                    val snapshots = transaction(database) {
                        requireOwner(ownerId)
                        FlightPlanDays.selectAll().where {
                            (FlightPlanDays.ownerId eq ownerId) and
                                (FlightPlanDays.timezone eq zone) and
                                (FlightPlanDays.localDate greaterEq start) and
                                (FlightPlanDays.localDate lessEq end)
                        }.orderBy(FlightPlanDays.localDate to SortOrder.ASC)
                            .limit(request.limit).map { it.snapshot() }
                    }
                    buildJsonObject {
                        put("action", action)
                        put("timezone", zone)
                        put("plans", JsonArray(snapshots.map { snapshot ->
                            val past = snapshot.localDate < LocalDate.now(clock.withZone(ZoneId.of(snapshot.timezone)))
                            buildJsonObject {
                                snapshot.toJson().forEach { (key, value) -> put(key, value) }
                                // Own presentation policy, not an inferred server ACL.
                                // Canonical past snapshots remain writable for repair/import.
                                put("archived", past)
                                put("readonly", past)
                            }
                        }))
                    }
                }
                FlightPlanActions.LEG_STATUS_UPDATE -> {
                    val snapshot = updateLegStatus(ownerId, root)
                    buildJsonObject { put("action", action); put("legs", snapshot.toJson()) }
                }
                FlightPlanActions.CANCEL_BATCH_DELETE -> {
                    contextKinds(root)
                    string(root, "local_today"); string(root, "local_tomorrow")
                    val request = json.decodeFromJsonElement(CancelBatchDeleteFlightPlanRequest.serializer(), root)
                    val context = context(request.planDate, request.localPlanDate, request.timezone,
                        request.timezoneOffsetMinutes)
                    val today = canonicalDate(request.localToday)
                    val tomorrow = canonicalDate(request.localTomorrow)
                    if (tomorrow != today.plusDays(1)) throw BadRequestException("本地日期范围无效")
                    for (alias in listOf("localPlanDate", "target_local_plan_date", "targetLocalPlanDate")) {
                        matchingAlias(root, alias, request.localPlanDate)
                    }
                    matchingAlias(root, "localToday", request.localToday)
                    matchingAlias(root, "localTomorrow", request.localTomorrow)
                    request.userScopeId?.let {
                        matchingAlias(root, "userScopeId", it)
                    }
                    val (snapshot, deleted) = transaction(database) {
                        requireOwner(ownerId)
                        val previous = find(ownerId, context.localDate, context.timezone, lock = true)
                        if (previous == null) {
                            context.snapshot(JsonArray(emptyList())) to 0
                        } else {
                            // Own backend policy: remove canceled rows only from the exact
                            // JWT owner/local-date/zone key. Client date/scope hints do not
                            // grant access or select another user's day. Original SQL is unknown.
                            val retained = previous.legs.filter { element ->
                                SourceFlightPlanStatus.parse(((element as? JsonObject)?.get("leg_status")
                                    as? JsonPrimitive)?.content) != SourceFlightPlanStatus.CANCELED
                            }.mapIndexed { index, element ->
                                buildJsonObject {
                                    (element as JsonObject).forEach { (key, value) -> put(key, value) }
                                    put("leg_index", index + 1)
                                }
                            }
                            val count = previous.legs.size - retained.size
                            val updated = previous.copy(legs = JsonArray(retained))
                            if (count > 0) FlightPlanDays.update({ key(ownerId, context.localDate, context.timezone) }) {
                                it[legsJson] = updated.legs.toString()
                                it[updatedAt] = clock.instant()
                            }
                            updated to count
                        }
                    }
                    buildJsonObject {
                        put("action", action)
                        put("deletedCount", deleted)
                        put("targetLocalPlanDate", snapshot.localDate.toString())
                        put("legs", snapshot.toJson())
                    }
                }
                else -> throw BadRequestException("未知的 flight-plan action")
            }
            response(requestId, true, "", data)
        } catch (error: LegTerminalConflict) {
            // Source callback clears its pending active state only for this String code.
            failure(requestId, action, 4091, error.message.orEmpty(), "LEG_TERMINAL_CONFLICT")
        } catch (error: DomainException) {
            failure(requestId, action, error.code, error.userMessage)
        } catch (_: SerializationException) {
            failure(requestId, action, 4001, "请求字段格式有误")
        } catch (_: DateTimeException) {
            failure(requestId, action, 4001, "日期或时间格式有误")
        } catch (error: Exception) {
            log.warn("Flight-plan operation failed, action={}, type={}", action, error.javaClass.simpleName)
            failure(requestId, action, 5005, "计划操作失败，请重试")
        }
    }

    /**
     * Project policy, not an inferred original backend: lock one JWT-owner/day/zone
     * row, keep its UTC plan_date and all unknown JSON, and update only this index.
     * No date cap or Flights/session/receipt mutation is inferred from the source.
     */
    private fun updateLegStatus(ownerId: String, root: JsonObject): DaySnapshot {
        string(root, "local_plan_date"); string(root, "timezone"); integer(root, "timezone_offset_minutes")
        integer(root, "leg_index"); string(root, "target_status")
        for (key in listOf("actual_arrival_at", "flight_no", "seat_no")) {
            if (root.containsKey(key) && root[key] != JsonNull) string(root, key)
        }
        if (root.containsKey("arrival_diff_minutes") && root["arrival_diff_minutes"] != JsonNull) {
            integer(root, "arrival_diff_minutes")
        }
        val request = json.decodeFromJsonElement(UpdateFlightPlanLegStatusRequest.serializer(), root)
        val date = canonicalDate(request.localPlanDate)
        val zone = timezone(request.timezone)
        if (request.legIndex <= 0) throw BadRequestException("leg_index 必须为正整数")
        val target = request.targetStatus.trim { it <= '\u0020' }
        val phase = when (target) {
            "waiting_takeoff" -> SourceFlightPlanStatus.WAITING
            "in_progress" -> SourceFlightPlanStatus.IN_PROGRESS
            "completed" -> SourceFlightPlanStatus.COMPLETED
            "canceled" -> SourceFlightPlanStatus.CANCELED
            else -> throw BadRequestException("target_status 无效")
        }
        val actual = request.actualArrivalAt?.trim { it <= '\u0020' }?.takeIf(String::isNotEmpty)
        val diff = request.arrivalDiffMinutes?.takeUnless { it == Int.MIN_VALUE }
        if (phase != SourceFlightPlanStatus.COMPLETED && (actual != null || diff != null)) {
            throw BadRequestException("到达信息仅用于已完成航段")
        }
        if (actual != null) try { Instant.parse(actual) } catch (_: DateTimeException) {
            throw BadRequestException("actual_arrival_at 必须是 ISO 时刻")
        }
        val flight = request.flightNo?.trim { it <= '\u0020' }?.takeIf(String::isNotEmpty)
        val seat = request.seatNo?.trim { it <= '\u0020' }?.takeIf(String::isNotEmpty)
        return transaction(database) {
            requireOwner(ownerId)
            val previous = find(ownerId, date, zone, lock = true)
                ?: throw BadRequestException("所选日期没有此航段，请刷新计划")
            val matching = previous.legs.withIndex().filter { (_, element) ->
                (element as? JsonObject)?.get("leg_index")?.let { value ->
                    (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                } == request.legIndex
            }
            if (matching.size != 1) throw BadRequestException("航段索引不存在或不唯一，请刷新计划")
            val position = matching.single().index
            val leg = matching.single().value as JsonObject
            val current = SourceFlightPlanStatus.parse((leg["leg_status"] as? JsonPrimitive)?.content)
            if (current == SourceFlightPlanStatus.UNKNOWN) throw BadRequestException("航段状态无效，请刷新计划")
            if (current == SourceFlightPlanStatus.COMPLETED || current == SourceFlightPlanStatus.CANCELED) {
                if (current != phase) throw LegTerminalConflict()
                // State idempotence: first terminal metadata wins, even after a lost ack.
                // This is not permanent request_id deduplication or a session fence.
                return@transaction previous
            }
            val replacement = buildJsonObject {
                leg.forEach { (key, value) -> put(key, value) }
                put("leg_status", target)
                actual?.let { put("actual_arrival_at", it) }
                diff?.let { put("arrival_diff_minutes", it) }
                flight?.let { put("flight_no", it) }
                seat?.let { put("seat_no", it) }
            }
            if (replacement == leg) return@transaction previous
            val next = previous.copy(legs = JsonArray(previous.legs.mapIndexed { index, element ->
                if (index == position) replacement else element
            }))
            val changed = FlightPlanDays.update({ key(ownerId, date, zone) }) {
                it[legsJson] = next.legs.toString()
                it[updatedAt] = clock.instant()
            }
            check(changed == 1) { "Locked flight plan disappeared" }
            next
        }
    }

    private class LegTerminalConflict : RuntimeException("航段已经结束，请刷新计划")

    private fun contextKinds(root: JsonObject) {
        string(root, "plan_date"); string(root, "local_plan_date"); string(root, "timezone")
        integer(root, "timezone_offset_minutes")
    }

    private fun context(planDate: String, localDate: String, zone: String, offset: Int) =
        DayContext(canonicalDate(planDate), canonicalDate(localDate), timezone(zone), offset)

    private fun canonicalDate(value: String): LocalDate {
        val date = try { LocalDate.parse(value) } catch (_: DateTimeException) {
            throw BadRequestException("日期必须是 ISO 本地日期")
        }
        if (date.toString() != value) throw BadRequestException("日期必须使用规范 ISO 格式")
        return date
    }

    private fun timezone(value: String): String {
        try { ZoneId.of(value) } catch (_: DateTimeException) { throw BadRequestException("timezone 无效") }
        return value
    }

    private fun validateLegs(legs: JsonArray) {
        legs.forEachIndexed { index, element ->
            val leg = element as? JsonObject ?: throw BadRequestException("每个航段必须是对象")
            if (integer(leg, "leg_index") != index + 1) throw BadRequestException("leg_index 必须从 1 连续排列")
            airport(leg["departure_airport"] as? JsonObject ?: throw BadRequestException("缺少 departure_airport"))
            airport(leg["arrival_airport"] as? JsonObject ?: throw BadRequestException("缺少 arrival_airport"))
            val departure = string(leg, "scheduled_departure_at")
            try { Instant.parse(departure) } catch (_: DateTimeException) {
                throw BadRequestException("scheduled_departure_at 必须是 ISO 时刻")
            }
            if (string(leg, "leg_status").isBlank()) throw BadRequestException("leg_status 不能为空")
            val distance = leg["distance_km"] as? JsonPrimitive
            val km = distance?.takeIf { !it.isString }?.doubleOrNull
            if (km == null || !km.isFinite() || km < 0.0) throw BadRequestException("distance_km 必须是有限非负数")
            string(leg, "flight_no"); string(leg, "seat_no")
            integer(leg, "focus_icon_code"); string(leg, "focus_scene_text")
            for (key in listOf("scheduled_departure_local", "actual_arrival_at", "expected_arrival_at")) {
                if (leg.containsKey(key) && leg[key] != JsonNull) string(leg, key)
            }
            if (leg.containsKey("arrival_diff_minutes") && leg["arrival_diff_minutes"] != JsonNull) {
                integer(leg, "arrival_diff_minutes")
            }
        }
    }

    private fun airport(airport: JsonObject) {
        // Source IDs may be zero/negative; no positivity or IATA-format guess.
        integer(airport, "id"); integer(airport, "country_region_id")
        for (key in listOf("code", "name", "city", "region")) string(airport, key)
        coordinate(string(airport, "latitude"), -90.0, 90.0)
        coordinate(string(airport, "longitude"), -180.0, 180.0)
        for (key in listOf("name_i18n", "city_i18n")) {
            val value = airport[key]
            if (value != null && value != JsonNull && value !is JsonObject) {
                throw BadRequestException("机场 i18n 必须是对象或未设置")
            }
        }
    }

    private fun coordinate(text: String, minimum: Double, maximum: Double) {
        val value = text.toDoubleOrNull()
        if (value == null || !value.isFinite() || value !in minimum..maximum) {
            throw BadRequestException("机场坐标必须是范围内的有限数值字符串")
        }
    }

    private fun string(root: JsonObject, key: String): String =
        (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BadRequestException("$key 必须是字符串")

    private fun integer(root: JsonObject, key: String): Int =
        (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw BadRequestException("$key 必须是整数")

    private fun matchingAlias(root: JsonObject, key: String, expected: String) {
        if (root.containsKey(key) && string(root, key) != expected) throw BadRequestException("$key 与日期范围不一致")
    }

    private fun requireOwner(ownerId: String) {
        if (Users.selectAll().where { Users.id eq ownerId }.limit(1).firstOrNull() == null) throw UnauthorizedException()
    }

    private fun key(ownerId: String, date: LocalDate, zone: String) =
        (FlightPlanDays.ownerId eq ownerId) and (FlightPlanDays.localDate eq date) and (FlightPlanDays.timezone eq zone)

    private fun find(ownerId: String, date: LocalDate, zone: String, lock: Boolean = false): DaySnapshot? {
        val query = FlightPlanDays.selectAll().where { key(ownerId, date, zone) }.limit(1)
        return (if (lock) query.forUpdate() else query).firstOrNull()?.snapshot()
    }

    private fun ResultRow.snapshot() = DaySnapshot(
        this[FlightPlanDays.planDate], this[FlightPlanDays.localDate], this[FlightPlanDays.timezone],
        json.parseToJsonElement(this[FlightPlanDays.legsJson]) as? JsonArray
            ?: error("Stored plan is not an array"),
    )

    private data class DayContext(val planDate: LocalDate, val localDate: LocalDate, val timezone: String, val offsetMinutes: Int) {
        fun snapshot(legs: JsonArray) = DaySnapshot(planDate, localDate, timezone, legs)
    }

    private data class DaySnapshot(val planDate: LocalDate, val localDate: LocalDate, val timezone: String, val legs: JsonArray) {
        fun toJson() = buildJsonObject {
            put("plan_date", planDate.toString())
            put("local_plan_date", localDate.toString())
            put("timezone", timezone)
            put("legs", legs)
        }
    }

    private fun failure(requestId: String, action: String, code: Int, message: String, wireCode: String? = null) =
        response(requestId, false, message, buildJsonObject {
            put("action", action)
            put("error_code", code)
            wireCode?.let { put("code", it) }
        })

    private fun response(requestId: String, success: Boolean, message: String, data: JsonObject) = buildJsonObject {
        put("request_id", requestId)
        put("success", success)
        put("message", message)
        put("data", data)
    }

    companion object {
        const val HISTORY_LIMIT = 200
    }
}
