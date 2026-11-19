package com.projectfocus.app.countdown

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator

/** Owns visual printing only. Inspection and departure remain explicit caller actions. */
internal class BoardingPrintController(
    private val host: ViewGroup,
    private val paper: View,
    private val overlay: BoardingFeedOverlay,
    private val inspectButton: View,
    private val onPhaseChanged: (Phase) -> Unit,
) {
    enum class Phase { WAITING, PRINTING, COMPLETE, RELEASED }
    var phase = Phase.WAITING
        private set
    val isReady: Boolean get() = phase == Phase.COMPLETE
    private var generation = 0L
    private var printAnimation: ValueAnimator? = null
    private var outletAnimation: ValueAnimator? = null
    private var reveal: Runnable? = null
    private val deferredStart = Runnable { if (phase == Phase.WAITING) begin() }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { release() }
    }

    init {
        paper.visibility = View.INVISIBLE
        inspectButton.visibility = View.GONE;inspectButton.isEnabled = false
        overlay.visibility = View.GONE
        host.addOnAttachStateChangeListener(attachment)
    }

    /** Call only after the paper's actual artwork and bound text have been laid out. */
    fun begin() {
        if (phase != Phase.WAITING) return
        if (!ValueAnimator.areAnimatorsEnabled() || host.windowVisibility != View.VISIBLE) {
            showStatic(showAction = true);return
        }
        if (paper.width <= 0 || paper.height <= 0) {
            paper.removeCallbacks(deferredStart);paper.post(deferredStart);return
        }
        val snapshot = try {
            Bitmap.createBitmap(paper.width, paper.height, Bitmap.Config.ARGB_8888).apply { paper.draw(Canvas(this)) }
        } catch (_: OutOfMemoryError) {
            showStatic(showAction = true);return
        }
        val bounds = try { BoardingFeedOverlay.paperBounds(snapshot) }
        catch (_: OutOfMemoryError) { showStatic(showAction = true);return }
        stopAnimations()
        val ticket = generation
        phase = Phase.PRINTING
        overlay.bind(snapshot, bounds);updatePlacement();overlay.visibility = View.VISIBLE
        paper.visibility = View.INVISIBLE
        inspectButton.visibility = View.GONE;inspectButton.isEnabled = false
        onPhaseChanged(phase)
        val animation = ValueAnimator.ofFloat(0f, 1f)
        printAnimation = animation
        animation.duration = 2700L;animation.interpolator = LinearInterpolator()
        animation.addUpdateListener {
            if (generation == ticket && printAnimation === it && phase == Phase.PRINTING) {
                if (!ValueAnimator.areAnimatorsEnabled()) showStatic(showAction = true)
                else overlay.showProgress(it.animatedValue as Float)
            }
        }
        animation.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (printAnimation !== animation || generation != ticket) return
                printAnimation = null
                if (!cancelled && phase == Phase.PRINTING) finishAnimated(ticket)
            }
        })
        animation.start()
    }

    /** Restored inspection and boarding phases keep their existing complete live paper. */
    fun showStatic(showAction: Boolean) {
        if (phase == Phase.RELEASED) return
        stopAnimations();phase = Phase.COMPLETE
        overlay.clear();overlay.visibility = View.GONE
        paper.visibility = View.VISIBLE
        inspectButton.alpha = 1f
        inspectButton.visibility = if (showAction) View.VISIBLE else View.GONE
        inspectButton.isEnabled = showAction
        onPhaseChanged(phase)
    }

    fun onHostVisibilityChanged(visible: Boolean) {
        if (!visible && phase == Phase.PRINTING) showStatic(showAction = true)
    }

    fun updatePlacement() {
        if (phase != Phase.RELEASED) overlay.place(paper.x, paper.y, paper.scaleX)
    }

    private fun finishAnimated(ticket: Long) {
        if (generation != ticket || phase != Phase.PRINTING) return
        if (!ValueAnimator.areAnimatorsEnabled()) { showStatic(showAction = true);return }
        phase = Phase.COMPLETE
        paper.visibility = View.VISIBLE;overlay.finish();onPhaseChanged(phase)
        val fade = ValueAnimator.ofFloat(1f, 0f)
        outletAnimation = fade
        fade.duration = 180L
        fade.addUpdateListener {
            if (generation == ticket && outletAnimation === it && phase == Phase.COMPLETE) {
                overlay.outletOpacity = it.animatedValue as Float
            }
        }
        fade.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (generation == ticket && outletAnimation === animation && phase == Phase.COMPLETE) {
                    outletAnimation = null;overlay.clear();overlay.visibility = View.GONE
                }
            }
        })
        fade.start()
        val next = Runnable {
            reveal = null
            if (generation != ticket || phase != Phase.COMPLETE) return@Runnable
            inspectButton.visibility = View.VISIBLE
            if (!ValueAnimator.areAnimatorsEnabled()) {
                inspectButton.alpha = 1f;inspectButton.isEnabled = true
            } else {
                inspectButton.alpha = 0f;inspectButton.isEnabled = false
                inspectButton.animate().alpha(1f).setStartDelay(0L).setDuration(500L).withEndAction {
                    if (generation == ticket && phase == Phase.COMPLETE) inspectButton.isEnabled = true
                }.start()
            }
        }
        reveal = next;host.postDelayed(next, 200L)
    }

    private fun stopAnimations() {
        generation++
        paper.removeCallbacks(deferredStart)
        reveal?.let(host::removeCallbacks);reveal = null
        val printing = printAnimation;printAnimation = null;printing?.cancel()
        val outlet = outletAnimation;outletAnimation = null;outlet?.cancel()
        inspectButton.animate().withEndAction(null).cancel()
    }

    fun release() {
        if (phase == Phase.RELEASED) return
        phase = Phase.RELEASED;stopAnimations()
        overlay.clear();overlay.visibility = View.GONE
        host.removeOnAttachStateChangeListener(attachment)
    }
}
