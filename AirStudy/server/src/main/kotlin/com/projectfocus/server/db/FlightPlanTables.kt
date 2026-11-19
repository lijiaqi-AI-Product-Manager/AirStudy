package com.projectfocus.server.db

import java.time.Instant
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/** This project's storage adapter; no claim about the original service schema. */
object FlightPlanDays : Table("flight_plan_days") {
    val ownerId = varchar("owner_id", 36).references(Tables.Users.id)
    val localDate = date("local_date")
    val timezone = varchar("timezone", 255)
    // UTC-facing plan_date and selected local_date are independent protocol fields.
    val planDate = date("plan_date")
    val timezoneOffsetMinutes = integer("timezone_offset_minutes")
    // Keep the full list, including cancelled/terminal and future unknown fields.
    val legsJson = text("legs_json")
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(ownerId, localDate, timezone)
}
