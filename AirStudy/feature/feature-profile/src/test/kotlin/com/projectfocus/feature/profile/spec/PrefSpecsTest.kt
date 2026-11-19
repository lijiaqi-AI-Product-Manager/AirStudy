package com.projectfocus.feature.profile.spec

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.datastore.PrefKey
import org.junit.Test




















class PrefSpecsTest {

    @Test
    fun `规格表自我诊断无问题`() {
        assertThat(PrefSpecs.diagnose()).isEmpty()
    }

    @Test
    fun `ALL 里的每一个键要么可达 要么显式登记为非设置项`() {
        val uncovered = PrefSpecs.uncoveredKeys(PrefKey.ALL)
        assertThat(uncovered).isEmpty()
    }

    @Test
    fun `非设置项里没有多余的键`() {

        //   说明有人删了 PrefKey 但忘了清这张名单 —— 它会永久掩盖一个真实缺口。
        val allIds = PrefKey.ALL.map { it.id }.toSet()
        val stale = PrefSpecs.NOT_A_SETTING.map { it.id }.filter { it !in allIds }
        assertThat(stale).isEmpty()
    }

    @Test
    fun `规格表里没有任何键重复`() {
        val keys = PrefSpecs.allKeys().map { it.id }
        assertThat(keys).containsNoDuplicates()
    }

    @Test
    fun `每一行都至少有一个可显示的分组`() {
        // 分组为空会让界面上出现一个只有标题的空块
        val empty = PrefSpecs.sections.filter { it.items.isEmpty() }
        assertThat(empty).isEmpty()
    }

    @Test
    fun `所有依赖的母项都是布尔键`() {

        //   如果母项不是 Boolean，`as? Boolean` 恒为 null → 子项永远置灰。
        //   这类错误在界面上表现为"有些行永远是灰的"，极难归因。
        val bad = PrefSpecs.sections
            .flatMap { it.items }
            .mapNotNull { item -> item.enabledWhen?.let { item to it } }
            .filter { (_, gate) -> gate.default !is Boolean }
            .map { (item, gate) -> "${item.key.id} ← ${gate.id}" }
        assertThat(bad).isEmpty()
    }

    @Test
    fun `依赖关系不存在环`() {
        // 虽然 spec 层面 enabledWhen 只能指 Boolean 键，
        // 但一个布尔键也可以自己依赖自己（比如 HapticMaster 依赖 HapticMaster）。
        // 那会让它永远置灰（自己关着→自己不可用→永远开不了）。
        val selfDependent = PrefSpecs.sections
            .flatMap { it.items }
            .filter { it.enabledWhen != null && it.enabledWhen === it.key }
            .map { it.key.id }
        assertThat(selfDependent).isEmpty()
    }

    @Test
    fun `滑杆区间都是有效且步长非零`() {
        val sliders = PrefSpecs.sections.flatMap { it.items }
            .mapNotNull { item -> (item.control as? PrefControl.Slider)?.let { item.key.id to it } }
        assertThat(sliders).isNotEmpty()
        sliders.forEach { (id, spec) ->
            assertThat(spec.max).isGreaterThan(spec.min)
            assertThat(spec.step).isGreaterThan(0f)
            // 步长不能比区间还大，否则只有一个可落点
            assertThat(spec.step).isLessThan(spec.max - spec.min)
            assertThat(id).isNotEmpty()
        }
    }

    @Test
    fun `滑杆的 min max 落在该项默认值两侧`() {

        //   控件先按 default 显示，绑定进度时被 clamp 到 min 或 max。
        val broken = PrefSpecs.sections.flatMap { it.items }.mapNotNull { item ->
            val slider = item.control as? PrefControl.Slider ?: return@mapNotNull null
            val default = (item.key.default as? Number)?.toFloat() ?: return@mapNotNull null
            if (default < slider.min || default > slider.max) {
                "${item.key.id}: default=$default 不在 [${slider.min}, ${slider.max}]"
            } else {
                null
            }
        }
        assertThat(broken).isEmpty()
    }

    @Test
    fun `分段控件的默认值是其中一个选项`() {

        //   → `coerceAtLeast(0)` 让它显示成第 0 个 → 用户看到"跟随系统"
        //   而实际值是别的 —— 界面在撒谎。
        val broken = PrefSpecs.sections.flatMap { it.items }.mapNotNull { item ->
            val seg = item.control as? PrefControl.Segmented ?: return@mapNotNull null
            val default = item.key.default as? String ?: return@mapNotNull null
            if (seg.options.none { it.value == default }) {
                "${item.key.id}: default=\"$default\" 不在 ${seg.options.map { it.value }}"
            } else {
                null
            }
        }
        assertThat(broken).isEmpty()
    }

    @Test
    fun `分段控件的选项值唯一`() {
        val broken = PrefSpecs.sections.flatMap { it.items }.mapNotNull { item ->
            val seg = item.control as? PrefControl.Segmented ?: return@mapNotNull null
            val values = seg.options.map { it.value }
            if (values.size != values.toSet().size) "${item.key.id}: $values" else null
        }
        assertThat(broken).isEmpty()
    }

    @Test
    fun `specOf 对每个登记过的键都能找到`() {
        PrefSpecs.allKeys().forEach { key ->
            assertThat(PrefSpecs.specOf(key)).isNotNull()
        }
    }

    @Test
    fun `specOf 对未登记的键返回 null`() {
        assertThat(PrefSpecs.specOf(PrefKey.RecentAirportIds)).isNull()
    }

    @Test
    fun `dependentsOf 能找到全部子项`() {
        val haptics = PrefSpecs.dependentsOf(PrefKey.HapticMaster).map { it.key.id }
        assertThat(haptics).containsExactly(
            PrefKey.HapticArrival.id,
            PrefKey.HapticTear.id,
            PrefKey.HapticTimeline.id,
            PrefKey.HapticPauseHold.id,
            PrefKey.HapticBoardingPass.id,
        )
    }

    @Test
    fun `没有子项的母项返回空列表而不是 null`() {
        assertThat(PrefSpecs.dependentsOf(PrefKey.RecentAirportIds)).isEmpty()
    }

    @Test
    fun `液态玻璃的七个滑杆都挂在 GlassEnabled 下`() {

        //   结果那个参数在界面上不可达。用测试锁住数量，删一个就红。
        val sliders = PrefSpecs.dependentsOf(PrefKey.GlassEnabled)
            .filter { it.control is PrefControl.Slider }
        assertThat(sliders).hasSize(7)
    }

    @Test
    fun `推送的四个子项都挂在 PushMaster 下`() {
        val subs = PrefSpecs.dependentsOf(PrefKey.PushMaster).map { it.key.id }
        assertThat(subs).containsExactly(
            PrefKey.PushDailyReport.id,
            PrefKey.PushWeeklyReport.id,
            PrefKey.PushMonthlyReport.id,
            PrefKey.PushPlanReminder.id,
        )
    }

    @Test
    fun `语音播报的三个子项都挂在 TtsEnabled 下`() {
        val subs = PrefSpecs.dependentsOf(PrefKey.TtsEnabled).map { it.key.id }
        assertThat(subs).containsExactly(
            PrefKey.TtsGender.id,
            PrefKey.TtsLang1.id,
            PrefKey.TtsLang2.id,
        )
    }

    @Test
    fun `非交互项一定不是因为母项而不可交互`() {

        //   两者同时存在会让"为什么这行是灰的"变成两个原因，无法向用户解释。
        val both = PrefSpecs.sections.flatMap { it.items }
            .filter { !it.interactive && it.enabledWhen != null }
            .map { it.key.id }
        assertThat(both).isEmpty()
    }

    @Test
    fun `正计时飞行被标为实验性功能`() {


        val spec = PrefSpecs.specOf(PrefKey.CountUpFlight)
        assertThat(spec).isNotNull()
        assertThat(spec!!.experimental).isTrue()
    }
}
