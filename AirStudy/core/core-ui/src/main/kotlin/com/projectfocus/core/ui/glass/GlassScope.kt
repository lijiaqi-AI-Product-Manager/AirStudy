package com.projectfocus.core.ui.glass

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.projectfocus.core.ui.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel


class GlassScope(private val scope: CoroutineScope = MainScope()) {

    val ticker = GlassTicker(scope)

    private val views = mutableListOf<LiquidGlassView>()

    /** 当前是否处于「流动」状态（有可见的、非静止的玻璃） */
    val isAnimating: Boolean get() = ticker.isRunning

    fun register(view: LiquidGlassView) {
        if (views.contains(view)) return
        views.add(view)
        view.ticker = ticker
        updateLiquidBudget()
        if (views.any { it.isAttachedToWindow && it.isVisible }) {
            ticker.resume()
        }
    }

    fun unregister(view: LiquidGlassView) {
        if (!views.remove(view)) return
        view.ticker = null
        if (views.isEmpty()) ticker.pause() else updateLiquidBudget()
    }

    /** 页面可见性变化时调用（onResume / onPause / onHiddenChanged） */
    fun setVisible(visible: Boolean) {
        if (visible) ticker.resume() else ticker.pause()
    }









    private fun updateLiquidBudget() {
        val sorted = views.sortedByDescending { it.width.toLong() * it.height }
        sorted.forEachIndexed { index, view ->
            val current = view.getGlassParams()
            val shouldFlow = index < LiquidGlassView.MAX_LIQUID_VIEWS
            if (shouldFlow && current.liquid <= 0.01f) {
                view.setGlassParams(current.copy(liquid = GlassParams.DEFAULT_LIQUID))
            } else if (!shouldFlow && current.liquid > 0.01f) {
                view.setGlassParams(current.copy(liquid = 0f))
            }
        }
    }

    /** 全局调整玻璃参数（设置页的 7 个滑杆调这里） */
    fun applyParams(params: GlassParams) {
        views.forEach { it.setGlassParams(params) }
    }

    /** 全局圆角（不同页面可以给不同的圆角体系） */
    fun applyCornerRadius(radiusPx: Float) {
        views.forEach { it.setGlassCornerRadius(radiusPx) }
    }

    fun dispose() {
        ticker.dispose()
        views.forEach { it.ticker = null }
        views.clear()
        scope.cancel()
    }
}















object GlassFactory {

    /**
     * 按设备能力创建玻璃容器。
     *
     * @param forceLevel 强制指定档位（调试 / 截图对比用）
     */
    fun create(
        context: Context,
        attrs: AttributeSet? = null,
        forceLevel: GlassLevel? = null,
    ): View {
        val level = forceLevel ?: GlassCapability.level(context)
        return if (level == GlassLevel.SOLID) {
            FallbackGlassView(context, attrs)
        } else {
            LiquidGlassView(context, attrs)
        }
    }

    /** 当前是否真的能出玻璃效果（供设置页灰掉开关） */
    fun isGlassAvailable(context: Context): Boolean =
        GlassCapability.level(context) != GlassLevel.SOLID
}


class FallbackGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        setBackgroundResource(R.drawable.bg_glass_fallback)
        clipChildren = false
        clipToPadding = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
