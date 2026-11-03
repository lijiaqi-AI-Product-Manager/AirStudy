package com.projectfocus.server.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Own finite current-entitlement record. No public grant or Users.isPremium migration. */
object SubscriptionEntitlements : Table("subscription_entitlements") {
    val ownerId = varchar("owner_id", 36).references(Tables.Users.id)
    val startsAt = timestamp("starts_at")
    val expiresAt = timestamp("expires_at")
    val isPremium = bool("is_premium").default(false)
    val revoked = bool("revoked").default(false)
    override val primaryKey = PrimaryKey(ownerId)
}
