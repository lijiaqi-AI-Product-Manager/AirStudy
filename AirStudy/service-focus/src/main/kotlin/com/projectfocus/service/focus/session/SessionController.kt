package com.projectfocus.service.focus.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton


sealed interface SessionState {

    /** 还没有开始任何飞行 */
    data object Idle : SessionState

    /** 正在飞 */
    data class Running(
        val sessionId: String,
        val categoryId: Long,
        /** 计划飞行总时长（毫秒）。用户选 45 分钟就是 2_700_000 */
        val plannedDurationMs: Long,

        val startedAtWallMs: Long,
        /** 已接受的专注时长（毫秒）；读取快照不会额外推进 */
        val accumulatedMs: Long,
        /** 最近一次采样窗口起点（单调时钟，毫秒）；保留原 public 字段名 */
        val segmentStartMs: Long,
    ) : SessionState {
        /** 已飞时长 */
        fun elapsedMs(@Suppress("UNUSED_PARAMETER") nowMonoMs: Long): Long =
            accumulatedMs.coerceIn(0L, plannedDurationMs.coerceAtLeast(0L))

        /** 进度 0f~1f */
        fun progress(nowMonoMs: Long): Float =
            acceptedProgress(elapsedMs(nowMonoMs), plannedDurationMs)

        /** 剩余时长 */
        fun remainingMs(nowMonoMs: Long): Long =
            (plannedDurationMs - elapsedMs(nowMonoMs)).coerceAtLeast(0L)

        /** 是否已到站 */
        fun isArrived(nowMonoMs: Long): Boolean = remainingMs(nowMonoMs) == 0L
    }

    /** 暂停中 */
    data class Paused(
        val sessionId: String,
        val categoryId: Long,
        val plannedDurationMs: Long,
        val startedAtWallMs: Long,
        /** 暂停时刻已累计的飞行时长 —— 暂停期间不增长 */
        val accumulatedMs: Long,
        /** 暂停发生在单调时钟的哪个点（用于展示「暂停了多久」） */
        val pausedAtMonoMs: Long,
    ) : SessionState {
        fun elapsedMs(): Long = accumulatedMs.coerceIn(0L, plannedDurationMs.coerceAtLeast(0L))

        fun progress(): Float =
            acceptedProgress(elapsedMs(), plannedDurationMs)

        fun remainingMs(): Long = (plannedDurationMs - elapsedMs()).coerceAtLeast(0L)

        /** 已经暂停了多久 */
        fun pausedForMs(nowMonoMs: Long): Long =
            (nowMonoMs - pausedAtMonoMs).coerceAtLeast(0L)
    }


    data class Arrived(
        val sessionId: String,
        val categoryId: Long,
        val plannedDurationMs: Long,
        val startedAtWallMs: Long,
        /** 实际总飞行时长（含各段累计，等于 plannedDurationMs 除非提前结束） */
        val totalElapsedMs: Long,
        /** 结束时的墙上时钟，用于写历史记录 */
        val endedAtWallMs: Long,
        /** true = 用户主动放弃；false = 自然到站 */
        val abandoned: Boolean,
    ) : SessionState
}

/**
 * 会话控制器。
 *
 * 使用方式：
 * ```
 * controller.start(categoryId = 3, plannedDurationMs = 45 * 60_000)
 * controller.pause(); controller.resume()
 * controller.tick()    // 每秒请求一次；接受后加一秒，到站时迁移到 Arrived
 * controller.finish()  // 用户点「下机」，把 Arrived 交给上层写历史
 * ```
 */
@Singleton
class SessionController(
    private val monotonic: MonotonicSource,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {


    @Inject
    constructor(monotonic: MonotonicSource) : this(
        monotonic = monotonic,
        wallClock = System::currentTimeMillis,
        idGenerator = { UUID.randomUUID().toString() },
    )

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()









    fun start(categoryId: Long, plannedDurationMs: Long, requestedSessionId: String? = null): Boolean {
        if (_state.value !is SessionState.Idle) return false
        require(plannedDurationMs > 0L) { "计划时长必须为正：$plannedDurationMs" }
        _state.value = SessionState.Running(
            sessionId = requestedSessionId?.also { require(it.isNotBlank()) } ?: idGenerator(),
            categoryId = categoryId,
            plannedDurationMs = plannedDurationMs,
            startedAtWallMs = wallClock(),
            accumulatedMs = 0L,
            segmentStartMs = monotonic.elapsedRealtimeMs(),
        )
        return true
    }








    fun pause(): Boolean {
        val running = _state.value as? SessionState.Running ?: return false
        val now = monotonic.elapsedRealtimeMs()
        _state.value = SessionState.Paused(
            sessionId = running.sessionId,
            categoryId = running.categoryId,
            plannedDurationMs = running.plannedDurationMs,
            startedAtWallMs = running.startedAtWallMs,
            accumulatedMs = running.elapsedMs(now),
            pausedAtMonoMs = now,
        )
        return true
    }

    /** 继续。只有 Paused 能继续。 */
    fun resume(): Boolean {
        val paused = _state.value as? SessionState.Paused ?: return false
        _state.value = SessionState.Running(
            sessionId = paused.sessionId,
            categoryId = paused.categoryId,
            plannedDurationMs = paused.plannedDurationMs,
            startedAtWallMs = paused.startedAtWallMs,
            // 已接受量冻结；恢复后从当前 mono 开始新的完整 1 秒采样窗口。
            accumulatedMs = paused.accumulatedMs,
            segmentStartMs = monotonic.elapsedRealtimeMs(),
        )
        return true
    }

    /**
     * 请求一个采样秒。服务持有协程，本类不创建 scope。
     *
     * 距最近窗口不足 1000ms 时不接受；否则只加一秒并从当前 mono 重开窗口。
     * 不用整段时差补计：即使阻塞十秒，这次也只接受一秒。
     *
     * @return 状态是否发生了迁移（到站）
     */
    fun tick(): Boolean {
        val running = _state.value as? SessionState.Running ?: return false
        val now = monotonic.elapsedRealtimeMs()
        if (now - running.segmentStartMs < 1_000L) return false
        val accepted = running.elapsedMs(now)
        val elapsed = accepted + (running.plannedDurationMs - accepted).coerceAtMost(1_000L)
        if (elapsed < running.plannedDurationMs) {
            _state.value = running.copy(accumulatedMs = elapsed, segmentStartMs = now)
            return false
        }
        _state.value = SessionState.Arrived(
            sessionId = running.sessionId,
            categoryId = running.categoryId,
            plannedDurationMs = running.plannedDurationMs,
            startedAtWallMs = running.startedAtWallMs,
            totalElapsedMs = running.plannedDurationMs,
            endedAtWallMs = wallClock(),
            abandoned = false,
        )
        return true
    }









    fun abandon(): Boolean {
        val now = monotonic.elapsedRealtimeMs()
        val (sessionId, categoryId, planned, elapsed, startedAtWall) = when (val s = _state.value) {
            is SessionState.Running -> AbandonSnapshot(
                s.sessionId, s.categoryId, s.plannedDurationMs,
                s.elapsedMs(now), s.startedAtWallMs,
            )
            is SessionState.Paused -> AbandonSnapshot(
                s.sessionId, s.categoryId, s.plannedDurationMs,
                s.elapsedMs(), s.startedAtWallMs,
            )
            else -> return false
        }
        _state.value = SessionState.Arrived(
            sessionId = sessionId,
            categoryId = categoryId,
            plannedDurationMs = planned,
            startedAtWallMs = startedAtWall,
            totalElapsedMs = elapsed.coerceAtMost(planned),
            endedAtWallMs = wallClock(),
            abandoned = true,
        )
        return true
    }

    private data class AbandonSnapshot(
        val sessionId: String,
        val categoryId: Long,
        val plannedDurationMs: Long,
        val elapsedMs: Long,
        val startedAtWallMs: Long,
    )











    fun settle(): SessionState.Arrived? {
        val arrived = _state.value as? SessionState.Arrived ?: return null
        _state.value = SessionState.Idle
        return arrived
    }

    /** 诊断 / 只读快照，不改变状态 */
    fun currentElapsedMs(): Long = when (val s = _state.value) {
        is SessionState.Running -> s.elapsedMs(monotonic.elapsedRealtimeMs())
        is SessionState.Paused -> s.elapsedMs()
        is SessionState.Arrived -> s.totalElapsedMs
        SessionState.Idle -> 0L
    }

    /** 单调时钟现值；保留 UI / 暂停时长调用接口，不用它补算专注进度。 */
    fun nowMono(): Long = monotonic.elapsedRealtimeMs()

    /** 墙钟现值，仅用于记录日期，包括仓库恢复终态时的到达日期。 */
    fun nowWall(): Long = wallClock()

    // ==================================================================
    // 恢复（进程重启后由 SessionRepository.restore 调用）
    // ==================================================================















    fun restoreRunning(
        sessionId: String,
        categoryId: Long,
        plannedDurationMs: Long,
        startedAtWallMs: Long,
        accumulatedMs: Long,
    ): Boolean {
        if (plannedDurationMs <= 0L) return false
        _state.value = SessionState.Running(
            sessionId = sessionId,
            categoryId = categoryId,
            plannedDurationMs = plannedDurationMs,
            startedAtWallMs = startedAtWallMs,
            // 持久化检查点限制在计划范围内，不补离线时间。
            accumulatedMs = accumulatedMs.coerceIn(0L, plannedDurationMs),
            segmentStartMs = monotonic.elapsedRealtimeMs(),
        )
        return true
    }

    /** 直接恢复暂停快照，不先发布一个会被观察到的 Running 中间状态。 */
    fun restorePaused(
        sessionId: String,
        categoryId: Long,
        plannedDurationMs: Long,
        startedAtWallMs: Long,
        accumulatedMs: Long,
    ): Boolean {
        if (plannedDurationMs <= 0L) return false
        _state.value = SessionState.Paused(
            sessionId = sessionId,
            categoryId = categoryId,
            plannedDurationMs = plannedDurationMs,
            startedAtWallMs = startedAtWallMs,
            accumulatedMs = accumulatedMs.coerceIn(0L, plannedDurationMs),
            pausedAtMonoMs = monotonic.elapsedRealtimeMs(),
        )
        return true
    }

    /**
     * 直接从库里恢复成「已到站」。
     *
     * 仓库已确认持久化终态或检查点达到计划后，可直接发布到站快照。
     * 仅有墙钟经过很久不能作为已经专注完成的依据。
     */
    fun restoreArrived(
        sessionId: String,
        categoryId: Long,
        plannedDurationMs: Long,
        startedAtWallMs: Long,
        totalElapsedMs: Long,
        endedAtWallMs: Long,
        abandoned: Boolean,
    ): Boolean {
        if (plannedDurationMs <= 0L) return false
        _state.value = SessionState.Arrived(
            sessionId = sessionId,
            categoryId = categoryId,
            plannedDurationMs = plannedDurationMs,
            startedAtWallMs = startedAtWallMs,
            totalElapsedMs = totalElapsedMs.coerceIn(0L, plannedDurationMs),
            endedAtWallMs = endedAtWallMs,
            abandoned = abandoned,
        )
        return true
    }
}

/** Float 秒 / Float 计划秒，与已查单人进度公式保持相同运算顺序。 */
private fun acceptedProgress(elapsedMs: Long, plannedMs: Long): Float {
    if (plannedMs <= 0L) return 1f
    fun seconds(ms: Long): Float = (ms / 1_000L).toFloat() + (ms % 1_000L).toFloat() / 1_000f
    return (seconds(elapsedMs) / seconds(plannedMs)).coerceIn(0f, 1f)
}











fun interface MonotonicSource {
    fun elapsedRealtimeMs(): Long
}

/** 生产实现。反射调用 SystemClock，避免本类在 JVM 单测里因 android.os 报 Stub! */
object RealMonotonicSource : MonotonicSource {
    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()
}
