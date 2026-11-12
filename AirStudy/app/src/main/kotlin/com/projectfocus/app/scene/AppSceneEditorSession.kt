package com.projectfocus.app.scene

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.projectfocus.app.R
import com.projectfocus.core.network.content.SceneAccessResolver
import com.projectfocus.core.network.content.SceneContentChecker
import com.projectfocus.feature.booking.seat.SeatSceneEditorSession
import com.projectfocus.feature.scene.source.SharedPreferencesSceneCatalogStore
import com.projectfocus.feature.scene.source.SourceSceneRecord
import com.projectfocus.feature.scene.ui.SourceSceneAddDialog
import com.projectfocus.feature.scene.ui.SourceSceneNameCheckResult
import com.projectfocus.feature.scene.ui.SourceSceneNameChecker
import com.projectfocus.feature.scene.ui.SourceSceneSaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import com.projectfocus.feature.scene.R as SceneR


internal class AppSceneEditorSession(
    private val activity: AppCompatActivity,
    private val accessResolver: SceneAccessResolver,
    private val contentChecker: SceneContentChecker,
    private val isCurrent: () -> Boolean = { true },
) : SeatSceneEditorSession {
    private val ownerJob = SupervisorJob(activity.lifecycleScope.coroutineContext[Job])
    private val scope = CoroutineScope(activity.lifecycleScope.coroutineContext + ownerJob)
    private val store = SharedPreferencesSceneCatalogStore.from(activity)
    private var closed = false
    private var opening = false
    private var child: SourceSceneAddDialog? = null

    override fun add(onChanged: () -> Unit) {
        if (!canDisplay() || opening || child?.isShowing == true) return
        opening = true
        scope.launch {
            try {
                val count = store.readEnabled(AppSceneCatalog.SCOPE).records.size
                // x4.k only resolves the source boolean after the free bound is reached.
                // Here it is adapted to this project's actual authenticated profile.
                val maximum = if (count < SceneAccessResolver.FREE_LIMIT) SceneAccessResolver.FREE_LIMIT
                    else accessResolver.maximumEnabledScenes()
                if (!canDisplay()) return@launch
                if (count >= maximum) {
                    message(R.string.scene_editor_limit_reached)
                    return@launch
                }
                val dialog = SourceSceneAddDialog(
                    context = activity,
                    availableChoices = { store.availableToEnable(AppSceneCatalog.SCOPE) },
                    nameChecker = SourceSceneNameChecker(::checkName),
                    onSave = { record, complete -> save(record, maximum, complete) },
                    onChanged = { if (canDisplay()) onChanged() },
                )
                child = dialog
                dialog.setOnDismissListener { if (child === dialog) child = null }
                dialog.show()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Timber.w(error, "无法打开场景编辑器")
                if (canDisplay()) message(SceneR.string.pf_scene_load_failed)
            } finally {
                opening = false
            }
        }
    }

    private fun checkName(
        name: String,
        complete: (SourceSceneNameCheckResult) -> Unit,
    ): AutoCloseable? {
        if (!canDisplay()) return null
        val task = scope.launch {
            val result = try {
                if (contentChecker.isAllowed(name)) SourceSceneNameCheckResult.ALLOWED
                else SourceSceneNameCheckResult.REJECTED
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Timber.w(error, "场景名称检测暂不可用")
                SourceSceneNameCheckResult.UNAVAILABLE
            }
            if (canDisplay()) complete(result)
        }
        return AutoCloseable { task.cancel() }
    }

    private fun save(
        record: SourceSceneRecord,
        maximum: Int,
        complete: (SourceSceneSaveResult) -> Unit,
    ): AutoCloseable? {
        if (!canDisplay()) return null
        val task = scope.launch {
            try {
                val current = store.readEnabled(AppSceneCatalog.SCOPE).records
                // A same-name custom update is a source upsert, not another slot.
                // Recheck the current list so another writer cannot exceed the limit
                // while the child dialog is open. Do not reject an existing-name edit.
                val replaces = current.any { existing -> matchesEnabled(existing, record) }
                if (!replaces && current.size >= maximum) {
                    complete(SourceSceneSaveResult.FAILED)
                    return@launch
                }
                if (!canDisplay()) return@launch
                store.addOrUpdateEnabled(AppSceneCatalog.SCOPE, record)
                complete(SourceSceneSaveResult.SAVED)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                // Enabled and custom history are source-ordered, independent apply()
                // calls. This reports a failure; it does not promise an atomic rollback.
                Timber.w(error, "保存场景失败")
                if (canDisplay()) complete(SourceSceneSaveResult.FAILED)
            }
        }
        return AutoCloseable { task.cancel() }
    }

    override fun remove(optionId: String, onChanged: () -> Unit) {
        if (!canDisplay()) return
        try {
            // The picker has already shown the source confirmation. Fresh IDs keep
            // a stale callback from deleting a different row; custom history remains.
            store.removeEnabled(AppSceneCatalog.SCOPE, optionId)
        } catch (error: Exception) {
            Timber.w(error, "移除场景失败")
            if (canDisplay()) message(SceneR.string.pf_scene_save_failed)
        } finally {
            // Complete the mutation even on a failed write; the fresh read retains
            // the old options instead of leaving the picker permanently disabled.
            if (canDisplay()) onChanged()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        ownerJob.cancel()
        val dialog = child
        child = null
        dialog?.dismiss()
    }

    private fun canDisplay(): Boolean =
        !closed && ownerJob.isActive && !activity.isFinishing && !activity.isDestroyed && isCurrent()

    private fun message(resource: Int) = Toast.makeText(activity, resource, Toast.LENGTH_LONG).show()

    private fun matchesEnabled(existing: SourceSceneRecord, incoming: SourceSceneRecord): Boolean =
        if (existing.isCustom && incoming.isCustom) existing.name == incoming.name
        else !existing.isCustom && !incoming.isCustom &&
            (existing.iconKey == incoming.iconKey ||
                (existing.name.isNotEmpty() && existing.name == incoming.name))
}
