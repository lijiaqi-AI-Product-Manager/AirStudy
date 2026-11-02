package com.projectfocus.feature.flight.hud

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class SourceNumberScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    private val digits = ArrayList<DigitFrame>()
    private var number = 0
    private var digitSize = 24
    private var digitColor = Color.BLACK
    private var minimumDigits = 0
    private var digitTypeface: Typeface? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }

    fun getValue(): Int = number

    fun setMinVisibleDigits(value: Int) {
        minimumDigits = max(0, value)
        ensureDigits(max(number.toString().length, minimumDigits))
        updateVisibility(number.toString().length)
    }

    fun setTextColor(value: Int) {
        digitColor = value
        digits.forEach { it.current.setTextColor(value); it.next.setTextColor(value) }
    }

    fun setTextSize(value: Int) {
        digitSize = value
        digits.forEach {
            it.current.setTextSize(TypedValue.COMPLEX_UNIT_SP, value.toFloat())
            it.next.setTextSize(TypedValue.COMPLEX_UNIT_SP, value.toFloat())
        }
    }

    fun setTypeface(value: Typeface?) {
        digitTypeface = value
        if (value != null) digits.forEach { it.current.typeface = value; it.next.typeface = value }
    }

    fun setValue(value: Int) {
        val newValue = max(0, value)
        val previous = number
        number = newValue
        val count = max(previous.toString().length, newValue.toString().length)
        ensureDigits(count)
        if (previous == 0 || newValue == previous) {
            setValueDirectly(newValue)
            return
        }
        val oldText = String.format(Locale.US, "%0${count}d", previous)
        val newText = String.format(Locale.US, "%0${count}d", newValue)
        repeat(count) { index ->
            val position = digits.size - count + index
            if (position in digits.indices) {
                val target = Character.getNumericValue(newText[index]).coerceIn(0, 9)
                val digit = digits[position]
                if (oldText[index] != newText[index]) digit.scrollTo(target)
                else digit.setDirectly(target)
            }
        }
        // Source reconciles visibility with the value captured by this call, after300ms.
        // It deliberately does not queue a latest-value replacement inside an animating digit.
        postDelayed({ updateVisibility(newValue.toString().length) }, 300L)
    }

    private fun ensureDigits(count: Int) {
        while (digits.size < count) {
            val digit = DigitFrame(context)
            digit.current.setTextSize(TypedValue.COMPLEX_UNIT_SP, digitSize.toFloat())
            digit.next.setTextSize(TypedValue.COMPLEX_UNIT_SP, digitSize.toFloat())
            digit.current.setTextColor(digitColor)
            digit.next.setTextColor(digitColor)
            digitTypeface?.let { digit.current.typeface = it; digit.next.typeface = it }
            digits.add(0, digit)
            addView(digit, 0)
        }
        updateVisibility(count)
    }

    private fun updateVisibility(count: Int) {
        val shown = max(count, minimumDigits)
        digits.forEachIndexed { index, digit ->
            digit.visibility = if (index < digits.size - shown) {
                if (minimumDigits > 0) INVISIBLE else GONE
            } else VISIBLE
        }
    }

    private fun setValueDirectly(value: Int) {
        val text = value.toString()
        ensureDigits(text.length)
        text.forEachIndexed { index, character ->
            val position = digits.size - text.length + index
            if (position in digits.indices) digits[position].setDirectly(Character.getNumericValue(character).coerceIn(0, 9))
        }
    }

    override fun getBaseline(): Int {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != GONE && child.baseline >= 0) return child.top + child.baseline
        }
        return super.getBaseline()
    }

    private class DigitFrame(context: Context) : ViewGroup(context) {
        var current = label()
        var next = label()
        private var value = 0
        private var animating = false

        init {
            addView(current)
            addView(next)
            next.visibility = GONE
        }

        private fun label() = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.BLACK)
            text = "0"
            typeface = ResourcesCompat.getFont(context, com.projectfocus.core.ui.R.font.jetbrainsmono_medium)
        }

        fun setDirectly(target: Int) {
            if (!animating) {
                value = target
                current.text = target.toString()
            }
        }

        fun scrollTo(target: Int) {
            if (animating || target == value) return
            animating = true
            next.text = target.toString()
            next.visibility = VISIBLE
            post {
                if (height == 0) {
                    current.text = target.toString()
                    next.visibility = GONE
                    value = target
                    animating = false
                } else {
                    next.translationY = height.toFloat()
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 300L
                        interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            val displacement = height * (it.animatedValue as Float)
                            current.translationY = -displacement
                            next.translationY = height - displacement
                            if (height > 0) {
                                val fade = max(1f, height * .6f)
                                current.alpha = 1f - min(1f, abs(current.translationY) / fade)
                                next.alpha = 1f - min(1f, abs(next.translationY) / fade)
                            }
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                val old = current
                                current = next
                                next = old
                                current.translationY = 0f
                                next.translationY = 0f
                                current.alpha = 1f
                                next.alpha = 1f
                                next.visibility = GONE
                                value = target
                                animating = false
                            }
                        })
                        start()
                    }
                }
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measureChild(current, widthMeasureSpec, heightMeasureSpec)
            measureChild(next, widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(current.measuredWidth, current.measuredHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            current.layout(0, 0, right - left, bottom - top)
            next.layout(0, 0, right - left, bottom - top)
        }

        override fun getBaseline(): Int = current.baseline.takeIf { it >= 0 }?.let { current.top + it } ?: super.getBaseline()
    }
}
