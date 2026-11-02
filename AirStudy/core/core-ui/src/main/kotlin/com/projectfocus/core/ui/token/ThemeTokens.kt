package com.projectfocus.core.ui.token

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.projectfocus.core.ui.R


























class ThemeTokens(private val context: Context) {

    // ==================== 颜色 ====================
    @get:ColorInt val bgBase get() = color(R.color.bg_base)
    @get:ColorInt val bgElevated get() = color(R.color.bg_elevated)
    @get:ColorInt val bgOverlay get() = color(R.color.bg_overlay)
    @get:ColorInt val scrim get() = color(R.color.scrim)

    @get:ColorInt val surfaceGlass get() = color(R.color.surface_glass)
    @get:ColorInt val surfaceGlassStroke get() = color(R.color.surface_glass_stroke)

    @get:ColorInt val textPrimary get() = color(R.color.text_primary)
    @get:ColorInt val textSecondary get() = color(R.color.text_secondary)
    @get:ColorInt val textTertiary get() = color(R.color.text_tertiary)
    @get:ColorInt val textDisabled get() = color(R.color.text_disabled)
    @get:ColorInt val textOnAccent get() = color(R.color.text_on_accent)

    @get:ColorInt val divider get() = color(R.color.divider)
    @get:ColorInt val strokeSubtle get() = color(R.color.stroke_subtle)

    @get:ColorInt val accentPrimary get() = color(R.color.accent_primary)
    @get:ColorInt val accentPrimaryPressed get() = color(R.color.accent_primary_pressed)
    @get:ColorInt val accentContainer get() = color(R.color.accent_container)


    @get:ColorInt val brandSoft get() = color(R.color.brand_soft)
    @get:ColorInt val accentPop get() = color(R.color.accent_pop)
    @get:ColorInt val glowBrand get() = color(R.color.glow_brand)
    @get:ColorInt val glowPop get() = color(R.color.glow_pop)
    @get:ColorInt val sparkle get() = color(R.color.sparkle)
    @get:ColorInt val buttonGradientStart get() = color(R.color.btn_gradient_start)
    @get:ColorInt val buttonGradientEnd get() = color(R.color.btn_gradient_end)

    @get:ColorInt val success get() = color(R.color.semantic_success)
    @get:ColorInt val warning get() = color(R.color.semantic_warning)
    @get:ColorInt val error get() = color(R.color.semantic_error)
    @get:ColorInt val info get() = color(R.color.semantic_info)

    @get:ColorInt val successContainer get() = color(R.color.semantic_success_container)
    @get:ColorInt val warningContainer get() = color(R.color.semantic_warning_container)
    @get:ColorInt val errorContainer get() = color(R.color.semantic_error_container)
    @get:ColorInt val infoContainer get() = color(R.color.semantic_info_container)

    @get:ColorInt val seatEmpty get() = color(R.color.seat_empty)
    @get:ColorInt val seatTaken get() = color(R.color.seat_taken)
    @get:ColorInt val seatMine get() = color(R.color.seat_mine)
    @get:ColorInt val seatDisabled get() = color(R.color.seat_disabled)

    @get:ColorInt val skyTakeoffTop get() = color(R.color.sky_takeoff_top)
    @get:ColorInt val skyTakeoffBottom get() = color(R.color.sky_takeoff_bottom)
    @get:ColorInt val skyCruiseTop get() = color(R.color.sky_cruise_top)
    @get:ColorInt val skyCruiseBottom get() = color(R.color.sky_cruise_bottom)
    @get:ColorInt val skyLandingTop get() = color(R.color.sky_landing_top)
    @get:ColorInt val skyLandingBottom get() = color(R.color.sky_landing_bottom)

    // ==================== 尺寸（px，已按 density 换算） ====================

    /** dp → px。用 `+ 0.5f` 四舍五入而不是 `toInt()` 截断 ——
     *  截断会让 1.6dp 在 xxhdpi 上变成 4px（应为 5px），累积后整体偏小。 */
    fun dp(value: Float): Float = value * context.resources.displayMetrics.density + 0.5f

    fun dpInt(value: Float): Int = dp(value).toInt()

    val spaceXxs get() = dimen(R.dimen.space_xxs)
    val spaceXs get() = dimen(R.dimen.space_xs)
    val spaceS get() = dimen(R.dimen.space_s)
    val spaceM get() = dimen(R.dimen.space_m)
    val spaceL get() = dimen(R.dimen.space_l)
    val spaceXl get() = dimen(R.dimen.space_xl)
    val spaceXxl get() = dimen(R.dimen.space_xxl)
    val screenMargin get() = dimen(R.dimen.screen_margin)

    val radiusS get() = dimen(R.dimen.radius_s)
    val radiusM get() = dimen(R.dimen.radius_m)
    val radiusL get() = dimen(R.dimen.radius_l)
    val radiusXl get() = dimen(R.dimen.radius_xl)

    val strokeHairline get() = dimen(R.dimen.stroke_hairline)
    val touchTargetMin get() = dimen(R.dimen.touch_target_min)

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)

    private fun dimen(id: Int): Int = context.resources.getDimensionPixelSize(id)








    @ColorInt
    fun withAlpha(@ColorInt color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }








    @ColorInt
    fun blend(@ColorInt from: Int, @ColorInt to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        return Color.argb(
            lerpChannel(Color.alpha(from), Color.alpha(to), f),
            lerpChannel(Color.red(from), Color.red(to), f),
            lerpChannel(Color.green(from), Color.green(to), f),
            lerpChannel(Color.blue(from), Color.blue(to), f),
        )
    }

    private fun lerpChannel(a: Int, b: Int, f: Float): Int = (a + (b - a) * f + 0.5f).toInt()
}
