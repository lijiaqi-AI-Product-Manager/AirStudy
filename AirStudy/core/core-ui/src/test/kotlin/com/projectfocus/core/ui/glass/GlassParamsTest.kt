package com.projectfocus.core.ui.glass

import com.google.common.truth.Truth.assertThat
import org.junit.Test















class GlassParamsTest {

    @Test
    fun `默认值等于设计规范`() {
        val p = GlassParams()
        assertThat(p.blurRadiusDp).isEqualTo(18f)
        assertThat(p.refractionHeightDp).isEqualTo(1.6f)
        assertThat(p.refractionOffsetDp).isEqualTo(0.9f)
        assertThat(p.dispersion).isEqualTo(0.35f)
        assertThat(p.liquid).isEqualTo(0.5f)
        assertThat(p.tintAlpha).isEqualTo(0.14f)
        assertThat(p.tintColor).isEqualTo(0xFFFFFFFF.toInt())
    }

    @Test
    fun `NaN 被替换为默认值而不是穿透`() {

        val p = GlassParams(
            blurRadiusDp = Float.NaN,
            refractionHeightDp = Float.NaN,
            dispersion = Float.NaN,
            liquid = Float.NaN,
            tintAlpha = Float.NaN,
        ).sanitized()

        assertThat(p.blurRadiusDp).isEqualTo(GlassParams.DEFAULT_BLUR_RADIUS_DP)
        assertThat(p.refractionHeightDp).isEqualTo(GlassParams.DEFAULT_REFRACTION_HEIGHT_DP)
        assertThat(p.dispersion).isEqualTo(GlassParams.DEFAULT_DISPERSION)
        assertThat(p.liquid).isEqualTo(GlassParams.DEFAULT_LIQUID)
        assertThat(p.tintAlpha).isEqualTo(GlassParams.DEFAULT_TINT_ALPHA)
    }

    @Test
    fun `Infinity 被替换为默认值`() {
        val p = GlassParams(
            blurRadiusDp = Float.POSITIVE_INFINITY,
            dispersion = Float.NEGATIVE_INFINITY,
        ).sanitized()
        assertThat(p.blurRadiusDp).isEqualTo(GlassParams.DEFAULT_BLUR_RADIUS_DP)
        assertThat(p.dispersion).isEqualTo(GlassParams.DEFAULT_DISPERSION)
    }







    @Test
    fun `coerceIn 无法拦截 NaN —— 证明必须先查 isFinite`() {
        val nan = Float.NaN
        assertThat(nan.coerceIn(0f, 1f).isNaN()).isTrue()
    }

    @Test
    fun `超出上界的值被夹到上界`() {
        val p = GlassParams(
            blurRadiusDp = 999f,
            refractionHeightDp = 999f,
            refractionOffsetDp = 999f,
            dispersion = 99f,
            liquid = 99f,
            tintAlpha = 99f,
        ).sanitized()

        assertThat(p.blurRadiusDp).isEqualTo(GlassParams.MAX_BLUR_RADIUS_DP)
        assertThat(p.refractionHeightDp).isEqualTo(GlassParams.MAX_REFRACTION_HEIGHT_DP)
        assertThat(p.refractionOffsetDp).isEqualTo(GlassParams.MAX_REFRACTION_OFFSET_DP)
        assertThat(p.dispersion).isEqualTo(1f)
        assertThat(p.liquid).isEqualTo(1f)
        assertThat(p.tintAlpha).isEqualTo(1f)
    }

    @Test
    fun `负值被夹到零`() {
        val p = GlassParams(
            blurRadiusDp = -10f,
            dispersion = -1f,
            tintAlpha = -0.5f,
        ).sanitized()
        assertThat(p.blurRadiusDp).isEqualTo(0f)
        assertThat(p.dispersion).isEqualTo(0f)
        assertThat(p.tintAlpha).isEqualTo(0f)
    }

    @Test
    fun `OFF 参数产生完全静止且无模糊`() {
        assertThat(GlassParams.OFF.isBlurDisabled).isTrue()
        assertThat(GlassParams.OFF.isStatic).isTrue()
        // OFF 仍然要有叠色，否则内容在复杂背景上看不清
        assertThat(GlassParams.OFF.tintAlpha).isGreaterThan(0f)
    }

    @Test
    fun `STATIC 保留模糊但不动`() {
        assertThat(GlassParams.STATIC.isStatic).isTrue()
        assertThat(GlassParams.STATIC.isBlurDisabled).isFalse()
        assertThat(GlassParams.STATIC.liquid).isEqualTo(0f)
    }

    @Test
    fun `sanitized 是幂等的`() {
        val once = GlassParams(blurRadiusDp = Float.NaN, dispersion = 99f).sanitized()
        val twice = once.sanitized()
        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `sanitized 不改变合法值`() {
        val legal = GlassParams(
            blurRadiusDp = 12f,
            refractionHeightDp = 2f,
            refractionOffsetDp = 1f,
            dispersion = 0.5f,
            liquid = 0.3f,
            tintAlpha = 0.2f,
            tintColor = 0xFF00FF00.toInt(),
        )
        assertThat(legal.sanitized()).isEqualTo(legal)
    }

    @Test
    fun `tintColor 原样保留（不参与夹值）`() {
        val p = GlassParams(tintColor = 0x12ABCDEF).sanitized()
        assertThat(p.tintColor).isEqualTo(0x12ABCDEF)
    }
}
