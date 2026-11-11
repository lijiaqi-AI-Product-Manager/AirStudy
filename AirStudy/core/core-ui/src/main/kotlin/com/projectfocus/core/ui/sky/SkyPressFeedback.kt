package com.projectfocus.core.ui.sky

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Feedback decorates native clicks without consuming gestures or changing business actions. */
object SkyPressFeedback {
    fun install(view: View) {
        fun settle(scale: Float, duration: Long) {
            view.animate().cancel()
            if (!ValueAnimator.areAnimatorsEnabled()) {
                view.scaleX = 1f; view.scaleY = 1f
            } else view.animate().scaleX(scale).scaleY(scale).setDuration(duration)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        view.setOnTouchListener { target, event ->
            if (!target.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> settle(.975f, 110L)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> settle(1f, 160L)
            }
            false
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.animate().cancel(); v.scaleX = 1f; v.scaleY = 1f
            }
        })
    }
}
