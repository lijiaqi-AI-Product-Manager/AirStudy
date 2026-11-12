package com.projectfocus.app.widget

import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId






































class DayChangeDetector(
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {








    private var lastDay: LocalDate? = null












    fun shouldRefresh(): Boolean {
        val today = today()
        if (today == lastDay) return false
        lastDay = today
        Timber.d("跨日检测：%s → %s，需要刷新今日小组件", lastDay, today)
        return true
    }

    /** 只读版本，供日志/测试用。不影响内部状态。 */
    fun peekWouldRefresh(): Boolean = today() != lastDay

    /** 手动标记"已经刷过了"。 */
    fun markRefreshed() {
        lastDay = today()
    }

    private fun today(): LocalDate {
        val zone = zoneProvider()

        //   而不是 `LocalDate.now()` —— 后者用的是 JVM 默认时区，
        //   而本类的时区是注入的（测试里钉死 Asia/Shanghai）。
        //   用错会表现为"测试通过、线上在跨时区用户那里差一天"。
        return java.time.Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
    }
}
