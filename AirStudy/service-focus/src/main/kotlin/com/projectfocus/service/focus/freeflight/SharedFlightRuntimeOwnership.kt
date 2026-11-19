package com.projectfocus.service.focus.freeflight

import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.session.SessionRepository
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightStoppedDriver
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

/** Produced only from this adapter's actual capture under the account/Core checks. */
data class FreeFlightHostAccountSnapshot internal constructor(
    val owner: FreeFlightOwnerToken,
    val account: TokenStore.Snapshot,
) {
    override fun toString() = "FreeFlightHostAccountSnapshot(owner=$owner, account=<redacted>)"
}

/**
 * Real shared Core/account/host adapter. No separate pretend runtime lock.
 * Lock order: TokenStore publication lease -> Core commandMutex -> supplied action.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SharedFlightRuntimeOwnership @Inject constructor(private val sessions: SessionRepository) : FlightSessionOwnership {
    private data class Capture(val owner: FreeFlightOwnerToken, val identity: TokenStore.Snapshot)
    private data class Reservation(val sessionId: String, val owner: FreeFlightOwnerToken, val identity: TokenStore.Snapshot)
    @Volatile private var capture: Capture? = null
    private val captureChanges = MutableStateFlow<Capture?>(null)
    private val retained = AtomicReference<Reservation?>()
    private val invalidatedHost = MutableStateFlow<FreeFlightOwnerToken?>(null)

    /** Replay current invalidation to service/UI collectors; never unlocks or claims cleanup. */
    val invalidations: Flow<FreeFlightOwnerToken> = merge(
        invalidatedHost.filterNotNull(),
        TokenStore.identities.mapNotNull { observed ->
            if (observed != TokenStore.snapshot()) null
            else capture?.takeIf { it.identity != observed }?.owner
        },
    ).distinctUntilChanged()

    /** Latest host/account observation, not a grant. Unlike old-owner events, rapid replacements
     * can be conflated without losing the fact that a particular Service's owner is now obsolete. */
    val currentHosts: Flow<FreeFlightOwnerToken?> = combine(captureChanges, TokenStore.identities,
        sessions.freeFlightHostChanges, sessions.state) { captured, account, _, _ ->
        if (captured == null || captured.identity != account || account != TokenStore.snapshot()) null
        else TokenStore.withSnapshotLease(account) {
            if (captureChanges.value !== captured || !sessions.isFreeFlightHostCurrent(captured.owner)) null
            else captured.owner
        }
    }.distinctUntilChanged()

    /** Exact captured object for a pending/active session, possibly stale; commands still verify it. */
    fun retainedOwner(sessionId: String): FreeFlightOwnerToken? = retained.get()?.takeIf { it.sessionId == sessionId }?.owner

    /** Production callers use the App factory, which first awaits actual account bootstrap. */
    suspend fun openHost(): FreeFlightOwnerToken {
        val account = TokenStore.snapshot()
        return TokenStore.withSnapshotLease(account) {
            sessions.openFreeFlightHost(scope(account)).also { owner ->
                val previous = capture
                capture = Capture(owner, account)
                captureChanges.value = capture
                if (previous != null && previous.owner != owner) invalidatedHost.value = previous.owner
            }
        } ?: throw IllegalStateException("Account changed while opening free-flight preparation")
    }

    /** Explicit cancelled preparation/stopped host only; screen detachment does not invalidate it. */
    suspend fun invalidateHost(owner: FreeFlightOwnerToken) {
        sessions.invalidateFreeFlightHost(owner)
        val observed = captureChanges.value
        if (observed?.owner == owner) captureChanges.compareAndSet(observed, null)
        invalidatedHost.value = owner
    }

    suspend fun isHostCurrent(owner: FreeFlightOwnerToken): Boolean {
        val saved = capture?.takeIf { it.owner == owner } ?: return false
        return TokenStore.withSnapshotLease(saved.identity) { sessions.isFreeFlightHostCurrent(owner) } ?: false
    }

    /**
     * Read the existing host capture; never opens/mints another owner. The exact
     * account snapshot is read while its real publication lease is held and the
     * actual Core host is verified. Do not call isHostCurrent here: that would
     * recursively acquire the same account lease. Network work happens later.
     */
    suspend fun captureHostAccount(owner: FreeFlightOwnerToken? = null): FreeFlightHostAccountSnapshot? {
        val saved = capture ?: return null
        if (owner != null && saved.owner != owner) return null
        return TokenStore.withSnapshotLease(saved.identity) {
            if (capture !== saved || !sessions.isFreeFlightHostCurrent(saved.owner)) null
            else TokenStore.snapshot().takeIf { it == saved.identity }?.let { FreeFlightHostAccountSnapshot(saved.owner, it) }
        }
    }

    /** Host-only side effects before formal admission; never acquires the runtime reservation. */
    suspend fun <T : Any> withPreparationOwner(sessionId: String, owner: FreeFlightOwnerToken,
                                              action: suspend () -> T): T? {
        val saved = capture?.takeIf { it.owner == owner } ?: return null
        return TokenStore.withSnapshotLease(saved.identity) {
            sessions.withFreeFlightPreparationLease(sessionId, owner, action)
        }
    }

    override suspend fun acquire(sessionId: String, owner: FreeFlightOwnerToken): Boolean {
        val saved = capture?.takeIf { it.owner == owner } ?: return false
        return TokenStore.withSnapshotLease(saved.identity) {
            sessions.reserveFreeFlightRuntime(sessionId, owner).also { allowed ->
                if (allowed) retained.set(Reservation(sessionId, owner, saved.identity))
            }
        } ?: false
    }

    override suspend fun release(sessionId: String, owner: FreeFlightOwnerToken) {
        // Exact reservation cleanup remains possible after identity/host invalidation.
        sessions.releaseFreeFlightRuntime(sessionId, owner)
        val saved = retained.get()
        if (saved?.sessionId == sessionId && saved.owner == owner) retained.compareAndSet(saved, null)
    }

    /** No completion/refund is fabricated: only an obsolete, actually stopped owner's slot is retired. */
    suspend fun retireStopped(proof: FreeFlightStoppedDriver): Boolean {
        proof.requireStopped()
        val saved = retained.get()?.takeIf { it.sessionId == proof.key.sessionId && it.owner == proof.key.owner }
            ?: return false
        while (true) {
            val current = TokenStore.snapshot()
            val retired = TokenStore.withSnapshotLease(current) {
                proof.requireStopped()
                sessions.retireObsoleteFreeFlightRuntime(saved.sessionId, saved.owner, saved.identity, proof)
            } ?: continue // The real publisher changed before acquisition; take its new exact snapshot.
            if (retired) retained.compareAndSet(saved, null)
            return retired
        }
    }

    override suspend fun <T : Any> withCurrentOwner(
        sessionId: String, owner: FreeFlightOwnerToken, action: suspend () -> T,
    ): T? {
        val saved = capture?.takeIf { it.owner == owner } ?: return null
        return TokenStore.withSnapshotLease(saved.identity) {
            sessions.withFreeFlightRuntimeLease(sessionId, owner, action)
        }
    }

    /** Called on Main after dispatch, never by hopping to Main while an IO lease is held. */
    fun <T : Any> tryWithUiOwner(sessionId: String, owner: FreeFlightOwnerToken, preparation: Boolean,
                                action: () -> T): FreeFlightPaintAttempt<T> {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val saved = capture?.takeIf { it.owner == owner } ?: return FreeFlightPaintAttempt.Obsolete
        return when (val attempt = TokenStore.tryWithSnapshotLease(saved.identity) {
            sessions.tryFreeFlightPaintLease(sessionId, owner, preparation, action)
        }) {
            is TokenStore.ImmediateSnapshot.Value -> attempt.value
            TokenStore.ImmediateSnapshot.Busy -> FreeFlightPaintAttempt.Busy
            TokenStore.ImmediateSnapshot.Obsolete -> FreeFlightPaintAttempt.Obsolete
        }
    }

    // Same Own SHA-256/local scope as app FlightPlanOwnership; no token is logged/persisted here.
    private fun scope(account: TokenStore.Snapshot): String =
        TokenStore.storageScope(account)
}
