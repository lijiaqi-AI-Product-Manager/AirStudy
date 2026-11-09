package com.projectfocus.service.focus.history

import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.network.di.TokenStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/** Login, process startup and newly saved terminal records wake the same constrained worker. */
@Singleton
class RecordCloudSyncTriggers @Inject constructor(
    private val db: AppDatabase,
    private val retries: RecordMutationRetryScheduler,
) {
    suspend fun observe(identity: TokenStore.Snapshot) {
        if (identity.token.isNullOrBlank() || TokenStore.snapshot() != identity) return
        val scope = TokenStore.storageScope(identity)
        combine(db.historyReadDao().countdowns(scope), db.freeFlightArchiveDao().observeTerminal(scope)) { down, up ->
            down.filter { it.ownerScope == scope && it.status in setOf("COMPLETED", "CANCELLED") } to
                up.filter { it.status in setOf("COMPLETED", "CANCELLED") && it.activeSlot == null }
        }.distinctUntilChanged().retryWhen { cause, attempt ->
            if (cause is CancellationException || TokenStore.snapshot() != identity) false
            else { delay(minOf(30_000L, 1_000L * (attempt + 1))); true }
        }.collect {
            if (TokenStore.snapshot() == identity) retries.enqueue(scope)
        }
    }
}
