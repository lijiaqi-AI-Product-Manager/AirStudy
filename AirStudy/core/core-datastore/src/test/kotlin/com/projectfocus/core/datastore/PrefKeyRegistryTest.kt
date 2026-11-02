package com.projectfocus.core.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.Test














class PrefKeyRegistryTest {

    @Test
    fun `没有任何键名重复`() {
        val dup = PrefKey.duplicates()
        assertThat(dup).isEmpty()
    }

    @Test
    fun `所有键名都有 pf 前缀`() {
        val bad = PrefKey.ALL.filterNotNull().filterNot { it.name.startsWith("pf_") }
        assertThat(bad.map { it.name }).isEmpty()
    }

    @Test
    fun `所有键名都是小写下划线`() {
        // 避免出现 pf_GlassEnabled 这种混写 —— 在不同文件里对比时极易看错
        val pattern = Regex("^pf_[a-z0-9_]+$")
        val bad = PrefKey.ALL.filterNotNull().filterNot { pattern.matches(it.name) }
        assertThat(bad.map { it.name }).isEmpty()
    }


    @Test
    fun `玻璃参数默认值在合法范围内`() {
        val ratioKeys = listOf(
            PrefKey.GlassDispersion to PrefKey.GlassDispersion.default,
            PrefKey.GlassLiquid to PrefKey.GlassLiquid.default,
            PrefKey.GlassTintAlphaDay to PrefKey.GlassTintAlphaDay.default,
            PrefKey.GlassTintAlphaNight to PrefKey.GlassTintAlphaNight.default,
        )

        ratioKeys.forEach { (key, v) ->
            if (v < 0f || v > 1f) throw AssertionError("${key.name} 默认值 $v 越界，应在 0..1")
        }

        assertThat(PrefKey.GlassBlurRadius.default).isAtLeast(0f)
        assertThat(PrefKey.GlassBlurRadius.default).isAtMost(48f)
        assertThat(PrefKey.GlassRefractionHeight.default).isAtLeast(0f)
        assertThat(PrefKey.GlassRefractionHeight.default).isAtMost(8f)
        assertThat(PrefKey.GlassRefractionOffset.default).isAtLeast(0f)
        assertThat(PrefKey.GlassRefractionOffset.default).isAtMost(6f)
    }

    @Test
    fun `主题模式默认值在枚举集合里`() {
        assertThat(PrefKey.ThemeMode.default).isIn(ThemeMode.ALL)
    }

    @Test
    fun `屏幕方向默认值在枚举集合里`() {
        assertThat(PrefKey.ScreenOrientation.default).isIn(Orientation.ALL)
    }

    @Test
    fun `TTS 音色默认值在枚举集合里`() {
        assertThat(PrefKey.TtsGender.default).isIn(Gender.ALL)
    }


    @Test
    fun `液态玻璃参数与竞品 7 个滑杆一一对应`() {
        val glassKeys = PrefKey.ALL.filterNotNull().filter {
            it.name.contains("glass") && it.legacyNames.any { legacy -> legacy.contains("liquid_glass_slider") }
        }
        // blur_frost / blur_liquid / dispersion / refraction_height /
        // refraction_offset / tint_alpha_day / tint_alpha_night = 7
        assertThat(glassKeys).hasSize(7)
    }

    @Test
    fun `含 legacy 名的键，legacy 名互不重复`() {
        val allLegacy = PrefKey.ALL.filterNotNull().flatMap { it.legacyNames }
        assertThat(allLegacy).containsNoDuplicates()
    }











    @Test
    fun `ALL 里的键对象都是单例且不重复`() {


        val clean = PrefKey.ALL.filterNotNull()
        val identities = clean.toSet()
        assertThat(identities).hasSize(clean.size)
        // 每个键的名字必须唯一（duplicates() 已在另一条测试里覆盖，
        // 这里再独立断言一次，避免 duplicates() 的实现出错时假绿）
        assertThat(PrefKey.ALL.filterNotNull().map { it.name }).containsNoDuplicates()
    }
}
