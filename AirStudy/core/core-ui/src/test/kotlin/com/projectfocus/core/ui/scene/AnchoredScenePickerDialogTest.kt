package com.projectfocus.core.ui.scene

import android.app.Activity
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import com.projectfocus.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
class AnchoredScenePickerDialogTest {
    @Test
    fun `dismiss does not choose and actual tag click delivers supplied option only once`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context = ContextThemeWrapper(controller.get(), R.style.Theme_Pf)
            val anchor = FrameLayout(context)
            controller.get().setContentView(anchor)
            val choices = listOf(ScenePickerOption("caller-a", "学习", 0, Color.GREEN), ScenePickerOption("caller-b", "工作", 0, Color.BLUE))
            val selected = mutableListOf<ScenePickerOption>()
            val empty = AnchoredScenePickerDialog(context, anchor, "座位 1A", emptyList(), { selected += it })
            empty.show()
            val emptyTags = empty.findViewById<com.google.android.flexbox.FlexboxLayout>(R.id.pf_scene_tags)
            assertEquals(1, emptyTags.childCount)
            assertFalse(emptyTags.getChildAt(0) is MaterialButton)
            assertEquals(context.getString(R.string.pf_scene_picker_empty), (emptyTags.getChildAt(0) as android.widget.TextView).text.toString())
            empty.dismiss()
            val cancelled = AnchoredScenePickerDialog(context, anchor, "座位 1A", choices, { selected += it })
            cancelled.show()
            cancelled.cancel()
            assertEquals(emptyList<ScenePickerOption>(), selected)
            val picker = AnchoredScenePickerDialog(context, anchor, "座位 1A", choices, { selected += it })
            picker.show()
            val button = picker.findViewById<android.view.View>(R.id.pf_scene_card_root).findViewWithTag<MaterialButton>("caller-b")
            button.performClick()
            button.performClick()
            assertEquals(listOf(choices[1]), selected)
            assertFalse(picker.isShowing)
            val iconOnly = choices[1].copy(id = "icon-only", label = "", iconRes = R.drawable.pf_scene_ic_add)
            val iconPicker = AnchoredScenePickerDialog(context, anchor, "座位 1A", listOf(iconOnly), { selected += it })
            iconPicker.show()
            val iconButton = iconPicker.findViewById<android.view.View>(R.id.pf_scene_card_root).findViewWithTag<MaterialButton>("icon-only")
            assertEquals("", iconButton.text.toString())
            iconButton.performClick()
            assertEquals(listOf(choices[1], iconOnly), selected)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
