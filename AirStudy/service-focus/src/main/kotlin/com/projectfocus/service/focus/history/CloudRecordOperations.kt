package com.projectfocus.service.focus.history

import com.projectfocus.core.model.flight.PlannedScene
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Both history entry points wait for the same confirmed account-bound operation. */
@Singleton
class CloudRecordOperations @Inject constructor(
    private val coordinator: RecordCloudMutationCoordinator,
    private val local: RecordDeletionStore,
    private val retries: RecordMutationRetryScheduler,
) : RecordDeletionOperations, RecordSceneOperations, RecordSummaryOperations {
    override suspend fun captureCountdown(id: String): RecordDeletionTarget? = local.captureCountdown(id)

    override suspend fun delete(target: RecordDeletionTarget): RecordDeletionResult = try {
        coordinator.delete(target)
    } finally { schedule(target.account.scope) }

    override suspend fun save(target: RecordSceneEditTarget, scene: PlannedScene): RecordSceneSaveResult = try {
        coordinator.saveScene(target, scene)
    } finally { schedule(target.account.scope) }

    override suspend fun deleteSummary(target: RecordSummaryActionTarget): RecordSummaryActionResult = try {
        coordinator.deleteSummary(target)
    } finally { schedule(target.account.scope) }

    override suspend fun saveSummaryScene(target: RecordSummaryActionTarget, scene: PlannedScene): RecordSummaryActionResult = try {
        coordinator.saveSummaryScene(target, scene)
    } finally { schedule(target.account.scope) }

    private suspend fun schedule(scope: String) = withContext(NonCancellable + Dispatchers.IO) {
        try { retries.enqueueIfPending(scope) } catch (_: Exception) { /* Durable operations remain available on next startup. */ }
    }
}
