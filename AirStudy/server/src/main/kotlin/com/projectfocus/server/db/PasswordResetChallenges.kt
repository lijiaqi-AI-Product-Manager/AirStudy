package com.projectfocus.server.db

import org.jetbrains.exposed.sql.Table

/** Own reset-only generation; no plaintext OTP/password and no registration-row reuse. */
object PasswordResetChallenges : Table("password_reset_challenges") {
    val email = varchar("email", 254)
    val userId = varchar("user_id", 36).references(Tables.Users.id)
    val generation = varchar("generation", 36)
    val digest = varchar("digest", 64)
    val issuedAt = long("issued_at")
    val expiresAt = long("expires_at")
    val lastSentAt = long("last_sent_at")
    val attempts = integer("attempts").default(0)
    val ready = bool("ready").default(false)
    val consumed = bool("consumed").default(false)
    override val primaryKey = PrimaryKey(email)
}
