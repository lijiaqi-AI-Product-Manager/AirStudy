package com.projectfocus.server.domain

import com.projectfocus.core.network.dto.*
import com.projectfocus.server.db.CloudFlightRecords as Records
import com.projectfocus.server.db.Tables.Flights
import com.projectfocus.server.db.Tables.Users
import java.time.Instant
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.transactions.transaction

class FlightRecordSummaryService(private val database: Database) {
    fun page(user: String, request: FlightRecordSummaryQuery): FlightRecordSummaryPage {
        request.validated()
        return transaction(database) {
            Users.selectAll().where { Users.id eq user }.forUpdate().singleOrNull()
                ?: throw FlightRecordFailure(io.ktor.http.HttpStatusCode.Unauthorized, "请重新登录")
            val legacy = Flights.selectAll().where { Flights.userId eq user }.associateBy { it[Flights.id] }
            val archived = Records.archiveJson.isNotNull()
            val rows = Records.select(Records.mode, Records.recordId, Records.revision, Records.deleted,
                Records.sceneJson, Records.summaryJson, archived).where { Records.userId eq user }.toList()
            val represented = rows.filter { it[Records.mode] == "down" }.map { it[Records.recordId] }.toSet()
            val summaries = mutableListOf<JsonObject>()
            for (row in rows) {
                if (row[Records.deleted]) continue
                val mode = FlightRecordMode.entries.single { it.path == row[Records.mode] }
                val id = row[Records.recordId]
                val base = when {
                    row[archived] -> row[Records.summaryJson]?.let { recordJson.parseToJsonElement(it) as JsonObject }
                        ?: run {
                            val payload = Records.select(Records.archiveJson).where {
                                (Records.userId eq user) and (Records.mode eq mode.path) and (Records.recordId eq id)
                            }.single()[Records.archiveJson]!!
                            val summary = recordJson.decodeFromString(FlightRecordArchiveSnapshot.serializer(), payload).summaryJson()
                            Records.update({ (Records.userId eq user) and (Records.mode eq mode.path) and (Records.recordId eq id) }) {
                                it[summaryJson] = summary.toString()
                            }
                            summary
                        }
                    mode == FlightRecordMode.DOWN && legacy[id] != null -> legacy.getValue(id).summaryJson()
                    else -> buildJsonObject { put("flightRecordId", id); put("recordType", mode.path) }
                }
                val scene = row[Records.sceneJson]?.let { recordJson.decodeFromString(FlightRecordScenePatch.serializer(), it) }
                summaries += base.withSummaryScene(scene, row[Records.revision])
            }
            legacy.values.filter { it[Flights.id] !in represented }.forEach { summaries += it.summaryJson().withSummaryScene(null, 0) }
            val matching = summaries.map { it to FlightRecordSummary.parse(it) }.filter { (_, record) ->
                (request.timerType == "all" || request.timerType == record.mode.path) && when (request.status) {
                    "all" -> true
                    "arrived" -> record.status == SummaryFlightStatus.ARRIVED
                    "cancelled" -> record.status == SummaryFlightStatus.CANCELLED
                    else -> record.status in setOf(SummaryFlightStatus.IN_PROGRESS, SummaryFlightStatus.LANDING)
                }
            }.sortedWith(compareByDescending<Pair<JsonObject, FlightRecordSummary>> { it.second.startedAtWallMs ?: Long.MIN_VALUE }
                .thenBy { it.second.mode.ordinal }.thenBy { it.second.id })
            val count = matching.size
            val pages = ((count.toLong() + request.limit - 1) / request.limit).toInt()
            val offset = ((request.page.toLong() - 1L) * request.limit).coerceAtMost(count.toLong()).toInt()
            FlightRecordSummaryPage(matching.drop(offset).take(request.limit).map { it.first },
                FlightRecordSummaryPagination(pages, count, request.page, request.limit))
        }
    }

    private fun ResultRow.summaryJson() = buildJsonObject {
        put("flightRecordId", this@summaryJson[Flights.id]); put("recordType", "down")
        put("flightStatus", when (this@summaryJson[Flights.status]) {
            "COMPLETED" -> "arrived"
            "CANCELLED", "ABANDONED" -> "cancelled"
            "ONGOING" -> "in_progress"
            else -> this@summaryJson[Flights.status].lowercase(java.util.Locale.ROOT)
        })
        put("departureAirport", buildJsonObject { put("code", this@summaryJson[Flights.departureCode]) })
        put("arrivalAirport", buildJsonObject { put("code", this@summaryJson[Flights.destinationCode]) })
        put("flightStartTime", Instant.ofEpochMilli(this@summaryJson[Flights.startTimeMillis]).toString())
        this@summaryJson[Flights.endTimeMillis]?.let { put("flightEndTime", Instant.ofEpochMilli(it).toString()) }
        put("durationSeconds", this@summaryJson[Flights.actualFocusMinutes].toDouble() * 60.0)
        put("flightDistanceKm", this@summaryJson[Flights.distanceKm])
        this@summaryJson[Flights.seat]?.let { put("seatNumber", it) }
    }
}
