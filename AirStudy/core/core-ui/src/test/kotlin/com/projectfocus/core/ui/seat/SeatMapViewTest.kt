package com.projectfocus.core.ui.seat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.common.seat.SeatMapGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.ceil


@RunWith(RobolectricTestRunner::class)
class SeatMapViewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density: Float = context.resources.displayMetrics.density

    private val seat = 35f * density
    private val gap = 15f * density
    private val aisle = 40f * density
    private val header = 28f * density
    private val rowGap = 12f * density
    private val totalWidth = 4 * seat + 3 * gap + aisle
    private val width = ceil(totalWidth).toInt()
    private val height = ceil(header + 30 * (seat + rowGap)).toInt()

    @Test
    fun `measure is at least the geometry`() {
        val view = SeatMapView(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertTrue(view.measuredWidth >= width)
        assertTrue(view.measuredHeight >= height)
    }

    @Test
    fun `click first seat selects 01A`() {
        val view = SeatMapView(context)
        var selected: String? = null
        view.onSeatSelected = { selected = it }
        layoutAndDraw(view)

        tap(view, firstSeatX(), firstSeatY())

        assertEquals("01A", selected)
        assertEquals("01A", view.selectedSeat)
    }

    @Test
    fun `click across the aisle selects 01D`() {
        val view = SeatMapView(context)
        var selected: String? = null
        view.onSeatSelected = { selected = it }
        layoutAndDraw(view)

        tap(view, seatCenterX(2), seatCenterY(0))

        assertEquals("01D", selected)
    }

    @Test
    fun `click last row right seat selects 30F`() {
        val view = SeatMapView(context)
        var selected: String? = null
        view.onSeatSelected = { selected = it }
        layoutAndDraw(view)

        tap(view, seatCenterX(3), seatCenterY(29))

        assertEquals("30F", selected)
    }

    @Test
    fun `cancel does not select`() {
        val view = SeatMapView(context)
        var selected: String? = null
        view.onSeatSelected = { selected = it }
        layoutAndDraw(view)

        val now = android.os.SystemClock.uptimeMillis()
        view.dispatchTouchEvent(
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, firstSeatX(), firstSeatY(), 0),
        )
        view.dispatchTouchEvent(
            MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_CANCEL, firstSeatX(), firstSeatY(), 0),
        )

        assertNull(selected)
        assertNull(view.selectedSeat)
    }

    @Test
    fun `multi touch does not select`() {
        val view = SeatMapView(context)
        var selected: String? = null
        view.onSeatSelected = { selected = it }
        layoutAndDraw(view)

        val now = android.os.SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = firstSeatX()
                y = firstSeatY()
                pressure = 1f
                size = 1f
            },
            MotionEvent.PointerCoords().apply {
                x = firstSeatX() + 80f
                y = firstSeatY()
                pressure = 1f
                size = 1f
            },
        )

        view.dispatchTouchEvent(
            MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, 0, 0,
            ),
        )
        view.dispatchTouchEvent(
            MotionEvent.obtain(
                now, now + 10,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0,
            ),
        )
        view.dispatchTouchEvent(
            MotionEvent.obtain(
                now, now + 20, MotionEvent.ACTION_UP, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, 0, 0,
            ),
        )

        assertNull(selected)
        assertNull(view.selectedSeat)
    }

    @Test
    fun `drag across seats does not select`() {
        val view = SeatMapView(context)
        var selected: String? = null
        view.onSeatSelected = { selected = it }
        layoutAndDraw(view)

        drag(view, firstSeatX(), firstSeatY(), seatCenterX(1), seatCenterY(0))

        assertNull(selected)
        assertNull(view.selectedSeat)
    }

    @Test
    fun `occupied seat is not selectable`() {
        val view = SeatMapView(context)
        var selected: String? = null
        view.onSeatSelected = { selected = it }
        view.setOccupied(setOf("01A"))
        layoutAndDraw(view)

        tap(view, firstSeatX(), firstSeatY())

        assertNull(selected)
        assertNull(view.selectedSeat)
    }

    private fun layoutAndDraw(view: SeatMapView) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        view.draw(Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)))
    }

    private fun firstSeatX(): Float = seatCenterX(0)

    private fun firstSeatY(): Float = seatCenterY(0)

    private fun seatCenterX(columnIndex: Int): Float {
        val left = (width - totalWidth) / 2f
        val aisleOffset = if (columnIndex >= 2) aisle else 0f
        return left + columnIndex * seat + columnIndex * gap + aisleOffset + seat / 2f
    }

    private fun seatCenterY(rowIndex: Int): Float =
        header + rowIndex * (seat + rowGap) + seat / 2f

    private fun tap(view: SeatMapView, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun drag(view: SeatMapView, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, fromX, fromY, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, toX, toY, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, toX, toY, 0))
    }
}
