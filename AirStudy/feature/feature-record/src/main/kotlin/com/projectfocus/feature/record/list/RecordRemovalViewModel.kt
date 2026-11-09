package com.projectfocus.feature.record.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.service.focus.history.RecordDeletionOperations
import com.projectfocus.service.focus.history.RecordDeletionResult
import com.projectfocus.service.focus.history.RecordDeletionTarget
import com.projectfocus.service.focus.history.RecordCloudFailure
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
)

@HiltViewModel
class RecordRemovalViewModel @Inject constructor(
    private val operations: RecordDeletionOperations,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RecordRemovalState())
    val state: StateFlow<RecordRemovalState> = mutableState.asStateFlow()

    fun delete(target: RecordDeletionTarget) {
        if (state.value.saving || !target.account.isCurrent()) return
        val request = RecordRemovalState(Math.addExact(state.value.attempt, 1L), target, saving = true)
        mutableState.value = request
        viewModelScope.launch {
            try {
                val notice = when (operations.delete(target)) {
                    RecordDeletionResult.DELETED, RecordDeletionResult.MISSING -> RecordRemovalNotice.DELETED
                    RecordDeletionResult.CHANGED -> RecordRemovalNotice.CHANGED
                    RecordDeletionResult.PROTECTED -> RecordRemovalNotice.PROTECTED
                    RecordDeletionResult.OBSOLETE -> null
                }
                val current = target.account.isCurrent()
                mutableState.value = request.copy(target = target.takeIf { current }, saving = false,
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
                mutableState.value = request.copy(target = target.takeIf { target.account.isCurrent() }, saving = false,
                    notice = notice.takeIf { target.account.isCurrent() })
            } catch (_: Exception) {
                mutableState.value = request.copy(target = target.takeIf { target.account.isCurrent() }, saving = false,
                    notice = RecordRemovalNotice.FAILED.takeIf { target.account.isCurrent() })
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
        if (!result.saving && result.target?.account?.isCurrent() == false)
            mutableState.value = result.copy(target = null, notice = null)
    }
}
