package com.projectfocus.feature.scene.ui

import android.app.Activity
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.view.WindowManager
import com.google.android.material.button.MaterialButton
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.feature.scene.R
import com.projectfocus.feature.scene.source.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SourceSceneAddDialogTest {
    @Test
    @Config(qualifiers = "w320dp-h480dp-mdpi")
    fun `small viewport uses actual source window bounds and both pages scroll within source caps`() = withHost { context ->
        val history = options((1..40).map { SourceSceneRecord("历史场景 $it", "study", 0xFF3CC612.toInt(), true) })
        val dialog = SourceSceneAddDialog(context,
            { SourceSceneEnableChoices(emptyList(), history.map { SourceSceneEnableCandidate(it, false) }) },
            SourceSceneNameChecker { _, _ -> null }, { _, _ -> null })
        dialog.show()
        assertEquals(280, dialog.window!!.attributes.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, dialog.window!!.attributes.height)
        val scroll = dialog.findViewById<View>(R.id.pf_scene_available_scroll)
        scroll.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("history must be measurable", scroll.measuredHeight > 0)
        assertTrue("source page1 cap is 270px at 480px viewport: ${scroll.measuredHeight}", scroll.measuredHeight <= 270)
        dialog.findViewById<View>(R.id.pf_scene_go_custom).performClick()
        val custom = dialog.findViewById<View>(R.id.pf_scene_page2_scroll)
        custom.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("custom page must be measurable", custom.measuredHeight > 0)
        assertTrue("source page2 cap is 384px at 480px viewport: ${custom.measuredHeight}", custom.measuredHeight <= 384)
        dialog.dismiss()
    }

    @Test
    fun `builtin stores directly while enabled history stays visible and does nothing`() = withHost { context ->
        val builtin = options(listOf(SourceSceneCatalog.builtins[2].record())).single()
        val history = options(listOf(SourceSceneRecord("旧场景", "work", 0xFF123456.toInt(), true))).single()
        var checks = 0
        var changes = 0
        val saved = mutableListOf<SourceSceneRecord>()
        val dialog = SourceSceneAddDialog(context,
            { SourceSceneEnableChoices(listOf(builtin), listOf(SourceSceneEnableCandidate(history, true))) },
            SourceSceneNameChecker { _, _ -> checks++; null },
            { record, complete -> saved += record; complete(SourceSceneSaveResult.SAVED); null },
            { changes++ })
        dialog.show()
        val enabled = dialog.option(history.optionId)
        assertEquals(.5f, enabled.alpha, .001f)
        enabled.performClick()
        assertTrue(saved.isEmpty())
        assertEquals(11, dialog.findViewById<LinearLayout>(R.id.pf_scene_icon_selector).childCount)
        assertEquals(80, dialog.findViewById<View>(R.id.pf_scene_custom_preview).layoutParams.width)
        assertEquals(48, dialog.findViewById<View>(R.id.pf_scene_add_custom).layoutParams.height)
        dialog.option(builtin.optionId).performClick()
        idle()
        assertEquals(listOf(builtin.record), saved)
        assertEquals(0, checks)
        assertEquals(1, changes)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `custom page returns then saves chosen icon only after actual allowed callback with Java trim`() = withHost { context ->
        var checked: String? = null
        var completeCheck: ((SourceSceneNameCheckResult) -> Unit)? = null
        var completeSave: ((SourceSceneSaveResult) -> Unit)? = null
        val saved = mutableListOf<SourceSceneRecord>()
        val dialog = SourceSceneAddDialog(context, { emptyChoices() },
            SourceSceneNameChecker { name, complete -> checked = name; completeCheck = complete; null },
            { record, complete -> saved += record; completeSave = complete; null })
        dialog.show()
        dialog.findViewById<View>(R.id.pf_scene_go_custom).performClick()
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.pf_scene_page2_scroll).visibility)
        dialog.findViewById<View>(R.id.pf_scene_cancel_custom).performClick()
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.pf_scene_page1).visibility)
        dialog.findViewById<View>(R.id.pf_scene_go_custom).performClick()
        dialog.findViewById<LinearLayout>(R.id.pf_scene_icon_selector).getChildAt(2).performClick()
        dialog.findViewById<EditText>(R.id.pf_scene_custom_name).setText(" \t\u00a0自定义\u00a0 \n")
        val add = dialog.findViewById<View>(R.id.pf_scene_add_custom)
        add.performClick()
        assertEquals("\u00a0自定义\u00a0", checked)
        assertFalse(add.isEnabled)
        assertTrue(saved.isEmpty())
        completeCheck!!(SourceSceneNameCheckResult.ALLOWED)
        idle()
        assertEquals(listOf(SourceSceneRecord("\u00a0自定义\u00a0", "reading", SourceSceneCatalog.builtins[2].color, true, -1, null)), saved)
        completeSave!!(SourceSceneSaveResult.FAILED)
        idle()
        assertTrue(dialog.isShowing)
        assertTrue(add.isEnabled)
        assertEquals(context.getString(R.string.pf_scene_save_failed), ShadowToast.getTextOfLatestToast())
        dialog.dismiss()
    }

    @Test
    fun `history reenable is moderated and both rejection and unavailable preserve the page`() = withHost { context ->
        val history = options(listOf(SourceSceneRecord("历史", "study", 0xFF3CC612.toInt(), true))).single()
        for (result in listOf(SourceSceneNameCheckResult.REJECTED, SourceSceneNameCheckResult.UNAVAILABLE)) {
            val names = mutableListOf<String>()
            var saves = 0
            val dialog = SourceSceneAddDialog(context,
                { SourceSceneEnableChoices(emptyList(), listOf(SourceSceneEnableCandidate(history, false))) },
                SourceSceneNameChecker { name, done -> names += name; done(result); null },
                { _, _ -> saves++; null })
            dialog.show()
            dialog.option(history.optionId).performClick()
            idle()
            assertEquals(listOf("历史"), names)
            assertEquals(0, saves)
            assertTrue(dialog.isShowing)
            assertTrue(dialog.option(history.optionId).isEnabled)
            assertTrue(ShadowToast.getTextOfLatestToast().contains(context.getString(R.string.pf_scene_name_check_failed_message)))
            dialog.dismiss()
        }
    }

    @Test
    fun `cancel pending closes check lease and late allowed never persists or refreshes`() = withHost { context ->
        var completion: ((SourceSceneNameCheckResult) -> Unit)? = null
        var closed = 0
        var saved = 0
        var refreshed = 0
        var dismissed = 0
        val dialog = SourceSceneAddDialog(context, { emptyChoices() },
            SourceSceneNameChecker { _, done -> completion = done; AutoCloseable { closed++ } },
            { _, _ -> saved++; null }, { refreshed++ })
        dialog.setOnDismissListener { dismissed++ }
        dialog.show()
        dialog.findViewById<View>(R.id.pf_scene_go_custom).performClick()
        dialog.findViewById<EditText>(R.id.pf_scene_custom_name).setText("保存前取消")
        dialog.findViewById<View>(R.id.pf_scene_add_custom).performClick()
        dialog.findViewById<View>(R.id.pf_scene_cancel_custom).performClick()
        completion!!(SourceSceneNameCheckResult.ALLOWED)
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(1, closed)
        assertEquals(1, dismissed)
        assertEquals(0, saved)
        assertEquals(0, refreshed)
    }

    private fun emptyChoices() = SourceSceneEnableChoices(emptyList(), emptyList())
    private fun options(records: List<SourceSceneRecord>) =
        SourceSceneOptionIds.snapshot(SourceSceneScope("editor-test"), SourceSceneCollection.ALL_CREATED_CUSTOM, records).options
    private fun SourceSceneAddDialog.option(id: String): MaterialButton =
        findViewById<View>(R.id.pf_scene_add_card).findViewWithTag(id)
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun withHost(block: (ContextThemeWrapper) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try { block(ContextThemeWrapper(controller.get(), UiR.style.Theme_Pf)) }
        finally { controller.pause().stop().destroy() }
    }
}
