package com.projectfocus.server.db

import org.jetbrains.exposed.sql.Table

/** Own one-use email challenge; plaintext codes and passwords are never stored here. */
object RegistrationChallenges : Table("registration_challenges") {
    val email = varchar("email", 254)
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
