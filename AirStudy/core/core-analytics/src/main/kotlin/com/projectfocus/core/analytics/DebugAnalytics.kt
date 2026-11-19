package com.projectfocus.core.analytics

import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton





































@Singleton
class DebugAnalytics @Inject constructor() : Analytics {

    override val enabled: Boolean get() = true

    /** 每个事件名的分钟级计数，用于限流 */
    private val counters = mutableMapOf<String, MinuteCounter>()

    /** 被限流丢弃的次数，flush 时汇总 */
    private val dropped = mutableMapOf<String, Int>()

    private val flushed = AtomicBoolean(false)

    override fun track(event: AnalyticsEvent) {
        runCatching {
            val name = event.name
            val counter = counters.getOrPut(name) { MinuteCounter() }
            if (!counter.tryAcquire()) {
                dropped.merge(name, 1, Int::plus)
                return
            }
            val params = event.params()
            if (params.isEmpty()) {
                Timber.tag(TAG).d("%s", name)
            } else {
                Timber.tag(TAG).d("%s %s", name, params.entries.joinToString(", ", "{", "}") { (k, v) ->
                    "$k=$v"
                })
            }
        }.onFailure {

        }
    }

    override fun setUserProperty(key: String, value: String?) {
        runCatching { Timber.tag(TAG).d("user_property %s=%s", key, value ?: "null") }
    }

    override fun flush() {
        runCatching {
            if (dropped.isNotEmpty()) {
                Timber.tag(TAG).d(
                    "flush（被限流丢弃：%s）",
                    dropped.entries.joinToString(", ") { "${it.key}×${it.value}" },
                )
                dropped.clear()
            } else {
                Timber.tag(TAG).d("flush")
            }
            counters.values.forEach { it.reset() }
            flushed.set(true)
        }
    }








    private class MinuteCounter {
        private var windowStartMs: Long = 0L
        private var count: Int = 0

        fun tryAcquire(): Boolean {
            val now = System.currentTimeMillis()
            if (now - windowStartMs >= 60_000L) {
                windowStartMs = now
                count = 0
            }
            if (count >= MAX_SAME_EVENT_PER_MIN) return false
            count++
            return true
        }

        fun reset() {
            windowStartMs = 0L
            count = 0
        }
    }

    companion object {
        private const val TAG = "Analytics"









        const val MAX_SAME_EVENT_PER_MIN = 20
    }
}
