package com.projectfocus.feature.booking.plan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.Scroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class SourceStringWheelPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var entries: List<String> = emptyList()
    private var selection = 0
    private var offset = 0f
    private var maximumOffset = 0f
    private var previousY = 0f
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null
    private val scroller = Scroller(context)
    private val rowHeight = (36f * resources.displayMetrics.density + .5f).toInt()
    private val normalSize = (16f * resources.displayMetrics.scaledDensity + .5f).toInt().toFloat()
    private val selectedSize = (20f * resources.displayMetrics.scaledDensity + .5f).toInt().toFloat()
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = normalSize
        color = 0x80FFFFFF.toInt()
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = selectedSize
        color = Color.WHITE
    }

    var onItemSelected: ((Int) -> Unit)? = null

    var selectedTypeface: Typeface?
        get() = selectedPaint.typeface
        set(value) { selectedPaint.typeface = value; invalidate() }

    /** Original setter is silent: current DEX invokes only bounds helpers/invalidate. */
    fun setSelectedIndex(index: Int) {
        if (entries.isEmpty()) return
        selection = max(0, min(index, entries.size - 1))
        offset = (selection * rowHeight).toFloat()
        invalidate()
    }

    fun setData(values: List<String>) {
        entries = values.toList()
        maximumOffset = max(0, (entries.size - 1) * rowHeight).toFloat()
        setSelectedIndex(0)
        invalidate()
    }

    fun hasData(values: List<String>): Boolean = entries == values
    fun getSelectedIndex(): Int = selection
    fun getSelectedItem(): String? = entries.getOrNull(selection)

    fun setColors(normal: Int, selected: Int) {
        normalPaint.color = normal
        selectedPaint.color = selected
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty()) return
        val centerY = (height / 2).toFloat()
        val first = max(0, (offset / rowHeight).toInt() - 2)
        val last = min(entries.size - 1, (offset / rowHeight).toInt() + 2)
        for (index in first..last) {
            val y = rowHeight * index - offset + centerY
            val distance = abs(y - centerY)
            val factor = max(0f, 1f - distance / (rowHeight * 1.5f))
            val paint = if (distance < rowHeight / 2f) {
                selectedPaint.textSize = (selectedSize - normalSize) * factor + normalSize
                selectedPaint
            } else {
                normalPaint.alpha = (max(.2f, factor) * 128f).toInt()
                normalPaint
            }
            val metrics = paint.fontMetrics
            canvas.drawText(entries[index], width / 2f, y - (metrics.descent + metrics.ascent) / 2f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Product host can withhold values/disable controls while loading.
        if (!isEnabled || entries.isEmpty()) return false
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) scroller.abortAnimation()
                previousY = event.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                offset += previousY - event.y
                offset = offset.coerceIn(-rowHeight.toFloat(), maximumOffset + rowHeight)
                previousY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                velocityTracker?.computeCurrentVelocity(1000, 8000f)
                val speed = (velocityTracker?.yVelocity ?: 0f).toInt()
                if (abs(speed) > 500) {
                    scroller.fling(0, offset.toInt(), 0, -speed, 0, 0, 0, maximumOffset.toInt())
                } else snapToRow()
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
            }
        }
        return true
    }

    private fun snapToRow() {
        if (entries.isEmpty()) return
        val next = max(0, min(Math.round(offset / rowHeight), entries.size - 1))
        val current = offset.toInt()
        scroller.startScroll(0, current, 0, rowHeight * next - current, 300)
        if (next != selection) {
            selection = next
            onItemSelected?.invoke(next)
        }
    }

    override fun computeScroll() {
        if (entries.isEmpty()) return
        if (!scroller.computeScrollOffset()) {
            if (!dragging && abs(offset - Math.round(offset / rowHeight) * rowHeight) > 1f) snapToRow()
            return
        }
        offset = scroller.currY.toFloat().coerceIn(0f, maximumOffset)
        invalidate()
        if (!scroller.isFinished) return
        val next = Math.round(offset / rowHeight)
        if (next != selection) {
            selection = next
            onItemSelected?.invoke(next)
        }
    }

    override fun onDetachedFromWindow() {
        scroller.abortAnimation()
        velocityTracker?.recycle()
        velocityTracker = null
        dragging = false
        super.onDetachedFromWindow()
    }
}
