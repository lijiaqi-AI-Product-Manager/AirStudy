package com.projectfocus.feature.booking.seat

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.common.seat.SeatMapGeometry
import com.projectfocus.feature.booking.R
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.projectfocus.core.ui.R as CoreUiR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeatSelectionMapViewTest {
    @Test
    fun `chair tap provides anchor without changing selection before a scene is chosen`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = SeatSelectionMapView(ContextThemeWrapper(controller.get(), CoreUiR.style.Theme_Pf))
            controller.get().setContentView(view)
            val first = view.findViewWithTag<TextView>("01A")
            var tapped: Pair<String, View>? = null
            view.onSeatTapped = { seat, anchor -> tapped = seat to anchor }
            first.performClick()
            assertThat(tapped?.first).isEqualTo("01A")
            assertThat(tapped?.second).isSameInstanceAs(first)
            assertThat(view.selectedSeat).isNull()
            val scene = SeatSceneChoice("fixture-study", "study", "学习", 0)
            view.select("1A", scene)
            assertThat(view.selectedSeat).isEqualTo("01A")
            assertThat(first.text.toString()).isEmpty()
            assertThat(first.background).isNotNull()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `source airframe scrolls thirty rows and last visible native seat accepts touch`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = SeatSelectionMapView(ContextThemeWrapper(controller.get(), CoreUiR.style.Theme_Pf))
            controller.get().setContentView(view)
            val density = view.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            repeat(2) {
                view.measure(View.MeasureSpec.makeMeasureSpec(dp(390), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(844), View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                shadowOf(Looper.getMainLooper()).idle()
            }

            val body = view.findViewById<ImageView>(R.id.pf_seat_dynamicSpaceshipBody)
            assertThat(body.drawable).isNotNull()
            assertThat(body.measuredWidth).isEqualTo(dp(3200))
            assertThat(body.measuredHeight).isEqualTo(dp(1850))
            assertThat(view.findViewById<View>(R.id.pf_seat_seatsArea).width).isEqualTo(dp(280))
            val area = view.findViewById<ViewGroup>(R.id.pf_seat_seatsArea)
            val expectedMono = checkNotNull(ResourcesCompat.getFont(view.context, CoreUiR.font.jetbrainsmono_medium))
            val headers = area.getChildAt(0) as ViewGroup
            val columnLabels = (0 until headers.childCount).map { headers.getChildAt(it) }.filterIsInstance<TextView>()
            assertThat(columnLabels.map { it.text.toString() }).containsExactly("A", "C", "D", "F").inOrder()
            columnLabels.forEach { assertThat(it.typeface).isEqualTo(expectedMono) }
            repeat(SeatMapGeometry.ROWS) { row ->
                val number = area.getChildAt(row + 1).findViewById<TextView>(R.id.pf_seat_rowNumberText)
                assertThat(number.typeface).isEqualTo(expectedMono)
                SeatMapGeometry.COLUMNS.indices.forEach { column ->
                    val seat = view.findViewWithTag<TextView>(SeatMapGeometry.label(row, column))
                    assertThat(seat).isNotNull()
                    assertThat(seat.width).isEqualTo(dp(35))
                    assertThat(seat.height).isEqualTo(dp(35))
                }
            }

            val output = File("build/reports/r159-seat-preview").apply { mkdirs() }
            fun capture(name: String) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(100, 106, 114)) // Explicit host backdrop, not a reference screenshot.
                view.draw(canvas)
                File(output, name).outputStream().use {
                    assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue()
                }
                bitmap.recycle()
            }
            capture("source-airframe-first.png")

            val scroll = view.findViewById<ScrollView>(R.id.pf_seat_scroll)
            val last = view.findViewWithTag<TextView>("30F")
            val bounds = Rect(0, 0, last.width, last.height)
            scroll.offsetDescendantRectToMyCoords(last, bounds)
            scroll.scrollTo(0, (bounds.bottom - scroll.height + dp(48)).coerceAtLeast(0))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(scroll.scrollY).isGreaterThan(0)
            val target = Rect(0, 0, last.width, last.height)
            view.offsetDescendantRectToMyCoords(last, target)
            assertThat(target.left).isAtLeast(0)
            assertThat(target.right).isAtMost(view.width)
            assertThat(target.top).isAtLeast(0)
            assertThat(target.bottom).isAtMost(view.height)

            val selected = mutableListOf<String>()
            view.onSeatSelected = { selected += it }
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event = MotionEvent.obtain(now, now + index * 20L, action,
                    target.exactCenterX(), target.exactCenterY(), 0)
                try {
                    assertThat(view.dispatchTouchEvent(event)).isTrue()
                } finally {
                    event.recycle()
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(view.selectedSeat).isEqualTo("30F")
            assertThat(selected).containsExactly("30F")
            assertThat(last.createAccessibilityNodeInfo().isChecked).isTrue()
            capture("source-airframe-last-selected.png")
            File(output, "README.txt").writeText(
                "Actual SeatSelectionMapView native render. Fixed host backdrop; no reference screenshot or visual fidelity acceptance.\n",
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
