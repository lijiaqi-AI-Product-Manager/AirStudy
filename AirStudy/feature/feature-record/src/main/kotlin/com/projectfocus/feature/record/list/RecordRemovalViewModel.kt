package com.projectfocus.feature.record.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.service.focus.history.RecordDeletionOperations
import com.projectfocus.service.focus.history.RecordDeletionResult
import com.projectfocus.service.focus.history.RecordDeletionTarget
import com.projectfocus.service.focus.history.RecordCloudFailure
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.service.focus.history.RecordSummaryActionTarget
import com.projectfocus.service.focus.history.RecordSummaryActionResult
import com.projectfocus.service.focus.history.RecordSummaryOperations
import com.projectfocus.core.network.record.RecordMutationOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RecordRemovalNotice { DELETED, CHANGED, PROTECTED, FAILED, LOGIN_REQUIRED, CLOUD }

data class RecordRemovalState(
    val attempt: Long = 0L,
    val target: RecordDeletionTarget? = null,
    val saving: Boolean = false,
    val notice: RecordRemovalNotice? = null,
    val summaryTarget: RecordSummaryActionTarget? = null,
)

@HiltViewModel
class RecordRemovalViewModel @Inject constructor(
    private val operations: RecordDeletionOperations,
    private val summaryOperations: RecordSummaryOperations? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RecordRemovalState())
    val state: StateFlow<RecordRemovalState> = mutableState.asStateFlow()

    fun delete(target: RecordDeletionTarget) {
        if (state.value.saving || !target.account.isCurrent()) return
        val request = RecordRemovalState(Math.addExact(state.value.attempt, 1L), target, saving = true)
        submit(target.account, request) { operations.delete(target) }
    }

    fun deleteSummary(target: RecordSummaryActionTarget) {
        if (state.value.saving || !target.account.isCurrent()) return
        val request = RecordRemovalState(Math.addExact(state.value.attempt, 1L), saving = true, summaryTarget = target)
        submit(target.account, request) {
            when (requireNotNull(summaryOperations).deleteSummary(target)) {
                RecordSummaryActionResult.APPLIED -> RecordDeletionResult.DELETED
                RecordSummaryActionResult.MISSING -> RecordDeletionResult.MISSING
                RecordSummaryActionResult.CHANGED -> RecordDeletionResult.CHANGED
                RecordSummaryActionResult.PROTECTED -> RecordDeletionResult.PROTECTED
                RecordSummaryActionResult.OBSOLETE -> RecordDeletionResult.OBSOLETE
            }
        }
    }

    private fun submit(account: HistoryAccount, request: RecordRemovalState, action: suspend () -> RecordDeletionResult) {
        mutableState.value = request
        viewModelScope.launch {
            try {
                val notice = when (action()) {
                    RecordDeletionResult.DELETED, RecordDeletionResult.MISSING -> RecordRemovalNotice.DELETED
                    RecordDeletionResult.CHANGED -> RecordRemovalNotice.CHANGED
                    RecordDeletionResult.PROTECTED -> RecordRemovalNotice.PROTECTED
                    RecordDeletionResult.OBSOLETE -> null
                }
                val current = account.isCurrent()
                mutableState.value = request.copy(target = request.target.takeIf { current }, summaryTarget = request.summaryTarget.takeIf { current }, saving = false,
                    notice = notice.takeIf { current })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RecordCloudFailure) {
                val reason = failure.reason
                val notice = when {
                    reason is RecordMutationOutcome.Rejected && reason.status == 401 -> RecordRemovalNotice.LOGIN_REQUIRED
                    reason is RecordMutationOutcome.Rejected && reason.status in setOf(409, 410) -> RecordRemovalNotice.CHANGED
                    else -> RecordRemovalNotice.CLOUD
                }
                mutableState.value = request.copy(target = request.target.takeIf { account.isCurrent() }, summaryTarget = request.summaryTarget.takeIf { account.isCurrent() }, saving = false,
                    notice = notice.takeIf { account.isCurrent() })
            } catch (_: Exception) {
                mutableState.value = request.copy(target = request.target.takeIf { account.isCurrent() }, summaryTarget = request.summaryTarget.takeIf { account.isCurrent() }, saving = false,
                    notice = RecordRemovalNotice.FAILED.takeIf { account.isCurrent() })
            } finally {
                if (state.value.attempt == request.attempt && state.value.saving)
                    mutableState.value = request.copy(saving = false)
            }
        }
    }

    fun acknowledge(attempt: Long) {
        if (state.value.attempt == attempt && !state.value.saving)
            mutableState.value = state.value.copy(notice = null)
    }

    fun discardObsoleteResult() {
        val result = state.value
        if (!result.saving && (result.target?.account ?: result.summaryTarget?.account)?.isCurrent() == false)
            mutableState.value = result.copy(target = null, summaryTarget = null, notice = null)
    }
}
