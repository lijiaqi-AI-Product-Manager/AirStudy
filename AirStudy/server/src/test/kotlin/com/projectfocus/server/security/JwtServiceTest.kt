package com.projectfocus.server.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.truth.Truth.assertThat
import com.projectfocus.server.config.JwtConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Date







@DisplayName("JWT")
class JwtServiceTest {

    private val cfg = JwtConfig(
        secret = "unit-test-secret-at-least-32-bytes-long!!",
        issuer = "projectfocus-test",
        ttlHours = 1,
    )
    private val svc = JwtService(cfg)

    @Test
    fun `签发的 token 能被自己的校验器验过`() {
        val t = svc.issueAccessToken("user-1234", "a@b.dev")
        val decoded = svc.verifyOrNull(t)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.subject).isEqualTo("user-1234")
        assertThat(decoded.getClaim("email").asString()).isEqualTo("a@b.dev")
        assertThat(decoded.issuer).isEqualTo("projectfocus-test")
    }

    @Test
    fun `过期 token 被判失败而不是抛异常`() {

        //   客户端看到"服务器故障"而不是"登录过期" —— 它就不知道该刷新 token。
        val expired = svc.issueAccessToken(
            "u1", "a@b.dev",
            nowMillis = System.currentTimeMillis() - cfg.ttlMillis - 120_000,
        )
        assertThat(svc.verifyOrNull(expired)).isNull()
        assertThat(svc.diagnose(expired)).isEqualTo(JwtService.VerifyFailure.EXPIRED)
    }

    @Test
    fun `别的密钥签的 token 被拒`() {
        val other = JwtService(cfg.copy(secret = "completely-different-secret-32-bytes!!"))
        val forged = other.issueAccessToken("attacker", "evil@b.dev")
        assertThat(svc.verifyOrNull(forged)).isNull()
        assertThat(svc.diagnose(forged)).isEqualTo(JwtService.VerifyFailure.BAD_SIGNATURE)
    }

    @Test
    fun `issuer 不匹配被拒 —— 预发 token 不能用在生产`() {
        val staging = JwtService(cfg.copy(issuer = "projectfocus-staging"))
        val t = staging.issueAccessToken("u1", "a@b.dev")
        assertThat(svc.verifyOrNull(t)).isNull()
    }

    @Test
    fun `alg none 攻击被拒`() {
        // 经典攻击：把 header 改成 {"alg":"none"} 并去掉签名
        val header = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"iss":"projectfocus-test","sub":"attacker","exp":${(System.currentTimeMillis() / 1000) + 3600}}""".toByteArray(),
        )
        val noneToken = "$header.$payload."
        assertThat(svc.verifyOrNull(noneToken)).isNull()
    }

    @Test
    fun `HS256 换成 HS512 签名被拒`() {
        val hs512 = JWT.create()
            .withIssuer("projectfocus-test")
            .withSubject("attacker")
            .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
            .sign(Algorithm.HMAC512(cfg.secret))
        assertThat(svc.verifyOrNull(hs512)).isNull()
    }

    @Test
    fun `空串或垃圾串不会崩`() {
        listOf("", "a", "a.b", "a.b.c", "..", "not.a.token").forEach {
            assertThat(svc.verifyOrNull(it)).isNull()
        }
    }

    @Test
    fun `30 秒时钟漂移内仍然通过`() {

        val justExpired = svc.issueAccessToken(
            "u1", "a@b.dev",
            nowMillis = System.currentTimeMillis() - cfg.ttlMillis - 10_000,   // 过期了 10 秒
        )
        assertThat(svc.verifyOrNull(justExpired)).isNotNull()
    }

    @Test
    fun `PfPrincipal 拒绝过短的 sub`() {
        val t = JWT.create()
            .withIssuer("projectfocus-test")
            .withSubject("short")
            .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
            .sign(Algorithm.HMAC256(cfg.secret))
        val decoded = svc.verifyOrNull(t)!!
        assertThat(PfPrincipal.from(decoded)).isNull()
    }
}

@DisplayName("refresh token 工具")
class TokenMathTest {

    @Test
    fun `生成的是 URL 安全字符串且长度足够`() {
        val t = TokenMath.generate()
        assertThat(t.length).isAtLeast(40)     // 32 字节 base64url ≈ 43 字符
        assertThat(t).matches("[A-Za-z0-9_\\-]+")
    }

    @Test
    fun `摘要稳定且是 64 位十六进制`() {
        val t = TokenMath.generate()
        val h1 = TokenMath.hash(t)
        val h2 = TokenMath.hash(t)
        assertThat(h1).isEqualTo(h2)
        assertThat(h1.length).isEqualTo(64)
        assertThat(h1).matches("[0-9a-f]{64}")
    }

    @Test
    fun `不同 token 摘要不同`() {
        val set = (1..500).map { TokenMath.hash(TokenMath.generate()) }.toSet()
        assertThat(set.size).isEqualTo(500)
    }

    @Test
    fun `过期判断`() {
        val now = 1_000_000L
        assertThat(TokenMath.isExpired(now - 1, now)).isTrue()
        assertThat(TokenMath.isExpired(now, now)).isTrue()      // 边界：等于即过期
        assertThat(TokenMath.isExpired(now + 1, now)).isFalse()
    }

    @Test
    fun `30 天后过期`() {
        val now = 0L
        assertThat(TokenMath.expiresAt(now)).isEqualTo(30L * 86_400_000L)
    }
}
