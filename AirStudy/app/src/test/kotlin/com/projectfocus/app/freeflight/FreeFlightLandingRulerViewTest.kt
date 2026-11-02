package com.projectfocus.app.freeflight

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real native ruler/gestures/fonts. Silent preference fixture only; no flight/grant/map evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FreeFlightLandingRulerViewTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var ruler: FreeFlightLandingRulerView
    private val changes = mutableListOf<Int>()
    private val silent = object : FreeFlightRulerFeedback {
        override fun tickVolume() = 0f
        override fun hapticIntensity() = 0
    }
    @Before fun open() {
        controller = Robolectric.buildActivity(Activity::class.java)
        activity = controller.get(); activity.setTheme(com.projectfocus.core.ui.R.style.Theme_Pf)
        controller.setup().visible()
        ruler = FreeFlightLandingRulerView(activity, silent, false).apply { onTimeChanged = { changes += it } }
        activity.setContentView(FrameLayout(activity).apply { addView(ruler, FrameLayout.LayoutParams(432, 120)) })
        ruler.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
        ruler.layout(0, 0, 432, 120)
    }
    @After fun close() { controller.pause().stop().destroy() }

    @Test fun sourceProgrammaticSnappingClampsAndPreservesSeparateRoundedMinutesWithoutPublishingAUserChange() {
        assertEquals(10, ruler.currentTime())
        ruler.setCurrentTime(13); assertEquals(13, ruler.currentTime())
        ruler.setCurrentTime(117); assertEquals(118, ruler.currentTime())
        ruler.setCurrentTime(119); assertEquals(120, ruler.currentTime())
        ruler.setCurrentTime(Int.MIN_VALUE); assertEquals(10, ruler.currentTime())
        ruler.setCurrentTime(Int.MAX_VALUE); assertEquals(240, ruler.currentTime())
        assertTrue(changes.isEmpty())
        assertEquals("59m", FreeFlightLandingRulerView.formatTime(59))
        assertEquals("1h", FreeFlightLandingRulerView.formatTime(60))
        assertEquals("1h1m", FreeFlightLandingRulerView.formatTime(61))
        assertEquals("4h", FreeFlightLandingRulerView.formatTime(240))
    }

    @Test fun actualSeventeenPixelDragUsesSourceTruncationBeforeTwoPointFiveSnapAndCancelDoesNotInventAnotherRange() {
        ruler.setCurrentTime(10)
        val now = SystemClock.uptimeMillis()
        fun event(action: Int, elapsed: Long, x: Float) = MotionEvent.obtain(now, now + elapsed, action, x, 70f, 0)
        event(MotionEvent.ACTION_DOWN, 0, 350f).also { ruler.dispatchTouchEvent(it); it.recycle() }
        event(MotionEvent.ACTION_MOVE, 100, 333f).also { ruler.dispatchTouchEvent(it); it.recycle() }
        assertEquals(listOf(13), changes)
        assertEquals(13, ruler.currentTime())
        event(MotionEvent.ACTION_CANCEL, 200, 333f).also { ruler.dispatchTouchEvent(it); it.recycle() }
        repeat(16) { shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS); ruler.computeScroll() }
        assertEquals(13, ruler.currentTime()); assertEquals(listOf(13), changes)
    }

    @Test fun actualNativeDayAndNightRulerHasSourceYellowPointerAndVisibleSelectedBadge() {
        val folder = File("build/reports/freeflight-landing-ruler").apply { mkdirs() }
        for (night in listOf(false, true)) {
            ruler.applyNight(night); ruler.setCurrentTime(60)
            shadowOf(Looper.getMainLooper()).idleFor(32, TimeUnit.MILLISECONDS)
            val bitmap = Bitmap.createBitmap(432, 120, Bitmap.Config.ARGB_8888)
            try {
                val background = if (night) Color.rgb(20, 30, 45) else Color.rgb(246, 245, 250)
                val canvas = Canvas(bitmap); canvas.drawColor(background); ruler.draw(canvas)
                val pointer = bitmap.getPixel(216, 45)
                assertTrue(Color.red(pointer) > 180 && Color.green(pointer) > 180 && Color.blue(pointer) < 100)
                val badgeInk = (190..242).sumOf { x -> (96..115).count { y -> bitmap.getPixel(x, y) != background } }
                assertTrue(badgeInk > 150)
                val file = File(folder, if (night) "source-ruler-night.png" else "source-ruler-day.png")
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }
}
