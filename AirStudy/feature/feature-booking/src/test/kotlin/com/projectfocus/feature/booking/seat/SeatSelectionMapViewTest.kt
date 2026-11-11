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
import androidx.core.view.descendants
import androidx.core.graphics.Insets
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
                    assertThat(seat.width).isEqualTo(dp(48))
                    assertThat(seat.height).isEqualTo(dp(48))
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

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun `small cabin keeps real selection and occupied seats with large text and safe areas`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context = ContextThemeWrapper(controller.get(), CoreUiR.style.Theme_Pf)
            val configuration = android.content.res.Configuration(controller.get().resources.configuration).apply { fontScale = 1.6f }
            context.applyOverrideConfiguration(configuration)
            val view = SeatSelectionMapView(context)
            controller.get().setContentView(view)
            view.setLightCabin(true)
            view.applySafeInsets(Insets.of(0, 30, 0, 24))
            view.setOccupied(setOf("1C", "30F"))
            val callbacks = mutableListOf<String>()
            view.onSeatSelected = { callbacks += it }
            view.findViewWithTag<TextView>("01C").performClick()
            assertThat(callbacks).isEmpty()
            assertThat(view.selectedSeat).isNull()
            val scene = SeatSceneChoice("actual-custom-scene", "reading", "阅读与思考", 0,
                R.drawable.pf_seat_ic_focus_reading, Color.rgb(51, 118, 193))
            view.select("1A", scene)
            layoutCabin(view, 320, 568)
            assertThat(view.selectedSeat).isEqualTo("01A")
            assertThat(view.findViewWithTag<TextView>("01C").isEnabled).isFalse()
            assertThat(view.findViewWithTag<TextView>("01A").createAccessibilityNodeInfo().isChecked).isTrue()
            val hint = view.descendants.filterIsInstance<TextView>().single { it.text.contains("阅读与思考") }
            assertThat(hint.text.toString()).isEqualTo("已选 01A · 阅读与思考")
            val first = view.findViewWithTag<TextView>("01A")
            val firstBounds = Rect(0, 0, first.width, first.height)
            view.offsetDescendantRectToMyCoords(first, firstBounds)
            val hintBounds = Rect(0, 0, hint.width, hint.height)
            view.offsetDescendantRectToMyCoords(hint, hintBounds)
            assertThat(firstBounds.top).isAtLeast(hintBounds.bottom)
            assertThat(firstBounds.left).isAtLeast(0)
            assertThat(firstBounds.right).isAtMost(view.width)
            captureCabin(view, "seats-small-large-text-selected.png")
            view.clearSelection()
            assertThat(view.selectedSeat).isNull()
            assertThat(view.descendants.filterIsInstance<TextView>().any { it.text.toString() == context.getString(R.string.seat_sky_hint) }).isTrue()
            assertThat(view.findViewWithTag<TextView>("01C").isEnabled).isFalse()
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-night-mdpi")
    fun `night cabin preserves custom scenes with the same icon and draws a raised selection`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = SeatSelectionMapView(ContextThemeWrapper(controller.get(), CoreUiR.style.Theme_Pf))
            controller.get().setContentView(view)
            view.setLightCabin(false)
            view.setOccupied(setOf("01F"))
            val first = SeatSceneChoice("scene-first", "reading", "读书", 0,
                R.drawable.pf_seat_ic_focus_reading, Color.rgb(62, 115, 194))
            val second = first.copy(optionId = "scene-second", title = "写作", color = Color.rgb(134, 85, 190))
            view.select("01C", first);view.select("01D", second)
            layoutCabin(view, 390, 844)
            assertThat(view.selectedSeat).isEqualTo("01D")
            assertThat(view.findViewWithTag<TextView>("01C").isSelected).isFalse()
            assertThat(view.findViewWithTag<TextView>("01D").isSelected).isTrue()
            assertThat(view.descendants.filterIsInstance<TextView>().any { it.text.toString() == "已选 01D · 写作" }).isTrue()
            captureCabin(view, "seats-night-selected.png")
            view.setLightCabin(true)
            // The exact same selection survives a material change; no scene callback is emitted.
            assertThat(view.selectedSeat).isEqualTo("01D")
        } finally { controller.pause().stop().destroy() }
    }

    private fun layoutCabin(view: View, width: Int, height: Int) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun captureCabin(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap);view.draw(canvas)
        val output = File("build/reports/ui-seats").apply { mkdirs() }
        File(output, name).outputStream().use { assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue() }
        bitmap.recycle()
    }
}
