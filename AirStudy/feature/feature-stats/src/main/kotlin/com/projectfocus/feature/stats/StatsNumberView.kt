package com.projectfocus.feature.stats

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.projectfocus.core.ui.R as UiR
import kotlin.math.abs

internal class StatsNumberView(context: Context, size: Float) : TextView(context) {
    private var revision = 0L
    private var animator: ValueAnimator? = null
    private var pending: Runnable? = null
    private var format: ((Double) -> CharSequence)? = null
    private var target = 0.0
    val isAnimating get() = animator != null || pending != null

    init {
        textSize = size
        setTextColor(StatsVisual(context).primaryText)
        includeFontPadding = false
        typeface = ResourcesCompat.getFont(context, UiR.font.harmonyos_sans_sc_black)
            ?: ResourcesCompat.getFont(context, UiR.font.harmonyos_sans_sc_medium)
    }

    fun showStatic(value: CharSequence) {
        revision++
        cancel(false)
        format = null
        text = value
    }

    fun animateTo(value: Double, formatter: (Double) -> CharSequence) {
        require(value.isFinite())
        val expected = ++revision
        cancel(false)
        format = formatter
        target = value
        text = formatter(0.0)
        if (!ValueAnimator.areAnimatorsEnabled()) { finish(); return }
        if (isLaidOut && width != 0) start(expected, value, formatter)
        else pending = Runnable {
            pending = null
            if (expected == revision) start(expected, value, formatter)
        }.also { post(it) }
    }

    private fun start(expected: Long, value: Double, formatter: (Double) -> CharSequence) {
        if (expected != revision || !isAttachedToWindow || !isShown || abs(value) < 0.0001) { finish(); return }
        val running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { current ->
                if (animator === current && revision == expected) text = formatter(value * (current.animatedValue as Float).toDouble())
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation && expected == revision) { animator = null; finish() }
                }
            })
        }
        animator = running
        running.start()
    }

    private fun finish() { format?.let { text = it(target) } }
    fun finishAnimation() = cancel(true)
    private fun cancel(showFinal: Boolean) {
        pending?.let(::removeCallbacks)
        pending = null
        val old = animator
        animator = null
        old?.removeAllListeners()
        old?.removeAllUpdateListeners()
        old?.cancel()
        if (showFinal) finish()
    }

    override fun onDetachedFromWindow() { cancel(true); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) cancel(true)
    }
}
