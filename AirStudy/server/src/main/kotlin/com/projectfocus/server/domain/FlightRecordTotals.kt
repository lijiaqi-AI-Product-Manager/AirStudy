package com.projectfocus.server.domain

import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.core.network.dto.FlightRecordArchiveSnapshot
import com.projectfocus.core.network.dto.FlightRecordMode
import com.projectfocus.server.db.CloudFlightRecords as Records
import com.projectfocus.server.db.Tables.Airports
import com.projectfocus.server.db.Tables.Flights
import com.projectfocus.server.db.Tables.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import java.time.Instant

/** Called inside the record transaction while its account row is locked. */
internal object FlightRecordTotals {
    fun recompute(user: String) {
        val cloud = Records.selectAll().where { Records.userId eq user }.toList()
        val replaced = cloud.filter { it[Records.mode] == FlightRecordMode.DOWN.path &&
            (it[Records.deleted] || it[Records.archiveJson] != null) }.map { it[Records.recordId] }.toSet()
        val legacy = Flights.selectAll().where { Flights.userId eq user }.filter {
            it[Flights.id] !in replaced && it[Flights.status] == "COMPLETED"
        }
        var minutes = legacy.sumOf { it[Flights.actualFocusMinutes].toLong() }
        var groups = legacy.count { it[Flights.focusType] == "GROUP" }
        val codes = legacy.map { it[Flights.destinationCode] }.distinct()
        val countries = Airports.selectAll().where { Airports.code inList codes }.map { it[Airports.country] }.toMutableSet()
        for (row in cloud) {
            if (row[Records.deleted]) continue
            val snapshot = row[Records.archiveJson]?.let {
                recordJson.decodeFromString(FlightRecordArchiveSnapshot.serializer(), it)
            } ?: continue
            snapshot.countdown?.takeIf { it.status == "COMPLETED" }?.let {
                minutes += it.actualFocusMinutes.toLong()
                if (it.focusType in setOf("GROUP", "ROOM")) groups++
                countries += it.destination.country
            }
            snapshot.countUp?.takeIf { it.status == FreeFlightStatus.COMPLETED }?.let {
                minutes += Math.round(it.clock.activeElapsedMs / 60_000.0).coerceAtMost(Int.MAX_VALUE.toLong())
                it.landingAirport?.country?.let(countries::add)
            }
        }
        Users.update({ Users.id eq user }) {
            it[totalFocusedMinutes] = minutes.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            it[totalGroupFlight] = groups
            it[countriesVisited] = countries.count { country -> country.isNotBlank() && !country.equals("Unknown", true) }
            it[updatedAt] = Instant.now()
        }
    }
}
