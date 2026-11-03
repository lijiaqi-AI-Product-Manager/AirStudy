package com.projectfocus.core.ui.seat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.projectfocus.core.common.seat.SeatMapGeometry
import java.util.Locale


class SeatMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onSeatSelected: ((String) -> Unit)? = null

    var selectedSeat: String? = null
        private set

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var gestureCancelled = false

    private val occupied = mutableSetOf<String>()

    private val seatSize = dp(35f)
    private val seatGap = dp(15f)
    private val aisle = dp(40f)
    private val rowGap = dp(12f)
    private val headerHeight = dp(28f)
    private val corner = dp(8f)

    private val seatPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(16f)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(12f)
        color = 0xFF4B5563.toInt()
    }

    private val seatRect = RectF()
    private val seatRects = HashMap<String, RectF>()

    init {
        isClickable = true
        contentDescription = "座位图，共 ${SeatMapGeometry.ROWS} 排"
    }

    fun setOccupied(seats: Set<String>) {
        occupied.clear()
        occupied.addAll(seats.filter(SeatMapGeometry::isValid))
        invalidate()
    }

    fun select(seat: String) {
        // 记录/草稿可能带脏值；不存在的座位不能变成“已选”。
        if (!SeatMapGeometry.isValid(seat)) return
        selectedSeat = seat
        invalidate()
    }

    fun clearSelection() {
        selectedSeat = null
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (SeatMapGeometry.COLUMNS.size * seatSize +
            (SeatMapGeometry.COLUMNS.size - 1) * seatGap + aisle).toInt() +
            paddingLeft + paddingRight
        val height = (headerHeight + SeatMapGeometry.ROWS * (seatSize + rowGap)).toInt() +
            paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        seatRects.clear()

        val totalWidth = SeatMapGeometry.COLUMNS.size * seatSize +
            (SeatMapGeometry.COLUMNS.size - 1) * seatGap + aisle
        var left = paddingLeft + (width - paddingLeft - paddingRight - totalWidth) / 2f
        val top = paddingTop + headerHeight

        // 列头
        smallPaint.color = 0xFF6B7280.toInt()
        var headerX = left
        SeatMapGeometry.COLUMNS.forEachIndexed { index, column ->
            if (index == 2) headerX += aisle
            canvas.drawText(
                column,
                headerX + seatSize / 2f,
                paddingTop + headerHeight / 2f + smallPaint.textSize / 3f,
                smallPaint,
            )
            headerX += seatSize + seatGap
        }

        for (row in 0 until SeatMapGeometry.ROWS) {
            var x = left
            val y = top + row * (seatSize + rowGap)
            for (index in SeatMapGeometry.COLUMNS.indices) {
                if (index == 2) x += aisle
                val label = SeatMapGeometry.label(row, index)
                val rect = RectF(x, y, x + seatSize, y + seatSize)
                seatRects[label] = rect
                drawSeat(canvas, rect, label)
                x += seatSize + seatGap
            }
            canvas.drawText(
                String.format(Locale.US, "%02d", row + 1),
                left + totalWidth / 2f,
                y + seatSize / 2f + smallPaint.textSize / 3f,
                smallPaint,
            )
        }
    }

    private fun drawSeat(canvas: Canvas, rect: RectF, label: String) {
        val isOccupied = label in occupied
        val isSelected = label == selectedSeat
        val fill = when {
            isSelected -> 0xFF0A59F7.toInt()
            isOccupied -> 0xFFE5E7EB.toInt()
            else -> 0xFF4B5563.toInt()
        }
        seatPaint.color = fill
        canvas.drawRoundRect(rect, corner, corner, seatPaint)

        textPaint.color = when {
            isSelected -> 0xFFFFFFFF.toInt()
            isOccupied -> 0xFF9CA3AF.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        canvas.drawText(
            label,
            rect.centerX(),
            rect.centerY() + textPaint.textSize / 3f,
            textPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                gestureCancelled = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                // 多指/手势被父容器接管，不再把后续 UP 当成点击。
                gestureCancelled = true
            }
            MotionEvent.ACTION_UP -> {
                if (gestureCancelled) return true
                val dx = event.x - downX
                val dy = event.y - downY
                // 拖动/滚动不应误选：手指位移超过系统 touchSlop 就不算点击。
                if (dx * dx + dy * dy > touchSlop * touchSlop) return true
                val seat = seatRects.entries.firstOrNull { it.value.contains(event.x, event.y) }?.key
                if (seat != null && seat !in occupied) {
                    selectedSeat = seat
                    invalidate()
                    onSeatSelected?.invoke(seat)
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    private fun dp(value: Int): Float = dp(value.toFloat())
}
