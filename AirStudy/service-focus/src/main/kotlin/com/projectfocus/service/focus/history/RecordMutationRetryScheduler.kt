package com.projectfocus.service.focus.history

import android.content.Context
import androidx.work.*
import com.projectfocus.core.network.di.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

interface RecordCloudWorkerOwner {
    val recordCloudMutations: RecordCloudMutationCoordinator
    val recordCloudSync: RecordCloudSyncCoordinator
    suspend fun awaitRecordCloudReady()
    fun requestRecordCloudSync() = Unit
    fun retryRecordCloudSync(account: HistoryAccount) { if (account.isCurrent()) requestRecordCloudSync() }
}

@Singleton
class RecordMutationRetryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val journal: RecordMutationJournal,
) {
    suspend fun enqueueIfPending(scope: String) {
        if (journal.pending(scope).isEmpty()) return
        enqueue(scope)
    }

    fun enqueue(scope: String) {
        val request = OneTimeWorkRequestBuilder<RecordMutationRetryWorker>()
            .setInputData(workDataOf("scope" to scope))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // Appending prevents an operation queued during a finishing worker from losing its wake-up.
        WorkManager.getInstance(context).enqueueUniqueWork("record-cloud-" + RecordCloudSnapshots.digest(scope),
            ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class RecordMutationRetryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val owner = applicationContext as? RecordCloudWorkerOwner ?: return Result.failure()
        return try {
            owner.awaitRecordCloudReady()
            val identity = TokenStore.snapshot()
            if (identity.token.isNullOrBlank() || inputData.getString("scope") != TokenStore.storageScope(identity)) return Result.success()
            val result = owner.recordCloudSync.synchronize(identity)
            when {
                result.obsolete -> Result.success()
                result.authenticationRequired -> Result.failure()
                result.retryable -> Result.retry()
                else -> Result.success()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}
