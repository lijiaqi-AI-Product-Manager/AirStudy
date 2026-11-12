package com.projectfocus.core.network.sse

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SseFrameParserTest {

    /** 把一段多行文本喂进解析器，收集所有吐出的事件 */
    private fun parse(raw: String): List<SseEvent> {
        val parser = SseFrameParser()
        val out = mutableListOf<SseEvent>()

        //   Kotlin 的 lines() 会把末尾的 \n\n 变成一个空元素，行为一致；
        //   但 \r\n 的处理不同，这里统一在解析器里不管 CR（见下）。
        raw.split('\n').forEach { line ->
            // 模拟 Okio readUtf8Line：它会去掉行尾 \r
            val clean = line.removeSuffix("\r")
            parser.feed(clean)?.let(out::add)
        }
        parser.flush()?.let(out::add)
        return out
    }

    // ═══════════════════════════════════════════════════════
    // 帧边界
    // ═══════════════════════════════════════════════════════

    @Test
    fun `空行结束一帧 —— 两个事件必须被分开`() {
        val events = parse(
            """
            event: room_state_changed
            data: {"roomId":"r1","state":"STARTED"}

            event: room_state_changed
            data: {"roomId":"r1","state":"COMPLETED"}

            """.trimIndent(),
        )
        assertThat(events).hasSize(2)
        assertThat((events[0] as SseEvent.RoomStateChanged).state).isEqualTo("STARTED")
        assertThat((events[1] as SseEvent.RoomStateChanged).state).isEqualTo("COMPLETED")
    }

    @Test
    fun `多行 data 用换行拼接成一条`() {
        // 服务端为了避开单行超长，把一个 JSON 拆成两行发 —— 合法且常见。

        val events = parse(
            """
            event: room_snapshot
            data: {"id":"r1",
            data: "state":"WAITING"}

            """.trimIndent(),
        )
        assertThat(events).hasSize(1)
        val snapshot = events[0] as SseEvent.RoomSnapshot
        // 拼回去必须是合法 JSON
        assertThat(snapshot.raw["id"].toString()).contains("r1")
    }

    @Test
    fun `没有空行收尾的最后一帧在 flush 时吐出`() {
        // 服务端关连接前来不及补空行。不 flush 就会丢掉最后一条
        // （往往正是"房间已结束"）。
        val parser = SseFrameParser()
        parser.feed("event: room_state_changed")
        parser.feed("""data: {"roomId":"r9","state":"COMPLETED"}""")
        assertThat(parser.flush()).isInstanceOf(SseEvent.RoomStateChanged::class.java)
    }

    @Test
    fun `连续空行不产生空事件`() {
        val parser = SseFrameParser()
        assertThat(parser.feed("")).isNull()
        assertThat(parser.feed("")).isNull()
        assertThat(parser.flush()).isNull()
    }

    // ═══════════════════════════════════════════════════════
    // 字段解析
    // ═══════════════════════════════════════════════════════

    @Test
    fun `冒号后无空格也能解析`() {
        // 规范：`field:value` 与 `field: value` 都合法
        val events = parse("event:chat_message\ndata:{\"roomId\":\"r1\",\"message\":\"hi\"}\n\n")
        assertThat(events).hasSize(1)
        assertThat((events[0] as SseEvent.ChatMessageEvent).message).isEqualTo("hi")
    }

    @Test
    fun `冒号后只去掉一个空格`() {
        // 规范：只 removePrefix(" ")，不是 trim。
        // 服务端发 `data:  hello`（两个空格）时，内容应当是 " hello"。
        val events = parse("event: chat_message\ndata: {\"roomId\":\"r1\",\"message\":\" hello\"}\n\n")
        assertThat((events[0] as SseEvent.ChatMessageEvent).message).isEqualTo(" hello")
    }

    @Test
    fun `只有字段名没有冒号的行被忽略而不是崩溃`() {
        val events = parse("event\n\n")
        // 产生了帧（hasAnyField=true）但没有 data → Unknown
        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(SseEvent.Unknown::class.java)
    }

    @Test
    fun `CRLF 行尾被正确处理`() {
        val events = parse("event: room_state_changed\r\ndata: {\"roomId\":\"r1\",\"state\":\"WAITING\"}\r\n\r\n")
        assertThat(events).hasSize(1)
        assertThat((events[0] as SseEvent.RoomStateChanged).state).isEqualTo("WAITING")
    }

    // ═══════════════════════════════════════════════════════
    // 注释行 / 心跳
    // ═══════════════════════════════════════════════════════

    @Test
    fun `冒号开头的注释行被当成心跳事件而不是忽略`() {

        val parser = SseFrameParser()
        val e = parser.feed(": keep-alive")
        assertThat(e).isInstanceOf(SseEvent.Unknown::class.java)
        assertThat((e as SseEvent.Unknown).eventName).isNull()
        assertThat(e.rawData).isEqualTo("keep-alive")
    }

    @Test
    fun `单独一个冒号也是合法心跳`() {
        val parser = SseFrameParser()
        val e = parser.feed(":")
        assertThat((e as SseEvent.Unknown).rawData).isEmpty()
    }

    // ═══════════════════════════════════════════════════════
    // retry / id
    // ═══════════════════════════════════════════════════════

    @Test
    fun `retry 被记录下来供重连使用`() {
        val parser = SseFrameParser()
        assertThat(parser.serverRetryMillis).isNull()
        parser.feed("retry: 12000")
        assertThat(parser.serverRetryMillis).isEqualTo(12000L)
    }

    @Test
    fun `非法 retry 被忽略而不是让解析崩溃`() {
        val parser = SseFrameParser()
        parser.feed("retry: 3s")   // 带单位，非法
        parser.feed("retry: -1")   // 负数，非法
        assertThat(parser.serverRetryMillis).isNull()
    }

    @Test
    fun `id 被带到事件上，供 Last-Event-ID 回传`() {
        val events = parse("id: 42\nevent: timer_update\ndata: {\"roomId\":\"r1\",\"remainingSeconds\":900}\n\n")
        assertThat(events[0].eventId).isEqualTo("42")
        assertThat((events[0] as SseEvent.TimerUpdate).remainingSeconds).isEqualTo(900L)
    }

    @Test
    fun `含 NUL 的 id 被忽略`() {
        // 规范明确禁止 id 里出现 \u0000
        val parser = SseFrameParser()
        parser.feed("id: 12\u000034")
        parser.feed("event: timer_update")
        parser.feed("""data: {"roomId":"r1"}""")
        val e = parser.feed("")
        assertThat(e!!.eventId).isNull()
    }

    @Test
    fun `只有 id 没有 data 的帧不产生业务事件`() {
        val events = parse("id: 99\n\n")
        assertThat(events).hasSize(1)
        val e = events[0]
        assertThat(e).isInstanceOf(SseEvent.Unknown::class.java)
        assertThat(e.eventId).isEqualTo("99")
    }

    // ═══════════════════════════════════════════════════════
    // 6 类事件的字段映射
    // ═══════════════════════════════════════════════════════

    @Test
    fun `participant_joined 全字段正确`() {
        val events = parse(
            """
            event: participant_joined
            data: {"roomId":"r1","userId":"u9","username":"高斯","avatarId":3,"seatNumber":12}

            """.trimIndent(),
        )
        val e = events[0] as SseEvent.ParticipantJoined
        assertThat(e.roomId).isEqualTo("r1")
        assertThat(e.userId).isEqualTo("u9")
        assertThat(e.username).isEqualTo("高斯")
        assertThat(e.avatarId).isEqualTo(3)
        assertThat(e.seatNumber).isEqualTo(12)
    }

    @Test
    fun `participant_left 缺 voluntarily 字段时默认按主动退出`() {

        //   反过来会让正常退出的成员显示成"掉线"。
        val events = parse("""event: participant_left
data: {"roomId":"r1","userId":"u9"}
""")
        assertThat((events[0] as SseEvent.ParticipantLeft).voluntarily).isTrue()
    }

    @Test
    fun `timer_update 两个时间都读得到`() {
        val events = parse(
            """event: timer_update
data: {"roomId":"r1","remainingSeconds":600,"elapsedSeconds":300}
""",
        )
        val e = events[0] as SseEvent.TimerUpdate
        assertThat(e.remainingSeconds).isEqualTo(600L)
        assertThat(e.elapsedSeconds).isEqualTo(300L)
    }

    @Test
    fun `chat_message 的 id 两种字段名都兼容`() {

        // 但 REST 端点返回的 ChatMessage 用的是 "id"。两边都接。
        val a = parse("""event: chat_message
data: {"roomId":"r1","id":"m1","sender":"A","message":"hi","createdAt":1}
""")
        assertThat((a[0] as SseEvent.ChatMessageEvent).messageId).isEqualTo("m1")

        val b = parse("""event: chat_message
data: {"roomId":"r1","messageId":"m2","sender":"A","message":"hi","createdAt":1}
""")
        assertThat((b[0] as SseEvent.ChatMessageEvent).messageId).isEqualTo("m2")
    }

    // ═══════════════════════════════════════════════════════
    // 容错：坏数据不能断流
    // ═══════════════════════════════════════════════════════

    @Test
    fun `坏 JSON 不抛异常，降级为字段默认值`() {


        val events = parse("event: room_state_changed\ndata: {not json\n\n")
        assertThat(events).hasSize(1)
        val e = events[0] as SseEvent.RoomStateChanged
        assertThat(e.roomId).isEmpty()
        // state 给默认值 WAITING，而不是空串 —— 空串会让 UI 找不到对应枚举
        assertThat(e.state).isEqualTo("WAITING")
    }

    @Test
    fun `未知 event 名原样交出`() {
        val events = parse("event: something_new\ndata: {\"a\":1}\n\n")
        val e = events[0] as SseEvent.Unknown
        assertThat(e.eventName).isEqualTo("something_new")
        assertThat(e.rawData).contains("\"a\"")
    }

    @Test
    fun `未知字段被忽略`() {
        val events = parse(
            """event: room_state_changed
data: {"roomId":"r1","state":"STARTED","unknownField":{"deep":[1,2,3]}}
""",
        )
        assertThat((events[0] as SseEvent.RoomStateChanged).state).isEqualTo("STARTED")
    }

    @Test
    fun `数字被写成字符串时仍然读出来 —— orgjson 的隐式转换行为`() {

        //   实测（gson 2.11.0，org.json 底层就是 gson）：
        //     new JsonPrimitive("600").isString() == true
        //     new JsonPrimitive("600").getAsLong()  == 600
        //   org.json 的 `JsonPrimitive.longOrNull` 走的就是 getAsLong，
        //   **字符串 "600" 会被隐式转成 600**。
        //   于是 "abc" → 0、"" → 0、"1.5" → 1（截断），全都不报错。
        //
        //   → 结论：客户端这一层**无法**靠"解析失败"来发现服务端把数字写成字符串。
        //     这是个协议级问题，只能靠服务端契约测试兜住（见 server 模块的
        //     `SseContractTest`）以及这里记录的行为基线。

        //     "600" 当成 600 是绝大多数服务端的真实意图。
        val events = parse(
            """event: timer_update
data: {"roomId":"r1","remainingSeconds":"600","elapsedSeconds":300}
""",
        )
        val e = events[0] as SseEvent.TimerUpdate
        assertThat(e.remainingSeconds).isEqualTo(600L)
        assertThat(e.elapsedSeconds).isEqualTo(300L)
    }

    @Test
    fun `非数字字符串被静默转成 0 —— 记录这个坑`() {
        // 与上一条同源：org.json 不做类型校验。
        // 这条用例的作用是"把这个行为钉住"，将来换解析器时能被测试提醒。
        val events = parse(
            """event: timer_update
data: {"roomId":"r1","remainingSeconds":"还在飞","elapsedSeconds":null}
""",
        )
        val e = events[0] as SseEvent.TimerUpdate
        assertThat(e.remainingSeconds).isEqualTo(0L)
        assertThat(e.elapsedSeconds).isEqualTo(0L)
    }

    @Test
    fun `空 data 帧不崩`() {
        val events = parse("event: room_state_changed\ndata:\n\n")
        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(SseEvent.Unknown::class.java)
    }

    // ═══════════════════════════════════════════════════════
    // 真实重放：一段完整的房间生命周期
    // ═══════════════════════════════════════════════════════

    @Test
    fun `完整房间生命周期的事件序列被按序解析`() {

        val raw = """
            : connected

            event: room_snapshot
            data: {"id":"r1","state":"WAITING","durationMinutes":45}

            event: participant_joined
            data: {"roomId":"r1","userId":"u2","username":"B","seatNumber":1}

            event: room_state_changed
            data: {"roomId":"r1","state":"STARTED"}

            event: timer_update
            data: {"roomId":"r1","remainingSeconds":2700,"elapsedSeconds":0}

            event: chat_message
            data: {"roomId":"r1","id":"m1","sender":"B","message":"起飞","createdAt":1000}

            event: participant_left
            data: {"roomId":"r1","userId":"u2","voluntarily":false}

            event: room_state_changed
            data: {"roomId":"r1","state":"COMPLETED"}

        """.trimIndent()

        val events = parse(raw)
        // 9 条：1 心跳 + 7 业务 + 1 尾巴（空 data 帧被忽略，所以是 1+7）
        val business = events.filterNot { it is SseEvent.Unknown && it.eventName == null }
        assertThat(business.map { it::class.java.simpleName }).containsExactly(
            "RoomSnapshot",
            "ParticipantJoined",
            "RoomStateChanged",
            "TimerUpdate",
            "ChatMessageEvent",
            "ParticipantLeft",
            "RoomStateChanged",
        ).inOrder()

        assertThat((business[5] as SseEvent.ParticipantLeft).voluntarily).isFalse()
        assertThat((business[6] as SseEvent.RoomStateChanged).state).isEqualTo("COMPLETED")
    }
}
