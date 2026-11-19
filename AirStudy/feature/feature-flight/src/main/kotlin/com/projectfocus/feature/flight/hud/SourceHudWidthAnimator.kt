package com.projectfocus.feature.flight.hud

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/** Source a5.e2.k / a5.b2 case0 / a5.d2 case0, owned by one attached HUD. */
internal class SourceHudWidthAnimator {
    private val naturalWidths = HashMap<Int, Int>()
    private val running = HashMap<Int, ValueAnimator>()
    private val pending = HashMap<View, Runnable>()

    fun fit(content: View, container: View) {
        pending.remove(content)?.let(content::removeCallbacks)
        val task = Runnable {
            pending.remove(content)
            val activity = content.context.activity()
            if (activity?.let { it.isFinishing || it.isDestroyed } == true) return@Runnable
            content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val measured = content.measuredWidth
            if (measured <= 0) return@Runnable
            val width = container.width
            val key = container.id
            if (width > 0) {
                if (naturalWidths[key] == measured || abs(measured - width) <= 1) {
                    naturalWidths[key] = measured
                } else {
                    naturalWidths[key] = measured
                    running.remove(key)?.cancel()
                    running[key] = ValueAnimator.ofInt(width, measured).apply {
                        duration = 240L
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { animation ->
                            container.layoutParams?.let { params ->
                                params.width = animation.animatedValue as Int
                                container.layoutParams = params
                            }
                        }
                        start()
                    }
                }
            } else container.layoutParams?.let { params ->
                params.width = measured
                container.layoutParams = params
                naturalWidths[key] = measured
            }
        }
        pending[content] = task
        content.post(task)
    }

    fun detach() {
        pending.forEach { (view, task) -> view.removeCallbacks(task) }
        pending.clear()
        running.values.forEach { it.cancel() }
        running.clear()
        naturalWidths.clear()
    }

    private fun Context.activity(): Activity? {
        var actual: Context = this
        while (actual is ContextWrapper) {
            if (actual is Activity) return actual
            val next = actual.baseContext
            if (next === actual) return null
            actual = next
        }
        return actual as? Activity
    }
}
