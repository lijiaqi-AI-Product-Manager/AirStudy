package com.projectfocus.server.domain

import at.favre.lib.crypto.bcrypt.BCrypt
import com.projectfocus.server.db.PasswordResetChallenges
import com.projectfocus.server.db.Tables.RefreshTokens
import com.projectfocus.server.db.Tables.Users
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Own policy: reset existing accounts only;30s resend,10min expiry,5 guesses.
 * User row→challenge row→refresh row is the shared mutation lock order. Password,
 * one-use consumption and refresh deletion commit atomically. Already-issued
 * access JWTs remain valid to their existing expiry; no full JWT revocation is
 * claimed. Original server storage, OTP and session policies remain unknown.
 */
class PasswordResetService(
    private val database: Database,
    private val delivery: RegistrationEmailDelivery,
    secret: String,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val digests = EmailChallengeDigests(secret)

    fun sendCode(rawEmail: String) {
        val email = AuthService.normalizeEmail(rawEmail)
        AuthService.validateEmail(email)
        val now = clock.millis()
        val expires = Math.addExact(now, VALID_MILLIS)
        val generation = UUID.randomUUID().toString()
        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        val owner = transaction(database) {
            val user = Users.selectAll().where { Users.email eq email }.limit(1).forUpdate().firstOrNull()
                ?: throw UserNotFoundException()
            val userId = user[Users.id]
            val digest = digest(email, userId, generation, code)
            val inserted = PasswordResetChallenges.insertIgnore {
                it[PasswordResetChallenges.email] = email
                it[PasswordResetChallenges.userId] = userId
                it[PasswordResetChallenges.generation] = generation
                it[PasswordResetChallenges.digest] = digest
                it[issuedAt] = now; it[expiresAt] = expires; it[lastSentAt] = now
                it[attempts] = 0; it[ready] = false; it[consumed] = false
            }.insertedCount
            if (inserted == 0) {
                val updated = PasswordResetChallenges.update({
                    (PasswordResetChallenges.email eq email) and
                        (PasswordResetChallenges.userId eq userId) and
                        (PasswordResetChallenges.lastSentAt lessEq now - RESEND_MILLIS)
                }) {
                    it[PasswordResetChallenges.generation] = generation
                    it[PasswordResetChallenges.digest] = digest
                    it[issuedAt] = now; it[expiresAt] = expires; it[lastSentAt] = now
                    it[attempts] = 0; it[ready] = false; it[consumed] = false
                }
                if (updated != 1) throw DomainException(1016, "操作太频繁，请稍后再试", HttpStatusCode.TooManyRequests)
            }
            userId
        }
        // No external call under a SQL lock, and no delivery at server startup.
        val accepted = try {
            delivery.deliver(RegistrationEmail(email, code, VALID_MILLIS / 1000, EmailChallengePurpose.PASSWORD_RESET))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        } catch (_: Exception) { false }
        val enabled = if (accepted) transaction(database) {
            PasswordResetChallenges.update({
                (PasswordResetChallenges.email eq email) and (PasswordResetChallenges.userId eq owner) and
                    (PasswordResetChallenges.generation eq generation) and (PasswordResetChallenges.consumed eq false)
            }) { it[ready] = clock.millis() < expires }
        } else 0
        if (!accepted || enabled != 1 || clock.millis() >= expires) {
            throw DomainException(5033, "验证码暂时无法发送，请稍后再试", HttpStatusCode.ServiceUnavailable)
        }
    }

    fun reset(rawEmail: String, rawCode: String, newPassword: String) {
        val email = AuthService.normalizeEmail(rawEmail)
        AuthService.validateEmail(email)
        AuthService.validatePassword(newPassword) // Own bcrypt byte bound; password is not trimmed.
        val code = rawCode.trim { it <= '\u0020' }
        val accepted = transaction(database) {
            val user = Users.selectAll().where { Users.email eq email }.limit(1).forUpdate().firstOrNull()
                ?: throw UserNotFoundException()
            val owner = user[Users.id]
            val row = PasswordResetChallenges.selectAll().where {
                (PasswordResetChallenges.email eq email) and (PasswordResetChallenges.userId eq owner)
            }.forUpdate().singleOrNull() ?: return@transaction false
            val generation = row[PasswordResetChallenges.generation]
            val now = clock.millis()
            if (!row[PasswordResetChallenges.ready] || row[PasswordResetChallenges.consumed] ||
                row[PasswordResetChallenges.expiresAt] <= now || row[PasswordResetChallenges.attempts] >= MAX_ATTEMPTS) return@transaction false
            val matches = MessageDigest.isEqual(row[PasswordResetChallenges.digest].toByteArray(UTF_8),
                digest(email, owner, generation, code).toByteArray(UTF_8)) && code.matches(Regex("[0-9]{6}"))
            if (!matches) {
                val attempts = row[PasswordResetChallenges.attempts] + 1
                PasswordResetChallenges.update({
                    (PasswordResetChallenges.email eq email) and (PasswordResetChallenges.userId eq owner) and
                        (PasswordResetChallenges.generation eq generation)
                }) {
                    it[PasswordResetChallenges.attempts] = attempts
                    if (attempts >= MAX_ATTEMPTS) it[ready] = false
                }
                // Throwing inside this transaction would lose the failed-guess counter.
                return@transaction false
            }
            val changed = Users.update({ Users.id eq owner }) {
                it[passwordHash] = BCrypt.withDefaults().hashToString(AuthService.BCRYPT_COST, newPassword.toCharArray())
                it[updatedAt] = Instant.ofEpochMilli(now)
            }
            check(changed == 1) { "Reset account changed" }
            PasswordResetChallenges.update({
                (PasswordResetChallenges.email eq email) and (PasswordResetChallenges.userId eq owner) and
                    (PasswordResetChallenges.generation eq generation)
            }) { it[consumed] = true; it[ready] = false }
            RefreshTokens.deleteWhere { RefreshTokens.userId eq owner }
            true
        }
        if (!accepted) throw DomainException(1015, "验证码无效或已过期", HttpStatusCode.BadRequest)
    }

    private fun digest(email: String, owner: String, generation: String, code: String) =
        digests.digest(EmailChallengePurpose.PASSWORD_RESET, "$email\u0000$owner", generation, code)

    companion object {
        const val RESEND_MILLIS = 30_000L
        const val VALID_MILLIS = 600_000L
        const val MAX_ATTEMPTS = 5
    }
}
