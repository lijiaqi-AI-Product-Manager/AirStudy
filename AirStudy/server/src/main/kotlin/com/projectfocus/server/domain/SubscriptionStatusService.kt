package com.projectfocus.server.domain

import com.projectfocus.core.network.dto.SubscriptionStatusSnapshot
import com.projectfocus.server.db.SubscriptionEntitlements
import com.projectfocus.server.db.Tables.Users
import java.time.Clock
import java.time.Duration
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Read-only own policy; the original service's grant/calendar/rounding rules are unknown. */
class SubscriptionStatusService(private val database: Database, private val clock: Clock = Clock.systemUTC()) {
    fun status(ownerId: String): SubscriptionStatusSnapshot = transaction(database) {
        if (Users.selectAll().where { Users.id eq ownerId }.limit(1).firstOrNull() == null) throw UnauthorizedException()
        val row = SubscriptionEntitlements.selectAll().where { SubscriptionEntitlements.ownerId eq ownerId }
            .limit(1).firstOrNull() ?: return@transaction SubscriptionStatusSnapshot(false, false, 0, null)
        val starts = row[SubscriptionEntitlements.startsAt]
        val expires = row[SubscriptionEntitlements.expiresAt]
        val now = clock.instant()
        val active = !row[SubscriptionEntitlements.revoked] && starts < expires && now >= starts && now < expires
        val days = if (active) {
            // Fixed-duration ceil, not local calendar dates: any positive fraction
            // of a 24h day counts as one. Exact expiry is inactive above.
            val remaining = Duration.between(now, expires)
            val whole = remaining.seconds / DAY_SECONDS
            Math.toIntExact(whole + if (remaining.seconds % DAY_SECONDS != 0L || remaining.nano != 0) 1L else 0L)
        } else 0
        SubscriptionStatusSnapshot(active, active && row[SubscriptionEntitlements.isPremium], days, expires.toString())
    }

    companion object { private const val DAY_SECONDS = 86_400L }
}
