package com.projectfocus.core.common.time

/**
 * 时间抽象。飞行进度的 1:1 复刻完全依赖计时口径，
 * 必须能在单测里把时间钉死（金标准用例要求「同输入同输出」）。
 */
fun interface Clock {
    /** 当前时间，Unix 毫秒 */
    fun nowMillis(): Long

    companion object {
        fun system(): Clock = Clock { System.currentTimeMillis() }
    }
}

/** 测试用可变时钟 */
class FakeClock(private var currentMillis: Long = 0L) : Clock {
    override fun nowMillis(): Long = currentMillis

    fun advanceBy(millis: Long) {
        currentMillis += millis
    }

    fun set(millis: Long) {
        currentMillis = millis
    }
}
