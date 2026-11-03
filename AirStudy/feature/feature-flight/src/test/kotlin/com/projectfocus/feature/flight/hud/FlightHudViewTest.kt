package com.projectfocus.feature.flight.hud

import android.content.Context
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.flight.R
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlightHudViewTest {
    private val running = FlightHudUiState(active = true, routeText = "R158A → R158B", remainingTimeText = "77:09",
        remainingMinutesText = "78", remainingKmText = "1234.5", traveledKmText = "25.5", progress = .2f,
        counters = FlightHudCounters.remaining(1234.5, 77 * 60 + 9))

    @Test
    fun `source corner geometry and active controls stay separate at phone widths and large fonts`() {
        for (night in listOf(false, true)) for (width in listOf(320, 390)) for (font in listOf(1f, 1.6f)) {
            val view = newView(night, font)
            view.render(running)
            layout(view, width, if (width == 320) 568 else 844)
            val pause = bounds(view, R.id.pf_flight_pause)
            assertThat(pause.left).isEqualTo(dp(view, 24))
            assertThat(pause.top).isEqualTo(dp(view, 68))
            assertThat(pause.width()).isEqualTo(dp(view, 40))
            assertThat(pause.height()).isEqualTo(dp(view, 40))
            assertThat(view.findViewById<View>(R.id.pf_flight_cancel).visibility).isEqualTo(View.GONE)
            assertThat(view.findViewById<SourceNumberScrollView>(R.id.pf_flight_distance_value).getValue()).isEqualTo(1235)
            assertThat(view.findViewById<SourceNumberScrollView>(R.id.pf_flight_time_value).contentDescription.toString()).isEqualTo("77:09")
            assertSeparate(view, listOf(R.id.pf_flight_pause, R.id.pf_flight_audio, R.id.pf_flight_map_style,
                R.id.pf_flight_window_toggle, R.id.pf_flight_distance_container, R.id.pf_flight_time_container))
            // 原activity_main的两个独立角落容器都贴底40dp，不能因数值字号不同而上浮。
            for (id in listOf(R.id.pf_flight_distance_container, R.id.pf_flight_time_container)) {
                assertThat(bounds(view, id).bottom).isEqualTo(view.height - dp(view, 40))
            }
            view.render(running.copy(paused = true))
            layout(view, width, if (width == 320) 568 else 844)
            assertThat(view.findViewById<View>(R.id.pf_flight_stats).visibility).isEqualTo(View.GONE)
            assertThat(view.findViewById<View>(R.id.pf_flight_right_controls).visibility).isEqualTo(View.GONE)
            val cancel = bounds(view, R.id.pf_flight_cancel)
            assertThat(cancel.width()).isEqualTo(dp(view, 120))
            assertThat(cancel.height()).isEqualTo(dp(view, 40))
            assertSeparate(view, listOf(R.id.pf_flight_pause, R.id.pf_flight_audio, R.id.pf_flight_cancel))
        }
    }

    @Test
    fun `single digit time keeps both original labels on one line`() {
        for (night in listOf(false, true)) for (width in listOf(320, 390)) for (font in listOf(1f, 1.6f)) {
            val view = newView(night, font)
            view.render(running.copy(remainingMinutesText = "8", remainingTimeText = "00:08:00", remainingKmText = "100.0", counters = FlightHudCounters.remaining(100.0, 480)))
            layout(view, width, if (width == 320) 568 else 844)
            for ((id, labelText) in listOf(
                R.id.pf_flight_distance_container to R.string.pf_hud_source_distance_remaining,
                R.id.pf_flight_time_container to R.string.pf_hud_source_time_remaining,
            )) {
                val container = view.findViewById<ViewGroup>(id)
                val label = view.findViewById<TextView>(if (id == R.id.pf_flight_distance_container)
                    R.id.pf_flight_distance_label else R.id.pf_flight_time_label)
                assertThat(label.text.toString()).isEqualTo(view.context.getString(labelText))
                val textLayout = checkNotNull(label.layout)
                assertThat(textLayout.lineCount).isEqualTo(1)
                assertThat(textLayout.getEllipsisCount(0)).isEqualTo(0)
                assertThat(textLayout.getLineWidth(0)).isAtMost(
                    (label.width - label.totalPaddingLeft - label.totalPaddingRight).toFloat() + 1f,
                )
                assertThat(label.left).isAtLeast(container.paddingLeft)
                assertThat(label.right).isAtMost(container.width - container.paddingRight)
            }
            assertSeparate(view, listOf(R.id.pf_flight_distance_container, R.id.pf_flight_time_container))
        }
    }

    @Test
    fun `pause resume window audio and cancellation retain distinct actions`() {
        val view = newView()
        var pause = 0
        var cancel = 0
        var window = 0
        view.callbacks = FlightHudCallbacks(onPauseResume = { pause++ }, onAbandon = { cancel++ }, onToggleWindow = { window++ })
        view.render(running)
        layout(view)
        view.findViewById<View>(R.id.pf_flight_pause).performClick()
        view.findViewById<View>(R.id.pf_flight_window_toggle).performClick()
        view.findViewById<View>(R.id.pf_flight_audio).performClick()
        assertThat(view.findViewById<View>(R.id.pf_flight_volume_panel).visibility).isEqualTo(View.VISIBLE)
        assertThat(pause).isEqualTo(1)
        assertThat(window).isEqualTo(1)
        assertThat(cancel).isEqualTo(0)
        view.render(running.copy(paused = true))
        view.findViewById<View>(R.id.pf_flight_cancel).performClick()
        assertThat(cancel).isEqualTo(0)
        view.findViewById<View>(R.id.pf_flight_cancel).performLongClick()
        assertThat(cancel).isEqualTo(1)
        view.render(FlightHudUiState()) // both Idle and Arrived map to inactive controls
        assertThat(view.visibility).isEqualTo(View.GONE)
        assertThat(view.findViewById<View>(R.id.pf_flight_volume_panel).visibility).isEqualTo(View.GONE)
        view.render(running)
        assertThat(view.findViewById<View>(R.id.pf_flight_pause_overlay).visibility).isEqualTo(View.GONE)
        assertThat(view.findViewById<View>(R.id.pf_flight_right_controls).visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `native corner control previews cover day night small viewport and large text`() {
        val output = File("build/reports/flight-hud-preview").apply { mkdirs() }
        val previousPause = ShadowChoreographer.isPaused()
        ShadowChoreographer.setPaused(false)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            // Replace native content inside one real attached window. Recreating windows within
            // this same test left Robolectric's second DecorView detached (hud10 diagnostics).
            val windowRoot = FrameLayout(controller.get())
            controller.get().setContentView(windowRoot)
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(windowRoot.isAttachedToWindow).isTrue()
            ShadowChoreographer.setPaused(true)
            for (night in listOf(false, true)) for (paused in listOf(false, true)) for (width in listOf(320, 390)) {
                val view = newView(night, if (width == 320) 1.6f else 1f, controller.get())
                // The capture has no map/window backdrop. Match Home's real fallback icon state.
                view.render(running.copy(paused = paused, lightIcons = night))
                windowRoot.removeAllViews()
                windowRoot.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                layout(windowRoot, width, if (width == 320) 568 else 844)
                com.google.common.truth.Truth.assertWithMessage("night=$night paused=$paused width=$width decorAttached=${controller.get().window.decorView.isAttachedToWindow} rootAttached=${windowRoot.isAttachedToWindow}")
                    .that(view.isAttachedToWindow).isTrue()
                view.viewTreeObserver.dispatchOnPreDraw()
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(view.context.getColor(com.projectfocus.core.ui.R.color.bg_base))
                    view.draw(canvas)
                    val name = "${if (night) "night" else "day"}-${if (paused) "paused" else "running"}-$width"
                    File(output, "$name.png").outputStream().use {
                        assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue()
                    }
                    File(output, "$name.json").writeText(actualHudPreviewMetadata(view).toString(2))
                } finally { bitmap.recycle() }
            }
        } finally {
            controller.pause().stop().destroy()
            ShadowChoreographer.setPaused(previousPause)
        }
        File(output, "README.txt").writeText("Native component previews on the resolved app background; not an original APK screenshot comparison.\n320dp uses 1.6 font scale. Real Home/map captures are in app/reports/flight-preparation-preview.\n")
    }

    private fun newView(night: Boolean = false, font: Float = 1f,
                        base: Context = ApplicationProvider.getApplicationContext<Context>()): FlightHudView {
        val app = base
        val config = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            fontScale = font
        }
        return FlightHudView(ContextThemeWrapper(app.createConfigurationContext(config), com.projectfocus.core.ui.R.style.Theme_Pf))
    }

    private fun layout(view: View, width: Int = 390, height: Int = 844) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(dp(view, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(view, height), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
    private fun bounds(view: FlightHudView, id: Int): Rect = Rect().also {
        val target = view.findViewById<View>(id)
        target.getDrawingRect(it)
        view.offsetDescendantRectToMyCoords(target, it)
    }
    private fun assertSeparate(view: FlightHudView, ids: List<Int>) {
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val first = bounds(view, ids[i])
            val second = bounds(view, ids[j])
            com.google.common.truth.Truth.assertWithMessage("${resourcesName(view, ids[i])}=$first / ${resourcesName(view, ids[j])}=$second; width=${view.width},font=${view.resources.configuration.fontScale}")
                .that(Rect.intersects(first, second)).isFalse()
        }
    }

    private fun resourcesName(view: View, id: Int): String = view.resources.getResourceEntryName(id)
}
