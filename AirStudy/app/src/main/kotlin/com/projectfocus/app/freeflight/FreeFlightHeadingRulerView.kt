package com.projectfocus.app.freeflight

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Scroller
import android.widget.SeekBar
import androidx.core.content.res.ResourcesCompat
import com.projectfocus.app.R
import com.projectfocus.core.ui.R as CoreUiR
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

interface FreeFlightRulerFeedback {
    fun tickVolume(): Float
    fun hapticIntensity(): Int
}


class FreeFlightHeadingRulerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onHeadingChanged: ((Float) -> Unit)? = null
    var feedback: FreeFlightRulerFeedback? = null
    var heading: Float = 0f
        private set
    var uiScale: Float = 1f
        private set
    private val major = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE;strokeCap = Paint.Cap.ROUND;strokeWidth = 4f }
    private val minor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255);strokeCap = Paint.Cap.ROUND;strokeWidth = 2f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE;textSize = 32f;textAlign = Paint.Align.CENTER
        runCatching { ResourcesCompat.getFont(context, CoreUiR.font.jetbrainsmono_medium) }.getOrNull()?.let { typeface = it }
    }
    private val readout = Paint(text)
    private val gold = context.getColor(R.color.pf_ff_heading_gold)
    private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold;style = Paint.Style.FILL }
    private val needle = Path()
    private val centerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x588FC6FF }
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var leftMask: LinearGradient? = null
    private var rightMask: LinearGradient? = null
    private val scroller = Scroller(context)
    private var lastTick = -1
    private var lastHapticMs = 0L
    private val density = resources.displayMetrics.density
    private var sound: SoundPool? = SoundPool.Builder().setMaxStreams(1)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(1).setContentType(4).build()).build()
    private val tick = sound?.load(context, R.raw.pf_ff_tick, 1) ?: 0
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.forceFinished(true)
            return true
        }
        override fun onScroll(first: MotionEvent?, event: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            update(heading + distanceX / density * .15f)
            return true
        }
        override fun onFling(first: MotionEvent?, event: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!ValueAnimator.areAnimatorsEnabled()) return false
            scroller.fling(heading.toInt(), 0, (-velocityX / density * .05f).toInt(), 0, Int.MIN_VALUE, Int.MAX_VALUE, 0, 0)
            postInvalidateOnAnimation()
            return true
        }
    })

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setHeading(value: Float) {
        require(value.isFinite())
        heading = value
        contentDescription = "${normalize(value.toInt())}\u00b0"
        invalidate()
    }

    fun setUiScale(value: Float) {
        val scale = value.coerceIn(.3f, 1f)
        if (uiScale == scale) return
        uiScale = scale
        buildMasks(width)
        invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = SeekBar::class.java.name
        if (isEnabled) {
            info.isScrollable = true
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, 359f, normalize(heading.toInt()).toFloat(),
            )
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isEnabled) when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { step(-5); return true }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { step(5); return true }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { step(-5); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { step(5); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun step(change: Int) {
        scroller.forceFinished(true)
        val degrees = normalize(heading.toInt())
        update(normalize(degrees + change).toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh);buildMasks(w) }

    private fun buildMasks(w: Int) {
        val fade = min(density * 72f * uiScale, w / 2f)
        if (fade <= 0f) { leftMask = null;rightMask = null;return }
        val positions = floatArrayOf(0f, .14f, .34f, .68f, 1f)
        val alphas = intArrayOf(0, 18, 72, 168, 255).map { Color.argb(it, 0, 0, 0) }.toIntArray()
        leftMask = LinearGradient(0f, 0f, fade, 0f, alphas, positions, Shader.TileMode.CLAMP)
        rightMask = LinearGradient(w - fade, 0f, w.toFloat(), 0f, alphas.reversedArray(), positions, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = uiScale * density
        val cx = width / 2f
        val radius = width * 1.2f
        readout.textSize = 39f * scale
        val readoutBaseline = 8f * density - readout.ascent()
        val needleTop = max(height - 140f * scale, readoutBaseline + 4f * density)
        val cy = radius + needleTop - 10f * scale
        val labelSize = 32f * scale;major.strokeWidth = 4f * scale;minor.strokeWidth = 2f * scale
        val layer = canvas.saveLayer(null, null)
        for (degree in floor(heading - 50f).toInt()..ceil(heading + 50f).toInt()) {
            if (degree % 5 != 0) continue
            val normalized = normalize(degree);val delta = degree - heading;val radians = Math.toRadians(delta.toDouble())
            val x = cx + (sin(radians) * radius).toFloat();val y = cy - (cos(radians) * radius).toFloat()
            if (x < -50f || x > width + 50f) continue
            canvas.save();canvas.rotate(delta, x, y)
            val isMajor = normalized % 30 == 0;val cardinal = normalized % 90 == 0
            var length = (if (isMajor) 60f else 30f) * scale
            if (cardinal) length *= 1.2f
            canvas.drawLine(x, y, x, y + length, if (isMajor) major else minor)
            if (isMajor) {
                val label = when (normalized) { 0 -> "N";90 -> "E";180 -> "S";270 -> "W";else -> normalized.toString() }
                text.color = if (cardinal) gold else Color.WHITE
                text.alpha = (max(.3f, min(1f, 1f - abs(delta) / 40f)) * 255f).toInt()
                text.textSize = labelSize * if (cardinal) 1.2f else 1f
                canvas.drawText(label, x, y + length + labelSize + 10f * scale, text)
            }
            canvas.restore()
        }
        val fade = min(72f * scale, cx)
        if (leftMask != null && rightMask != null) {
            mask.shader = leftMask;canvas.drawRect(0f, 0f, fade, height.toFloat(), mask)
            mask.shader = rightMask;canvas.drawRect(width - fade, 0f, width.toFloat(), height.toFloat(), mask);mask.shader = null
        }
        canvas.restoreToCount(layer)
        val halfWidth = scale * 20f
        centerLine.strokeWidth = scale
        canvas.drawLine(cx, needleTop + halfWidth, cx, height - 12f * scale, centerLine)
        needle.reset()
        needle.moveTo(cx, needleTop)
        needle.lineTo(cx - halfWidth, needleTop + halfWidth * 1.5f)
        needle.lineTo(cx + halfWidth, needleTop + halfWidth * 1.5f)
        needle.close()
        canvas.drawPath(needle, pointer)
        canvas.drawText("${normalize(heading.toInt())}\u00b0", cx, readoutBaseline, readout)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val consumed = gestures.onTouchEvent(event)
        if (!consumed && event.action == MotionEvent.ACTION_UP && abs(heading % 5f) > .1f) {
            val target = Math.round(heading / 5f) * 5
            if (ValueAnimator.areAnimatorsEnabled()) {
                val start = heading.toInt()
                scroller.startScroll(start, 0, target - start, 0, 200)
                postInvalidateOnAnimation()
            } else {
                update(target.toFloat())
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { update(scroller.currX.toFloat());postInvalidate() }
    }

    private fun update(value: Float) {
        setHeading(value)
        val degree = normalize(value.toInt())
        onHeadingChanged?.invoke(value)
        if (degree % 5 == 0 && degree != lastTick) {
            lastTick = degree
            feedback?.let { settings ->
                runCatching {
                    val intensity = settings.hapticIntensity().coerceIn(0, 100);val now = System.currentTimeMillis()
                    if (intensity > 0 && now - lastHapticMs >= 50L) {
                        lastHapticMs = now
                        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                        if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(
                            ((70f * intensity / 100f).toInt() + 10).toLong(), ((intensity / 100f) * 255f).toInt().coerceIn(1, 255),
                        )) else performHapticFeedback(4)
                    }
                }
                val volume = runCatching { settings.tickVolume().coerceIn(0f, 1f) }.getOrDefault(.3f)
                if (volume > .001f) runCatching { sound?.play(tick, volume, volume, 0, 0, 1f) }
            }
        }
    }

    override fun onDetachedFromWindow() { sound?.release();sound = null;super.onDetachedFromWindow() }

    private fun normalize(value: Int): Int = ((value % 360) + 360) % 360
}
