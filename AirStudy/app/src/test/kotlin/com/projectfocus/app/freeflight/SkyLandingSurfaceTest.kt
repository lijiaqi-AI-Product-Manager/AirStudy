package com.projectfocus.app.freeflight

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.projectfocus.app.R
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.feature.airport.ui.AirportSkyStateView
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import com.projectfocus.core.ui.R as UiR

/** Native widgets and named airport fixtures; no real-device or map-service acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkyLandingSurfaceTest {
    private val silent = object : FreeFlightRulerFeedback {
        override fun tickVolume() = 0f
        override fun hapticIntensity() = 0
    }
    private val option = FreeFlightLandingOption(
        PlannedAirport(1, "TSN", "Tianjin Binhai International Airport", "Tianjin", "CN", 39.1244, 117.346), 77.5, 6)

    @Test fun loadingErrorAndEmptyKeepTheirOwnFeedbackAndOldChoicesCannotSelect() {
        fixture { surface, root, events ->
            surface.showOptions(listOf(option));surface.render(10, null, false);layout(root)
            val old = requireNotNull(surface.cards.findViewHolderForAdapterPosition(0)).itemView
            surface.loading();layout(root)
            val sketch = walk(surface).filterIsInstance<AirportSkyStateView>().single()
            assertTrue(sketch.isShown)
            assertNotNull(ReflectionHelpers.getField<ValueAnimator?>(sketch, "animator"))
            assertFalse(surface.cards.isShown)
            old.performClick();surface.confirm.performClick()
            assertTrue(events.selected.isEmpty());assertEquals(0, events.confirmed)
            capture(root, "landing-query-loading-day")
            surface.failed();layout(root)
            assertFalse(sketch.isShown)
            assertNull(ReflectionHelpers.getField<ValueAnimator?>(sketch, "animator"))
            val status = surface.findViewById<TextView>(R.id.pf_ff_land_query_status)
            assertEquals(root.context.getString(R.string.pf_ff_land_retry), status.text.toString())
            status.performClick();assertEquals(1, events.retried)
            capture(root, "landing-query-retry-day")
            surface.showOptions(emptyList());surface.render(10, null, false);layout(root)
            assertEquals(root.context.getString(R.string.pf_ff_land_empty), status.text.toString())
            assertFalse(status.isClickable);assertFalse(sketch.isShown);assertFalse(surface.confirm.isEnabled)
            status.performClick();surface.confirm.performClick()
            assertEquals(1, events.retried);assertEquals(0, events.confirmed)
            capture(root, "landing-query-empty-day")
        }
    }

    @Test fun hiddenUnfocusedAndDetachedLoadingStopsItsOwnedFrames() {
        fixture { surface, root, events ->
            surface.loading();layout(root)
            val sketch = walk(surface).filterIsInstance<AirportSkyStateView>().single()
            assertNotNull(ReflectionHelpers.getField<ValueAnimator?>(sketch, "animator"))
            events.focus(false);advance(32)
            assertNull(ReflectionHelpers.getField<ValueAnimator?>(sketch, "animator"))
            events.focus(true);layout(root)
            assertNotNull(ReflectionHelpers.getField<ValueAnimator?>(sketch, "animator"))
            surface.visibility = View.INVISIBLE;advance(32)
            assertNull(ReflectionHelpers.getField<ValueAnimator?>(sketch, "animator"))
            surface.visibility = View.VISIBLE;layout(root)
            assertNotNull(ReflectionHelpers.getField<ValueAnimator?>(sketch, "animator"))
            root.removeView(surface);advance(32)
            assertNull(ReflectionHelpers.getField<ValueAnimator?>(sketch, "animator"))
            assertTrue(events.selected.isEmpty());assertEquals(0, events.confirmed)
        }
    }

    @Test fun reducedMotionKeepsRealFeedbackAndNightSelectionReadable() {
        fixture(night = true, motion = 0f) { surface, root, events ->
            surface.loading();layout(root)
            val sketch = walk(surface).filterIsInstance<AirportSkyStateView>().single()
            assertTrue(sketch.isShown)
            assertNull(ReflectionHelpers.getField<ValueAnimator?>(sketch, "animator"))
            surface.showOptions(listOf(option));surface.render(60, null, false);layout(root)
            assertEquals(1f, surface.cards.alpha);assertEquals(0f, surface.cards.translationX)
            val card = requireNotNull(surface.cards.findViewHolderForAdapterPosition(0)).itemView
            card.performClick();assertEquals(listOf(option), events.selected)
            assertFalse(surface.confirm.isEnabled)
            surface.render(60, "TSN", false);layout(root)
            assertTrue(card.isSelected);assertTrue(surface.confirm.isEnabled)
            val background = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(background)
                // View.draw establishes the same background bounds used on screen.
                card.draw(canvas)
                assertEquals(Rect(0, 0, card.width, card.height), card.background.bounds)
                background.eraseColor(Color.TRANSPARENT)
                card.background.draw(canvas)
                val paper = background.getPixel(card.width / 2, card.height / 2)
                assertEquals(255, Color.alpha(paper));assertTrue(Color.luminance(paper) < .15f)
                val name = card.findViewById<TextView>(R.id.pf_ff_card_tv_airport_name)
                val eta = card.findViewById<TextView>(R.id.pf_ff_card_tv_airport_city)
                assertTrue(ColorUtils.calculateContrast(name.currentTextColor, paper) >= 4.5)
                assertTrue(ColorUtils.calculateContrast(eta.currentTextColor, paper) >= 4.5)
            } finally { background.recycle() }
            assertTrue(card.contentDescription.contains(option.airport.displayName))
            surface.render(60, "TSN", true);surface.confirm.performClick()
            assertEquals(0, events.confirmed)
            assertEquals(root.context.getString(R.string.pf_ff_land_confirming), surface.confirm.text.toString())
            assertFalse(surface.confirm.isEnabled)
            capture(root, "landing-night-reduced-motion-selection")
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h568dp-mdpi")
    fun compactLargeTextAndRepeatedSystemAreasKeepChoicesAndConfirmReachable() {
        fixture(width = 320, height = 568, fontScale = 1.6f, mark = "none") { surface, root, _ ->
            surface.showOptions(listOf(option));surface.render(10, null, false);layout(root)
            val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            val insets = WindowInsetsCompat.Builder().setInsets(types, Insets.of(48, 36, 0, 28)).build()
            val first = IntArray(2)
            repeat(3) { index ->
                ViewCompat.dispatchApplyWindowInsets(surface, insets);layout(root)
                val point = IntArray(2);surface.confirm.getLocationOnScreen(point)
                assertTrue(point[0] >= 48 && point[1] >= 36)
                assertTrue(point[0] + surface.confirm.width <= root.width)
                assertTrue(point[1] + surface.confirm.height <= root.height - 28)
                assertTrue(surface.confirm.height >= 48)
                if (index == 0) point.copyInto(first) else assertArrayEquals(first, point)
            }
            val card = requireNotNull(surface.cards.findViewHolderForAdapterPosition(0)).itemView
            val eta = card.findViewById<TextView>(R.id.pf_ff_card_tv_airport_city)
            val visible = Rect();assertTrue(eta.getLocalVisibleRect(visible))
            assertEquals(eta.height, visible.height())
            val label = requireNotNull(surface.confirm.layout)
            assertTrue(label.height <= surface.confirm.height - surface.confirm.compoundPaddingTop - surface.confirm.compoundPaddingBottom)
            capture(root, "landing-small-large-text-system-areas")
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-mdpi")
    fun shortLandscapeKeepsRulerChoiceAndConfirmationInsideSystemAreas() {
        fixture(width = 640, height = 360, fontScale = 1.6f, mark = "none") { surface, root, events ->
            val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            val insets = WindowInsetsCompat.Builder().setInsets(types, Insets.of(48, 24, 0, 24)).build()
            ViewCompat.dispatchApplyWindowInsets(surface, insets)
            surface.showOptions(listOf(option));surface.render(35, null, false);layout(root)
            val card = requireNotNull(surface.cards.findViewHolderForAdapterPosition(0)).itemView
            for (control in listOf(surface.ruler, card, surface.confirm)) {
                val point = IntArray(2);control.getLocationOnScreen(point)
                assertTrue("left system area", point[0] >= 48)
                assertTrue("top system area", point[1] >= 24)
                assertTrue("right edge", point[0] + control.width <= root.width)
                assertTrue("bottom system area", point[1] + control.height <= root.height - 24)
            }
            val eta = card.findViewById<TextView>(R.id.pf_ff_card_tv_airport_city)
            val visible = Rect();assertTrue(eta.getLocalVisibleRect(visible))
            assertEquals(eta.height, visible.height())
            card.performClick();assertEquals(listOf(option), events.selected)
            assertFalse(surface.confirm.isEnabled)
            surface.render(35, "TSN", false);layout(root)
            assertTrue(surface.confirm.height >= 48)
            val label = requireNotNull(surface.confirm.layout)
            assertTrue(label.height <= surface.confirm.height - surface.confirm.compoundPaddingTop - surface.confirm.compoundPaddingBottom)
            surface.confirm.performClick();assertEquals(1, events.confirmed)
            capture(root, "landing-short-landscape-large-text-system-areas")
        }
    }

    private class Events(val focus: (Boolean) -> Unit) {
        val selected = mutableListOf<FreeFlightLandingOption>()
        var confirmed = 0
        var retried = 0
    }

    private fun fixture(night: Boolean = false, motion: Float = 1f, width: Int = 432,
        height: Int = 960, fontScale: Float = 1f, mark: String = "badge",
        block: (FreeFlightLandingSurface, FrameLayout, Events) -> Unit) {
        val oldScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        val oldMotion = ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java, "sDurationScale")
        val oldPause = ShadowChoreographer.isPaused();val oldDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", motion)
        RuntimeEnvironment.setFontScale(fontScale)
        val owner = Robolectric.buildActivity(Activity::class.java)
        try {
            val activity = owner.get();activity.setTheme(UiR.style.Theme_Pf)
            owner.setup()
            val events = Events { focused -> owner.windowFocusChanged(focused) }
            val surface = FreeFlightLandingSurface(activity, silent, night, "vertical", mark,
                {}, { events.selected += it }, { events.confirmed++ }, { events.retried++ })
            val root = FrameLayout(activity).apply {
                setBackgroundColor(if (night) Color.rgb(20, 30, 45) else Color.rgb(246, 245, 250))
                tag = width to height;addView(surface)
            }
            activity.setContentView(root);owner.visible();layout(root)
            owner.windowFocusChanged(true);layout(root)
            block(surface, root, events)
        } finally {
            try { owner.pause().stop().destroy() } finally {
                RuntimeEnvironment.setFontScale(oldScale)
                ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", oldMotion)
                ShadowChoreographer.setFrameDelay(oldDelay);ShadowChoreographer.setPaused(oldPause)
            }
        }
    }
    private fun advance(millis: Long) {
        var left = millis
        while (left > 0) { val step = minOf(16L, left);shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS);left -= step }
    }
    private fun layout(root: FrameLayout) {
        val size = root.tag as Pair<*, *>
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(size.first as Int, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size.second as Int, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight);advance(32)
        }
    }
    private fun walk(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(walk(view.getChildAt(index)))
    }
    private fun capture(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val file = File("build/reports/freeflight-landing-surface/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
