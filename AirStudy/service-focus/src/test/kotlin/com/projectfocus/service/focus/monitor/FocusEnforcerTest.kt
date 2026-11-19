package com.projectfocus.service.focus.monitor

import com.google.common.truth.Truth.assertThat
import org.junit.Test


class FocusEnforcerTest {

    private val self = "com.projectfocus.app"
    private val tiktok = "com.ss.android.ugc.aweme"
    private val wechat = "com.tencent.mm"

    private fun enforcer(
        rules: List<BlockRule> = listOf(
            BlockRule(RuleType.PACKAGE, tiktok, categoryLabel = "工作"),
        ),
    ) = FocusEnforcer().apply {
        configure(selfPackage = self, rules = rules)
        enabled = true
    }

    private fun ev(pkg: String, at: Long) = UsageEvent(pkg, at)

    // ==================================================================
    // 基本判定
    // ==================================================================

    @Test
    fun `未启用时恒不拦截`() {
        val e = FocusEnforcer().apply {
            configure(selfPackage = self, rules = listOf(BlockRule(RuleType.PACKAGE, tiktok)))
            enabled = false
        }
        assertThat(e.evaluate(listOf(ev(tiktok, 0)), 10_000)).isEqualTo(EnforcementDecision.None)
    }

    @Test
    fun `暂停时恒不拦截`() {
        val e = enforcer()
        e.paused = true
        assertThat(e.evaluate(listOf(ev(tiktok, 0)), 10_000)).isEqualTo(EnforcementDecision.None)
    }

    @Test
    fun `被屏蔽 App 停留不足阈值不拦截`() {
        val e = enforcer()
        // 1999ms < 2000ms
        assertThat(e.evaluate(listOf(ev(tiktok, 0)), 1_999)).isEqualTo(EnforcementDecision.None)
    }

    @Test
    fun `恰好等于阈值不拦截 —— 必须是严格大于`() {
        val e = enforcer()

        // 这条锁定边界语义：等于 2000 不拦，2001 才拦。
        assertThat(e.evaluate(listOf(ev(tiktok, 0)), 2_000)).isEqualTo(EnforcementDecision.None)
        assertThat(e.evaluate(emptyList(), 2_001)).isInstanceOf(EnforcementDecision.Block::class.java)
    }

    @Test
    fun `超过阈值拦截并带上分类名`() {
        val e = enforcer()
        val d = e.evaluate(listOf(ev(tiktok, 0)), 2_500)
        assertThat(d).isInstanceOf(EnforcementDecision.Block::class.java)
        val block = d as EnforcementDecision.Block
        assertThat(block.packageName).isEqualTo(tiktok)
        assertThat(block.categoryLabel).isEqualTo("工作")
        assertThat(block.foregroundDurationMs).isEqualTo(2_500)
    }

    @Test
    fun `不在屏蔽名单的 App 永远不拦截`() {
        val e = enforcer()
        assertThat(e.evaluate(listOf(ev(wechat, 0)), 60_000)).isEqualTo(EnforcementDecision.None)
    }

    // ==================================================================
    // 白名单
    // ==================================================================

    @Test
    fun `系统 UI 不拦截`() {
        val e = enforcer(listOf(BlockRule(RuleType.PACKAGE, "com.android.systemui")))
        assertThat(e.evaluate(listOf(ev("com.android.systemui", 0)), 10_000))
            .isEqualTo(EnforcementDecision.None)
    }


    @Test
    fun `权限控制器弹窗不拦截`() {
        val e = enforcer(listOf(BlockRule(RuleType.PACKAGE, "com.android.permissioncontroller")))
        assertThat(e.evaluate(listOf(ev("com.android.permissioncontroller", 0)), 10_000))
            .isEqualTo(EnforcementDecision.None)
    }

    @Test
    fun `各家 ROM 的安全中心按前缀白名单`() {
        val roms = listOf(
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.vivo.permissionmanager",
            "com.lbe.security.miui",
        )
        roms.forEach { pkg ->
            val e = enforcer(listOf(BlockRule(RuleType.PACKAGE, pkg)))
            assertThat(e.evaluate(listOf(ev(pkg, 0)), 10_000))
                .isEqualTo(EnforcementDecision.None)
        }
    }

    @Test
    fun `自己的包名不拦截`() {
        val e = enforcer(listOf(BlockRule(RuleType.PACKAGE, self)))
        assertThat(e.evaluate(listOf(ev(self, 0)), 10_000)).isEqualTo(EnforcementDecision.None)
    }

    // ==================================================================
    // 反绕过：这是整套机制的核心
    // ==================================================================











    @Test
    fun `拦截后重新打开仍要重新等满阈值`() {
        val e = enforcer()
        // 第一次命中：0 → 2500
        e.evaluate(listOf(ev(tiktok, 0)), 2_500).also {
            assertThat(it).isInstanceOf(EnforcementDecision.Block::class.java)
        }

        e.evaluate(listOf(ev(self, 3_000)), 3_000)

        // 立刻重新打开抖音：起点已被重置到 2500，此时 now=3100，只过了 600ms
        assertThat(e.evaluate(listOf(ev(tiktok, 3_100)), 3_100))
            .isEqualTo(EnforcementDecision.None)

        // 等到 2500 + 2001 = 4501 才再次命中
        assertThat(e.evaluate(emptyList(), 4_500)).isEqualTo(EnforcementDecision.None)
        assertThat(e.evaluate(emptyList(), 4_501)).isInstanceOf(EnforcementDecision.Block::class.java)
    }


    @Test
    fun `中途切到不拦的 App 不重置计时`() {
        val e = enforcer()
        e.evaluate(listOf(ev(tiktok, 0)), 500)
        e.evaluate(listOf(ev(wechat, 600)), 600)
        // 回到抖音，起点仍是 0
        val d = e.evaluate(listOf(ev(tiktok, 700)), 2_100)
        assertThat(d).isInstanceOf(EnforcementDecision.Block::class.java)
    }


    @Test
    fun `时间戳回拨不会导致永不拦截`() {
        val e = enforcer()
        e.evaluate(listOf(ev(tiktok, 100_000)), 100_000)
        // 时间被回拨到 1_000（比起点还早）
        val d = e.evaluate(listOf(ev(tiktok, 1_000)), 1_000)
        assertThat(d).isEqualTo(EnforcementDecision.None)
        // 起点被重置到 1000，再过 2001ms 仍然会拦
        assertThat(e.evaluate(emptyList(), 3_001)).isInstanceOf(EnforcementDecision.Block::class.java)
    }

    @Test
    fun `durationOf 在回拨时返回 0 而不是负数`() {
        val tracker = ForegroundTracker()
        tracker.onForeground(tiktok, 10_000)
        assertThat(tracker.durationOf(tiktok, 5_000)).isEqualTo(0L)
    }

    // ==================================================================
    // 状态表
    // ==================================================================

    @Test
    fun `状态表超过容量时淘汰最旧的`() {
        val tracker = ForegroundTracker(maxTracked = 3)
        tracker.onForeground("a", 1)
        tracker.onForeground("b", 2)
        tracker.onForeground("c", 3)
        tracker.onForeground("d", 4)
        assertThat(tracker.trackedCount).isEqualTo(3)
        assertThat(tracker.startOf("a")).isNull() // 最旧被淘汰
        assertThat(tracker.startOf("d")).isEqualTo(4)
    }

    @Test
    fun `重复进入同一包名不会增加记录数`() {
        val tracker = ForegroundTracker()
        repeat(100) { tracker.onForeground(tiktok, it.toLong()) }
        assertThat(tracker.trackedCount).isEqualTo(1)
        // 起点保持第一次的时间
        assertThat(tracker.startOf(tiktok)).isEqualTo(0L)
    }

    @Test
    fun `invalidate 是删除而 resetStart 是重置`() {
        val tracker = ForegroundTracker()
        tracker.onForeground(tiktok, 0)
        tracker.invalidate(tiktok)
        assertThat(tracker.startOf(tiktok)).isNull()

        tracker.onForeground(tiktok, 0)
        tracker.resetStart(tiktok, 500)
        assertThat(tracker.startOf(tiktok)).isEqualTo(500)
    }

    @Test
    fun `关闭时清空跟踪状态`() {
        val e = enforcer()
        e.evaluate(listOf(ev(tiktok, 0)), 100)
        e.enabled = false
        e.enabled = true
        // 重新打开后计时从零开始
        assertThat(e.evaluate(emptyList(), 2_500)).isEqualTo(EnforcementDecision.None)
    }

    // ==================================================================
    // 规则
    // ==================================================================

    @Test
    fun `分类规则按展开后的包名集合匹配`() {
        val rule = BlockRule(
            type = RuleType.CATEGORY,
            pattern = "social",
            categoryLabel = "社交",
            expandedPackages = setOf(tiktok, "com.instagram.android"),
        )
        val e = enforcer(listOf(rule))
        val d = e.evaluate(listOf(ev("com.instagram.android", 0)), 2_500)
        assertThat(d).isInstanceOf(EnforcementDecision.Block::class.java)
        assertThat((d as EnforcementDecision.Block).categoryLabel).isEqualTo("社交")
    }

    @Test
    fun `域名规则不参与前台应用判定`() {
        val e = enforcer(listOf(BlockRule(RuleType.DOMAIN, "douyin.com")))
        assertThat(e.blockedPackageCount()).isEqualTo(0)
        assertThat(e.evaluate(listOf(ev("douyin.com", 0)), 10_000))
            .isEqualTo(EnforcementDecision.None)
    }

    @Test
    fun `多条规则同时命中时命中的是第一条`() {
        val e = enforcer(
            listOf(
                BlockRule(RuleType.PACKAGE, tiktok, categoryLabel = "娱乐"),
                BlockRule(RuleType.CATEGORY, "video", categoryLabel = "视频", expandedPackages = setOf(tiktok)),
            ),
        )
        val d = e.evaluate(listOf(ev(tiktok, 0)), 2_500) as EnforcementDecision.Block
        assertThat(d.categoryLabel).isEqualTo("娱乐")
    }

    @Test
    fun `同一批事件里最后一次命中者才是目标`() {
        val other = "com.kuaishou.nebula"
        val e = enforcer(
            listOf(
                BlockRule(RuleType.PACKAGE, tiktok),
                BlockRule(RuleType.PACKAGE, other),
            ),
        )
        // 先抖音后快手（都在批里）
        val d = e.evaluate(listOf(ev(tiktok, 0), ev(other, 100)), 3_000) as EnforcementDecision.Block
        assertThat(d.packageName).isEqualTo(other)
    }

    @Test
    fun `一批事件里最后一个是自己则不拦截`() {
        val e = enforcer()
        // 抖音 → 自己（用户被顶回来后又切回专注页）
        // 此时 tiktok 的计时还在，但「最后前台」是自己，不该拦
        val d = e.evaluate(listOf(ev(tiktok, 0), ev(self, 3_000)), 3_000)
        assertThat(d).isEqualTo(EnforcementDecision.None)
    }

    @Test
    fun `空事件列表不产生拦截`() {
        val e = enforcer()
        assertThat(e.evaluate(emptyList(), 100_000)).isEqualTo(EnforcementDecision.None)
    }

    @Test
    fun `重新 configure 会替换规则集`() {
        val e = enforcer()
        assertThat(e.blockedPackageCount()).isEqualTo(1)
        e.configure(selfPackage = self, rules = listOf(BlockRule(RuleType.PACKAGE, wechat)))
        assertThat(e.blockedPackageCount()).isEqualTo(1)
        assertThat(e.evaluate(listOf(ev(tiktok, 0)), 10_000)).isEqualTo(EnforcementDecision.None)
        assertThat(e.evaluate(listOf(ev(wechat, 0)), 10_000)).isInstanceOf(EnforcementDecision.Block::class.java)
    }
}
