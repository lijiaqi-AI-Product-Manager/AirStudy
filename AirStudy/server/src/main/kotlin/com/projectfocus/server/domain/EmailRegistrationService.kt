package com.projectfocus.server.domain

import com.projectfocus.server.db.RegistrationChallenges
import com.projectfocus.server.db.Tables.Users
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Own verified-registration policy:30s resend interval,10min expiry,5 guesses.
 * SQL persists a generation-bound HMAC only. Delivery is outside transactions;
 * only its matching generation can be enabled, and account creation consumes it
 * in the same transaction. These server policies are not original wire evidence.
 */
class EmailRegistrationService(
    private val database: Database,
    private val auth: AuthService,
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
        val generation = UUID.randomUUID().toString()
        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        val digest = digest(email, generation, code)
        transaction(database) {
            val inserted = RegistrationChallenges.insertIgnore {
                it[RegistrationChallenges.email] = email
                it[RegistrationChallenges.generation] = generation
                it[RegistrationChallenges.digest] = digest
                it[issuedAt] = now; it[expiresAt] = now + VALID_MILLIS
                it[lastSentAt] = now; it[attempts] = 0; it[ready] = false; it[consumed] = false
            }.insertedCount
            if (inserted == 0) {
                val updated = RegistrationChallenges.update({
                    (RegistrationChallenges.email eq email) and
                        (RegistrationChallenges.lastSentAt lessEq now - RESEND_MILLIS)
                }) {
                    it[RegistrationChallenges.generation] = generation
                    it[RegistrationChallenges.digest] = digest
                    it[issuedAt] = now; it[expiresAt] = now + VALID_MILLIS
                    it[lastSentAt] = now; it[attempts] = 0; it[ready] = false; it[consumed] = false
                }
                if (updated != 1) throw DomainException(1016, "操作太频繁，请稍后再试", HttpStatusCode.TooManyRequests)
            }
        }
        val accepted = try { delivery.deliver(RegistrationEmail(email, code, VALID_MILLIS / 1000)) }
            catch (_: Exception) { false }
        val enabled = if (accepted) transaction(database) {
            RegistrationChallenges.update({
                (RegistrationChallenges.email eq email) and (RegistrationChallenges.generation eq generation) and
                    (RegistrationChallenges.consumed eq false)
            }) { it[ready] = clock.millis() < now + VALID_MILLIS }
        } else 0
        if (!accepted || enabled != 1 || clock.millis() >= now + VALID_MILLIS) {
            throw DomainException(5033, "验证码暂时无法发送，请稍后再试", HttpStatusCode.ServiceUnavailable)
        }
    }

    fun register(rawEmail: String, rawCode: String, password: String) {
        val email = AuthService.normalizeEmail(rawEmail)
        AuthService.validateEmail(email)
        AuthService.validatePassword(password)
        val code = rawCode.trim { it <= '\u0020' }
        val accepted = transaction(database) {
            if (Users.selectAll().where { Users.email eq email }.limit(1).any()) throw EmailAlreadyUsedException()
            val row = RegistrationChallenges.selectAll().where { RegistrationChallenges.email eq email }.forUpdate().singleOrNull()
                ?: return@transaction false
            val generation = row[RegistrationChallenges.generation]
            if (!row[RegistrationChallenges.ready] || row[RegistrationChallenges.consumed] ||
                row[RegistrationChallenges.expiresAt] <= clock.millis() || row[RegistrationChallenges.attempts] >= MAX_ATTEMPTS) return@transaction false
            val matches = MessageDigest.isEqual(row[RegistrationChallenges.digest].toByteArray(UTF_8),
                digest(email, generation, code).toByteArray(UTF_8)) && code.matches(Regex("[0-9]{6}"))
            if (!matches) {
                val attempts = row[RegistrationChallenges.attempts] + 1
                RegistrationChallenges.update({ (RegistrationChallenges.email eq email) and (RegistrationChallenges.generation eq generation) }) {
                    it[RegistrationChallenges.attempts] = attempts
                    if (attempts >= MAX_ATTEMPTS) it[ready] = false
                }
                // Return first; throwing here would roll back failed-attempt storage.
                return@transaction false
            }
            RegistrationChallenges.update({ (RegistrationChallenges.email eq email) and (RegistrationChallenges.generation eq generation) }) {
                it[consumed] = true; it[ready] = false
            }
            auth.createRegisteredAccount(email, password)
            true
        }
        if (!accepted) throw DomainException(1015, "验证码无效或已过期", HttpStatusCode.BadRequest)
    }

    private fun digest(email: String, generation: String, code: String): String =
        digests.digest(EmailChallengePurpose.REGISTER, email, generation, code)

    companion object {
        const val RESEND_MILLIS = 30_000L
        const val VALID_MILLIS = 600_000L
        const val MAX_ATTEMPTS = 5
    }
}
