package com.projectfocus.feature.record.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.list.RecordItem
import com.projectfocus.feature.record.list.RecordPageState
import com.projectfocus.service.focus.history.HistoryAccount
import kotlinx.coroutines.*

internal class RecordSummaryFeedback(private val context: Context, private val scope: CoroutineScope,
    private val page: () -> RecordPageState?, private val canShow: () -> Boolean,
    private val retry: (HistoryAccount) -> Unit) : AutoCloseable {
    private var target: Pair<HistoryAccount, RecordItem>? = null
    private var dialog: AlertDialog? = null
    private var effect: Job? = null

    fun open(item: RecordItem) {
        val account = page()?.takeIf { it.ready && item.cloudSummary != null && it.items.contains(item) }?.account ?: return
        close(); target = account to item
        guarded(account, item) {
            val shown = MaterialAlertDialogBuilder(context).setTitle(R.string.pf_record_summary_title)
                .setMessage(R.string.pf_record_summary_details).setNegativeButton(R.string.pf_record_recovery_close, null)
                .setPositiveButton(R.string.pf_record_recovery_retry, null).create()
            dialog = shown
            shown.setOnDismissListener { if (dialog === shown) dialog = null }
            shown.show()
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (dialog === shown && current(account, item)) {
                    dialog = null; shown.dismiss()
                    guarded(account, item) { retry(account) }
                }
            }
        }
    }

    fun keepFor(state: RecordPageState) {
        target?.let { (account, item) -> if (!state.ready || state.account != account || !state.items.contains(item)) close() }
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

    override fun close() {
        effect?.cancel(); effect = null; target = null
        val old = dialog; dialog = null; old?.dismiss()
    }
}
