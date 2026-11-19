package com.projectfocus.feature.record.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.record.R
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.service.focus.history.RecordRecoveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal class RecordRecoveryFeedback(
    private val context: Context,
    root: View,
    private val scope: CoroutineScope,
    private val canShow: () -> Boolean,
    private val retry: (HistoryAccount) -> Unit,
    private val login: (HistoryAccount) -> Unit,
) : AutoCloseable {
    private val notice = root.findViewById<View>(R.id.pf_record_recovery_notice)
    private val summary = root.findViewById<TextView>(R.id.pf_record_recovery_summary)
    private val details = root.findViewById<TextView>(R.id.pf_record_recovery_details)
    private var current = RecordRecoveryStatus()
    private var dialog: AlertDialog? = null
    private var effect: Job? = null

    init { details.setOnClickListener { showDetails(current) } }

    fun render(status: RecordRecoveryStatus) {
        if (current != status) close()
        current = status
        notice.visibility = if (status.visible) View.VISIBLE else View.GONE
        summary.text = when {
            status.authenticationRequired -> context.getString(R.string.pf_record_recovery_login_short)
            status.pendingCount > 0 -> context.getString(R.string.pf_record_recovery_pending, status.pendingCount)
            else -> context.getString(R.string.pf_record_recovery_unavailable)
        }
        details.setText(if (status.syncing) R.string.pf_record_recovery_retrying else R.string.pf_record_recovery_view)
    }

    private fun showDetails(status: RecordRecoveryStatus) {
        guarded(status) {
            dialog?.dismiss()
            val message = buildList {
                if (status.authenticationRequired) add(context.getString(R.string.pf_record_recovery_login))
                if (status.missingArchives > 0) add(context.getString(R.string.pf_record_recovery_missing, status.missingArchives))
                if (status.unsupportedScenes > 0) add(context.getString(R.string.pf_record_recovery_unsupported, status.unsupportedScenes))
                if (status.protectedRecords > 0) add(context.getString(R.string.pf_record_recovery_protected, status.protectedRecords))
                if (status.pendingChanges > 0) add(context.getString(R.string.pf_record_recovery_changes, status.pendingChanges))
                if (status.pendingUploads > 0) add(context.getString(R.string.pf_record_recovery_uploads, status.pendingUploads))
                if (status.unavailable) add(context.getString(R.string.pf_record_recovery_network))
                if (status.missingArchives > 0 || status.unsupportedScenes > 0) add(context.getString(R.string.pf_record_recovery_safe))
            }.joinToString("\n\n")
            val shown = MaterialAlertDialogBuilder(context).setTitle(R.string.pf_record_recovery_title)
                .setMessage(message).setNegativeButton(R.string.pf_record_recovery_close, null)
                .setPositiveButton(if (status.authenticationRequired) R.string.pf_record_recovery_login_action
                    else R.string.pf_record_recovery_retry, null).create()
            dialog = shown
            shown.setOnDismissListener { if (dialog === shown) dialog = null }
            shown.show()
            shown.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = status.authenticationRequired || !status.syncing
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (dialog === shown && current == status && canShow() && status.visible) {
                    dialog = null; shown.dismiss()
                    guarded(status) {
                        val account = status.account ?: return@guarded
                        if (status.authenticationRequired) login(account) else if (!status.syncing) retry(account)
                    }
                }
            }
        }
    }

    private fun guarded(status: RecordRecoveryStatus, action: () -> Unit) {
        effect?.cancel()
        val identity = TokenStore.snapshot()
        effect = scope.launch {
            yield()
            while (isActive && current == status && status.visible && canShow()) {
                when (TokenStore.tryWithSnapshotLease(identity) {
                    if (current == status && status.visible && canShow()) action()
                }) {
                    is TokenStore.ImmediateSnapshot.Value -> return@launch
                    TokenStore.ImmediateSnapshot.Obsolete -> return@launch
                    TokenStore.ImmediateSnapshot.Busy -> delay(16)
                }
            }
        }
    }

    override fun close() {
        notice.visibility = View.GONE
        effect?.cancel(); effect = null
        val previous = dialog; dialog = null
        previous?.dismiss()
    }
}
