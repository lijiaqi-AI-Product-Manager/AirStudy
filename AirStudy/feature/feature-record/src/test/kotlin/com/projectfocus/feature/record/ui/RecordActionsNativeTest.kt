package com.projectfocus.feature.record.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ActivityScenario
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.share.RecordPreviewHostActivity
import com.projectfocus.core.ui.R as UiR
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "zh-rCN-w432dp-h936dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordActionsNativeTest {
    private lateinit var scenario: ActivityScenario<RecordPreviewHostActivity>
    private lateinit var activity: RecordPreviewHostActivity
    private var prompt: RecordDeleteDialog? = null
    private var gate: RecordLoginGate? = null
    private var valid = true
    private var confirmations = 0

    @Before fun open() {
        scenario = ActivityScenario.launch(RecordPreviewHostActivity::class.java)
        scenario.onActivity { activity = it }
    }
    @After fun close() { gate?.close(); prompt?.dismiss(); scenario.close() }
    private fun idle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)) }
    private fun show(context: Context = activity): RecordDeleteDialog =
        RecordDeleteDialog(context, { valid }, { confirmations++ }).also { prompt = it; it.show(); measure(it); idle(); measure(it) }

    private fun measure(dialog: RecordDeleteDialog) {
        val decor = dialog.window!!.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(dialog.window!!.attributes.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dialog.context.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
    }
    private fun capture(view: View, name: String) {
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/record-card/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
    private fun readable(dialog: RecordDeleteDialog) {
        val message = dialog.findViewById<TextView>(R.id.pf_record_delete_message)
        val confirm = dialog.findViewById<TextView>(R.id.pf_record_delete_confirm)
        val cancel = dialog.findViewById<TextView>(R.id.pf_record_delete_cancel)
        assertTrue(message.width > 0 && message.height > 0)
        assertTrue(message.lineCount > 0)
        assertTrue(confirm.height > 0 && cancel.height > 0)
        assertTrue(confirm.top >= 0 && cancel.top >= 0)
        assertTrue(dialog.window!!.decorView.height <= dialog.context.resources.displayMetrics.heightPixels)
        for (text in listOf(message, confirm, cancel)) for (line in 0 until text.lineCount)
            assertTrue(text.layout.getLineWidth(line) <= text.width - text.paddingLeft - text.paddingRight + 1f)
    }

    @Test fun portraitConfirmationUsesTheRoundedDialogAndPreventsDuplicateSubmission() {
        val dialog = show()
        val card = dialog.findViewById<CardView>(R.id.pf_record_delete_dialog)
        assertEquals(392, dialog.window!!.attributes.width)
        assertEquals(28f, card.radius, .01f)
        assertEquals("是否删除该飞行记录？", dialog.findViewById<TextView>(R.id.pf_record_delete_message).text.toString())
        val confirm = dialog.findViewById<TextView>(R.id.pf_record_delete_confirm)
        assertEquals(ResourcesCompat.getFont(activity, UiR.font.harmonyos_sans_sc_medium), confirm.typeface)
        assertEquals(LinearLayout.VERTICAL, (confirm.parent as LinearLayout).orientation)
        readable(dialog)
        capture(dialog.window!!.decorView, "record-delete-day")
        confirm.performClick(); confirm.performClick()
        assertEquals(1, confirmations)
        assertFalse(confirm.isEnabled)
        dialog.setPending(false)
        confirm.performClick()
        assertEquals(2, confirmations)
        dialog.dismiss()
        confirm.performClick()
        assertEquals(2, confirmations)
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h740dp-night-mdpi")
    fun narrowNightConfirmationKeepsBothActionsAndTheFullMessage() {
        val dialog = show(); readable(dialog)
        assertEquals(280, dialog.window!!.attributes.width)
        capture(dialog.window!!.decorView, "record-delete-night-narrow")
    }

    @Test @Config(qualifiers = "en-rUS-sw600dp-w900dp-h640dp-land-mdpi")
    fun tabletConfirmationPlacesCancelBeforeDeleteInOneRow() {
        val dialog = show(); readable(dialog)
        val confirm = dialog.findViewById<TextView>(R.id.pf_record_delete_confirm)
        val actions = confirm.parent as LinearLayout
        assertEquals(600, dialog.window!!.attributes.width)
        assertEquals(LinearLayout.HORIZONTAL, actions.orientation)
        assertEquals(R.id.pf_record_delete_cancel, actions.getChildAt(0).id)
        capture(dialog.window!!.decorView, "record-delete-tablet")
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h740dp-mdpi")
    fun largerTextWrapsTheMessageAndRetainsBothActions() {
        val configuration = Configuration(activity.resources.configuration).apply { fontScale = 1.6f }
        val context = ContextThemeWrapper(activity, UiR.style.Theme_Pf).apply { applyOverrideConfiguration(configuration) }
        val dialog = show(context); readable(dialog)
        assertTrue(dialog.findViewById<TextView>(R.id.pf_record_delete_message).lineCount > 1)
        capture(dialog.window!!.decorView, "record-delete-large-text")
    }

    @Test @Config(qualifiers = "zh-rCN-w393dp-h852dp-420dpi")
    fun fractionalDensityPreservesTheOriginalFloatingPointCornerRadius() {
        val dialog = show(); readable(dialog)
        val density = activity.resources.displayMetrics.density
        assertEquals(28f * density, dialog.findViewById<CardView>(R.id.pf_record_delete_dialog).radius, .01f)
        capture(dialog.window!!.decorView, "record-delete-fractional-density")
    }

    @Test fun anInvalidatedRecordClosesItsPromptAndDisablesRetainedClickHandlers() {
        val dialog = show()
        val confirm = dialog.findViewById<TextView>(R.id.pf_record_delete_confirm)
        valid = false
        capture(dialog.window!!.decorView, "record-delete-obsolete")
        idle()
        assertFalse(dialog.isShowing)
        confirm.performClick()
        assertEquals(0, confirmations)
    }

    @Test fun theLoginOverlayOpensOnlyTheCurrentEntryAndReleasesItsBackground() {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(936, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 432, 936)
        var visible = false
        var logins = 0
        gate = RecordLoginGate(activity, content) { visible = it }
        gate!!.open({ valid }) { logins++ }
        idle()
        content.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(936, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 432, 936)
        assertTrue(visible)
        capture(content, "record-login-gate")
        val button = content.findViewById<View>(R.id.pf_record_login_button)
        button.performClick()
        assertEquals(1, logins); assertFalse(visible)
        gate!!.open({ valid }) { logins++ }
        button.performClick()
        assertEquals(1, logins)
        assertTrue(gate!!.isOpen)
        gate!!.close()
        valid = false
        button.performClick()
        assertEquals(1, logins)
        assertNull(content.findViewById<View>(R.id.pf_record_login_gate))
    }
}
