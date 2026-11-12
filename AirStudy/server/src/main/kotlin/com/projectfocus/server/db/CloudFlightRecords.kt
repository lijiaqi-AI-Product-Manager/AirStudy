package com.projectfocus.server.db

import org.jetbrains.exposed.sql.Table

/** Terminal snapshots and deletion markers use account, mode and record identity together. */
object CloudFlightRecords : Table("cloud_flight_records") {
    val userId = varchar("user_id", 36).references(Tables.Users.id)
    val mode = varchar("mode", 4)
    val recordId = varchar("record_id", 128)
    val revision = long("revision")
    val deleted = bool("deleted")
    val archiveJson = text("archive_json").nullable()
    val summaryJson = text("summary_json").nullable()
    val sceneJson = text("scene_json").nullable()
    override val primaryKey = PrimaryKey(userId, mode, recordId)
    init { index(true, userId, revision) }
}

/** Retained after deletion so a lost HTTP response can be retried without repeating a change. */
object FlightRecordOperations : Table("flight_record_operations") {
    val userId = varchar("user_id", 36).references(Tables.Users.id)
    val operationId = varchar("operation_id", 128)
    val requestHash = varchar("request_hash", 64)
    val revision = long("revision")
    override val primaryKey = PrimaryKey(userId, operationId)
}
