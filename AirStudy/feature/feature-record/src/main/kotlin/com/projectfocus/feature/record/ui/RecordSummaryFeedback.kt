package com.projectfocus.feature.record.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.list.RecordItem
import com.projectfocus.feature.record.list.RecordPageState
import com.projectfocus.feature.record.list.RecordRemovalViewModel
import com.projectfocus.feature.record.list.RecordRemovalState
import com.projectfocus.feature.record.list.RecordRemovalNotice
import com.projectfocus.feature.record.list.FlightStatus
import com.projectfocus.service.focus.history.HistoryAccount
import kotlinx.coroutines.*

internal class RecordSummaryFeedback(private val context: Context, private val scope: CoroutineScope,
    private val page: () -> RecordPageState?, private val canShow: () -> Boolean,
    private val retry: (HistoryAccount) -> Unit, private val removal: RecordRemovalViewModel) : AutoCloseable {
    private var target: Pair<HistoryAccount, RecordItem>? = null
    private var dialog: AlertDialog? = null
    private var effect: Job? = null
    private var deleteDialog: RecordDeleteDialog? = null
    private var notice: Job? = null

    fun open(item: RecordItem) {
        val account = page()?.takeIf { it.ready && item.cloudSummary != null && it.items.contains(item) }?.account ?: return
        if (item.status == FlightStatus.CANCELLED) { close(); openDelete(account, item); return }
        close(); target = account to item
        guarded(account, item) {
            val builder = MaterialAlertDialogBuilder(context).setTitle(R.string.pf_record_summary_title)
                .setMessage(R.string.pf_record_summary_details).setNegativeButton(R.string.pf_record_recovery_close, null)
                .setPositiveButton(R.string.pf_record_recovery_retry, null)
            if (item.status in setOf(FlightStatus.COMPLETED, FlightStatus.CANCELLED))
                builder.setNeutralButton(R.string.pf_record_delete_title, null)
            val shown = builder.create()
            dialog = shown
            shown.setOnDismissListener { if (dialog === shown) dialog = null }
            shown.show()
            shown.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                isEnabled = !removal.state.value.saving
                setOnClickListener {
                    if (dialog === shown && current(account, item) && !removal.state.value.saving) openDelete(account, item)
                }
            }
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (dialog === shown && current(account, item)) {
                    dialog = null; shown.dismiss()
                    guarded(account, item) { retry(account) }
                }
            }
        }
    }

    fun keepFor(state: RecordPageState) {
        target?.let { (account, item) ->
            if (!account.isCurrent() || state.account != account) close()
            else if (!state.ready || !state.items.contains(item)) closeCurrent()
            else {
                val request = removal.state.value
                dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !request.saving
                if (request.summaryTarget == state.summaryTarget(item)) deleteDialog?.setPending(request.saving)
            }
        }
    }

    private fun openDelete(account: HistoryAccount, item: RecordItem) {
        val action = page()?.summaryTarget(item)?.takeIf { it.account == account } ?: return
        closeCurrent(); target = account to item
        guarded(account, item) {
            val prompt = RecordDeleteDialog(context, { current(account, item) }) {
                if (current(account, item) && !removal.state.value.saving) removal.deleteSummary(action)
            }
            deleteDialog = prompt
            prompt.setOnDismissListener { if (deleteDialog === prompt) deleteDialog = null }
            prompt.show()
        }
    }

    fun showNotice(state: RecordRemovalState) {
        notice?.cancel()
        val message = state.notice ?: return
        val account = state.summaryTarget?.account ?: return
        val identity = TokenStore.snapshot()
        notice = scope.launch {
            yield()
            while (isActive && canShow() && account.isCurrent() && removal.state.value == state) {
                when (TokenStore.tryWithSnapshotLease(identity) {
                    removal.acknowledge(state.attempt)
                    if (message == RecordRemovalNotice.DELETED) closeCurrent()
                    android.widget.Toast.makeText(context, when (message) {
                        RecordRemovalNotice.DELETED -> R.string.pf_record_delete_success
                        RecordRemovalNotice.CHANGED -> R.string.pf_record_delete_changed
                        RecordRemovalNotice.PROTECTED -> R.string.pf_record_delete_blocked
                        RecordRemovalNotice.FAILED -> R.string.pf_record_delete_failed
                        RecordRemovalNotice.LOGIN_REQUIRED -> R.string.pf_record_cloud_login_required
                        RecordRemovalNotice.CLOUD -> R.string.pf_record_cloud_delete_failed
                    }, android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    is TokenStore.ImmediateSnapshot.Value -> return@launch
                    TokenStore.ImmediateSnapshot.Obsolete -> return@launch
                    TokenStore.ImmediateSnapshot.Busy -> delay(16)
                }
            }
        }
    }

    private fun current(account: HistoryAccount, item: RecordItem) = target == (account to item) &&
        account.isCurrent() && canShow() && page()?.let { it.ready && it.account == account && it.items.contains(item) } == true

    private fun guarded(account: HistoryAccount, item: RecordItem, action: () -> Unit) {
        effect?.cancel()
        val identity = TokenStore.snapshot()
        effect = scope.launch {
            yield()
            while (isActive && current(account, item)) {
                when (TokenStore.tryWithSnapshotLease(identity) { if (current(account, item)) action() }) {
                    is TokenStore.ImmediateSnapshot.Value -> return@launch
                    TokenStore.ImmediateSnapshot.Obsolete -> return@launch
                    TokenStore.ImmediateSnapshot.Busy -> delay(16)
                }
            }
        }
    }

    private fun closeCurrent() {
        effect?.cancel(); effect = null; target = null
        val old = dialog; dialog = null; old?.dismiss()
        val prompt = deleteDialog; deleteDialog = null; prompt?.dismiss()
    }

    override fun close() {
        notice?.cancel(); notice = null
        closeCurrent()
    }
}
