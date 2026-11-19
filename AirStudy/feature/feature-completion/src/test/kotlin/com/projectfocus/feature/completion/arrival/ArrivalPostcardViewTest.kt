package com.projectfocus.feature.completion.arrival

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.projectfocus.core.ui.R as UiR
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w432dp-h960dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArrivalPostcardViewTest {
    @Test fun disabledMotionBeforeAttachmentKeepsTheCompleteScene() {
        val oldScale = ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java, "sDurationScale")
        try {
            ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", 0f)
            val view = ArrivalPostcardView(RuntimeEnvironment.getApplication())
            view.showArrival()
            assertNull(animation(view));assertEquals(1f, fraction(view))
            assertFalse(ReflectionHelpers.getField<Boolean>(view, "pending"))
        } finally { ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", oldScale) }
    }

    @Test fun dayIllustrationIsOpaqueAndDecorative() {
        fixture { view, _, _ ->
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.importantForAccessibility)
            capture(view, "arrival-illustration-day") { bitmap ->
                assertEquals(255, Color.alpha(bitmap.getPixel(1, 1)))
                assertTrue(Color.luminance(bitmap.getPixel(1, 1)) > .5f)
                assertNotEquals(bitmap.getPixel(1, 1), bitmap.getPixel(180, 250))
            }
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w432dp-h960dp-night-mdpi")
    fun nightIllustrationKeepsItsOwnDarkPalette() {
        fixture { view, _, _ ->
            capture(view, "arrival-illustration-night") { bitmap ->
                assertEquals(255, Color.alpha(bitmap.getPixel(1, 1)))
                assertTrue(Color.luminance(bitmap.getPixel(1, 1)) < .15f)
                assertNotEquals(bitmap.getPixel(1, 1), bitmap.getPixel(180, 250))
            }
        }
    }

    @Test fun lostFocusAndDetachmentStopTheOwnedArrivalFrames() {
        fixture { view, root, focus ->
            view.showArrival();advance(64)
            assertNotNull(animation(view))
            focus(false);advance(32)
            assertNull(animation(view));assertEquals(1f, fraction(view))
            focus(true);advance(32)
            assertNull(animation(view))
            view.showArrival();advance(32)
            assertNotNull(animation(view))
            root.removeView(view);advance(32)
            assertNull(animation(view));assertEquals(1f, fraction(view))
        }
    }

    @Test fun disabledMotionShowsTheCompleteIllustrationWithoutAFrameLoop() {
        fixture(motion = 0f) { view, _, _ ->
            view.showArrival();advance(32)
            assertNull(animation(view));assertEquals(1f, fraction(view))
            capture(view, "arrival-illustration-reduced-motion") { bitmap ->
                assertEquals(255, Color.alpha(bitmap.getPixel(180, 180)))
            }
        }
    }

    private fun fixture(motion: Float = 1f, block: (ArrivalPostcardView, FrameLayout, (Boolean) -> Unit) -> Unit) {
        val oldScale = ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java, "sDurationScale")
        val oldPause = ShadowChoreographer.isPaused();val oldDelay = ShadowChoreographer.getFrameDelay()
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", motion)
        ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        val owner = Robolectric.buildActivity(Activity::class.java)
        try {
            val activity = owner.get();activity.setTheme(UiR.style.Theme_Pf);owner.setup()
            val view = ArrivalPostcardView(activity)
            val root = FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(360, 360)) }
            activity.setContentView(root);owner.visible();layout(root)
            owner.windowFocusChanged(true);layout(root)
            block(view, root) { owner.windowFocusChanged(it) }
        } finally {
            try { owner.pause().stop().destroy() } finally {
                ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", oldScale)
                ShadowChoreographer.setFrameDelay(oldDelay);ShadowChoreographer.setPaused(oldPause)
            }
        }
    }

    private fun layout(root: FrameLayout) {
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 432, 960);advance(32)
        }
    }
    private fun animation(view: ArrivalPostcardView): ValueAnimator? = ReflectionHelpers.getField(view, "animator")
    private fun fraction(view: ArrivalPostcardView): Float = ReflectionHelpers.getField(view, "fraction")
    private fun advance(millis: Long) {
        var left = millis
        while (left > 0) { val step = minOf(16L, left);shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS);left -= step }
    }
    private fun capture(view: View, name: String, check: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap));check(bitmap)
            val file = File("build/reports/ui-arrival/$name.png");file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
