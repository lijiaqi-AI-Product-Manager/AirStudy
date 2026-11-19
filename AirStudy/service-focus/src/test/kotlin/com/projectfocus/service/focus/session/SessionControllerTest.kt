package com.projectfocus.service.focus.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test


class SessionControllerTest {

    /** 可控单调时钟 */
    private class FakeMono(var now: Long = 1_000_000L) : MonotonicSource {
        override fun elapsedRealtimeMs(): Long = now

        fun advance(ms: Long) {
            now += ms
        }
    }

    private lateinit var mono: FakeMono
    private lateinit var activeController: SessionController
    private var wallNow = 1_700_000_000_000L
    private var nextId = 100

    private fun controller(): SessionController {
        mono = FakeMono()
        return SessionController(
            monotonic = mono,
            wallClock = { wallNow },
            idGenerator = { "sess-${nextId++}" },
        ).also { activeController = it }
    }

    private val min45 = 45L * 60 * 1_000

    /**
     * 连续服务采样夹具：每过完整一秒实际请求一次 tick，余下毫秒只推进 mono。
     * 红测中的 mono.advance 刻意不使用它，用来模拟停顿和无采样区间。
     */
    private fun advance(ms: Long, acceptLastTick: Boolean = true) {
        require(ms >= 0L)
        var remaining = ms
        while (remaining >= 1_000L) {
            mono.advance(1_000L)
            remaining -= 1_000L
            if (acceptLastTick || remaining != 0L) activeController.tick()
        }
        mono.advance(remaining)
    }

    // ==================================================================
    // 基本流转
    // ==================================================================

    @Test
    fun `初始状态是 Idle`() {
        assertThat(controller().state.value).isEqualTo(SessionState.Idle)
    }

    @Test
    fun `开始飞行后进入 Running 且进度为零`() {
        val c = controller()
        assertThat(c.start(categoryId = 3, plannedDurationMs = min45)).isTrue()
        val s = c.state.value as SessionState.Running
        assertThat(s.categoryId).isEqualTo(3)
        assertThat(s.progress(mono.now)).isEqualTo(0f)
        assertThat(s.elapsedMs(mono.now)).isEqualTo(0L)
    }

    @Test
    fun `已经在飞时再次 start 被拒绝且不覆盖原会话`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        val original = c.state.value as SessionState.Running

        advance(60_000)
        val beforeDuplicateStart = c.state.value as SessionState.Running
        assertThat(c.start(categoryId = 9, plannedDurationMs = min45)).isFalse()

        val after = c.state.value as SessionState.Running
        // 已接受进度、当前采样窗口、会话身份均不能被重复开始重置。
        assertThat(after.sessionId).isEqualTo(original.sessionId)
        assertThat(after.categoryId).isEqualTo(3)
        assertThat(after.segmentStartMs).isEqualTo(beforeDuplicateStart.segmentStartMs)
        assertThat(after.accumulatedMs).isEqualTo(beforeDuplicateStart.accumulatedMs)
    }

    @Test
    fun `计划时长必须为正`() {
        val c = controller()
        var caught: IllegalArgumentException? = null
        try {
            c.start(categoryId = 1, plannedDurationMs = 0)
        } catch (e: IllegalArgumentException) {
            caught = e
        }
        assertThat(caught).isNotNull()
        assertThat(c.state.value).isEqualTo(SessionState.Idle)
    }

    // ==================================================================
    // 时间推进
    // ==================================================================

    @Test
    fun `R158 running time does not advance without accepted ticks`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        val running = c.state.value as SessionState.Running

        mono.advance(10_000)

        assertThat(c.currentElapsedMs()).isEqualTo(0L)
        assertThat(running.elapsedMs(mono.now)).isEqualTo(0L)
        assertThat(running.remainingMs(mono.now)).isEqualTo(min45)
        assertThat(running.progress(mono.now)).isEqualTo(0f)
        assertThat(running.isArrived(mono.now)).isFalse()
    }

    @Test
    fun `R158 one tick after ten blocked seconds adds only one second`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        mono.advance(10_000)

        assertThat(c.tick()).isFalse()
        assertThat(c.currentElapsedMs()).isEqualTo(1_000L)
        repeat(5) { assertThat(c.tick()).isFalse() }
        assertThat(c.currentElapsedMs()).isEqualTo(1_000L)

        mono.advance(999)
        assertThat(c.tick()).isFalse()
        assertThat(c.currentElapsedMs()).isEqualTo(1_000L)
        mono.advance(1)
        assertThat(c.tick()).isFalse()
        assertThat(c.currentElapsedMs()).isEqualTo(2_000L)
    }

    @Test
    fun `R158 pause freezes accepted seconds and resume starts a new tick window`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        mono.advance(1_000)
        assertThat(c.tick()).isFalse()
        mono.advance(500)

        assertThat(c.pause()).isTrue()
        assertThat(c.currentElapsedMs()).isEqualTo(1_000L)
        mono.advance(10_000)
        assertThat(c.tick()).isFalse()
        assertThat(c.currentElapsedMs()).isEqualTo(1_000L)

        assertThat(c.resume()).isTrue()
        mono.advance(999)
        assertThat(c.tick()).isFalse()
        assertThat(c.currentElapsedMs()).isEqualTo(1_000L)
        mono.advance(1)
        assertThat(c.tick()).isFalse()
        assertThat(c.currentElapsedMs()).isEqualTo(2_000L)
    }

    @Test
    fun `进度随已接受的每秒 tick 推进`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(9L * 60 * 1_000) // 540 次实际采样
        val s = c.state.value as SessionState.Running
        // 9/45 = 0.2
        assertThat(s.progress(mono.now)).isWithin(1e-6f).of(0.2f)
        assertThat(s.remainingMs(mono.now)).isEqualTo(min45 - 9L * 60 * 1_000)
    }








    @Test
    fun `暂停期间不计入已飞时长`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(10L * 60 * 1_000) // 600 次有效采样

        assertThat(c.pause()).isTrue()
        advance(3L * 60 * 1_000) // 暂停 3 分钟，tick 不累计
        assertThat(c.currentElapsedMs()).isEqualTo(10L * 60 * 1_000)

        assertThat(c.resume()).isTrue()
        advance(2L * 60 * 1_000) // 再接受 120 次采样

        assertThat(c.currentElapsedMs()).isEqualTo(12L * 60 * 1_000)
    }

    @Test
    fun `多次暂停继续的累计正确`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        repeat(3) {
            advance(60_000)
            c.pause()
            advance(5L * 60 * 1_000) // 每次暂停 5 分钟
            c.resume()
        }
        // 三次各飞 1 分钟 = 3 分钟
        assertThat(c.currentElapsedMs()).isEqualTo(3L * 60 * 1_000)
    }

    /** 单人分支不按停顿时差追赶；之后的实际有效采样仍能正常到站。 */
    @Test
    fun `长时间没有 tick 不追赶而完整有效采样后正常到站`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        mono.advance(50L * 60 * 1_000) // 50 分钟，期间一次 tick 都没有

        val s = c.state.value as SessionState.Running
        assertThat(s.isArrived(mono.now)).isFalse()
        assertThat(s.elapsedMs(mono.now)).isEqualTo(0L)
        assertThat(s.progress(mono.now)).isEqualTo(0f)

        assertThat(c.tick()).isFalse()
        assertThat(c.currentElapsedMs()).isEqualTo(1_000L)
        advance(min45 - 1_000L, acceptLastTick = false)

        assertThat(c.tick()).isTrue()
        assertThat(c.state.value).isInstanceOf(SessionState.Arrived::class.java)
        assertThat((c.state.value as SessionState.Arrived).totalElapsedMs).isEqualTo(min45)
    }

    @Test
    fun `到站后 tick 只迁移一次`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(min45, acceptLastTick = false)
        assertThat(c.tick()).isTrue()
        val first = c.state.value
        assertThat(c.tick()).isFalse()
        assertThat(c.state.value).isEqualTo(first)
    }

    @Test
    fun `未到站时 tick 不迁移`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(min45 - 1)
        assertThat(c.tick()).isFalse()
        assertThat(c.state.value).isInstanceOf(SessionState.Running::class.java)
    }

    // ==================================================================
    // 非法迁移
    // ==================================================================

    @Test
    fun `Idle 状态下 pause resume abandon 都是 no-op`() {
        val c = controller()
        assertThat(c.pause()).isFalse()
        assertThat(c.resume()).isFalse()
        assertThat(c.abandon()).isFalse()
        assertThat(c.state.value).isEqualTo(SessionState.Idle)
    }

    @Test
    fun `重复 pause 是幂等的`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(60_000)
        assertThat(c.pause()).isTrue()
        val first = c.state.value
        advance(10_000)

        //   否则 pausedAtMonoMs 会被刷新，「暂停了多久」这个展示值会归零
        assertThat(c.pause()).isFalse()
        assertThat(c.state.value).isEqualTo(first)
    }

    @Test
    fun `Running 状态下 resume 无效`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        assertThat(c.resume()).isFalse()
    }

    @Test
    fun `Arrived 状态下 start 被拒绝直到结算`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(min45)
        c.tick()
        assertThat(c.state.value).isInstanceOf(SessionState.Arrived::class.java)


        assertThat(c.start(categoryId = 4, plannedDurationMs = min45)).isFalse()
        assertThat(c.settle()).isNotNull()
        assertThat(c.start(categoryId = 4, plannedDurationMs = min45)).isTrue()
    }

    // ==================================================================
    // 放弃与结算
    // ==================================================================

    @Test
    fun `放弃时记录的是实际时长而不是计划时长`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(12L * 60 * 1_000)

        assertThat(c.abandon()).isTrue()
        val arrived = c.state.value as SessionState.Arrived
        assertThat(arrived.abandoned).isTrue()
        assertThat(arrived.totalElapsedMs).isEqualTo(12L * 60 * 1_000)
        assertThat(arrived.plannedDurationMs).isEqualTo(min45)
    }

    @Test
    fun `暂停中放弃记录的是暂停时的已飞时长`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(8L * 60 * 1_000)
        c.pause()
        advance(20L * 60 * 1_000) // 暂停了很久之后才放弃

        assertThat(c.abandon()).isTrue()
        val arrived = c.state.value as SessionState.Arrived
        assertThat(arrived.totalElapsedMs).isEqualTo(8L * 60 * 1_000)
    }

    @Test
    fun `自然到站标记为非放弃`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(min45, acceptLastTick = false)
        c.tick()
        val arrived = c.state.value as SessionState.Arrived
        assertThat(arrived.abandoned).isFalse()
        assertThat(arrived.totalElapsedMs).isEqualTo(min45)
    }





    @Test
    fun `结算返回记录并回到 Idle`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(5L * 60 * 1_000)
        c.abandon()

        val settled = c.settle()
        assertThat(settled).isNotNull()
        assertThat(settled!!.categoryId).isEqualTo(3)
        assertThat(c.state.value).isEqualTo(SessionState.Idle)
        // 重复结算返回 null
        assertThat(c.settle()).isNull()
    }

    @Test
    fun `放弃后不能再放弃`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(60_000)
        assertThat(c.abandon()).isTrue()
        assertThat(c.abandon()).isFalse()
    }

    // ==================================================================
    // 进度边界
    // ==================================================================

    @Test
    fun `进度不会超过 1 也不会小于 0`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        val s = c.state.value as SessionState.Running
        // 传一个比起点更早的时间（模拟异常输入）
        assertThat(s.progress(mono.now - 10_000)).isEqualTo(0f)
        assertThat(s.copy(accumulatedMs = -10_000L).progress(mono.now)).isEqualTo(0f)
        val over = s.copy(accumulatedMs = min45 + 100L * 60 * 1_000)
        assertThat(over.elapsedMs(mono.now)).isEqualTo(min45)
        assertThat(over.progress(mono.now)).isEqualTo(1f)
        assertThat(over.isArrived(mono.now)).isTrue()
    }

    @Test
    fun `剩余时长不会为负`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        val s = (c.state.value as SessionState.Running).copy(accumulatedMs = min45 + 100L * 60 * 1_000)
        assertThat(s.remainingMs(mono.now + 100L * 60 * 1_000)).isEqualTo(0L)
    }

    @Test
    fun `暂停后展示的暂停时长正确`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        advance(60_000)
        c.pause()
        val paused = c.state.value as SessionState.Paused
        assertThat(paused.pausedForMs(mono.now)).isEqualTo(0L)

        advance(90_000)
        assertThat(paused.pausedForMs(mono.now)).isEqualTo(90_000L)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restorePaused directly publishes a frozen checkpoint without Running`() = runTest {
        val c = controller()
        val observed = mutableListOf<SessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.state.toList(observed)
        }
        val startedAt = wallNow - 90_000L

        assertThat(c.restorePaused("saved-paused", 9, min45, startedAt, 60_000L)).isTrue()
        val paused = c.state.value as SessionState.Paused
        assertThat(observed).containsExactly(SessionState.Idle, paused).inOrder()
        assertThat(paused.sessionId).isEqualTo("saved-paused")
        assertThat(paused.categoryId).isEqualTo(9)
        assertThat(paused.startedAtWallMs).isEqualTo(startedAt)
        assertThat(paused.pausedAtMonoMs).isEqualTo(mono.now)
        mono.advance(10_000L)
        assertThat(c.tick()).isFalse()
        assertThat(c.currentElapsedMs()).isEqualTo(60_000L)
        assertThat(paused.pausedForMs(mono.now)).isEqualTo(10_000L)

        assertThat(c.resume()).isTrue()
        mono.advance(999L)
        assertThat(c.tick()).isFalse()
        assertThat(c.currentElapsedMs()).isEqualTo(60_000L)
        mono.advance(1L)
        assertThat(c.tick()).isFalse()
        assertThat(c.currentElapsedMs()).isEqualTo(61_000L)
    }

    @Test
    fun `restorePaused rejects invalid plans and clamps persisted boundaries`() {
        val c = controller()
        c.start(categoryId = 3, plannedDurationMs = min45)
        val before = c.state.value
        listOf(0L, -1L).forEach { invalid ->
            assertThat(c.restorePaused("invalid", 9, invalid, wallNow, 1_000L)).isFalse()
            assertThat(c.state.value).isEqualTo(before)
        }
        assertThat(c.restorePaused("negative", 9, min45, wallNow, -1_000L)).isTrue()
        val negative = c.state.value as SessionState.Paused
        assertThat(negative.elapsedMs()).isEqualTo(0L)
        assertThat(negative.progress()).isEqualTo(0f)
        assertThat(c.restorePaused("over", 9, min45, wallNow, min45 + 1_000L)).isTrue()
        val over = c.state.value as SessionState.Paused
        assertThat(over.elapsedMs()).isEqualTo(min45)
        assertThat(over.remainingMs()).isEqualTo(0L)
        assertThat(over.progress()).isEqualTo(1f)
    }

    @Test
    fun `progress uses Float seconds division for both running and paused snapshots`() {
        val c = controller()
        assertThat(c.restoreRunning("float-boundary", 3, 16_777_217_000L, wallNow, 8_388_609_000L)).isTrue()
        // Float operands yield 0x3f000001; a Double ratio followed by toFloat yields 0.5f.
        val expected = Float.fromBits(0x3f000001)
        assertThat((c.state.value as SessionState.Running).progress(mono.now)).isEqualTo(expected)
        assertThat(c.pause()).isTrue()
        assertThat((c.state.value as SessionState.Paused).progress()).isEqualTo(expected)
    }

    @Test
    fun `wall clock changes affect terminal dates without adding focus seconds`() {
        val c = controller()
        val startedAt = wallNow
        c.start(categoryId = 3, plannedDurationMs = 2_000L)
        mono.advance(1_000L)
        assertThat(c.tick()).isFalse()
        wallNow -= 90L * 24 * 60 * 60_000
        assertThat(c.nowWall()).isEqualTo(wallNow)
        assertThat(c.currentElapsedMs()).isEqualTo(1_000L)

        mono.advance(1_000L)
        assertThat(c.tick()).isTrue()
        val arrived = c.state.value as SessionState.Arrived
        assertThat(arrived.startedAtWallMs).isEqualTo(startedAt)
        assertThat(arrived.endedAtWallMs).isEqualTo(wallNow)
        assertThat(arrived.totalElapsedMs).isEqualTo(2_000L)
    }

    @Test
    fun `会话 id 唯一`() {
        val c = controller()
        val ids = mutableSetOf<String>()
        repeat(5) {
            c.start(categoryId = 1, plannedDurationMs = 1_000)
            ids += (c.state.value as SessionState.Running).sessionId
            c.abandon()
            c.settle()
        }
        assertThat(ids).hasSize(5)
    }
}
