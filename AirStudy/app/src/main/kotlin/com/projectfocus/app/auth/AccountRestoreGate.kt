package com.projectfocus.app.auth

import com.projectfocus.core.network.di.TokenStore
import kotlinx.coroutines.CompletableDeferred

/** In-memory evidence from the actual account restore; never serialized or logged. */
class AccountRestorePublication internal constructor(
    internal val before: TokenStore.Snapshot,
    internal val after: TokenStore.Snapshot,
)

/** One Application bootstrap, captured before its asynchronous account restoration. */
internal class AccountRestoreGate(
    private val bootstrapIdentity: TokenStore.Snapshot = TokenStore.snapshot(),
) {
    private val finished = CompletableDeferred<AccountRestorePublication?>()

    fun restore(accounts: EmailAccountSessionStore) {
        try { complete(accounts.restoreIfAnonymous()) }
        finally { complete(null) }
    }

    // A generation changed before restore (including null→null logout) is not bootstrap.
    // CompletableDeferred keeps the first completion; later callbacks cannot relabel it.
    fun complete(publication: AccountRestorePublication?) {
        finished.complete(publication?.takeIf {
            bootstrapIdentity.token.isNullOrEmpty() && it.before == bootstrapIdentity
        })
    }

    suspend fun await(): AccountRestorePublication? = finished.await()
}
