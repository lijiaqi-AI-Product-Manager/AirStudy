package com.projectfocus.server.domain

import com.projectfocus.core.network.dto.BulkSyncRequest
import com.projectfocus.core.network.dto.FlightHistoryDto
import com.projectfocus.core.network.dto.SessionDetails
import com.projectfocus.core.network.dto.SessionHistoryDto
import com.projectfocus.core.network.dto.StartSessionRequest
import com.projectfocus.core.network.dto.SyncResultDto
import com.projectfocus.server.db.Tables.Airports
import com.projectfocus.server.db.Tables.Flights
import com.projectfocus.server.db.Tables.Users
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID























class FlightService {














    fun sync(userId: String, req: BulkSyncRequest): SyncResultDto {
        if (req.sessions.size > MAX_BATCH) {
            throw DomainException(3010, "单次最多同步 $MAX_BATCH 条记录", io.ktor.http.HttpStatusCode.BadRequest)
        }

        return transaction {
            lockUserTotals(userId)
            var accepted = 0
            var rejected = 0
            val serverIds = mutableListOf<String>()

            for (dto in req.sessions) {
                val sanitized = sanitize(dto) ?: run { rejected++; null } ?: continue
                insertFlight(userId, sanitized)
                val storedOwner = Flights.selectAll().where { Flights.id eq sanitized.id }.singleOrNull()?.get(Flights.userId)
                if (storedOwner != userId) {
                    rejected++
                    continue
                }
                accepted++
                serverIds += sanitized.id
            }

            // 用户统计：一次同步只更新一次，不是每条一次
            if (accepted > 0) recomputeUserTotals(userId)

            SyncResultDto(accepted = accepted, rejected = rejected, serverIds = serverIds)
        }
    }


    fun start(userId: String, req: StartSessionRequest, now: Long = System.currentTimeMillis()): SessionDetails {
        if (req.durationMinutes !in FlightMath.MIN_DURATION_MINUTES..FlightMath.MAX_DURATION_MINUTES) {
            throw DomainException(3003, "时长不合法", io.ktor.http.HttpStatusCode.BadRequest)
        }
        val distance = FlightMath.distanceKm(req.durationMinutes)
        val id = UUID.randomUUID().toString()

        transaction {
            check(insertFlight(
                userId,
                SanitizedFlight(
                    id = id,
                    roomId = null,
                    focusType = "SOLO",
                    departureCode = req.departureAirportCode.uppercase(),
                    destinationCode = req.destinationAirportCode.uppercase(),
                    startTimeMillis = now,
                    endTimeMillis = null,
                    durationMinutes = req.durationMinutes,
                    actualFocusMinutes = 0,
                    distanceKm = distance,
                    status = "IN_PROGRESS",
                    seat = null,
                    quote = null,
                    quoteAuthor = null,
                ),
            )) { "A new flight identifier was not inserted" }
        }
        return SessionDetails(id = id, startTimeMillis = now, durationMinutes = req.durationMinutes, distanceKm = distance)
    }


    fun history(userId: String, page: Int, size: Int = 20): SessionHistoryDto = transaction {
        val p = page.coerceAtLeast(0)
        val s = size.coerceIn(1, 100)
        val total = Flights.selectAll().where { Flights.userId eq userId }.count().toInt()
        val rows = Flights.selectAll()
            .where { Flights.userId eq userId }
            .orderBy(Flights.startTimeMillis to SortOrder.DESC)
            .limit(s).offset((p.toLong() * s))
            .map { it.toDto() }

        SessionHistoryDto(
            sessions = rows,
            total = total,
            page = p,
            hasMore = (p + 1L) * s < total,
        )
    }









    fun delete(userId: String, flightId: String) {
        transaction {
            lockUserTotals(userId)
            val deleted = Flights.deleteWhere { (Flights.id eq flightId) and (Flights.userId eq userId) }
            if (deleted == 0) throw FlightNotFoundException()
            recomputeUserTotals(userId)
        }
    }

    // ═══════════════════════════════════════════════════════

    private data class SanitizedFlight(
        val id: String,
        val roomId: String?,
        val focusType: String,
        val departureCode: String,
        val destinationCode: String,
        val startTimeMillis: Long,
        val endTimeMillis: Long?,
        val durationMinutes: Int,
        val actualFocusMinutes: Int,
        val distanceKm: Double,
        val status: String,
        val seat: String?,
        val quote: String?,
        val quoteAuthor: String?,
    )










    private fun sanitize(dto: FlightHistoryDto, now: Long = System.currentTimeMillis()): SanitizedFlight? {
        if (!isUuid(dto.id)) return null
        if (dto.startTimeMillis <= 0 || dto.startTimeMillis > now + 86_400_000L) return null
        if (dto.durationMinutes !in FlightMath.MIN_DURATION_MINUTES..FlightMath.MAX_DURATION_MINUTES) return null

        val actual = dto.actualFocusMinutes.coerceIn(0, dto.durationMinutes)
        return SanitizedFlight(
            id = dto.id,
            roomId = dto.roomId?.takeIf { isUuid(it) },
            focusType = if (dto.focusType in FOCUS_TYPES) dto.focusType else "SOLO",
            departureCode = dto.departureAirportCode.uppercase().take(4),
            destinationCode = dto.destinationAirportCode.uppercase().take(4),
            startTimeMillis = dto.startTimeMillis,
            endTimeMillis = dto.endTimeMillis,
            durationMinutes = dto.durationMinutes,
            actualFocusMinutes = actual,

            distanceKm = FlightMath.distanceKm(dto.durationMinutes),
            status = if (dto.status in FLIGHT_STATUSES) dto.status else "COMPLETED",
            seat = dto.seat?.take(4),
            quote = dto.quote?.take(256),
            quoteAuthor = dto.quoteAuthor?.take(64),
        )
    }

    private fun insertFlight(userId: String, f: SanitizedFlight): Boolean {
        return Flights.insertIgnore {
            it[id] = f.id
            it[Flights.userId] = userId
            it[roomId] = f.roomId
            it[focusType] = f.focusType
            it[departureCode] = f.departureCode
            it[destinationCode] = f.destinationCode
            it[startTimeMillis] = f.startTimeMillis
            it[endTimeMillis] = f.endTimeMillis
            it[durationMinutes] = f.durationMinutes
            it[actualFocusMinutes] = f.actualFocusMinutes
            it[distanceKm] = f.distanceKm
            it[status] = f.status
            it[seat] = f.seat
            it[quote] = f.quote
            it[quoteAuthor] = f.quoteAuthor
        }.insertedCount == 1
    }










    /** Record writes and their aggregate share the account row lock and transaction. */
    private fun lockUserTotals(userId: String) {
        Users.selectAll().where { Users.id eq userId }.forUpdate().single()
    }

    private fun recomputeUserTotals(userId: String) {
        val rows = Flights.selectAll().where { Flights.userId eq userId }.toList()
        val completed = rows.filter { it[Flights.status] == "COMPLETED" }
        val totalMinutes = completed.sumOf { it[Flights.actualFocusMinutes] }
        val groupFlights = completed.count { it[Flights.focusType] == "GROUP" }
        val codes = completed.map { it[Flights.destinationCode] }.distinct()
        val countries = Airports.selectAll()
            .where { Airports.code inList codes }
            .map { it[Airports.country] }
            .distinct()
            .size

        Users.update({ Users.id eq userId }) {
            it[totalFocusedMinutes] = totalMinutes
            it[totalGroupFlight] = groupFlights
            it[countriesVisited] = countries
            it[updatedAt] = Instant.now()
        }
    }

    private fun isUuid(s: String): Boolean =
        s.length == 36 && runCatching { UUID.fromString(s) }.isSuccess

    companion object {
        const val MAX_BATCH = 100
        private val FOCUS_TYPES = setOf("SOLO", "GROUP")
        private val FLIGHT_STATUSES = setOf("COMPLETED", "CANCELLED", "IN_PROGRESS", "ABANDONED")
    }
}

internal fun ResultRow.toDto(): FlightHistoryDto = FlightHistoryDto(
    id = this[Flights.id],
    roomId = this[Flights.roomId],
    focusType = this[Flights.focusType],
    departureAirportCode = this[Flights.departureCode],
    destinationAirportCode = this[Flights.destinationCode],
    startTimeMillis = this[Flights.startTimeMillis],
    endTimeMillis = this[Flights.endTimeMillis],
    durationMinutes = this[Flights.durationMinutes],
    actualFocusMinutes = this[Flights.actualFocusMinutes],
    distanceKm = this[Flights.distanceKm],
    status = this[Flights.status],
    categoryId = null,
    seat = this[Flights.seat],
    logJson = null,
    createdAt = this[Flights.createdAt].toEpochMilli(),
    updatedAt = this[Flights.updatedAt].toEpochMilli(),
    quote = this[Flights.quote],
    quoteAuthor = this[Flights.quoteAuthor],
)
