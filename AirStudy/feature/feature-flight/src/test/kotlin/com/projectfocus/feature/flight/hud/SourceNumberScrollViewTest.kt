package com.projectfocus.feature.flight.hud

import android.app.Activity
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import java.io.File
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

/** Frozen original numeric contracts exercised on a real attached native View hierarchy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceNumberScrollViewTest {
    @Test fun sourceInitialNegativeFontAndBaselineAreActualNativeLabels() = withWidget { view, layout ->
        view.setValue(-8)
        layout()
        assertThat(view.getValue()).isEqualTo(0)
        assertThat(displayed(view)).isEqualTo("0")
        val text = labels(view.getChildAt(0) as ViewGroup).single { it.visibility == View.VISIBLE }
        assertThat(text.includeFontPadding).isFalse()
        assertThat(text.currentTextColor).isEqualTo(Color.BLACK)
        assertThat(text.typeface).isEqualTo(ResourcesCompat.getFont(view.context, com.projectfocus.core.ui.R.font.jetbrainsmono_medium))
        assertThat(view.baseline).isGreaterThan(0)
        assertDrawnNumeral(view, text)
    }

    @Test fun changedDigitsActuallyTranslateFadeAndSwapAfterTheSourceDuration() = withWidget { view, layout ->
        view.setValue(19)
        layout()
        view.setValue(20)
        shadowOf(Looper.getMainLooper()).idle()
        advanceFrames(150)
        val ones = view.getChildAt(1) as ViewGroup
        val visible = labels(ones).filter { it.visibility == View.VISIBLE }
        assertThat(visible).hasSize(2)
        assertThat(visible.any { it.translationY < 0f }).isTrue()
        assertThat(visible.any { it.translationY > 0f }).isTrue()
        assertThat(visible.all { it.alpha < 1f }).isTrue()
        settle()
        layout()
        assertThat(displayed(view)).isEqualTo("20")
        assertThat((0 until view.childCount).all { index ->
            labels(view.getChildAt(index) as ViewGroup).count { it.visibility == View.VISIBLE } == 1
        }).isTrue()
    }

    @Test fun shrinkingAndMinimumDigitsPreserveTheOriginalVisibilityContract() = withWidget { view, layout ->
        view.setValue(999)
        layout()
        view.setValue(7)
        settle()
        layout()
        assertThat(displayed(view)).isEqualTo("7")
        assertThat((0 until view.childCount).count { view.getChildAt(it).visibility == View.GONE }).isEqualTo(2)
        view.setMinVisibleDigits(2)
        layout()
        assertThat(displayed(view)).isEqualTo("07")
        assertThat(view.getChildAt(0).visibility).isEqualTo(View.INVISIBLE)
        assertThat(view.baseline).isGreaterThan(0)
    }

    @Test fun activeDigitKeepsItsOriginalTargetUntilTheFollowingAcceptedUpdate() = withWidget { view, layout ->
        view.setValue(17)
        layout()
        view.setValue(18)
        shadowOf(Looper.getMainLooper()).idle()
        view.setValue(19)
        settle()
        layout()
        assertThat(view.getValue()).isEqualTo(19)
        assertThat(displayed(view)).isEqualTo("18")
        view.setValue(19)
        layout()
        assertThat(displayed(view)).isEqualTo("19")
    }

    private fun displayed(view: SourceNumberScrollView): String = (0 until view.childCount)
        .map { view.getChildAt(it) as ViewGroup }.filter { it.visibility == View.VISIBLE }
        .joinToString("") { digit -> labels(digit).single { it.visibility == View.VISIBLE }.text.toString() }

    private fun labels(view: ViewGroup): List<TextView> = (0 until view.childCount).map { view.getChildAt(it) as TextView }
    private fun settle() = advanceFrames(350)
    private fun advanceFrames(milliseconds: Int) {
        var left = milliseconds
        while (left > 0) {
            val step = minOf(16, left)
            shadowOf(Looper.getMainLooper()).idleFor(step.toLong(), TimeUnit.MILLISECONDS)
            left -= step
        }
    }

    private fun withWidget(action: (SourceNumberScrollView, () -> Unit) -> Unit) {
        // Robolectric4.14 advances frames automatically unless explicitly paused.
        // Keep the real production ValueAnimator and advance its actual scheduled frames below.
        val previousPause = ShadowChoreographer.isPaused()
        val previousDelay = ShadowChoreographer.getFrameDelay()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val view = SourceNumberScrollView(activity)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val density = activity.resources.displayMetrics.density
        val layout = {
            root.measure(View.MeasureSpec.makeMeasureSpec((390 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((844 * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
            Unit
        }
        try {
            layout()
            assertThat(view.isAttachedToWindow).isTrue()
            action(view, layout)
        } finally {
            settle()
            controller.pause().stop().destroy()
            ShadowChoreographer.setPaused(previousPause)
            ShadowChoreographer.setFrameDelay(previousDelay)
        }
    }

    private fun assertDrawnNumeral(view: SourceNumberScrollView, text: TextView) {
        System.err.println("Actual single-line text before preDraw: layoutWidth=${text.layout?.width},scroll=${text.scrollX},lineLeft=${text.layout?.getLineLeft(0)}")
        view.viewTreeObserver.dispatchOnPreDraw()
        System.err.println("Actual single-line text after preDraw: layoutWidth=${text.layout?.width},scroll=${text.scrollX},lineLeft=${text.layout?.getLineLeft(0)}")
        fun draw(actual: View, name: String): Int {
            assertThat(actual.width).isGreaterThan(0)
            assertThat(actual.height).isGreaterThan(0)
            val bitmap = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                // ViewGroup.drawChild normally applies the child's scroll before View.draw.
                // Reproduce that viewport when drawing an attached leaf in isolation too.
                canvas.translate(-actual.scrollX.toFloat(), -actual.scrollY.toFloat())
                actual.draw(canvas)
                val output = File("build/reports/source-number-diagnostics").apply { mkdirs() }
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return (0 until bitmap.width).sumOf { x -> (0 until bitmap.height).count { y ->
                    val color = bitmap.getPixel(x, y)
                    Color.red(color) < 100 && Color.green(color) < 100 && Color.blue(color) < 100
                } }
            } finally { bitmap.recycle() }
        }
        val whole = draw(view, "attached-number")
        val leaf = draw(text, "actual-attached-text-child")
        val painted = Bitmap.createBitmap(text.width, text.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(painted)
            canvas.drawColor(Color.WHITE)
            canvas.drawText(text.text.toString(), 0f, text.baseline.toFloat(), text.paint)
            File("build/reports/source-number-diagnostics/actual-text-paint.png").outputStream().use {
                painted.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { painted.recycle() }
        System.err.println("Actual numeral pixels: hierarchy=$whole child=$leaf; " + actualHudPreviewMetadata(view))
        assertThat(whole).isGreaterThan(0)
        assertThat(leaf).isGreaterThan(0)
    }
}
