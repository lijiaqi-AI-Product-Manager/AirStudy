package com.projectfocus.core.ui.motion

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import androidx.core.view.children
import androidx.core.view.isVisible
import com.projectfocus.core.ui.R


object PfMotion {
    /** 报告 --dur-fast / --dur-base / --dur-slow */
    const val DURATION_FAST: Long = 160L
    const val DURATION_BASE: Long = 280L
    const val DURATION_SLOW: Long = 520L

    /** 错帧间隔（报告：40–120ms，取 60） */
    const val STAGGER_STEP: Long = 60L

    /**
     * 系统是否要求减弱动画（开发者选项 / 无障碍「移除动画」= 动画时长缩放 0）。
     * 报告验收清单：开启"减弱动态效果"时动画会关闭。
     */
    fun isReducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    internal fun spring(context: Context): Interpolator =
        AnimationUtils.loadInterpolator(context, R.interpolator.pf_spring)

    internal fun easeOut(context: Context): Interpolator =
        AnimationUtils.loadInterpolator(context, R.interpolator.pf_ease_out)
}

private fun View.dp(value: Float): Float = value * resources.displayMetrics.density

/**
 * 按压回弹：按下缩到 0.96，松手用弹簧曲线弹回（报告 .btn-primary:active）。
 * 只做视觉，不吞触摸事件 —— 点击监听照常触发。
 */
@SuppressLint("ClickableViewAccessibility")
fun View.pressSpring(pressedScale: Float = 0.96f) {
    if (PfMotion.isReducedMotion(context)) return
    val spring = PfMotion.spring(context)
    val easeOut = PfMotion.easeOut(context)
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(pressedScale).scaleY(pressedScale)
                .setDuration(PfMotion.DURATION_FAST).setInterpolator(easeOut).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f)
                .setDuration(PfMotion.DURATION_BASE).setInterpolator(spring).start()
        }
        false
    }
}

/**
 * 选中「弹一下」：0.8 → 略超 1 → 1（报告 @keyframes pop，弹簧曲线自带过冲）。
 */
fun View.popIn() {
    if (PfMotion.isReducedMotion(context)) {
        scaleX = 1f
        scaleY = 1f
        return
    }
    scaleX = 0.8f
    scaleY = 0.8f
    animate().scaleX(1f).scaleY(1f)
        .setDuration(PfMotion.DURATION_SLOW)
        .setInterpolator(PfMotion.spring(context))
        .start()
}

/**
 * 小幅漂浮循环（报告 .float：上浮 6dp + 旋转 -2°，3.2 秒一个来回）。
 * 给 3D 物件 / 吉祥物 / 四角星用；一页最多一两处，别让整页都在晃。
 *
 * @return 动画句柄；View detach 时会自动 cancel，一般不用手动管。
 */
fun View.floatLoop(
    amplitudeDp: Float = 6f,
    rotationDeg: Float = -2f,
    periodMs: Long = 3200L,
): Animator? {
    if (PfMotion.isReducedMotion(context)) return null
    val animator = ObjectAnimator.ofPropertyValuesHolder(
        this,
        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -dp(amplitudeDp)),
        PropertyValuesHolder.ofFloat(View.ROTATION, 0f, rotationDeg),
    ).apply {
        duration = periodMs / 2
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
    }
    cancelOnDetach(animator)
    animator.start()
    return animator
}

/**
 * 错帧入场：子 View 依次从下方 12dp 淡入上浮（报告 .list > * 的 rise）。
 * 在数据第一次渲染后调用一次即可；重复调用会重播。
 */
fun ViewGroup.staggerIn(stepMs: Long = PfMotion.STAGGER_STEP, offsetDp: Float = 12f) {
    val targets = children.filter { it.isVisible }.toList()
    if (PfMotion.isReducedMotion(context)) {
        targets.forEach { it.alpha = 1f; it.translationY = 0f }
        return
    }
    val easeOut = PfMotion.easeOut(context)
    targets.forEachIndexed { index, child ->
        child.alpha = 0f
        child.translationY = dp(offsetDp)
        child.animate().alpha(1f).translationY(0f)
            .setStartDelay(index * stepMs)
            .setDuration(PfMotion.DURATION_SLOW)
            .setInterpolator(easeOut)
            .start()
    }
}

private fun View.cancelOnDetach(animator: Animator) {
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            animator.cancel()
            v.removeOnAttachStateChangeListener(this)
        }
    })
}
