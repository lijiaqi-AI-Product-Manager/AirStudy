package com.projectfocus.server.domain

import com.projectfocus.core.network.dto.AuthUserDto
import com.projectfocus.core.network.dto.UserProfileDto
import com.projectfocus.server.db.Tables.RefreshTokens
import com.projectfocus.server.db.Tables.Users
import com.projectfocus.server.security.JwtService
import com.projectfocus.server.security.TokenMath
import com.projectfocus.server.security.PfPrincipal
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import io.ktor.server.auth.principal
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import at.favre.lib.crypto.bcrypt.BCrypt
import java.time.Instant
import java.util.UUID
import java.util.Locale
import org.jetbrains.exposed.sql.Database


class AuthService(
    private val jwt: JwtService,
    private val database: Database? = null,
) {


    internal fun createRegisteredAccount(email: String, password: String): Unit = transaction(database) {
        val normalizedEmail = normalizeEmail(email)
        validateEmail(normalizedEmail)
        validatePassword(password)
        val nick = "Traveler" // Original register request has no nickname field.


        //   真正的保护是 email 上的 uniqueIndex —— 插入时数据库会拦。
        //   这里的 pre-check 只是为了让正常路径返回 1010 而不是 500。
        val exists = Users.selectAll().where { Users.email eq normalizedEmail }.limit(1).any()
        if (exists) throw EmailAlreadyUsedException()

        val userId = UUID.randomUUID().toString()
        val now = Instant.now()
        Users.insert {
            it[id] = userId
            it[Users.email] = normalizedEmail

            //   成本 12 是 4 倍时间 —— 登录接口 QPS 会掉到 12/核。
            //   10 在"抗暴力破解"与"移动端能忍的登录延迟"之间是合理的折中。
            it[passwordHash] = BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray())
            it[Users.username] = nick
            it[avatarId] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }
        // No token/refresh pair: explicit login is a separate action.
        Unit
    }

    fun login(email: String, password: String): AuthUserDto = transaction(database) {
        val normalizedEmail = normalizeEmail(email)
        // Own reset serialization: login either finishes before reset's
        // refresh revocation, or verifies the updated password after it.
        val row = Users.selectAll().where { Users.email eq normalizedEmail }.limit(1).forUpdate().firstOrNull()

            //   否则"不存在"（1ms 返回）与"密码错"（70ms 返回）的**耗时可区分**，
            //   攻击者据此可以枚举"哪些邮箱注册过" —— 这就是 timing attack。
            //   用一个固定的假 hash 兜住，把响应时间拉平。
            ?: run {
                BCrypt.verifyer().verify(password.toCharArray(), DUMMY_HASH)
                throw InvalidCredentialsException()
            }


        //   实测：`verifyStrict` 在未指定 Version 时直接抛
        //   `IllegalArgumentException: Using strict requires to define a Version`
        //   —— 它要求调用方**明确知道**哈希是 $2a$ 还是 $2y$。

        //   所以 `verify` 足够，且两者在比较环节都是常数时间。
        if (!BCrypt.verifyer().verify(password.toCharArray(), row[Users.passwordHash].toCharArray()).verified) {
            throw InvalidCredentialsException()
        }

        val (access, _) = issueTokenPair(row[Users.id], row[Users.email])
        AuthUserDto(id = row[Users.id], email = row[Users.email], token = access)
    }










    fun refresh(refreshToken: String): AuthUserDto = transaction(database) {
        val hash = TokenMath.hash(refreshToken)
        val candidate = RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq hash }.limit(1).firstOrNull()
            ?: throw UnauthorizedException()
        // Same user→refresh lock order as reset. Re-read after the user lock:
        // a pre-reset snapshot is not permission to rotate a revoked token.
        val user = Users.selectAll().where { Users.id eq candidate[RefreshTokens.userId] }.limit(1).forUpdate().firstOrNull()
            ?: throw UnauthorizedException()
        val row = RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq hash }.limit(1).forUpdate().firstOrNull()
            ?: throw UnauthorizedException()
        if (row[RefreshTokens.userId] != user[Users.id]) throw UnauthorizedException()

        val now = System.currentTimeMillis()
        if (row[RefreshTokens.revokedAt] != null) {

            //   保守做法：把这个用户**全部** refresh token 都吊销（强制全设备重登）。
            //   激进的攻击者可能只是合法用户重放了旧请求 —— 但安全上宁可多踢一次。
            revokeAllFor(row[RefreshTokens.userId])
            throw UnauthorizedException()
        }
        if (TokenMath.isExpired(row[RefreshTokens.expiresAt], now)) {
            throw UnauthorizedException()
        }

        RefreshTokens.deleteWhere { RefreshTokens.tokenHash eq hash }
        val (access, _) = issueTokenPair(user[Users.id], user[Users.email])
        AuthUserDto(id = user[Users.id], email = user[Users.email], token = access)
    }

    fun logout(refreshToken: String) = transaction(database) {
        RefreshTokens.deleteWhere { RefreshTokens.tokenHash eq TokenMath.hash(refreshToken) }
    }

    fun logoutEverywhere(userId: String) = transaction(database) { revokeAllFor(userId) }










    fun principalOf(call: io.ktor.server.application.ApplicationCall): PfPrincipal =
        call.principal<PfPrincipal>() ?: throw UnauthorizedException()

    fun profile(userId: String): UserProfileDto = transaction(database) {
        val row = Users.selectAll().where { Users.id eq userId }.limit(1).firstOrNull()
            ?: throw UserNotFoundException()
        row.toProfile()
    }









    fun updateProfile(userId: String, username: String?, avatarId: Int?): UserProfileDto = transaction(database) {
        val row = Users.selectAll().where { Users.id eq userId }.limit(1).firstOrNull()
            ?: throw UserNotFoundException()

        val newNick = username?.let { validateNickname(it) } ?: row[Users.username]
        val newAvatar = avatarId?.let {
            if (it !in AVATAR_RANGE) throw DomainException(1014, "头像编号不合法", io.ktor.http.HttpStatusCode.BadRequest)
            it
        } ?: row[Users.avatarId]

        Users.update({ Users.id eq userId }) {
            it[Users.username] = newNick
            it[Users.avatarId] = newAvatar
            it[updatedAt] = Instant.now()
        }
        row.toProfile().copy(username = newNick, avatarId = newAvatar)
    }

    // ───────────────────────────────────────────────────────

    /**
     * 签发 access + refresh。
     *
     * @return pair(accessToken, refreshToken)
     */
    private fun issueTokenPair(userId: String, email: String): Pair<String, String> {
        val access = jwt.issueAccessToken(userId, email)
        val refresh = TokenMath.generate()
        val now = System.currentTimeMillis()
        RefreshTokens.insert {
            it[tokenHash] = TokenMath.hash(refresh)
            it[RefreshTokens.userId] = userId
            it[issuedAt] = now
            it[expiresAt] = TokenMath.expiresAt(now)
            it[userAgent] = ""
        }
        return access to refresh
    }

    private fun revokeAllFor(userId: String) {
        RefreshTokens.deleteWhere { RefreshTokens.userId eq userId }
    }

    companion object {
        private val EMAIL_RE = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
        const val MAX_USERNAME = 20
        const val MIN_PASSWORD = 8
        const val MAX_PASSWORD = 72          // bcrypt 只取前 72 字节，超出部分被静默忽略
        val AVATAR_RANGE = 0..11









        const val BCRYPT_COST = 10

        /**
         * 固定"假 hash"，用于把"用户不存在"的耗时拉平到与 bcrypt 相当。
         * 明文是 `"not-a-real-password"`，永远不会匹配任何用户。
         */
        private val DUMMY_HASH = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"

        fun normalizeEmail(raw: String): String = raw.trim { it <= '\u0020' }.lowercase(Locale.ROOT)

        fun validateEmail(email: String) {
            if (email.length > 254 || !EMAIL_RE.matches(email)) {
                throw DomainException(1001, "邮箱格式不正确", io.ktor.http.HttpStatusCode.BadRequest)
            }
        }

        fun validatePassword(pwd: String) {
            if (pwd.length < MIN_PASSWORD) {
                throw DomainException(1002, "密码至少 $MIN_PASSWORD 位", io.ktor.http.HttpStatusCode.BadRequest)
            }


            if (pwd.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD) {
                throw DomainException(1003, "密码过长（最多 $MAX_PASSWORD 字节）", io.ktor.http.HttpStatusCode.BadRequest)
            }
        }










        fun validateNickname(raw: String): String {
            val s = raw.trim()
            if (s.isEmpty()) throw NicknameInvalidException("昵称不能为空")
            if (s.any { it.isISOControl() }) throw NicknameInvalidException("昵称不能包含控制字符")
            val points = s.codePointCount(0, s.length)
            if (points > MAX_USERNAME) throw NicknameInvalidException("昵称最多 $MAX_USERNAME 个字")
            return s
        }
    }
}

internal fun ResultRow.toProfile(): UserProfileDto = UserProfileDto(
    id = this[Users.id],
    username = this[Users.username],
    avatarId = this[Users.avatarId],
    isPremium = this[Users.isPremium],
    xp = this[Users.xp],
    streak = this[Users.streak],
    totalGroupFlight = this[Users.totalGroupFlight],
    totalHostedFlight = this[Users.totalHostedFlight],
    challengesCompleted = this[Users.challengesCompleted],
    totalFocusedMinutes = this[Users.totalFocusedMinutes],
    countriesVisited = this[Users.countriesVisited],
    perfectWeeks = this[Users.perfectWeeks],
    rank = this[Users.rank],
)
