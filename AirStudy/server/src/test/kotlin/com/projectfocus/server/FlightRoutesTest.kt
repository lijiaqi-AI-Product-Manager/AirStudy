package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import io.ktor.client.statement.bodyAsText
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 飞行记录端点的集成测试。
 *
 * 重点守两件事：
 *   1. **同步幂等** —— 弱网下客户端必然会重发，重发不能产生重复记录
 *   2. **服务端不信任客户端上报的距离** —— 改包上传 99999km 必须被改回真实值
 */
@DisplayName("飞行记录端点")
class FlightRoutesTest : ServerTestBase() {

    private suspend fun registerAndLogin(c: TestClient, email: String): String {
        val login = c.registerAndLogin(email)
        val token = c.str(login, "token")!!
        val profile = c.withToken(token).patch("/user/update", json("username" to "飞"))
        assertThat(profile.status.value).isEqualTo(200)
        return token
    }

    private fun flight(
        id: String = UUID.randomUUID().toString(),
        duration: Int = 45,
        actual: Int = 45,
        distance: Double = 600.0,
        status: String = "COMPLETED",
        start: Long = System.currentTimeMillis() - 3_600_000,
        dep: String = "PEK",
        dst: String = "SHA",
    ) = json(
        "id" to id,
        "focusType" to "SOLO",
        "departureAirportCode" to dep,
        "destinationAirportCode" to dst,
        "startTimeMillis" to start,
        "durationMinutes" to duration,
        "actualFocusMinutes" to actual,
        "distanceKm" to distance,
        "status" to status,
        "createdAt" to start,
        "updatedAt" to start,
    )

    @Test
    fun `同步一条记录后能在历史里查到`() = serverTest("fl1") { c ->
        val t = registerAndLogin(c, "f1@pf.dev")
        val sync = c.withToken(t).post("/session/sync", """{"sessions":[${flight()}]}""")
        assertThat(sync.status.value).isEqualTo(200)
        assertThat(c.str(sync, "accepted")).isEqualTo("1")

        val hist = c.withToken(t).get("/session/history")
        assertThat(hist.status.value).isEqualTo(200)
        assertThat(c.str(hist, "total")).isEqualTo("1")
        assertThat(c.str(hist, "hasMore")).isEqualTo("false")
    }

    @Test
    fun `重复同步同一批不会产生重复记录`() = serverTest("fl2") { c ->
        val t = registerAndLogin(c, "f2@pf.dev")
        val id = UUID.randomUUID().toString()
        val body = """{"sessions":[${flight(id = id)}]}"""

        c.withToken(t).post("/session/sync", body)
        val again = c.withToken(t).post("/session/sync", body)
        assertThat(c.str(again, "accepted")).isEqualTo("1")

        val hist = c.withToken(t).get("/session/history")
        assertThat(c.str(hist, "total")).isEqualTo("1")
    }

    @Test fun anExistingIdentifierFromAnotherAccountIsRejectedWithoutAcknowledgementOrOverwrite() = serverTest("flight-owner-collision") { c ->
        val owner = registerAndLogin(c, "history-owner@airstudy.test")
        val other = registerAndLogin(c, "history-other@airstudy.test")
        val id = UUID.randomUUID().toString()
        val original = """{"sessions":[${flight(id = id, actual = 33)}]}"""
        assertThat(c.withToken(owner).post("/session/sync", original).status.value).isEqualTo(200)
        val foreign = c.withToken(other).post("/session/sync", """{"sessions":[${flight(id = id, actual = 1)}]}""")
        assertThat(foreign.status.value).isEqualTo(200)
        assertThat(c.str(foreign, "accepted")).isEqualTo("0")
        assertThat(c.str(foreign, "rejected")).isEqualTo("1")
        assertThat(Json.parseToJsonElement(foreign.bodyAsText()).jsonObject.getValue("data").jsonObject.getValue("serverIds").jsonArray).isEmpty()
        assertThat(c.str(c.withToken(other).get("/session/history"), "total")).isEqualTo("0")
        assertThat(c.str(c.withToken(other).get("/user/me"), "totalFocusedMinutes")).isEqualTo("0")
        val history = c.withToken(owner).get("/session/history")
        assertThat(c.str(history, "total")).isEqualTo("1")
        assertThat(history.bodyAsText()).contains("\"actualFocusMinutes\":33")
        assertThat(c.str(c.withToken(owner).get("/user/me"), "totalFocusedMinutes")).isEqualTo("33")
        val retry = c.withToken(owner).post("/session/sync", original)
        assertThat(c.str(retry, "accepted")).isEqualTo("1")
        assertThat(c.str(retry, "rejected")).isEqualTo("0")
    }

    @Test fun aMixedBatchOnlyAcknowledgesItsOwnNewAndRepeatedIdentifiers() = serverTest("flight-owner-mixed") { c ->
        val owner = registerAndLogin(c, "mixed-owner@airstudy.test")
        val other = registerAndLogin(c, "mixed-other@airstudy.test")
        val foreignId = UUID.randomUUID().toString()
        val ownId = UUID.randomUUID().toString()
        c.withToken(owner).post("/session/sync", """{"sessions":[${flight(id = foreignId)}]}""")
        val body = """{"sessions":[${flight(id = foreignId)},${flight(id = ownId, actual = 17)},${flight(id = ownId, actual = 1)}]}"""
        val result = c.withToken(other).post("/session/sync", body)
        assertThat(result.status.value).isEqualTo(200)
        assertThat(c.str(result, "accepted")).isEqualTo("2")
        assertThat(c.str(result, "rejected")).isEqualTo("1")
        val ids = Json.parseToJsonElement(result.bodyAsText()).jsonObject.getValue("data").jsonObject.getValue("serverIds").jsonArray
            .map { it.jsonPrimitive.content }
        assertThat(ids).containsExactly(ownId, ownId).inOrder()
        val history = c.withToken(other).get("/session/history")
        assertThat(c.str(history, "total")).isEqualTo("1")
        assertThat(history.bodyAsText()).contains("\"actualFocusMinutes\":17")
        assertThat(history.bodyAsText()).doesNotContain(foreignId)
    }

    @Test fun simultaneousFirstSubmissionsCannotBothClaimTheSameIdentifier() = serverTest("flight-owner-concurrent") { c ->
        val first = registerAndLogin(c, "race-first@airstudy.test")
        val second = registerAndLogin(c, "race-second@airstudy.test")
        val id = UUID.randomUUID().toString()
        val body = """{"sessions":[${flight(id = id)}]}"""
        val clients = listOf(TestClient(c.http).withToken(first), TestClient(c.http).withToken(second))
        val results = coroutineScope {
            val start = CompletableDeferred<Unit>()
            val work = clients.map { client -> async { start.await(); client.post("/session/sync", body) } }
            start.complete(Unit)
            work.awaitAll()
        }
        assertThat(results.map { it.status.value }).containsExactly(200, 200)
        assertThat(results.sumOf { c.str(it, "accepted")!!.toInt() }).isEqualTo(1)
        assertThat(results.sumOf { c.str(it, "rejected")!!.toInt() }).isEqualTo(1)
        val counts = listOf(first, second).map { token -> c.str(c.withToken(token).get("/session/history"), "total")!!.toInt() }
        assertThat(counts).containsExactly(0, 1)
    }

    @Test
    fun `客户端上报的 distanceKm 被服务端重算覆盖`() = serverTest("fl3") { c ->
        val t = registerAndLogin(c, "f3@pf.dev")
        // 客户端谎报 99999 km，实际时长 45 分钟
        c.withToken(t).post("/session/sync", """{"sessions":[${flight(duration = 45, distance = 99999.0)}]}""")

        val hist = c.withToken(t).get("/session/history")
        val body = hist.bodyAsText()

        assertThat(body).contains("\"distanceKm\":600.0")
        assertThat(body).doesNotContain("99999")
    }

    @Test
    fun `actualFocusMinutes 超过时长被截断而不是拒绝`() = serverTest("fl4") { c ->
        val t = registerAndLogin(c, "f4@pf.dev")
        // 上报 999 分钟实际专注，但计划只有 45 分钟
        c.withToken(t).post("/session/sync", """{"sessions":[${flight(duration = 45, actual = 999)}]}""")

        val body = c.withToken(t).get("/session/history").bodyAsText()
        assertThat(body).contains("\"actualFocusMinutes\":45")
    }

    @Test
    fun `时长越界的记录被 rejected 而不是 accepted`() = serverTest("fl5") { c ->
        val t = registerAndLogin(c, "f5@pf.dev")
        val r = c.withToken(t).post("/session/sync", """{"sessions":[${flight(duration = 9999)}]}""")
        assertThat(c.str(r, "accepted")).isEqualTo("0")
        assertThat(c.str(r, "rejected")).isEqualTo("1")
    }

    @Test
    fun `非法 UUID 被拒`() = serverTest("fl6") { c ->
        val t = registerAndLogin(c, "f6@pf.dev")
        val r = c.withToken(t).post("/session/sync", """{"sessions":[${flight(id = "not-a-uuid")}]}""")
        assertThat(c.str(r, "rejected")).isEqualTo("1")
    }

    @Test
    fun `未来时间戳超过一天被拒`() = serverTest("fl7") { c ->
        val t = registerAndLogin(c, "f7@pf.dev")
        val future = System.currentTimeMillis() + 3L * 86_400_000L
        val r = c.withToken(t).post("/session/sync", """{"sessions":[${flight(start = future)}]}""")
        assertThat(c.str(r, "rejected")).isEqualTo("1")
    }

    @Test
    fun `单批超过 100 条被整体拒绝`() = serverTest("fl8") { c ->
        val t = registerAndLogin(c, "f8@pf.dev")
        val many = (1..101).joinToString(",") { flight() }
        val r = c.withToken(t).post("/session/sync", """{"sessions":[$many]}""")
        assertThat(c.errorCode(r)).isEqualTo(3010)
    }

    @Test
    fun `分页 hasMore 的边界正确`() = serverTest("fl9") { c ->
        val t = registerAndLogin(c, "f9@pf.dev")
        val base = System.currentTimeMillis() - 10_000_000L
        // 25 条，每秒一条
        val all = (0 until 25).joinToString(",") { i -> flight(start = base + i * 1000L) }
        c.withToken(t).post("/session/sync", """{"sessions":[$all]}""")

        val p0 = c.withToken(t).get("/session/history?page=0&size=10")
        assertThat(c.str(p0, "total")).isEqualTo("25")
        assertThat(c.str(p0, "hasMore")).isEqualTo("true")

        val p2 = c.withToken(t).get("/session/history?page=2&size=10")
        assertThat(c.str(p2, "hasMore")).isEqualTo("false")   // 第 3 页只有 5 条

        val p3 = c.withToken(t).get("/session/history?page=3&size=10")
        assertThat(c.element(p3, "sessions").toString()).isEqualTo("[]")
    }

    @Test
    fun `历史按时间倒序`() = serverTest("fl10") { c ->
        val t = registerAndLogin(c, "f10@pf.dev")
        val now = System.currentTimeMillis()
        val old = flight(start = now - 7_200_000L)      // 2 小时前
        val recent = flight(start = now - 60_000L)      // 1 分钟前
        c.withToken(t).post("/session/sync", """{"sessions":[$old,$recent]}""")

        val body = c.withToken(t).get("/session/history").bodyAsText()
        val idxRecent = body.indexOf("\"startTimeMillis\":${now - 60_000L}")
        val idxOld = body.indexOf("\"startTimeMillis\":${now - 7_200_000L}")
        assertThat(idxRecent).isLessThan(idxOld)
    }

    @Test
    fun `删除自己的记录成功`() = serverTest("fl11") { c ->
        val t = registerAndLogin(c, "f11@pf.dev")
        val id = UUID.randomUUID().toString()
        c.withToken(t).post("/session/sync", """{"sessions":[${flight(id = id)}]}""")

        val del = c.withToken(t).delete("/session/$id")
        assertThat(del.status.value).isEqualTo(200)
        assertThat(c.str(c.withToken(t).get("/session/history"), "total")).isEqualTo("0")
    }

    @Test
    fun `不能删别人的记录（IDOR 防护）`() = serverTest("fl12") { c ->
        val owner = registerAndLogin(c, "owner@pf.dev")
        val attacker = registerAndLogin(c, "atk@pf.dev")
        val id = UUID.randomUUID().toString()
        c.withToken(owner).post("/session/sync", """{"sessions":[${flight(id = id)}]}""")

        val r = c.withToken(attacker).delete("/session/$id")
        assertThat(r.status.value).isEqualTo(404)
        // 原主人的记录还在
        assertThat(c.str(c.withToken(owner).get("/session/history"), "total")).isEqualTo("1")
    }

    @Test
    fun `start 返回的距离也是按时长算的`() = serverTest("fl13") { c ->
        val t = registerAndLogin(c, "f13@pf.dev")
        val r = c.withToken(t).post(
            "/start",
            json(
                "durationMinutes" to 30,
                "departureAirportCode" to "PEK",
                "destinationAirportCode" to "NRT",
            ),
        )
        assertThat(r.status.value).isEqualTo(200)
        assertThat(c.str(r, "distanceKm")).isEqualTo("400.0")   // 30/60*800
    }

    @Test
    fun `同步后用户累计统计被更新`() = serverTest("fl14") { c ->
        val t = registerAndLogin(c, "f14@pf.dev")
        val f = flight(duration = 60, actual = 60)
        c.withToken(t).post("/session/sync", """{"sessions":[$f]}""")

        val me = c.withToken(t).get("/user/me")
        assertThat(c.str(me, "totalFocusedMinutes")).isEqualTo("60")
    }

    @Test
    fun `未登录不能访问同步与历史`() = serverTest("fl15") { c ->
        assertThat(c.withToken(null).post("/session/sync", """{"sessions":[]}""").status.value).isEqualTo(401)
        assertThat(c.withToken(null).get("/session/history").status.value).isEqualTo(401)
    }
}
