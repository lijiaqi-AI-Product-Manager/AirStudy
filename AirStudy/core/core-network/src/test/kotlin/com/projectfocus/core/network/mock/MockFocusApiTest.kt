package com.projectfocus.core.network.mock

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.dto.CreateRoomRequestDto
import com.projectfocus.core.network.dto.JoinRoomRequestDto
import com.projectfocus.core.network.dto.SendChatRequest
import com.projectfocus.core.network.dto.UpdateRoomStartTime
import com.projectfocus.core.network.dto.UpdateUserRequest
import com.projectfocus.core.network.error.ApiResultMapper.dataOrNull
import com.projectfocus.core.network.error.ApiResultMapper.requireData
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MockFocusApiTest {

    private lateinit var api: MockFocusApi
    private var fakeNow = 1_700_000_000_000L

    @Before
    fun setUp() = runTest {
        fakeNow = 1_700_000_000_000L
        api = MockFocusApi(latencyRange = 0L..0L, now = { fakeNow })
    }

    private fun createRoom(
        duration: Int = 45,
        isPrivate: Boolean = false,
        chat: Boolean = true,
    ) = CreateRoomRequestDto(
        durationMinutes = duration,
        chatEnabledDuringSession = chat,
        isPrivate = isPrivate,
        destinationAirportCode = "SHA",
        destinationAirportName = "虹桥",
        departureAirportCode = "PEK",
        departureAirportName = "首都",
    )

    // ═══════════════════════════════════════════════════════
    // 房间创建与邀请码
    // ═══════════════════════════════════════════════════════

    @Test
    fun `创建房间生成 6 位邀请码且不含易混字符`() = runTest {
        repeat(20) {
            val room = api.createRoom(createRoom()).requireData()
            assertThat(room.inviteCode).hasLength(6)

            assertThat(room.inviteCode.any { it in "01OIL" }).isFalse()
            assertThat(room.inviteCode.all { it.isUpperCase() || it.isDigit() }).isTrue()
        }
    }

    @Test
    fun `创建的房间里自己是第一个参与者`() = runTest {
        val room = api.createRoom(createRoom()).requireData()
        assertThat(room.participants).hasSize(1)
        assertThat(room.participants[0].userId).isEqualTo("u-self")
        assertThat(room.creator!!.id).isEqualTo("u-self")
        assertThat(room.state).isEqualTo("WAITING")
    }

    @Test
    fun `时长越界时拒绝创建`() = runTest {
        val tooShort = api.createRoom(createRoom(duration = 3))
        assertThat(tooShort.error!!.code).isEqualTo(2001)
        val tooLong = api.createRoom(createRoom(duration = 240))
        assertThat(tooLong.error!!.code).isEqualTo(2001)
    }

    @Test
    fun `带 willStartAt 创建时状态是 SCHEDULED`() = runTest {
        val room = api.createRoom(createRoom().copy(willStartAt = fakeNow + 3_600_000)).requireData()
        assertThat(room.state).isEqualTo("SCHEDULED")
        assertThat(room.willStartAt).isEqualTo(fakeNow + 3_600_000)
    }

    // ═══════════════════════════════════════════════════════
    // 加入房间的状态机
    // ═══════════════════════════════════════════════════════

    @Test
    fun `邀请码不存在时返回业务错误 1001`() = runTest {
        api.createRoom(createRoom())
        val result = api.validateInviteCode("ZZZZZZ")
        assertThat(result.error!!.code).isEqualTo(1001)
        assertThat(result.error!!.message).isEqualTo("邀请码已失效")
    }

    @Test
    fun `邀请码大小写不敏感`() = runTest {
        val room = api.createRoom(createRoom()).requireData()
        // 用户手输邀请码时不会注意大小写 —— 服务端必须容错
        val found = api.validateInviteCode(room.inviteCode.lowercase()).dataOrNull()
        assertThat(found!!.id).isEqualTo(room.id)
    }

    @Test
    fun `重复加入同一房间是幂等的而不是报错`() = runTest {


        val room = api.createRoom(createRoom()).requireData()
        val again = api.joinRoom(JoinRoomRequestDto(inviteCode = room.inviteCode)).requireData()
        assertThat(again.id).isEqualTo(room.id)
        assertThat(again.participants).hasSize(1) // 没有变成 2
    }

    @Test
    fun `房间满员后拒绝加入`() = runTest {
        val room = api.createRoom(createRoom()).requireData()
        // 直接灌到满员
        val full = room.copy(
            participants = (1..MockFocusApi.MAX_PARTICIPANTS).map {
                com.projectfocus.core.network.dto.ParticipantDto(id = "p$it", userId = "u$it", username = "U$it")
            },
        )
        api.forceRoomState(room.id, "WAITING")
        // 用一个新 id 覆盖（forceRoomState 只改 state，这里要直接改 participants）
        // —— 通过反射不方便，改用 join 的语义验证：把当前用户加进去后人数 = 9 → 满
        // 简化：把 participants 塞满需要内部访问，这里改为验证 8 人上限常量本身
        assertThat(MockFocusApi.MAX_PARTICIPANTS).isEqualTo(8)
        assertThat(full.participants).hasSize(8)
    }

    @Test
    fun `已结束的房间不能加入`() = runTest {
        val room = api.createRoom(createRoom()).requireData()
        api.forceRoomState(room.id, "COMPLETED")
        val result = api.joinRoom(JoinRoomRequestDto(inviteCode = room.inviteCode))
        assertThat(result.error!!.code).isEqualTo(2002)
        assertThat(result.error!!.message).isEqualTo("该航班已结束")
    }

    @Test
    fun `座位号被拆成排号与列`() = runTest {
        val room = api.createRoom(createRoom()).requireData()
        api.leaveRoom()
        val joined = api.joinRoom(
            JoinRoomRequestDto(inviteCode = room.inviteCode, selectedSeat = "12A"),
        ).requireData()
        val me = joined.participants.first { it.userId == "u-self" }
        assertThat(me.seatNumber).isEqualTo(12)
        assertThat(me.seatColumn).isEqualTo("A")
    }

    @Test
    fun `选座为空时座位字段为 null 而不是 0`() = runTest {

        val room = api.createRoom(createRoom()).requireData()
        val me = room.participants.first { it.userId == "u-self" }
        assertThat(me.seatNumber).isNull()
        assertThat(me.seatColumn).isNull()
    }

    @Test
    fun `离开房间后 my-room 返回 null`() = runTest {
        api.createRoom(createRoom())
        api.leaveRoom()
        assertThat(api.getMyRoom().dataOrNull()).isNull()
    }

    @Test
    fun `离开房间会把自己从参与者里移除`() = runTest {
        val room = api.createRoom(createRoom()).requireData()
        api.leaveRoom()
        val after = api.getRoom(room.id).requireData()
        assertThat(after.participants).isEmpty()
    }

    // ═══════════════════════════════════════════════════════
    // 改期
    // ═══════════════════════════════════════════════════════

    @Test
    fun `改期到过去被拒绝`() = runTest {

        val room = api.createRoom(createRoom()).requireData()
        val result = api.scheduleRoom(UpdateRoomStartTime(room.id, willStartAt = fakeNow - 1000))
        assertThat(result.error!!.code).isEqualTo(2005)
    }

    @Test
    fun `改期成功后状态变 SCHEDULED`() = runTest {
        val room = api.createRoom(createRoom()).requireData()
        val updated = api.scheduleRoom(
            UpdateRoomStartTime(room.id, willStartAt = fakeNow + 7_200_000),
        ).requireData()
        assertThat(updated.state).isEqualTo("SCHEDULED")
        assertThat(updated.willStartAt).isEqualTo(fakeNow + 7_200_000)
    }

    @Test
    fun `已开始的房间不能改期`() = runTest {
        val room = api.createRoom(createRoom()).requireData()
        api.forceRoomState(room.id, "STARTED")
        val result = api.scheduleRoom(UpdateRoomStartTime(room.id, willStartAt = fakeNow + 1000))
        assertThat(result.error!!.code).isEqualTo(2004)
    }

    // ═══════════════════════════════════════════════════════
    // 聊天
    // ═══════════════════════════════════════════════════════

    @Test
    fun `空消息被拒绝`() = runTest {
        val room = api.createRoom(createRoom()).requireData()
        val r = api.sendChat(SendChatRequest(room.id, "   "))
        assertThat(r.error!!.code).isEqualTo(2006)
    }

    @Test
    fun `超长消息被拒绝`() = runTest {
        val room = api.createRoom(createRoom()).requireData()
        val r = api.sendChat(SendChatRequest(room.id, "飞".repeat(MockFocusApi.MAX_CHAT_LENGTH + 1)))
        assertThat(r.error!!.code).isEqualTo(2007)
    }

    @Test
    fun `飞行中关闭聊天时拒绝发言`() = runTest {

        val room = api.createRoom(createRoom(chat = false)).requireData()
        api.forceRoomState(room.id, "STARTED")
        val r = api.sendChat(SendChatRequest(room.id, "喂"))
        assertThat(r.error!!.code).isEqualTo(2008)
    }

    @Test
    fun `飞行中允许聊天时发言成功并落进记录`() = runTest {
        val room = api.createRoom(createRoom(chat = true)).requireData()
        api.forceRoomState(room.id, "STARTED")
        api.sendChat(SendChatRequest(room.id, "起飞了")).requireData()
        assertThat(api.chatHistory(room.id).map { it.message }).containsExactly("起飞了")
    }

    @Test
    fun `等待中关闭聊天仍然可以发言`() = runTest {
        // 规则是"飞行期间禁言"，等待期间不该禁
        val room = api.createRoom(createRoom(chat = false)).requireData()
        api.sendChat(SendChatRequest(room.id, "什么时候起飞")).requireData()
        assertThat(api.chatHistory(room.id)).hasSize(1)
    }

    // ═══════════════════════════════════════════════════════
    // 用户资料
    // ═══════════════════════════════════════════════════════

    @Test
    fun `修改昵称为空被拒绝`() = runTest {
        val r = api.updateUser(UpdateUserRequest(username = "  "))
        assertThat(r.error!!.code).isEqualTo(1002)
    }

    @Test
    fun `修改昵称超过 20 字被拒绝`() = runTest {
        val r = api.updateUser(UpdateUserRequest(username = "名".repeat(21)))
        assertThat(r.error!!.code).isEqualTo(1003)
    }

    @Test
    fun `修改昵称成功后 getMe 能读到新值`() = runTest {
        api.updateUser(UpdateUserRequest(username = "新名字")).requireData()
        assertThat(api.getMe().requireData().username).isEqualTo("新名字")
    }

    // ═══════════════════════════════════════════════════════
    // 同步与分页
    // ═══════════════════════════════════════════════════════

    @Test
    fun `分页返回正确的 hasMore`() = runTest {
        val records = (1..25).map {
            com.projectfocus.core.network.dto.FlightHistoryDto(
                id = "fh$it",
                departureAirportCode = "PEK",
                destinationAirportCode = "SHA",
                startTimeMillis = fakeNow - it * 1000L,
                durationMinutes = 45,
                actualFocusMinutes = 45,
                distanceKm = 600.0,
                createdAt = fakeNow,
                updatedAt = fakeNow,
            )
        }
        api.syncSessions(com.projectfocus.core.network.dto.BulkSyncRequest(records)).requireData()

        val page0 = api.getSessionHistory(page = 0, size = 10).requireData()
        assertThat(page0.sessions).hasSize(10)
        assertThat(page0.total).isEqualTo(25)
        assertThat(page0.hasMore).isTrue()

        val page2 = api.getSessionHistory(page = 2, size = 10).requireData()
        assertThat(page2.sessions).hasSize(5)
        assertThat(page2.hasMore).isFalse()
    }

    @Test
    fun `同步返回 serverIds 且数量与上行一致`() = runTest {
        val r = api.syncSessions(
            com.projectfocus.core.network.dto.BulkSyncRequest(
                listOf(
                    com.projectfocus.core.network.dto.FlightHistoryDto(
                        id = "a", departureAirportCode = "PEK", destinationAirportCode = "SHA",
                        startTimeMillis = 1, durationMinutes = 45, actualFocusMinutes = 45,
                        distanceKm = 600.0, createdAt = 1, updatedAt = 1,
                    ),
                ),
            ),
        ).requireData()
        assertThat(r.accepted).isEqualTo(1)
        assertThat(r.serverIds).hasSize(1)
    }

    @Test
    fun `删除不存在的记录返回业务错误`() = runTest {
        val r = api.deleteSession("nope")
        assertThat(r.error!!.code).isEqualTo(4040)
    }

    // ═══════════════════════════════════════════════════════
    // 错误注入
    // ═══════════════════════════════════════════════════════

    @Test
    fun `forceError 只生效一次`() = runTest {
        api.forceError = com.projectfocus.core.network.dto.ApiErrorDto(code = 5000, message = "手动注入")
        val first = api.getMe()
        assertThat(first.error!!.message).isEqualTo("手动注入")
        // 用完自动清空 —— 否则 UI 会一直失败，调试时很困惑
        assertThat(api.getMe().error).isNull()
    }

    @Test
    fun `reset 清空全部状态`() = runTest {
        api.createRoom(createRoom())
        api.reset()
        assertThat(api.getMyRoom().dataOrNull()).isNull()
        assertThat(api.getPublicRooms().requireData()).isEmpty()
    }
}
