package com.projectfocus.app.scene

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.content.SceneAccessResolver
import com.projectfocus.core.network.content.SceneContentChecker
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.ui.scene.AnchoredScenePickerDialog
import com.projectfocus.core.ui.scene.ScenePickerOption
import com.projectfocus.feature.record.list.RecordItem
import com.projectfocus.feature.scene.source.SharedPreferencesSceneCatalogStore
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import com.projectfocus.feature.scene.R as SceneR

/** One record's picker and its child editor share the preparation catalog. */
internal class RecordScenePickerSession private constructor(
    private val activity: AppCompatActivity,
    private val anchor: View,
    private val item: RecordItem,
    private val canShow: () -> Boolean,
    private val onSelected: (PlannedScene) -> Unit,
    private val access: SceneAccessResolver,
    private val content: SceneContentChecker,
) : AutoCloseable {
    private val identity = TokenStore.snapshot()
    private val store = SharedPreferencesSceneCatalogStore.from(activity)
    private var dialog: AnchoredScenePickerDialog? = null
    private var editor: AppSceneEditorSession? = null
    private var closed = false
    private var lastOptions = emptyList<ScenePickerOption>()
    private fun current() = !closed && !activity.isFinishing && !activity.isDestroyed &&
        identity == TokenStore.snapshot() && canShow()

    private fun options(): List<ScenePickerOption> = store.readEnabled(AppSceneCatalog.SCOPE).options.map { option ->
        val value = SourceSceneCatalog.present(activity, option)
        ScenePickerOption(value.optionId, value.title, value.iconRes, value.color)
    }

    private fun show(): Boolean {
        if (!current()) return false
        try {
            val entries = store.readEnabled(AppSceneCatalog.SCOPE).options
            val selected = entries.firstOrNull { it.optionId == item.sceneOptionId }
                ?: entries.firstOrNull { it.record.iconKey == item.sceneIconKey &&
                    SourceSceneCatalog.label(activity, it.record) == item.sceneTitle }
            val ownedEditor = AppSceneEditorSession(activity, access, content, ::current)
            editor = ownedEditor
            lastOptions = options()
            val created = AnchoredScenePickerDialog(activity, anchor, null, lastOptions,
                onSelected = { picked ->
                    try {
                        if (current()) {
                            val option = store.readEnabled(AppSceneCatalog.SCOPE).find(picked.id)
                            if (option != null && current()) {
                                val record = option.record
                                onSelected(PlannedScene(option.optionId, record.name, record.iconKey, record.color,
                                    record.isCustom, record.cloudId, record.clientUuid, SourceSceneCatalog.label(activity, record)))
                            }
                        }
                    } catch (_: Exception) {
                        if (current()) Toast.makeText(activity, SceneR.string.pf_scene_load_failed, Toast.LENGTH_SHORT).show()
                    }
                }, selectedId = selected?.optionId,
                onAdd = ownedEditor::add,
                onRemove = { option, changed -> if (current()) ownedEditor.remove(option.id, changed) },
                reloadOptions = {
                    if (!current()) emptyList() else try {
                        options().also { lastOptions = it }
                    } catch (_: Exception) {
                        if (current()) Toast.makeText(activity, SceneR.string.pf_scene_load_failed, Toast.LENGTH_SHORT).show()
                        lastOptions
                    }
                })
            dialog = created
            created.setOnDismissListener { if (dialog === created) close() }
            created.show()
            return true
        } catch (_: Exception) {
            close()
            if (identity == TokenStore.snapshot() && canShow())
                Toast.makeText(activity, SceneR.string.pf_scene_load_failed, Toast.LENGTH_SHORT).show()
            return false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val previous = dialog
        dialog = null
        previous?.dismiss()
        editor?.close()
        editor = null
    }

    companion object {
        fun open(activity: AppCompatActivity, anchor: View, item: RecordItem, canShow: () -> Boolean,
            onSelected: (PlannedScene) -> Unit, access: SceneAccessResolver, content: SceneContentChecker): AutoCloseable? =
            RecordScenePickerSession(activity, anchor, item, canShow, onSelected, access, content).let {
                if (it.show()) it else null
            }
    }
}
