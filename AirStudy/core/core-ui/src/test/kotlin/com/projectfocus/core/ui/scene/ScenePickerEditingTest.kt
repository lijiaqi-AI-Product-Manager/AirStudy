package com.projectfocus.core.ui.scene

import android.app.Activity
import android.content.DialogInterface
import android.graphics.Color
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.projectfocus.core.ui.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ScenePickerEditingTest {
    @Test
    fun `delete cancel preserves item and confirmation removes enabled item without selecting`() = withHost { context ->
        val option = option("one")
        var options = listOf(option)
        val removed = mutableListOf<String>()
        val selected = mutableListOf<String>()
        val picker = AnchoredScenePickerDialog(context, null, null, options, { selected += it.id },
            onRemove = { value, changed -> removed += value.id; options = emptyList(); changed() },
            reloadOptions = { options })
        picker.show()
        picker.findViewById<View>(R.id.pf_scene_edit).performClick()
        picker.option("one").performClick()
        var confirmation = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(confirmation.isShowing)
        confirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        assertEquals(emptyList<String>(), removed)
        assertTrue(picker.isShowing)
        picker.option("one").performClick()
        confirmation = ShadowDialog.getLatestDialog() as AlertDialog
        confirmation.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("one"), removed)
        assertEquals(emptyList<String>(), selected)
        assertTrue(picker.isShowing)
        assertNull(picker.findViewById<View>(R.id.pf_scene_card_root).findViewWithTag<View>("one"))
        picker.dismiss()
    }

    @Test
    fun `add completion reloads real tags without selecting and action has no option string tag`() = withHost { context ->
        var options = emptyList<ScenePickerOption>()
        var selected = 0
        val picker = AnchoredScenePickerDialog(context, null, null, options, { selected++ },
            onAdd = { changed -> options = listOf(option("new")); changed() }, reloadOptions = { options })
        picker.show()
        val add = picker.findViewById<MaterialButton>(R.id.pf_scene_add)
        assertFalse(add.tag is String)
        add.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("new", picker.option("new").tag)
        assertEquals(0, selected)
        assertTrue(picker.isShowing)
        picker.option("new").performClick()
        assertEquals(1, selected)
        assertFalse(picker.isShowing)
    }

    @Test
    fun `parent dismissal closes confirmation and preserves caller dismissal listener`() = withHost { context ->
        var dismissals = 0
        var removals = 0
        val picker = AnchoredScenePickerDialog(context, null, null, listOf(option("one")), {},
            onRemove = { _, _ -> removals++ })
        picker.setOnDismissListener { dismissals++ }
        picker.show()
        picker.findViewById<View>(R.id.pf_scene_edit).performClick()
        picker.option("one").performClick()
        val confirmation = ShadowDialog.getLatestDialog()
        picker.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(confirmation.isShowing)
        assertEquals(1, dismissals)
        assertEquals(0, removals)
    }

    private fun option(id: String) = ScenePickerOption(id, id, R.drawable.pf_scene_ic_add, Color.GREEN)
    private fun AnchoredScenePickerDialog.option(id: String): MaterialButton =
        findViewById<View>(R.id.pf_scene_card_root).findViewWithTag(id)

    private fun withHost(block: (ContextThemeWrapper) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try { block(ContextThemeWrapper(controller.get(), R.style.Theme_Pf)) }
        finally { controller.pause().stop().destroy() }
    }
}
