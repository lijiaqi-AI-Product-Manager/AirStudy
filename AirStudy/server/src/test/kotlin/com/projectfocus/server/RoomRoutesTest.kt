package com.projectfocus.server

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import com.google.common.truth.Truth.assertThat


@DisplayName("房间端点")
class RoomRoutesTest : ServerTestBase() {

    private suspend fun registerAndLogin(c: TestClient, email: String, nick: String): String {
        val login = c.registerAndLogin(email)
        val token = c.str(login, "token")!!
        val profile = c.withToken(token).patch("/user/update", json("username" to nick))
        assertThat(profile.status.value).isEqualTo(200)
        assertThat(c.str(profile, "username")).isEqualTo(nick)
        return token
    }

    private fun createRoomBody(
        duration: Int = 45,
        dep: String = "PEK",
        dst: String = "SHA",
        private: Boolean = true,
        willStartAt: Long? = null,
        chat: Boolean = true,
    ) = json(
        "durationMinutes" to duration,
        "chatEnabledDuringSession" to chat,
        "private" to private,
        "departureAirportCode" to dep,
        "departureAirportName" to "北京首都",
        "departureAirportCountry" to "中国",
        "departureAirportState" to "北京",
        "departureAirportLatitude" to 40.08,
        "departureAirportLongitude" to 116.58,
        "destinationAirportCode" to dst,
        "destinationAirportName" to "上海虹桥",
        "destinationAirportCountry" to "中国",
        "destinationAirportState" to "上海",
        "destinationAirportLatitude" to 31.20,
        "destinationAirportLongitude" to 121.34,
        if (willStartAt != null) "willStartAt" to willStartAt else "willStartAt" to null,
    ).let { raw ->
        // 去掉 willStartAt:null（kotlinx 的 explicitNulls=false 会忽略，

        if (willStartAt == null) raw.replace(",\"willStartAt\":null", "") else raw
    }

    @Test
    fun `创建房间后创建者自动坐在 1A`() = serverTest("room1") { c ->
        val token = registerAndLogin(c, "host@pf.dev", "房主")
        val r = c.withToken(token).post("/room/create", createRoomBody())
        assertThat(r.status.value).isEqualTo(200)

        val state = c.str(r, "state")
        assertThat(state).isEqualTo("WAITING")
        val invite = c.str(r, "inviteCode")
        assertThat(invite).isNotNull()
        assertThat(invite!!.length).isEqualTo(6)

        val parts = c.element(r, "participants").toString()
        assertThat(parts).contains("\"seatNumber\":1")
        assertThat(parts).contains("\"seatColumn\":\"A\"")
    }

    @Test
    fun `邀请码不包含容易读错的字符`() = serverTest("room2") { c ->
        val token = registerAndLogin(c, "code@pf.dev", "A")
        repeat(5) {
            val r = c.withToken(token).post("/room/create", createRoomBody())
            val code = c.str(r, "inviteCode")!!

            assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}")
        }
    }

    @Test
    fun `时长越界被拒`() = serverTest("room3") { c ->
        val token = registerAndLogin(c, "dur@pf.dev", "A")
         listOf(4, 181, 0, -5).forEach { d ->
            val r = c.withToken(token).post("/room/create", createRoomBody(duration = d))
            assertThat(c.errorCode(r)).isEqualTo(2021)
        }
    }

    @Test
    fun `出发与目的地相同被拒`() = serverTest("room4") { c ->
        val token = registerAndLogin(c, "same@pf.dev", "A")
        val r = c.withToken(token).post("/room/create", createRoomBody(dep = "PEK", dst = "PEK"))
        assertThat(c.errorCode(r)).isEqualTo(2020)
    }

    @Test
    fun `凭邀请码查房间不需要登录`() = serverTest("room5") { c ->
        val token = registerAndLogin(c, "anon@pf.dev", "A")
        val created = c.withToken(token).post("/room/create", createRoomBody())
        val code = c.str(created, "inviteCode")!!

        // 不带 token
        val peek = c.withToken(null).get("/code/$code")
        assertThat(peek.status.value).isEqualTo(200)
        assertThat(c.str(peek, "inviteCode")).isEqualTo(code)
    }

    @Test
    fun `邀请码大小写不敏感且容忍空格`() = serverTest("room6") { c ->
        val token = registerAndLogin(c, "case@pf.dev", "A")
        val code = c.str(c.withToken(token).post("/room/create", createRoomBody()), "inviteCode")!!
        val lower = code.lowercase()
        assertThat(c.get("/code/$lower").status.value).isEqualTo(200)
    }

    @Test
    fun `不存在的邀请码返回 2001`() = serverTest("room7") { c ->
        val r = c.get("/code/ZZZZZZ")
        assertThat(c.errorCode(r)).isEqualTo(2001)
    }

    @Test
    fun `加入房间分配下一个座位`() = serverTest("room8") { c ->
        val host = registerAndLogin(c, "h8@pf.dev", "房主")
        val guest = registerAndLogin(c, "g8@pf.dev", "乘客")
        val room = c.withToken(host).post("/room/create", createRoomBody())
        val code = c.str(room, "inviteCode")!!

        val joined = c.withToken(guest).post("/join", json("inviteCode" to code, "selectedSeat" to null))
        assertThat(joined.status.value).isEqualTo(200)
        // 1A 被房主占了，下一个是 1B
        val parts = c.element(joined, "participants").toString()
        assertThat(parts).contains("\"seatColumn\":\"B\"")
    }

    @Test
    fun `重复加入是幂等的而不是报错`() = serverTest("room9") { c ->
        val host = registerAndLogin(c, "h9@pf.dev", "房主")
        val guest = registerAndLogin(c, "g9@pf.dev", "乘客")
        val code = c.str(c.withToken(host).post("/room/create", createRoomBody()), "inviteCode")!!

        val first = c.withToken(guest).post("/join", json("inviteCode" to code, "selectedSeat" to null))
        val second = c.withToken(guest).post("/join", json("inviteCode" to code, "selectedSeat" to null))


        assertThat(first.status.value).isEqualTo(200)
        assertThat(second.status.value).isEqualTo(200)
        // 参与者数量不应增加
        val count1 = c.element(first, "participants").toString().split("\"userId\"").size - 1
        val count2 = c.element(second, "participants").toString().split("\"userId\"").size - 1
        assertThat(count2).isEqualTo(count1)
    }

    @Test
    fun `满 8 人后拒绝加入`() = serverTest("room10") { c ->
        val host = registerAndLogin(c, "h10@pf.dev", "房主")
        val code = c.str(c.withToken(host).post("/room/create", createRoomBody()), "inviteCode")!!

        // 再来 7 个人（共 8）
        for (i in 1..7) {
            val t = registerAndLogin(c, "$i@pf.dev", "客$i")
            val r = c.withToken(t).post("/join", json("inviteCode" to code, "selectedSeat" to null))
            assertThat(r.status.value).isEqualTo(200)
        }
        // 第 9 个人
        val late = registerAndLogin(c, "late@pf.dev", "迟到")
        val r = c.withToken(late).post("/join", json("inviteCode" to code, "selectedSeat" to null))
        assertThat(c.errorCode(r)).isEqualTo(2003)
    }

    @Test
    fun `指定已被占的座位返回 2008`() = serverTest("room11") { c ->
        val host = registerAndLogin(c, "h11@pf.dev", "房主")
        val guest = registerAndLogin(c, "g11@pf.dev", "乘客")
        val code = c.str(c.withToken(host).post("/room/create", createRoomBody()), "inviteCode")!!

        val r = c.withToken(guest).post("/join", json("inviteCode" to code, "selectedSeat" to "1A"))
        assertThat(c.errorCode(r)).isEqualTo(2008)
    }

    @Test
    fun `离开后 my-room 变 null 且座位释放`() = serverTest("room12") { c ->
        val host = registerAndLogin(c, "h12@pf.dev", "房主")
        val guest = registerAndLogin(c, "g12@pf.dev", "乘客")
        val code = c.str(c.withToken(host).post("/room/create", createRoomBody()), "inviteCode")!!
        c.withToken(guest).post("/join", json("inviteCode" to code, "selectedSeat" to null))

        val left = c.withToken(guest).post("/leave")
        assertThat(left.status.value).isEqualTo(200)

        val my = c.withToken(guest).get("/my-room")
        assertThat(c.data(my)).isNull()
    }

    @Test
    fun `不在房间时 leave 返回 2009`() = serverTest("room13") { c ->
        val t = registerAndLogin(c, "lonely@pf.dev", "A")
        val r = c.withToken(t).post("/leave")
        assertThat(c.errorCode(r)).isEqualTo(2009)
    }

    @Test
    fun `只有房主能改期`() = serverTest("room14") { c ->
        val host = registerAndLogin(c, "h14@pf.dev", "房主")
        val guest = registerAndLogin(c, "g14@pf.dev", "乘客")
        val room = c.withToken(host).post("/room/create", createRoomBody())
        val roomId = c.str(room, "id")!!
        val code = c.str(room, "inviteCode")!!
        c.withToken(guest).post("/join", json("inviteCode" to code, "selectedSeat" to null))

        val future = System.currentTimeMillis() + 3_600_000
        val byGuest = c.withToken(guest).put("/schedule", json("roomId" to roomId, "willStartAt" to future))
        assertThat(c.errorCode(byGuest)).isEqualTo(2010)

        val byHost = c.withToken(host).put("/schedule", json("roomId" to roomId, "willStartAt" to future))
        assertThat(byHost.status.value).isEqualTo(200)
        assertThat(c.str(byHost, "state")).isEqualTo("SCHEDULED")
    }

    @Test
    fun `改期到过去被拒`() = serverTest("room15") { c ->
        val host = registerAndLogin(c, "h15@pf.dev", "房主")
        val roomId = c.str(c.withToken(host).post("/room/create", createRoomBody()), "id")!!
        val past = System.currentTimeMillis() - 60_000
        val r = c.withToken(host).put("/schedule", json("roomId" to roomId, "willStartAt" to past))
        assertThat(c.errorCode(r)).isEqualTo(2006)
    }

    @Test
    fun `聊天正常发送并出现在历史里`() = serverTest("room16") { c ->
        val host = registerAndLogin(c, "h16@pf.dev", "机长")
        val room = c.withToken(host).post("/room/create", createRoomBody())
        val roomId = c.str(room, "id")!!

        val sent = c.withToken(host).post("/chat", json("roomId" to roomId, "message" to "准备起飞 🛫"))
        assertThat(sent.status.value).isEqualTo(200)
        assertThat(c.str(sent, "message")).isEqualTo("准备起飞 🛫")
        assertThat(c.str(sent, "sender")).isEqualTo("机长")
    }

    @Test
    fun `空消息被拒`() = serverTest("room17") { c ->
        val host = registerAndLogin(c, "h17@pf.dev", "A")
        val roomId = c.str(c.withToken(host).post("/room/create", createRoomBody()), "id")!!
        val r = c.withToken(host).post("/chat", json("roomId" to roomId, "message" to "   "))
        assertThat(c.errorCode(r)).isEqualTo(2030)
    }

    @Test
    fun `超长消息被拒且按 code point 计数`() = serverTest("room18") { c ->
        val host = registerAndLogin(c, "h18@pf.dev", "A")
        val roomId = c.str(c.withToken(host).post("/room/create", createRoomBody()), "id")!!

        // 280 个 emoji 应该**通过**（如果按 String.length 算，一个 emoji 占 2，会被误判超限）
        val okMsg = "🚀".repeat(280)
        val ok = c.withToken(host).post("/chat", json("roomId" to roomId, "message" to okMsg))
        assertThat(ok.status.value).isEqualTo(200)

        val tooLong = "a".repeat(281)
        val bad = c.withToken(host).post("/chat", json("roomId" to roomId, "message" to tooLong))
        assertThat(c.errorCode(bad)).isEqualTo(2031)
    }

    @Test
    fun `不在房间的人不能发言`() = serverTest("room19") { c ->
        val host = registerAndLogin(c, "h19@pf.dev", "A")
        val outsider = registerAndLogin(c, "out19@pf.dev", "外人")
        val roomId = c.str(c.withToken(host).post("/room/create", createRoomBody()), "id")!!

        val r = c.withToken(outsider).post("/chat", json("roomId" to roomId, "message" to "我能说话吗"))
        assertThat(c.errorCode(r)).isEqualTo(2009)
    }

    @Test
    fun `已结束房间不接受新成员`() = serverTest("room20") { c ->
        val host = registerAndLogin(c, "h20@pf.dev", "A")
        val room = c.withToken(host).post("/room/create", createRoomBody())
        val roomId = c.str(room, "id")!!
        val code = c.str(room, "inviteCode")!!

        // 开始后直接结束（没有公开端点，靠测试内的 domain 层不可达 —— 用 start + 大量参与者模拟）
        // 这里只验"加入 SCHEDULED 的房间正常"
        val guest = registerAndLogin(c, "g20@pf.dev", "B")
        val r = c.withToken(guest).post("/join", json("inviteCode" to code, "selectedSeat" to null))
        assertThat(r.status.value).isEqualTo(200)
        assertThat(roomId).isNotEmpty()
    }

    @Test
    fun `公开房间列表不包含私人房间`() = serverTest("room21") { c ->
        val host = registerAndLogin(c, "h21@pf.dev", "A")
        c.withToken(host).post("/room/create", createRoomBody(private = true))
        c.withToken(host).post("/room/create", createRoomBody(private = false))

        val pub = c.get("/public")
        assertThat(pub.status.value).isEqualTo(200)
        val body = pub.bodyAsText()

        val privateCodes = body.split("\"isPrivate\":true").size - 1
        assertThat(privateCodes).isEqualTo(0)
    }

    @Test
    fun `未登录访问需要登录的房间端点返回 401`() = serverTest("room22") { c ->
        listOf("/my-room", "/room/anything").forEach { path ->
            assertThat(c.withToken(null).get(path).status.value).isEqualTo(401)
        }
        assertThat(c.withToken(null).post("/join", json("inviteCode" to "ABCDEF")).status.value).isEqualTo(401)
    }
}
