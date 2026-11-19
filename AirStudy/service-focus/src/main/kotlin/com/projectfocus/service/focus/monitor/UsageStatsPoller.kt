package com.projectfocus.service.focus.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock


class UsageStatsPoller(
    private val context: Context,
    private val clock: MonotonicClock = SystemMonotonicClock,
) {

    private val usm: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager








    private var lastWallClockEnd: Long = 0L


















    var selfInForeground: Boolean = false











    fun poll(selfPackage: String): List<UsageEvent> {
        val manager = usm ?: return emptyList()
        val nowWall = clock.wallClockMs()

        val begin = if (lastWallClockEnd == 0L) {
            nowWall - FIRST_LOOKBACK_MS
        } else {

            (lastWallClockEnd - OVERLAP_MS).coerceAtLeast(0L)
        }
        // end 必须严格大于 begin，否则 queryEvents 直接返回空
        if (nowWall <= begin) return emptyList()

        val usageEvents = runCatching { manager.queryEvents(begin, nowWall) }.getOrNull()
            ?: return emptyList()


        // 后者会让区间的右端永远卡在最后一个事件上，长时间无事件时区间无限变大）
        lastWallClockEnd = nowWall

        val raw = ArrayList<RawEvent>(MAX_EVENTS_PER_POLL)
        val event = UsageEvents.Event()

        //   死循环。所以加一个 counter 上限兜底。
        var guard = 0
        while (usageEvents.hasNextEvent() && guard < MAX_EVENTS_PER_POLL) {
            guard++
            if (!usageEvents.getNextEvent(event)) continue
            if (!isForegroundTransition(event.eventType)) continue
            val pkg = event.packageName ?: continue
            if (pkg.isEmpty()) continue
            raw += RawEvent(pkg, event.timeStamp)
        }

        if (raw.isEmpty()) {
            return if (selfInForeground) listOf(UsageEvent(selfPackage, clock.elapsedRealtimeMs())) else emptyList()
        }


        val nowElapsed = clock.elapsedRealtimeMs()
        val events = raw.map { e ->
            // delta = nowWall - e.wall；elapsed = nowElapsed - delta
            UsageEvent(e.packageName, nowElapsed - (nowWall - e.wallClockMs))
        }.sortedBy { it.timestampMs }



        return if (selfInForeground) {
            events + UsageEvent(selfPackage, nowElapsed)
        } else {
            events
        }
    }

    /** 重置游标。Service 重启 / 会话切换时调用，避免把上次会话的旧事件当成新的。 */
    fun reset() {
        lastWallClockEnd = 0L
    }

    private fun isForegroundTransition(type: Int): Boolean = when (type) {
        // API 29+ 的主类型
        UsageEvents.Event.ACTIVITY_RESUMED -> true
        // ≤ API 28 的主类型（API 29+ 仍保留此常量，但不再投递）
        @Suppress("DEPRECATION")
        UsageEvents.Event.MOVE_TO_FOREGROUND -> true


        UsageEvents.Event.ACTIVITY_PAUSED -> true
        @Suppress("DEPRECATION")
        UsageEvents.Event.MOVE_TO_BACKGROUND -> true
        else -> false
    }

    private data class RawEvent(val packageName: String, val wallClockMs: Long)

    companion object {







        const val FIRST_LOOKBACK_MS: Long = 10_000L

        /** 查询区间重叠量。见类注释 ② */
        const val OVERLAP_MS: Long = 1_000L










        const val MAX_EVENTS_PER_POLL: Int = 2_000
    }
}









interface MonotonicClock {
    fun elapsedRealtimeMs(): Long
    fun wallClockMs(): Long
}

/** 真实实现。两个方法各取各的，不做任何转换。 */
object SystemMonotonicClock : MonotonicClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun wallClockMs(): Long = System.currentTimeMillis()
}
