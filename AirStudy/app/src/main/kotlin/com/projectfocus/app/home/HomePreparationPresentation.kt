package com.projectfocus.app.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import com.projectfocus.app.R
import com.projectfocus.feature.home.map.SourceMapHostView
import java.io.Closeable








internal class HomePreparationPresentation(
    private val root: View,
    private val isCurrent: (HomePreparationPresentation) -> Boolean,
    private val restoreHome: (HomePreparationPresentation) -> Unit,
) : Closeable {
    private val menu = root.findViewById<View>(R.id.right_menu_container)
    private val button = root.findViewById<View>(R.id.btnStartFlight)
    private val ground = root.findViewById<View>(R.id.groundHomeContent)
    private val status = root.findViewById<View>(R.id.tvHomeStatus)
    private val map = root.findViewById<SourceMapHostView>(R.id.sourceMap)
    private val menuVisibility = menu.visibility
    private val buttonVisibility = button.visibility
    private val statusVisibility = status.visibility
    private val accessibility = ground.importantForAccessibility
    private var menuAnimator: ObjectAnimator? = null
    private var closed = false

    fun show() {
        check(isCurrent(this))
        ground.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        status.visibility = View.GONE
        button.isEnabled = false
        map.setStatusControlsVisible(false)
        menuAnimator = ObjectAnimator.ofFloat(menu, View.ALPHA, menu.alpha, 0f).apply {
            duration = 500L
            var cancelled = false
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && isCurrent(this@HomePreparationPresentation)) menu.visibility = View.INVISIBLE
                }
            })
            start()
        }
        button.animate().cancel()
        button.animate().setListener(null)
        var cancelled = false
        button.animate().alpha(0f).scaleX(.8f).scaleY(.8f).setDuration(300L)
            .setStartDelay(0L).setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || !isCurrent(this@HomePreparationPresentation)) return
                    button.animate().setListener(null)
                    button.visibility = View.GONE
                    button.scaleX = 1f;button.scaleY = 1f
                    button.translationX = 0f;button.translationY = 0f
                }
            }).start()
    }

    override fun close() {
        if (closed) return
        closed = true
        // A late teardown must never cancel another controller's current animator.
        if (!isCurrent(this)) return
        menuAnimator?.cancel();menuAnimator = null
        button.animate().cancel();button.animate().setListener(null)
        menu.alpha = 1f;menu.visibility = menuVisibility
        button.alpha = 1f;button.scaleX = 1f;button.scaleY = 1f
        button.translationX = 0f;button.translationY = 0f
        button.visibility = buttonVisibility
        status.visibility = statusVisibility
        ground.importantForAccessibility = accessibility
        map.setStatusControlsVisible(true)
        restoreHome(this)
    }
}
