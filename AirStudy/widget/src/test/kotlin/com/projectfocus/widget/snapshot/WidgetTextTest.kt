package com.projectfocus.widget.snapshot

import com.google.common.truth.Truth.assertThat
import org.junit.Test








class WidgetTextTest {

    // ==================================================================
    // 倒计时
    // ==================================================================

    @Test
    fun `不足一小时不显示小时位`() {
        assertThat(WidgetText.countdown(45L * 60 * 1_000)).isEqualTo("45:00")
        assertThat(WidgetText.countdown(25L * 60 * 1_000)).isEqualTo("25:00")
    }

    @Test
    fun `超过一小时显示小时位`() {
        assertThat(WidgetText.countdown(60L * 60 * 1_000)).isEqualTo("1:00:00")

        assertThat(WidgetText.countdown(180L * 60 * 1_000)).isEqualTo("3:00:00")

        //   不能加那 999 毫秒 —— 加了之后向上取整会变成 2:00:00。

        //     实现是对的、断言是错的，测试反而报了「实现了 bug」。）
        assertThat(WidgetText.countdown((119L * 60 + 59) * 1_000)).isEqualTo("1:59:59")
        // 再多 1 毫秒 → 向上取整成 2:00:00，这是正确行为
        assertThat(WidgetText.countdown((119L * 60 + 59) * 1_000 + 1)).isEqualTo("2:00:00")
    }








    @Test
    fun `剩一毫秒仍显示一秒 —— 向上取整`() {
        assertThat(WidgetText.countdown(1)).isEqualTo("00:01")
        assertThat(WidgetText.countdown(999)).isEqualTo("00:01")
        assertThat(WidgetText.countdown(1_000)).isEqualTo("00:01")
        assertThat(WidgetText.countdown(1_001)).isEqualTo("00:02")
    }

    @Test
    fun `零与负数都显示为零`() {
        assertThat(WidgetText.countdown(0)).isEqualTo("00:00")
        assertThat(WidgetText.countdown(-1)).isEqualTo("00:00")
        assertThat(WidgetText.countdown(-100_000)).isEqualTo("00:00")
    }








    @Test
    fun `同一量级内字符串长度恒定`() {
        val under1h = (0..59).flatMap { m ->
            listOf(0L, 1L, 9L, 59L).map { s -> WidgetText.countdown((m * 60 + s) * 1_000) }
        }
        // "45:00" / "05:09" 都是 5 个字符
        assertThat(under1h.map { it.length }.distinct()).containsExactly(5)

        val over1h = (1..9).map { h -> WidgetText.countdown(h * 3600L * 1_000) }
        assertThat(over1h.map { it.length }.distinct()).containsExactly(7)
    }

    @Test
    fun `补零正确`() {
        assertThat(WidgetText.countdown(9L * 1_000)).isEqualTo("00:09")
        assertThat(WidgetText.countdown(65L * 1_000)).isEqualTo("01:05")
        assertThat(WidgetText.countdown((3605L) * 1_000)).isEqualTo("1:00:05")
    }

    // ==================================================================
    // 其他文本
    // ==================================================================

    @Test
    fun `已飞时长文案`() {
        assertThat(WidgetText.elapsedLabel(0)).isEqualTo("刚刚起飞")
        assertThat(WidgetText.elapsedLabel(59_000)).isEqualTo("刚刚起飞")
        assertThat(WidgetText.elapsedLabel(60_000)).isEqualTo("已飞 1 分")
        assertThat(WidgetText.elapsedLabel(32L * 60_000)).isEqualTo("已飞 32 分")
        // 负数按 0 处理
        assertThat(WidgetText.elapsedLabel(-5_000)).isEqualTo("刚刚起飞")
    }

    @Test
    fun `进度百分比夹紧在 0 到 100`() {
        assertThat(WidgetText.progressPercent(0f)).isEqualTo("0%")
        assertThat(WidgetText.progressPercent(0.5f)).isEqualTo("50%")
        assertThat(WidgetText.progressPercent(1f)).isEqualTo("100%")
        assertThat(WidgetText.progressPercent(1.5f)).isEqualTo("100%")
        assertThat(WidgetText.progressPercent(-0.3f)).isEqualTo("0%")
    }

    @Test
    fun `里程格式随量级切换`() {
        assertThat(WidgetText.formatDistance(0)).isEqualTo("0 km")
        assertThat(WidgetText.formatDistance(999)).isEqualTo("999 km")
        assertThat(WidgetText.formatDistance(1_000)).isEqualTo("1.0 千公里")
        assertThat(WidgetText.formatDistance(12_340)).isEqualTo("12.3 千公里")
        // 负数（不该出现，但不能崩）
        assertThat(WidgetText.formatDistance(-5)).isEqualTo("0 km")
    }

    @Test
    fun `今日副标题`() {
        assertThat(WidgetText.todaySubtitle(120, 3400)).isEqualTo("120 分钟 · 3.4 千公里")
        assertThat(WidgetText.todaySubtitle(0, 0)).isEqualTo("0 分钟 · 0 km")
    }

    @Test
    fun `连续天数文案`() {
        assertThat(WidgetText.streakLabel(0)).isEqualTo("今天还没飞")
        assertThat(WidgetText.streakLabel(-1)).isEqualTo("今天还没飞")
        assertThat(WidgetText.streakLabel(1)).isEqualTo("连续 1 天")
        assertThat(WidgetText.streakLabel(37)).isEqualTo("连续 37 天")
    }





    @Test
    fun `目的地缺失时不显示 null`() {
        assertThat(WidgetText.routeLabel("PEK", null)).isEqualTo("专注航线")
        assertThat(WidgetText.routeLabel("PEK", "")).isEqualTo("专注航线")
        assertThat(WidgetText.routeLabel("PEK", "  ")).isEqualTo("专注航线")
        assertThat(WidgetText.routeLabel(null, "NRT")).isEqualTo("→ NRT")
        assertThat(WidgetText.routeLabel("PEK", "NRT")).isEqualTo("PEK → NRT")
    }

    // ==================================================================
    // 快照计算
    // ==================================================================

    @Test
    fun `飞行中快照的进度与已飞时长`() {
        val snap = WidgetSnapshot.Flying(
            remainingMs = 15L * 60_000,
            totalMs = 45L * 60_000,
            destinationCode = "NRT",
            scenarioLabel = "工作",
        )
        assertThat(snap.elapsedMs).isEqualTo(30L * 60_000)
        assertThat(snap.progress).isWithin(1e-6f).of(2f / 3f)
    }

    @Test
    fun `零总时长的快照不会除零`() {
        val flying = WidgetSnapshot.Flying(remainingMs = 0, totalMs = 0, destinationCode = null, scenarioLabel = "x")
        assertThat(flying.progress).isEqualTo(1f)

        val paused = WidgetSnapshot.Paused(elapsedMs = 0, totalMs = 0, scenarioLabel = "x")
        assertThat(paused.progress).isEqualTo(0f)
        assertThat(paused.remainingMs).isEqualTo(0L)
    }

    @Test
    fun `剩余时间大于总时长时进度夹紧为 0`() {
        val snap = WidgetSnapshot.Flying(
            remainingMs = 50L * 60_000,
            totalMs = 45L * 60_000,
            destinationCode = null,
            scenarioLabel = "工作",
        )
        assertThat(snap.progress).isEqualTo(0f)
        assertThat(snap.elapsedMs).isEqualTo(0L)
    }
}
