package com.projectfocus.feature.completion.arrival

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.ui.widget.RollingNumberView
import com.projectfocus.feature.completion.R
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w432dp-h960dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArrivalSkyPresentationTest {
    @Test fun dayPageUsesTheBoundAirportAndActualTotalsWithAllActionsAvailable() {
        fixture { binder, root ->
            binder.bind(record(), 1234.5, 90);layout(root);advance(1800);layout(root)
            assertEquals("PVG", text(root, R.id.arrival_airport_code_on_media))
            assertEquals("上海", text(root, R.id.arrival_city_title))
            assertEquals(1235, root.findViewById<RollingNumberView>(R.id.nsv_mileage).getValue())
            assertEquals(90, root.findViewById<RollingNumberView>(R.id.nsv_focus).getValue())
            assertNotNull(walk(root).filterIsInstance<ArrivalPostcardView>().singleOrNull())
            assertTrue(root.findViewById<View>(R.id.welcome_subtitle).isShown)
            assertContrast(root)
            val media = root.findViewById<View>(R.id.arrival_media_frame)
            val bitmap = Bitmap.createBitmap(media.width, media.height, Bitmap.Config.ARGB_8888)
            try {
                media.draw(Canvas(bitmap))
                assertEquals(0, Color.alpha(bitmap.getPixel(1, 1)))
                assertEquals(255, Color.alpha(bitmap.getPixel(media.width / 2, media.height / 2)))
            } finally { bitmap.recycle() }
            var actions = 0
            binder.onShare = { actions++ };binder.onReport = { actions++ }
            binder.onComplete = { actions++ };binder.onAirport = { actions++ }
            for (id in listOf(R.id.restart_flight_button, R.id.report_airport_button, R.id.complete_flight_button, R.id.arrival_airport_link))
                assertTrue(root.findViewById<View>(id).performClick())
            assertEquals(4, actions)
            capture(root, "arrival-page-day")
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w432dp-h960dp-night-mdpi")
    fun nightPageKeepsTotalsReadableWhenSystemMotionIsDisabled() {
        fixture(motion = 0f) { binder, root ->
            binder.bind(record(), 1234.5, 90);layout(root)
            val illustration = walk(root).filterIsInstance<ArrivalPostcardView>().single()
            assertNull(ReflectionHelpers.getField<ValueAnimator?>(illustration, "animator"))
            assertEquals(1f, ReflectionHelpers.getField<Float>(illustration, "fraction"))
            assertContrast(root)
            capture(root, "arrival-page-night-reduced-motion")
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h568dp-notnight-mdpi")
    fun smallPageKeepsLargeTextActionsAndScrolledTotalsOutsideSystemAreas() {
        for (scale in listOf(1.6f, 2f)) fixture(width = 320, height = 568, fontScale = scale, motion = 0f) { binder, root ->
            binder.bind(record(), 1234.5, 90);layout(root)
            val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            val insets = WindowInsetsCompat.Builder().setInsets(types, Insets.of(24, 28, 0, 24)).build()
            val overlay = root.findViewById<View>(R.id.flight_completion_overlay)
            val first = IntArray(2)
            repeat(2) { index ->
                ViewCompat.dispatchApplyWindowInsets(overlay, insets);layout(root)
                val button = root.findViewById<TextView>(R.id.complete_flight_button)
                val point = IntArray(2);button.getLocationOnScreen(point)
                assertTrue(point[0] >= 24);assertTrue(point[1] >= 28)
                assertTrue(point[0] + button.width <= root.width)
                assertTrue(point[1] + button.height <= root.height - 24)
                assertTrue(button.height >= 48 && button.width >= 48)
                assertTrue(button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                if (index == 0) point.copyInto(first) else assertArrayEquals(first, point)
            }
            assertEquals(LinearLayout.VERTICAL, root.findViewById<LinearLayout>(R.id.flight_completion_actions).orientation)
            capture(root, "arrival-small-${scale}-top")
            val scroll = root.findViewById<ScrollView>(R.id.arrival_completion_scroll)
            scroll.scrollTo(0, scroll.getChildAt(0).height);layout(root)
            val stats = root.findViewById<LinearLayout>(R.id.stats_container)
            assertEquals(LinearLayout.VERTICAL, stats.orientation)
            val position = IntArray(2);stats.getLocationOnScreen(position)
            val actions = root.findViewById<View>(R.id.flight_completion_actions)
            val actionPosition = IntArray(2);actions.getLocationOnScreen(actionPosition)
            assertTrue("Totals remain above the fixed actions", position[1] + stats.height <= actionPosition[1])
            capture(root, "arrival-small-${scale}-totals")
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-notnight-mdpi")
    fun landscapeKeepsThePrimaryActionReachableAndTheSquarePostcardCompact() {
        fixture(width = 640, height = 360, fontScale = 1.6f, motion = 0f) { binder, root ->
            binder.bind(record(), 1234.5, 90);layout(root)
            val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ViewCompat.dispatchApplyWindowInsets(root.findViewById(R.id.flight_completion_overlay),
                WindowInsetsCompat.Builder().setInsets(types, Insets.of(48, 24, 0, 24)).build())
            layout(root)
            val media = root.findViewById<View>(R.id.arrival_media_frame)
            assertEquals(media.width, media.height)
            assertTrue(media.width <= 260)
            for (id in listOf(R.id.restart_flight_button, R.id.report_airport_button, R.id.complete_flight_button)) {
                val action = root.findViewById<View>(id);val point = IntArray(2);action.getLocationOnScreen(point)
                assertTrue(point[0] >= 48 && point[1] >= 24)
                assertTrue(point[0] + action.width <= root.width && point[1] + action.height <= root.height - 24)
                assertTrue(action.width >= 48 && action.height >= 48)
            }
            capture(root, "arrival-landscape-large-text-top")
            val scroll = root.findViewById<ScrollView>(R.id.arrival_completion_scroll)
            scroll.scrollTo(0, scroll.getChildAt(0).height);layout(root)
            capture(root, "arrival-landscape-large-text-totals")
        }
    }

    private fun fixture(width: Int = 432, height: Int = 960, fontScale: Float = 1f, motion: Float = 1f,
        block: (FlightCompletionBinder, FrameLayout) -> Unit) {
        val oldScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        val oldMotion = ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java, "sDurationScale")
        val oldPause = ShadowChoreographer.isPaused();val oldDelay = ShadowChoreographer.getFrameDelay()
        RuntimeEnvironment.setFontScale(fontScale)
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", motion)
        ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        val owner = Robolectric.buildActivity(Activity::class.java)
        try {
            val activity = owner.get();activity.setTheme(UiR.style.Theme_Pf);owner.setup()
            val overlay = LayoutInflater.from(activity).inflate(R.layout.fragment_flight_completion, null)
            val binder = FlightCompletionBinder(overlay)
            val root = FrameLayout(activity).apply { tag = width to height;addView(overlay, FrameLayout.LayoutParams(-1, -1)) }
            activity.setContentView(root);owner.visible();layout(root);owner.windowFocusChanged(true);layout(root)
            block(binder, root)
        } finally {
            try { owner.pause().stop().destroy() } finally {
                RuntimeEnvironment.setFontScale(oldScale)
                ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", oldMotion)
                ShadowChoreographer.setFrameDelay(oldDelay);ShadowChoreographer.setPaused(oldPause)
            }
        }
    }
    private fun assertContrast(root: View) {
        val paper = root.context.getColor(UiR.color.sky_card_solid)
        for (id in listOf(R.id.tv_mileage_unit, R.id.tv_focus_unit))
            assertTrue(ColorUtils.calculateContrast(root.findViewById<TextView>(id).currentTextColor, paper) >= 4.5)
    }
    private fun text(root: View, id: Int) = root.findViewById<TextView>(id).text.toString()
    private fun layout(root: FrameLayout) {
        val size = root.tag as Pair<*, *>
        repeat(5) {
            root.measure(View.MeasureSpec.makeMeasureSpec(size.first as Int, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size.second as Int, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight);advance(32)
        }
    }
    private fun advance(millis: Long) {
        var left = millis
        while (left > 0) { val step = minOf(16L, left);shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS);left -= step }
    }
    private fun walk(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) yieldAll(walk(view.getChildAt(index)))
    }
    private fun capture(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val path = File("build/reports/ui-arrival/$name.png");path.parentFile!!.mkdirs()
            path.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun record(): FlightHistoryEntity = FlightHistoryEntity(
        id = "r7",
        roomId = null,
        focusType = "SOLO",
        roomParticipantCount = null,
        departureAirportCode = "PEK",
        departureAirportName = "北京首都国际机场",
        departureCity = "北京",
        departureCountry = "中国",
        departureLatitude = 40.08,
        departureLongitude = 116.58,
        destinationAirportCode = "PVG",
        destinationAirportName = "上海浦东国际机场",
        destinationCity = "上海",
        destinationCountry = "中国",
        destinationLatitude = 31.14,
        destinationLongitude = 121.81,
        startTimeMillis = 1_700_000_000_000L,
        endTimeMillis = 1_700_002_700_000L,
        durationMinutes = 45,
        actualFocusMinutes = 45,
        distanceKm = 600.0,
        status = "COMPLETED",
        categoryId = null,
        seat = "12A",
        hasAnnouncedDeparture = true,
        hasAnnouncedDescent = true,
        hasAnnouncedArrival = true,
        logJson = null,
        isSynced = true,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_002_700_000L,
        quote = "The journey is the reward.",
        quoteAuthor = "Steve Jobs",
    )
}
