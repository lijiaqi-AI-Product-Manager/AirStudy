package com.projectfocus.feature.flight.hud

import com.google.common.truth.Truth.assertThat
import org.junit.Test












class HudSnapshotTest {

    @Test
    fun `route HUD formats the preserved float field at decimal boundaries`() {
        // Double-only subtraction would give 98.74999998 and wrongly format it as 98.7.
        val first = HudSnapshot.of(6_000L, 8, totalDistanceKm = 100.0)
        assertThat(first.remainingKmText).isEqualTo("98.8")
        assertThat(first.traveledKmText).isEqualTo("1.3")
        assertThat(HudSnapshot.of(18_000L, 8, totalDistanceKm = 100.0).remainingKmText).isEqualTo("96.3")
        assertThat(HudSnapshot.of(930_000L, 93, totalDistanceKm = 1234.5).remainingKmText).isEqualTo("1028.8")
    }

    // ── 1. 核心等式：已行 + 剩余 = 总里程 ──────────────────

    @Test
    fun `已行加剩余在显示精度上恒等于总里程`() {

        //   25 分钟 = 333.333…（非整数，round 的坑在这里暴露）
        val cases = listOf(15, 25, 45, 60, 90, 180)
        for (minutes in cases) {
            val totalKm = minutes / 60.0 * 800.0
            // 每秒取一帧，覆盖整段航程
            var sec = 0L
            while (sec <= minutes * 60L) {
                val hud = HudSnapshot.of(sec * 1000L, minutes)
                val traveled = hud.traveledKmText.toDouble()
                val remaining = hud.remainingKmText.toDouble()
                // 各保留 1 位小数，所以和与总里程的误差不超过 0.1
                assertThat(kotlin.math.abs(traveled + remaining - totalKm))
                    .isAtMost(0.1 + 1e-9)
                sec += 7L   // 步长取 7 秒，不是 1 秒 —— 避免整除导致恰好落在刻度上
            }
        }
    }

    @Test
    fun `四十五分钟在整段航程上是 600 公里`() {
        assertThat(HudSnapshot.of(0L, 45).traveledKmText).isEqualTo("0.0")
        assertThat(HudSnapshot.of(0L, 45).remainingKmText).isEqualTo("600.0")
        // 半程 22.5 分钟 = 1350 秒
        assertThat(HudSnapshot.of(1_350_000L, 45).traveledKmText).isEqualTo("300.0")
        assertThat(HudSnapshot.of(1_350_000L, 45).remainingKmText).isEqualTo("300.0")
        // 终点
        assertThat(HudSnapshot.of(2_700_000L, 45).remainingKmText).isEqualTo("0.0")
    }

    @Test
    fun `有路线时用冻结总里程而不从分钟重算`() {
        val start = HudSnapshot.of(0L, 45, totalDistanceKm = 1234.5)
        val middle = HudSnapshot.of(1_350_000L, 45, totalDistanceKm = 1234.5)
        val end = HudSnapshot.of(2_700_000L, 45, totalDistanceKm = 1234.5)

        assertThat(start.remainingKmText).isEqualTo("1234.5")
        assertThat(middle.traveledKmText).isEqualTo("617.3")
        assertThat(middle.remainingKmText).isEqualTo("617.3")
        assertThat(middle.progress).isEqualTo(0.5f)
        assertThat(middle.remainingText).isEqualTo("00:22:30")
        assertThat(end.traveledKmText).isEqualTo("1234.5")
        assertThat(end.remainingKmText).isEqualTo("0.0")
    }

    @Test
    fun `零路线里程保持零而时间继续推进`() {
        val hud = HudSnapshot.of(1_350_000L, 45, totalDistanceKm = 0.0)
        assertThat(hud.traveledKmText).isEqualTo("0.0")
        assertThat(hud.remainingKmText).isEqualTo("0.0")
        assertThat(hud.progress).isEqualTo(0.5f)
        assertThat(hud.elapsedText).isEqualTo("00:22:30")
    }

    @Test
    fun `冻结路线的小数里程在整段飞行显示精度内守恒`() {
        val total = 1234.567
        for (seconds in 0L..2700L step 7) {
            val hud = HudSnapshot.of(seconds * 1000L, 45, totalDistanceKm = total)
            assertThat(kotlin.math.abs(hud.traveledKmText.toDouble() + hud.remainingKmText.toDouble() - total))
                .isAtMost(0.1 + 1e-9)
        }
    }

    @Test
    fun `损坏路线里程不能悄悄改成计时器里程`() {
        for (distance in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                HudSnapshot.of(0L, 45, totalDistanceKm = distance)
            }
        }
    }

    @Test
    fun `结束瞬间剩余里程不会是负零`() {

        //   屏幕上出现「-0.0 km」是明显的脏。这里断言被归一化。
        val hud = HudSnapshot.of(45 * 60_000L, 45)
        assertThat(hud.remainingKmText).isEqualTo("0.0")
        assertThat(hud.remainingKmText.contains("-")).isFalse()
    }

    @Test
    fun `超时后各数值都不会倒退或变负`() {
        // 用户把会话跑过头（比如到站没触发 settle）
        val hud = HudSnapshot.of(999 * 60_000L, 45)
        assertThat(hud.traveledKmText).isEqualTo("600.0")
        assertThat(hud.remainingKmText).isEqualTo("0.0")
        assertThat(hud.remainingText).isEqualTo("00:00:00")
        assertThat(hud.remainingSeconds).isEqualTo(0L)
        assertThat(hud.progress).isEqualTo(1f)
    }

    @Test
    fun `已用毫秒为负数时当作零`() {
        // 防御：恢复会话时库里的值可能离谱
        val hud = HudSnapshot.of(-5000L, 45)
        assertThat(hud.traveledKmText).isEqualTo("0.0")
        assertThat(hud.elapsedText).isEqualTo("00:00:00")
    }

    // ── 2. 时间口径 ────────────────────────────────────────

    @Test
    fun `时间文本是 HH MM SS 且向下取整`() {
        // 1 小时 23 分 45.9 秒 → 45（不是 46）
        val hud = HudSnapshot.of((1 * 3600L + 23 * 60L + 45L) * 1000L + 900L, 180)
        assertThat(hud.elapsedText).isEqualTo("01:23:45")
    }

    @Test
    fun `剩余时间单调不增，不会倒着跳`() {

        //   剩 0.7 秒显示 00:00:01，下一帧剩 0.0 秒显示 00:00:00
        //   —— 看起来是正常递减。但若用 round，
        //   剩 1.6 秒显示 00:00:02，剩 1.4 秒仍显示 00:00:01，
        //   会出现 02 → 01 → 02 的抖动。
        var last = Long.MAX_VALUE
        var sec = 0L
        while (sec <= 45 * 60L) {
            val hud = HudSnapshot.of(sec * 1000L, 45)
            val parsed = parseHms(hud.remainingText)
            assertThat(parsed).isAtMost(last)
            last = parsed
            sec += 250L   // 四分之一秒采样，能抓到抖动
        }
    }

    @Test
    fun `已用时间单调不减`() {
        var last = -1L
        var ms = 0L
        while (ms <= 45 * 60_000L) {
            val hud = HudSnapshot.of(ms, 45)
            val parsed = parseHms(hud.elapsedText)
            assertThat(parsed).isAtLeast(last)
            last = parsed
            ms += 250L
        }
    }

    // ── 3. 变化检测（节流） ────────────────────────────────

    @Test
    fun `首帧所有字段都算变化`() {
        val hud = HudSnapshot.of(0L, 45)
        val changed = HudChanged.between(null, hud)
        HudChanged.Field.entries.forEach {
            assertThat(it in changed).isTrue()
        }
        assertThat(changed.needsLayout).isTrue()
    }

    @Test
    fun `同一秒内的两帧不产生任何变化`() {

        //   显示值一个都没变，于是整帧跳过。
        val a = HudSnapshot.of(100_000L, 45)
        val b = HudSnapshot.of(100_200L, 45)
        assertThat(HudChanged.between(a, b).any).isFalse()
    }

    @Test
    fun `跨秒后已用时间字段被标记为变化`() {
        val a = HudSnapshot.of(100_000L, 45)
        val b = HudSnapshot.of(101_000L, 45)
        val changed = HudChanged.between(a, b)
        assertThat(HudChanged.Field.ELAPSED in changed).isTrue()
        assertThat(HudChanged.Field.REMAINING_TIME in changed).isTrue()
    }

    @Test
    fun `单帧内里程不变但秒数变了，只标记时间字段`() {

        //   但如果是 100 ms 内（0.022 km），就可能不变。
        val a = HudSnapshot.of(100_000L, 45)
        val b = HudSnapshot.of(100_100L, 45)
        val changed = HudChanged.between(a, b)
        // 100ms 内里程四舍五入后相同
        assertThat(HudChanged.Field.TRAVELED in changed).isFalse()
    }

    @Test
    fun `进度变化小于阈值时不触发重绘`() {
        // 45 分钟总长下，0.001 进度 = 27 秒。所以 1 秒的变化不会触发进度重绘。
        val a = HudSnapshot.of(100_000L, 45)
        val b = HudSnapshot.of(101_000L, 45)
        assertThat(HudChanged.Field.PROGRESS in HudChanged.between(a, b)).isFalse()
    }

    @Test
    fun `进度变化超过阈值时触发重绘`() {
        val a = HudSnapshot.of(100_000L, 45)
        val b = HudSnapshot.of(140_000L, 45)   // 40 秒 ≈ 0.0148 进度
        assertThat(HudChanged.Field.PROGRESS in HudChanged.between(a, b)).isTrue()
    }

    @Test
    fun `三小时航班的整段重绘次数不超过每秒一次`() {

        //   3 小时 = 10,800 秒。以 100 ms 为帧间隔重算（每秒 10 次），
        //   若每次都用精确比较，会有 10,800 × 10 = 108,000 次重绘。
        //   用显示值比较后，最多 10,800 次（每秒一次）。
        var last: HudSnapshot? = null
        var redraws = 0
        var ms = 0L
        while (ms <= 180 * 60_000L) {
            val hud = HudSnapshot.of(ms, 180)
            if (HudChanged.between(last, hud).any) redraws++
            last = hud
            ms += 100L
        }
        println("[perf] 3 小时航班以 100ms 帧重算，实际重绘 $redraws 次")
        // 上限 = 秒数 + 首帧 + 余量。允许略超是因为里程与时间不同步变化
        assertThat(redraws).isAtMost(180 * 60 + 100)
    }

    // ── 4. 舷窗视频时段 ────────────────────────────────────

    @Test
    fun `时段边界是左闭右开`() {
        // 规格：06:00-11:00 morning / 11:00-16:00 noon
        //     16:00-19:00 sunset / 19:00-06:00 night
        assertThat(WindowVideo.forHour(5)).isEqualTo(WindowVideo.NIGHT)
        assertThat(WindowVideo.forHour(6)).isEqualTo(WindowVideo.MORNING)   // 边界
        assertThat(WindowVideo.forHour(10)).isEqualTo(WindowVideo.MORNING)
        assertThat(WindowVideo.forHour(11)).isEqualTo(WindowVideo.NOON)     // 边界
        assertThat(WindowVideo.forHour(15)).isEqualTo(WindowVideo.NOON)
        assertThat(WindowVideo.forHour(16)).isEqualTo(WindowVideo.SUNSET)   // 边界
        assertThat(WindowVideo.forHour(18)).isEqualTo(WindowVideo.SUNSET)
        assertThat(WindowVideo.forHour(19)).isEqualTo(WindowVideo.NIGHT)    // 边界
        assertThat(WindowVideo.forHour(0)).isEqualTo(WindowVideo.NIGHT)
    }

    @Test
    fun `每个小时都能映射到一个视频，没有空隙`() {

        //   漏掉的话 when 会走到 else，而在 Kotlin 里如果写了 else 就是
        //   静默给一个错值；如果没写 else 会编译失败 —— 但这里试枚举。
        for (h in 0..23) {
            assertThat(WindowVideo.forHour(h)).isNotNull()
        }
        // 24 小时的映射必须覆盖全部 4 个视频
        val set = (0..23).map { WindowVideo.forHour(it) }.toSet()
        assertThat(set).hasSize(WindowVideo.entries.size)
    }

    @Test
    fun `越界小时被取模而不是崩或返回错值`() {
        // 调用方可能传 UTC 小时（比如 25 表示次日 1 点）
        assertThat(WindowVideo.forHour(24)).isEqualTo(WindowVideo.NIGHT)
        assertThat(WindowVideo.forHour(25)).isEqualTo(WindowVideo.NIGHT)
        assertThat(WindowVideo.forHour(30)).isEqualTo(WindowVideo.MORNING)
        assertThat(WindowVideo.forHour(-1)).isEqualTo(WindowVideo.NIGHT)
    }

    @Test
    fun `视频资源名与规格书一致`() {
        assertThat(WindowVideo.MORNING.asset).isEqualTo("morning_loop")
        assertThat(WindowVideo.NOON.asset).isEqualTo("noon_loop")
        assertThat(WindowVideo.SUNSET.asset).isEqualTo("sunset_loop")
        assertThat(WindowVideo.NIGHT.asset).isEqualTo("night_loop")
    }

    // ── 辅助 ───────────────────────────────────────────────

    private fun parseHms(text: String): Long {
        val parts = text.split(":")
        return parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
    }
}
