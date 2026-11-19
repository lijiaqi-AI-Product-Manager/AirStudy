package com.projectfocus.app.freeflight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Scroller
import androidx.core.content.res.ResourcesCompat
import com.projectfocus.app.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min


class FreeFlightLandingRulerView(
    context: Context,
    private val feedback: FreeFlightRulerFeedback,
    night: Boolean,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val majorLength = 24f * density
    private val minorLength = 13f * density
    private val spacing = 17f * density
    private val labelGap = 14f * density
    private val major = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.8f * density }
    private val minor = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.4f * density }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, com.projectfocus.core.ui.R.font.jetbrainsmono_medium)
    }
    private val selectedLabel = Paint(label)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 0); strokeWidth = 1.5f * density; style = Paint.Style.FILL_AND_STROKE
    }
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val scroller = Scroller(context)
    private var leftFade: LinearGradient? = null
    private var rightFade: LinearGradient? = null
    private var offset = 0f
    private var lastTickOffset = 0f
    private var touchActive = false
    private var selected = 10f
    private var selectedMinutes = 10
    private var fadingLabel: Float? = null
    private var changedAt = 0L
    private var lastHapticAt = 0L
    private var sound: SoundPool? = SoundPool.Builder().setMaxStreams(1)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(1).setContentType(4).build()).build()
    private val tick = sound?.load(context, R.raw.pf_ff_tick, 1) ?: 0
    var onTimeChanged: ((Int) -> Unit)? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        // Discriminator3 inherits onDown/onSingleTapUp. Heading's force-finish is case1 only.
        override fun onScroll(first: MotionEvent?, event: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val previous = offset
            offset = clampOffset(offset + distanceX.toInt())
            updateTime(); tick(offset - previous); invalidate()
            return true
        }
        override fun onFling(first: MotionEvent?, event: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            scroller.fling(offset.toInt(), 0, (-velocityX).toInt(), 0, (-width / 2f).toInt(), (position(240f) - width / 2f).toInt(), 0, 0)
            invalidate(); return true
        }
    })

    init { isClickable = true; isFocusable = true; applyNight(night) }

    fun currentTime(): Int = selectedMinutes
    fun setCurrentTime(minutes: Int) {
        val previousTouch = touchActive; touchActive = false
        rememberLabel()
        selected = (Math.round(minutes / 2.5f) * 2.5f).coerceIn(10f, 240f)
        selectedMinutes = Math.round(selected)
        changedAt = System.currentTimeMillis()
        offset = clampOffset(position(selected) - width / 2f)
        scroller.abortAnimation(); lastTickOffset = offset
        contentDescription = formatTime(selectedMinutes); invalidate(); touchActive = previousTouch
        // Source programmatic setCurrentTime does not call the time-changed listener.
    }

    fun applyNight(night: Boolean) {
        val foreground = if (night) Color.WHITE else Color.BLACK
        major.color = foreground
        minor.color = Color.argb(150, if (night) 255 else 0, if (night) 255 else 0, if (night) 255 else 0)
        label.color = foreground; selectedLabel.color = if (night) Color.BLACK else Color.WHITE; badge.color = foreground
        invalidate()
    }

    private fun position(minutes: Float): Float = ((minutes - 10f) * spacing) / 2.5f
    private fun minutesAt(position: Float): Int = ((2.5f * position) / spacing + 10f).toInt()
    private fun clampOffset(value: Float): Float = max(-width / 2f, min(position(240f) - width / 2f, value))
    private fun rememberLabel() {
        val interval = if (selected < 120f) 15f else if (selected < 360f) 30f else 60f
        val nearest = Math.round(selected / interval) * interval
        fadingLabel = nearest.takeIf { abs(selected - it) <= 5.001f }
    }
    private fun updateTime() {
        val rounded = Math.round(minutesAt(width / 2f + offset) / 2.5f) * 2.5f
        val minutes = Math.round(rounded).coerceIn(10, 240)
        if (minutes == selectedMinutes) return
        rememberLabel(); selected = rounded; selectedMinutes = minutes; changedAt = System.currentTimeMillis()
        if (touchActive) {
            val now = System.currentTimeMillis()
            val intensity = feedback.hapticIntensity().coerceIn(0, 100)
            if (intensity > 0 && now - lastHapticAt >= 50L) {
                runCatching {
                    val fraction = intensity / 100f
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(
                        ((70f * fraction).toInt() + 10).toLong(), (fraction * 255f).toInt().coerceIn(1, 255),
                    )) else performHapticFeedback(0)
                }
                lastHapticAt = now
            }
        }
        contentDescription = formatTime(selectedMinutes)
        onTimeChanged?.invoke(selectedMinutes)
    }
    private fun tick(delta: Float) {
        if (abs(offset - lastTickOffset) < spacing) return
        val speed = min(1f, abs(delta) / 100f)
        val rate = ((speed * speed * .120000005f) + 1f).coerceIn(.8f, 1.12f)
        val volume = feedback.tickVolume().coerceIn(0f, 1f)
        if (volume > .001f && tick != 0) runCatching { sound?.play(tick, volume, volume, 0, 0, rate) }
        lastTickOffset = Math.round(offset / spacing) * spacing
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val fade = 100f * density
        val colors = intArrayOf(0, 18, 72, 168, 255).map { Color.argb(it, 0, 0, 0) }.toIntArray()
        val stops = floatArrayOf(0f, .14f, .34f, .68f, 1f)
        leftFade = LinearGradient(0f, 0f, fade, 0f, colors, stops, Shader.TileMode.CLAMP)
        rightFade = LinearGradient(w - fade, 0f, w.toFloat(), 0f, colors.reversedArray(), stops, Shader.TileMode.CLAMP)
        setCurrentTime(selectedMinutes)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val width = width.toFloat(); val height = height.toFloat(); val baseline = height * .8f
        val layer = canvas.saveLayer(null, null)
        var time = max(10f, floor(minutesAt(offset - 200f) / 2.5f) * 2.5f)
        val last = min(240f, ceil(minutesAt(offset + width + 200f) / 2.5f) * 2.5f)
        val now = System.currentTimeMillis()
        while (time <= last) {
            val x = position(time) - offset
            if (x >= -200f && x <= width + 200f) {
                val interval = if (time < 120f) 15f else if (time < 360f) 30f else 60f
                if (abs(time % interval) >= .001f) canvas.drawLine(x, baseline, x, baseline - minorLength, minor)
                else {
                    canvas.drawLine(x, baseline, x, baseline - majorLength, major)
                    val alpha = label.alpha
                    val near = abs(time - selected) <= 5.001f
                    if (near || fadingLabel?.let { abs(time - it) < .001f } == true) {
                        val progress = min(1f, (now - changedAt) / 20f)
                        label.alpha = if (near) (alpha - ((alpha - 128) * progress)).toInt() else (((alpha - 128) * progress) + 128).toInt()
                    }
                    canvas.drawText(formatTime(Math.round(time)), x, labelGap + baseline, label)
                    label.alpha = alpha
                }
            }
            time += 2.5f
        }
        val fade = 100f * density
        if (leftFade != null && rightFade != null) {
            mask.shader = leftFade; canvas.drawRect(0f, 0f, fade, height, mask)
            mask.shader = rightFade; canvas.drawRect(width - fade, 0f, width, height, mask); mask.shader = null
        }
        canvas.restoreToCount(layer)
        val y = labelGap + baseline; val center = width / 2f
        val text = formatTime(Math.round(selected)); val metrics = selectedLabel.fontMetrics; val half = selectedLabel.measureText(text) / 2f
        val rect = RectF(center - half - 6f*density, metrics.top + y - .1f*density, center + half + 6f*density, metrics.bottom + y + .1f*density)
        val badgeAlpha = badge.alpha; val textAlpha = selectedLabel.alpha
        badge.alpha = 255; selectedLabel.alpha = 255
        canvas.drawRoundRect(rect, 8f*density, 8f*density, badge); canvas.drawText(text, center, y, selectedLabel)
        badge.alpha = badgeAlpha; selectedLabel.alpha = textAlpha
        val top = baseline - majorLength - 16f - 6f - density*10f
        val triangle = Path().apply { moveTo(center - 12f, top); lineTo(center + 12f, top); lineTo(center, top + 16f); close() }
        canvas.drawPath(triangle, pointer)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        gestures.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> touchActive = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchActive = false
                val target = clampOffset(position((Math.round(minutesAt(width / 2f + offset) / 2.5f) * 2.5f).coerceIn(10f, 240f)) - width / 2f)
                scroller.startScroll(offset.toInt(), 0, (target - offset).toInt(), 0, 200); invalidate()
            }
        }
        return true
    }
    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val previous = offset; offset = scroller.currX.toFloat(); updateTime(); tick(offset - previous); invalidate()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); sound?.release(); sound = null }

    companion object {
        fun formatTime(minutes: Int): String {
            val hours = minutes / 60; val remainder = minutes % 60
            return if (hours <= 0) "${remainder}m" else if (remainder <= 0) "${hours}h" else "${hours}h${remainder}m"
        }
    }
}
