package com.projectfocus.server.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.AlgorithmMismatchException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.SignatureVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.Payload
import com.projectfocus.server.config.JwtConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.UUID


class JwtService(private val config: JwtConfig) {








    private val algorithm: Algorithm = Algorithm.HMAC256(config.secret)












    fun verifier(): JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .acceptLeeway(LEEWAY_SECONDS)
        .build()

    /**
     * 签发 access token。
     *
     * @param nowMillis 允许注入 —— 测试里要造"已过期"的 token，
     *                  如果内部直接 `System.currentTimeMillis()` 就没法测。
     */
    fun issueAccessToken(
        userId: String,
        email: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String = JWT.create()
        .withIssuer(config.issuer)
        .withSubject(userId)
        .withClaim(CLAIM_EMAIL, email)
        .withIssuedAt(Date(nowMillis))
        .withExpiresAt(Date(nowMillis + config.ttlMillis))

        .withJWTId(UUID.randomUUID().toString())
        .sign(algorithm)










    fun verifyOrNull(token: String): DecodedJWT? = try {
        verifier().verify(token)
    } catch (_: JWTVerificationException) {
        null
    }

    /**
     * 与 [verifyOrNull] 相同，但把失败原因分成三档 —— 只在测试与日志里用。
     */
    fun diagnose(token: String): VerifyFailure? = try {
        verifier().verify(token)
        null
    } catch (e: TokenExpiredException) {
        VerifyFailure.EXPIRED
    } catch (e: SignatureVerificationException) {
        VerifyFailure.BAD_SIGNATURE
    } catch (e: AlgorithmMismatchException) {
        VerifyFailure.BAD_ALGORITHM
    } catch (_: JWTVerificationException) {
        VerifyFailure.MALFORMED
    }

    enum class VerifyFailure { EXPIRED, BAD_SIGNATURE, BAD_ALGORITHM, MALFORMED }

    companion object {
        const val CLAIM_EMAIL = "email"
        private const val LEEWAY_SECONDS = 30L
    }
}










data class PfPrincipal(
    val userId: String,
    val email: String,
) : io.ktor.server.auth.Principal {
    companion object {







        fun from(jwt: Payload): PfPrincipal? {
            val sub = jwt.subject

            // 长度不对说明签发端有 bug 或 token 被伪造，直接判失败。
            if (sub.isNullOrBlank() || sub.length !in 8..36) return null
            return PfPrincipal(
                userId = sub,
                email = jwt.getClaim(JwtService.CLAIM_EMAIL).asString().orEmpty(),
            )
        }
    }
}











object TokenMath {
    private val random = SecureRandom()
    private const val TOKEN_BYTES = 32
    const val DEFAULT_TTL_DAYS = 30

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        // 手工转 hex 而不是 String.format —— 后者在循环里创建 Formatter，慢 5 倍
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun expiresAt(nowMillis: Long, days: Int = DEFAULT_TTL_DAYS): Long =
        nowMillis + days * 86_400_000L

    fun isExpired(expiresAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis <= nowMillis

    private val HEX = "0123456789abcdef".toCharArray()
}
