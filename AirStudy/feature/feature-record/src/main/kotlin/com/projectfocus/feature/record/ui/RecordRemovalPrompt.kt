package com.projectfocus.feature.record.ui

import android.content.Context
import android.widget.Toast
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.list.FlightStatus
import com.projectfocus.feature.record.list.RecordItem
import com.projectfocus.feature.record.list.RecordPageState
import com.projectfocus.feature.record.list.RecordRemovalNotice
import com.projectfocus.feature.record.list.RecordRemovalState
import com.projectfocus.feature.record.list.RecordRemovalViewModel
import com.projectfocus.service.focus.history.RecordDeletionTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal class RecordRemovalPrompt(
    private val context: Context,
    private val scope: CoroutineScope,
    private val model: RecordRemovalViewModel,
    private val page: () -> RecordPageState?,
    private val canShow: () -> Boolean,
) {
    private var dialog: RecordDeleteDialog? = null
    private var selected: Pair<RecordItem, RecordDeletionTarget>? = null
    private var revision = 0L
    private var openJob: Job? = null
    private var noticeJob: Job? = null

    fun open(item: RecordItem) {
        if (model.state.value.saving || item.status != FlightStatus.CANCELLED) return
        val target = page()?.deletionTarget(item) ?: return
        close()
        val request = revision
        val identity = TokenStore.snapshot()
        selected = item to target
        fun current() = revision == request && canShow() && target.account.isCurrent() &&
            page()?.deletionTarget(item) == target
        openJob = scope.launch {
            yield()
            while (isActive && current()) {
                when (TokenStore.tryWithSnapshotLease(identity) {
                    if (current()) {
                        val prompt = RecordDeleteDialog(context, ::current) {
                            if (current() && !model.state.value.saving) model.delete(target)
                        }
                        dialog = prompt
                        prompt.setOnDismissListener {
                            if (dialog === prompt) { dialog = null; selected = null; ++revision }
                        }
                        prompt.show()
                    }
                }) {
                    is TokenStore.ImmediateSnapshot.Value -> return@launch
                    TokenStore.ImmediateSnapshot.Obsolete -> { close(); return@launch }
                    TokenStore.ImmediateSnapshot.Busy -> delay(16)
                }
            }
        }
    }

    fun keepFor(state: RecordPageState) {
        selected?.let { (item, target) ->
            if (state.deletionTarget(item) != target) close()
            else {
                val saving = model.state.value
                if (saving.target == target) dialog?.setPending(saving.saving)
            }
        }
    }

    fun showProtected(item: RecordItem) {
        close()
        val target = page()?.deletionTarget(item) ?: return
        val request = revision
        val identity = TokenStore.snapshot()
        noticeJob = scope.launch {
            yield()
            while (isActive && revision == request && canShow() && target.account.isCurrent() &&
                page()?.deletionTarget(item) == target) {
                when (TokenStore.tryWithSnapshotLease(identity) {
                    Toast.makeText(context, R.string.pf_record_delete_blocked, Toast.LENGTH_SHORT).show()
                }) {
                    is TokenStore.ImmediateSnapshot.Value -> return@launch
                    TokenStore.ImmediateSnapshot.Obsolete -> return@launch
                    TokenStore.ImmediateSnapshot.Busy -> delay(16)
                }
            }
        }
    }

    fun showNotice(state: RecordRemovalState) {
        noticeJob?.cancel()
        val notice = state.notice ?: return
        val target = state.target ?: return
        val identity = TokenStore.snapshot()
        noticeJob = scope.launch {
            yield()
            while (isActive && canShow() && target.account.isCurrent() && model.state.value == state) {
                when (TokenStore.tryWithSnapshotLease(identity) {
                    model.acknowledge(state.attempt)
                    if (notice == RecordRemovalNotice.DELETED && selected?.second == target) closeDialog()
                    val message = when (notice) {
                        RecordRemovalNotice.DELETED -> R.string.pf_record_delete_success
                        RecordRemovalNotice.CHANGED -> R.string.pf_record_delete_changed
                        RecordRemovalNotice.PROTECTED -> R.string.pf_record_delete_blocked
                        RecordRemovalNotice.FAILED -> R.string.pf_record_delete_failed
                        RecordRemovalNotice.LOGIN_REQUIRED -> R.string.pf_record_cloud_login_required
                        RecordRemovalNotice.CLOUD -> R.string.pf_record_cloud_delete_failed
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }) {
                    is TokenStore.ImmediateSnapshot.Value -> return@launch
                    TokenStore.ImmediateSnapshot.Obsolete -> return@launch
                    TokenStore.ImmediateSnapshot.Busy -> delay(16)
                }
            }
        }
    }

    private fun closeDialog() {
        ++revision
        openJob?.cancel(); openJob = null
        val previous = dialog
        dialog = null; selected = null
        previous?.dismiss()
    }

    fun close() {
        noticeJob?.cancel(); noticeJob = null
        closeDialog()
    }
}
