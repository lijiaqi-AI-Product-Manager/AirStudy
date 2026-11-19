package com.projectfocus.core.ui.glass

import androidx.annotation.ColorInt


data class GlassParams(
    /** 毛玻璃模糊半径（dp）。0 = 不模糊，纯折射。 */
    val blurRadiusDp: Float = DEFAULT_BLUR_RADIUS_DP,

    /** 折射高度（dp）—— 边缘弯折的「厚度」。越大玻璃越「厚」。 */
    val refractionHeightDp: Float = DEFAULT_REFRACTION_HEIGHT_DP,

    /** 折射偏移（dp）—— 色散的基础偏移量。 */
    val refractionOffsetDp: Float = DEFAULT_REFRACTION_OFFSET_DP,

    /** 色散强度 0~1。0.6 以上会像「故障艺术」，慎用。 */
    val dispersion: Float = DEFAULT_DISPERSION,

    /** 液态扰动强度 0~1。0 = 完全静止（省电模式可用）。 */
    val liquid: Float = DEFAULT_LIQUID,

    /** 叠色透明度 0~1。日间用 0.42，夜间用 0.14（见 colors.xml 的说明）。 */
    val tintAlpha: Float = DEFAULT_TINT_ALPHA,

    /** 叠色本身。默认白 —— 在深色背景上「提亮」，在浅色背景上「不发灰」。 */
    @param:ColorInt val tintColor: Int = DEFAULT_TINT_COLOR,
) {

    init {
        // 构造时不做校验 —— data class 的 init 里抛异常会让 copy() 也抛，
        // 反而难用。统一在 sanitized() 里夹，调用方显式要求时才夹。
    }








    fun sanitized(): GlassParams = GlassParams(
        blurRadiusDp = blurRadiusDp.safe(DEFAULT_BLUR_RADIUS_DP, 0f, MAX_BLUR_RADIUS_DP),
        refractionHeightDp = refractionHeightDp.safe(DEFAULT_REFRACTION_HEIGHT_DP, 0f, MAX_REFRACTION_HEIGHT_DP),
        refractionOffsetDp = refractionOffsetDp.safe(DEFAULT_REFRACTION_OFFSET_DP, 0f, MAX_REFRACTION_OFFSET_DP),
        dispersion = dispersion.safe(DEFAULT_DISPERSION, 0f, 1f),
        liquid = liquid.safe(DEFAULT_LIQUID, 0f, 1f),
        tintAlpha = tintAlpha.safe(DEFAULT_TINT_ALPHA, 0f, 1f),
        tintColor = tintColor,
    )

    val isBlurDisabled: Boolean get() = blurRadiusDp <= 0.05f
    val isStatic: Boolean get() = liquid <= 0.01f

    companion object {
        const val DEFAULT_BLUR_RADIUS_DP = 18f
        const val DEFAULT_REFRACTION_HEIGHT_DP = 1.6f
        const val DEFAULT_REFRACTION_OFFSET_DP = 0.9f
        const val DEFAULT_DISPERSION = 0.35f
        const val DEFAULT_LIQUID = 0.5f
        const val DEFAULT_TINT_ALPHA = 0.14f

        /**
         * 白色。用 Int 字面量而不是 Color.WHITE，
         * 因为这是**唯一允许出现十六进制的地方**（它是 token 的默认值，
         * 不是业务硬编码），而 Color.WHITE 的静态初始化在单元测试里可能没被 mock。
         */
        const val DEFAULT_TINT_COLOR = 0xFFFFFFFF.toInt()

        const val MAX_BLUR_RADIUS_DP = 48f
        const val MAX_REFRACTION_HEIGHT_DP = 8f
        const val MAX_REFRACTION_OFFSET_DP = 6f

        /** 完全关掉玻璃（省电 / 无障碍「减少动效」） */
        val OFF = GlassParams(
            blurRadiusDp = 0f,
            refractionHeightDp = 0f,
            refractionOffsetDp = 0f,
            dispersion = 0f,
            liquid = 0f,
            tintAlpha = 0.24f,
        )

        /** 跟随系统「移除动画」时用：保留玻璃质感但不流动 */
        val STATIC = GlassParams(liquid = 0f)

        private fun Float.safe(fallback: Float, min: Float, max: Float): Float =
            if (isFinite()) coerceIn(min, max) else fallback
    }
}
