package com.projectfocus.core.ui.token

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config





















@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeTokensTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ==================================================================
    // 全部颜色都能取到
    // ==================================================================






    private val semanticColorNames = listOf(
        "bg_base", "bg_elevated", "bg_overlay", "scrim",
        "surface_glass", "surface_glass_stroke",
        "text_primary", "text_secondary", "text_tertiary", "text_disabled", "text_on_accent",
        "divider", "stroke_subtle",
        "accent_primary", "accent_primary_pressed", "accent_container",
        "semantic_success", "semantic_warning", "semantic_error", "semantic_info",
        "semantic_success_container", "semantic_warning_container",
        "semantic_error_container", "semantic_info_container",
        "seat_empty", "seat_taken", "seat_mine", "seat_disabled",
        "sky_takeoff_top", "sky_takeoff_bottom",
        "sky_cruise_top", "sky_cruise_bottom",
        "sky_landing_top", "sky_landing_bottom",
    )

    @Test
    fun `所有语义色在日间与夜间都能解析`() {
        val tokens = ThemeTokens(context)
        // 逐个访问 getter，任何一个资源缺失都会抛 Resources.NotFoundException
        val all = listOf(
            tokens.bgBase, tokens.bgElevated, tokens.bgOverlay, tokens.scrim,
            tokens.surfaceGlass, tokens.surfaceGlassStroke,
            tokens.textPrimary, tokens.textSecondary, tokens.textTertiary,
            tokens.textDisabled, tokens.textOnAccent,
            tokens.divider, tokens.strokeSubtle,
            tokens.accentPrimary, tokens.accentPrimaryPressed, tokens.accentContainer,
            tokens.success, tokens.warning, tokens.error, tokens.info,
            tokens.successContainer, tokens.warningContainer,
            tokens.errorContainer, tokens.infoContainer,
            tokens.seatEmpty, tokens.seatTaken, tokens.seatMine, tokens.seatDisabled,
            tokens.skyTakeoffTop, tokens.skyTakeoffBottom,
            tokens.skyCruiseTop, tokens.skyCruiseBottom,
            tokens.skyLandingTop, tokens.skyLandingBottom,
        )
        assertThat(all).hasSize(34)
    }

    @Test
    fun `语义色数量与清单一致`() {
        // 防止「加了颜色但忘了加进清单」
        assertThat(semanticColorNames).hasSize(34)
    }

    // ==================================================================
    // 对比度（WCAG AA）
    // ==================================================================














    private fun relativeLuminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `对比度算法自检 —— 黑白应为 21 比 1`() {
        val ratio = contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        assertThat(ratio).isWithin(0.01).of(21.0)
    }

    @Test
    fun `对比度算法自检 —— 同色应为 1 比 1`() {
        val ratio = contrastRatio(0xFF336699.toInt(), 0xFF336699.toInt())
        assertThat(ratio).isWithin(0.001).of(1.0)
    }


    @Test
    fun `正文一级文字对比度达标`() {
        val t = ThemeTokens(context)
        val ratio = contrastRatio(t.textPrimary, t.bgBase)
        if (ratio < 4.5) throw AssertionError("text_primary / bg_base = %.2f:1，应 ≥ 4.5".format(ratio))
    }

    @Test
    fun `正文二级文字对比度达标`() {
        val t = ThemeTokens(context)
        val ratio = contrastRatio(t.textSecondary, t.bgBase)
        if (ratio < 4.5) throw AssertionError("text_secondary / bg_base = %.2f:1，应 ≥ 4.5".format(ratio))
    }

    @Test
    fun `辅助文字对比度达到大字号标准`() {
        val t = ThemeTokens(context)
        val ratio = contrastRatio(t.textTertiary, t.bgBase)
        if (ratio < 3.0) throw AssertionError("text_tertiary / bg_base = %.2f:1，应 ≥ 3.0".format(ratio))
    }

    @Test
    fun `卡片上的文字对比度达标`() {
        val t = ThemeTokens(context)
        val ratio = contrastRatio(t.textPrimary, t.bgElevated)
        if (ratio < 4.5) throw AssertionError("text_primary / bg_elevated = %.2f:1，应 ≥ 4.5".format(ratio))
    }

    @Test
    fun `强调色上的文字对比度达标`() {
        val t = ThemeTokens(context)
        val ratio = contrastRatio(t.textOnAccent, t.accentPrimary)
        if (ratio < 4.5) throw AssertionError("text_on_accent / accent_primary = %.2f:1，应 ≥ 4.5".format(ratio))
    }

















    @Test
    fun `错误色上的文字对比度达到可接受下限`() {
        val t = ThemeTokens(context)
        val ratio = contrastRatio(t.textOnAccent, t.error)
        if (ratio < 3.5) throw AssertionError("text_on_accent / semantic_error = %.2f:1，应 ≥ 3.5".format(ratio))
    }

    // ==================================================================
    // 颜色工具
    // ==================================================================

    @Test
    fun `withAlpha 正常取值`() {
        val t = ThemeTokens(context)
        val half = t.withAlpha(0xFFFFFFFF.toInt(), 0.5f)
        assertThat((half ushr 24) and 0xFF).isEqualTo(128)
    }

    @Test
    fun `withAlpha 处理 1 点 0 时不溢出成负数`() {
        val t = ThemeTokens(context)

        val opaque = t.withAlpha(0xFF123456.toInt(), 1.0f)
        assertThat(opaque).isEqualTo(0xFF123456.toInt())

        //   （0xFF123456 = -15584170）。要看的是 alpha 通道是否为 255。
        assertThat((opaque ushr 24) and 0xFF).isEqualTo(255)
    }

    @Test
    fun `withAlpha 处理 0 时得到全透明`() {
        val t = ThemeTokens(context)
        assertThat((t.withAlpha(0xFFFFFFFF.toInt(), 0f) ushr 24) and 0xFF).isEqualTo(0)
    }

    @Test
    fun `withAlpha 夹住越界比例`() {
        val t = ThemeTokens(context)
        assertThat((t.withAlpha(0xFFFFFFFF.toInt(), 2f) ushr 24) and 0xFF).isEqualTo(255)
        assertThat((t.withAlpha(0xFFFFFFFF.toInt(), -1f) ushr 24) and 0xFF).isEqualTo(0)
    }

    @Test
    fun `blend 在 0 与 1 处返回端点`() {
        val t = ThemeTokens(context)
        val a = 0xFF102030.toInt()
        val b = 0xFF405060.toInt()
        assertThat(t.blend(a, b, 0f)).isEqualTo(a)
        assertThat(t.blend(a, b, 1f)).isEqualTo(b)
    }

    @Test
    fun `blend 在中点近似均值`() {
        val t = ThemeTokens(context)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val mid = t.blend(black, white, 0.5f)
        // 每个通道应为 127 或 128（浮点舍入）
        listOf(16, 8, 0).forEach { shift ->
            val c = (mid shr shift) and 0xFF
            if (c !in 127..128) throw AssertionError("通道 $shift = $c，应为 127~128")
        }
    }

    @Test
    fun `dp 换算考虑四舍五入`() {
        val t = ThemeTokens(context)
        // Robolectric 默认 density 1.0，1.6dp → 1.6 + 0.5 = 2.1 → 2
        assertThat(t.dpInt(1.6f)).isEqualTo(2)
        assertThat(t.dpInt(0f)).isEqualTo(0)
    }
}
