package com.projectfocus.server

import io.ktor.client.statement.bodyAsText
import com.projectfocus.server.security.JwtService
import com.projectfocus.server.config.ServerConfig
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 认证 + 用户档案端点。
 *
 * 每条用例都对应一个**真实会发生的场景**（不是"测个方法"）。
 */
@DisplayName("认证端点")
class AuthRoutesTest : ServerTestBase() {

    @Test
    fun `注册不登录且显式登录后才能访问 user-me`() = serverTest("auth1") { c ->
        val reg = c.registerWithCode("gauss@pf.dev")
        assertThat(reg.status.value).isEqualTo(201)
        assertThat(c.str(reg, "token")).isNull()
        assertThat(c.str(reg, "refreshToken")).isNull()
        assertThat(c.jsonOf(reg).keys).doesNotContain("token")
        assertThat(c.currentToken).isNull()
        assertThat(c.get("/user/me").status.value).isEqualTo(401)

        val login = c.post("/auth/login", json("email" to "gauss@pf.dev", "password" to "hunter2!!"))
        assertThat(login.status.value).isEqualTo(200)
        val token = c.str(login, "token")
        assertThat(token).isNotEmpty()

        val me = c.withToken(token).get("/user/me")
        assertThat(me.status.value).isEqualTo(200)
        assertThat(c.str(me, "username")).isEqualTo("Traveler") // Own default, not an original source label.

        assertThat(c.str(me, "xp")).isEqualTo("0")
        assertThat(c.str(me, "totalFocusedMinutes")).isEqualTo("0")
        val named = c.patch("/user/update", json("username" to "高斯"))
        assertThat(named.status.value).isEqualTo(200)
        assertThat(c.str(named, "username")).isEqualTo("高斯")
    }

    @Test
    fun `重复邮箱返回 1010 而不是 500`() = serverTest("auth2") { c ->
        assertThat(c.registerWithCode("dup@pf.dev").status.value).isEqualTo(201)
        val again = c.post("/auth/register", json("email" to "dup@pf.dev", "password" to "hunter2!!",
            "code" to c.deliveredRegistrationCode("dup@pf.dev")))
        assertThat(again.status.value).isEqualTo(409)
        assertThat(c.errorCode(again)).isEqualTo(1010)
    }

    @Test
    fun `密码错误与用户不存在返回同一个错误码`() = serverTest("auth3") { c ->
        assertThat(c.registerWithCode("real@pf.dev").status.value).isEqualTo(201)

        val wrongPwd = c.post("/auth/login", json("email" to "real@pf.dev", "password" to "wrongpass1"))
        val noUser = c.post("/auth/login", json("email" to "nobody@pf.dev", "password" to "wrongpass1"))


        assertThat(c.errorCode(wrongPwd)).isEqualTo(1011)
        assertThat(c.errorCode(noUser)).isEqualTo(1011)
        assertThat(wrongPwd.status.value).isEqualTo(401)
        assertThat(noUser.status.value).isEqualTo(401)
    }

    @Test
    fun `没有 token 访问 me 返回 401 与统一错误体`() = serverTest("auth4") { c ->
        val r = c.get("/user/me")
        assertThat(r.status.value).isEqualTo(401)
        assertThat(c.errorCode(r)).isEqualTo(1001)
    }

    @Test
    fun `伪造签名的 token 被拒`() = serverTest("auth5") { c ->
        // 用别的密钥签一个格式完全合法的 token
        val other = JwtService(
            ServerConfig.fromEnv { if (it == "PF_JWT_SECRET") "another-secret-at-least-32-bytes-long!!!" else null }.jwt,
        )
        val forged = other.issueAccessToken(UUID.randomUUID().toString(), "attacker@pf.dev")
        val r = c.withToken(forged).get("/user/me")
        assertThat(r.status.value).isEqualTo(401)
    }

    @Test
    fun `过期 token 被拒`() = serverTest("auth6") { c ->
        val cfg = testConfig("auth6")
        val svc = JwtService(cfg.jwt)
        // 签发时刻往前推 ttl+1 分钟 → 已过期
        val expired = svc.issueAccessToken(
            UUID.randomUUID().toString(),
            "old@pf.dev",
            nowMillis = System.currentTimeMillis() - cfg.jwt.ttlMillis - 60_000,
        )
        assertThat(c.withToken(expired).get("/user/me").status.value).isEqualTo(401)
    }

    @Test
    fun `改昵称后立刻生效 —— 不在 JWT 里缓存昵称`() = serverTest("auth7") { c ->
        val login = c.registerAndLogin("nick@pf.dev")
        val token = c.str(login, "token")!!
        val previous = c.withToken(token).patch("/user/update", json("username" to "旧名"))
        assertThat(previous.status.value).isEqualTo(200)
        assertThat(c.str(previous, "username")).isEqualTo("旧名")
        val upd = c.withToken(token).patch("/user/update", json("username" to "新名"))
        assertThat(upd.status.value).isEqualTo(200)
        assertThat(c.str(upd, "username")).isEqualTo("新名")

        // 用同一个 token 再查一次 —— 必须是新名字
        val me = c.withToken(token).get("/user/me")
        assertThat(c.str(me, "username")).isEqualTo("新名")
    }

    @Test
    fun `昵称可以是 emoji 且按字计数`() = serverTest("auth8") { c ->
        val login = c.registerAndLogin("emoji@pf.dev")
        val token = c.str(login, "token")!!
        val named = c.withToken(token).patch("/user/update", json("username" to "🧑‍🚀机长"))
        assertThat(named.status.value).isEqualTo(200)
        assertThat(c.str(named, "username")).isEqualTo("🧑‍🚀机长")

        // 21 个 emoji 应该超限（20 是上限）
        val tooLong = "🚀".repeat(21)
        val r = c.withToken(token).patch("/user/update", json("username" to tooLong))
        assertThat(c.errorCode(r)).isEqualTo(1012)
    }

    @Test
    fun `空昵称被拒`() = serverTest("auth9") { c ->
        val login = c.registerAndLogin("empty@pf.dev")
        val r = c.withToken(c.str(login, "token")).patch("/user/update", json("username" to "   "))
        assertThat(c.errorCode(r)).isEqualTo(1012)
    }

    @Test
    fun `短密码被拒且不落库`() = serverTest("auth10") { c ->
        // No code: email/password validation must precede the missing-code branch.
        val r = c.post("/auth/register", json("email" to "short@pf.dev", "password" to "abc"))
        assertThat(c.errorCode(r)).isEqualTo(1002)
        assertThat(r.status.value).isEqualTo(400)
        assertThat(c.post("/auth/login", json("email" to "short@pf.dev", "password" to "abc")).status.value).isEqualTo(401)
        // 确认没落库：用同样的邮箱注册合法密码应该成功（说明上一笔没占位）
        val ok = c.registerWithCode("short@pf.dev", "longenough")
        assertThat(ok.status.value).isEqualTo(201)
    }

    @Test
    fun `非法邮箱被拒`() = serverTest("auth11") { c ->
        listOf("notanemail", "a@b", "@pf.dev", "a b@pf.dev").forEach { bad ->
            val r = c.post("/auth/register", json("email" to bad, "password" to "hunter2!!"))
            // 用 withMessage 而不是 named —— Truth 的 Subject.named() 不可链式
            assertThat(c.errorCode(r)).isEqualTo(1001)
            assertThat(r.status.value).isEqualTo(400)
            assertThat(c.post("/auth/login", json("email" to bad, "password" to "hunter2!!")).status.value).isEqualTo(401)
            if (c.errorCode(r) != 1001) throw AssertionError("email=$bad 的错误码不对：${c.errorCode(r)}")
        }
    }

    @Test
    fun `邮箱大小写不敏感`() = serverTest("auth12") { c ->
        assertThat(c.registerWithCode("Mixed@PF.dev").status.value).isEqualTo(201)
        val login = c.post("/auth/login", json("email" to "  mixed@pf.DEV ", "password" to "hunter2!!"))
        assertThat(login.status.value).isEqualTo(200)
    }

    @Test
    fun `不存在的 refresh 返回 1001`() = serverTest("auth13") { c ->
        val login = c.registerAndLogin("ref@pf.dev")
        assertThat(login.status.value).isEqualTo(200)

        val refreshed = c.post("/auth/refresh", json("refreshToken" to "bogus"))
        assertThat(c.errorCode(refreshed)).isEqualTo(1001)   // 不存在的 refresh 直接 401
    }

    @Test
    fun `health 端点真的查了数据库`() = serverTest("auth14") { c ->
        val r = c.get("/health")
        assertThat(r.status.value).isEqualTo(200)
        assertThat(r.bodyAsText()).contains("\"db\":\"ok\"")
    }

    @Test
    fun `verify 端点对有效 token 返回用户信息`() = serverTest("auth15") { c ->
        val login = c.registerAndLogin("ver@pf.dev")
        val token = c.str(login, "token")!!
        val v = c.withToken(token).get("/auth/verify")
        assertThat(v.status.value).isEqualTo(200)
        assertThat(c.str(v, "email")).isEqualTo("ver@pf.dev")
    }
}
