package com.projectfocus.core.network.di

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TokenStoreFlightLeaseTest {
    @After fun cleanup() { TokenStore.clear() }

    @Test fun realPublicationWaitsForEntireSuspendingLease() = runTest {
        TokenStore.set("owner-a")
        val identity = TokenStore.snapshot()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = async { TokenStore.withSnapshotLease(identity) { entered.complete(Unit); release.await(); TokenStore.snapshot() } }
        entered.await()
        val attempted = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.IO) { attempted.complete(Unit); TokenStore.set("owner-b"); TokenStore.snapshot() }
        try {
            attempted.await()
            assertEquals(identity, TokenStore.snapshot())
            assertFalse(writer.isCompleted)
        } finally { release.complete(Unit) }
        assertEquals(identity, held.await())
        assertEquals("owner-b", writer.await().token)
    }

    @Test fun cancelledWaitingLeaseDoesNotLeakAndOldGenerationNeverRuns() = runTest {
        TokenStore.set("same-token")
        val identity = TokenStore.snapshot()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = async { TokenStore.withSnapshotLease(identity) { entered.complete(Unit); release.await() } }
        entered.await()
        var ran = false
        val waiting = launch { TokenStore.withSnapshotLease(identity) { ran = true } }
        runCurrent()
        waiting.cancelAndJoin()
        release.complete(Unit)
        held.await()
        TokenStore.set("same-token")
        assertNull(TokenStore.withSnapshotLease(identity) { ran = true; 1 })
        assertFalse(ran)
    }

    @Test fun reentrantPublicationFailsBeforeWaitingAndDoesNotChangeIdentity() = runTest {
        TokenStore.set("owner-a")
        val identity = TokenStore.snapshot()
        TokenStore.withSnapshotLease(identity) {
            val failure = runCatching { TokenStore.clear() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(identity, TokenStore.snapshot())
        }
        TokenStore.clear()
        assertNull(TokenStore.snapshot().token)
    }

    @Test fun comparePublishAndExistingUncontendedMonitorUseKeepAtomicGenerations() = runTest {
        TokenStore.clear()
        val before = TokenStore.snapshot()
        val after = requireNotNull(TokenStore.publishIfCurrent(before, "restored"))
        assertEquals("restored", after.token)
        assertEquals(before.generation + 1, after.generation)
        assertNull(TokenStore.publishIfCurrent(before, "stale-login"))
        assertEquals(after, TokenStore.snapshot())
        synchronized(TokenStore) { TokenStore.set("restored") }
        assertEquals(after.generation + 1, TokenStore.snapshot().generation)
    }

    @Test fun cancellingSuspendingPublisherWhileLeasedNeverPublishesAfterRelease() = runTest {
        TokenStore.set("owner-a")
        val identity = TokenStore.snapshot()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = async { TokenStore.withSnapshotLease(identity) { entered.complete(Unit); release.await() } }
        entered.await()
        val waiting = launch { TokenStore.publishIfCurrentCancellable(identity, "cancelled-login") }
        runCurrent()
        waiting.cancelAndJoin()
        release.complete(Unit)
        held.await()
        assertEquals(identity, TokenStore.snapshot())
        assertEquals("owner-b", TokenStore.publishIfCurrentCancellable(identity, "owner-b")?.token)
    }

    @Test fun acknowledgedAccountKeepsItsOwnerAcrossCredentialRotationAndExpiresOldActions() = runTest {
        TokenStore.clear()
        val before = TokenStore.snapshot()
        val owner = TokenStore.storageScope(TokenStore.Snapshot("account-token-one", 0))
        val first = requireNotNull(TokenStore.publishAccountIfCurrent(before, "account-token-one", owner))
        val second = requireNotNull(TokenStore.publishAccountIfCurrentCancellable(first, "account-token-two", owner))
        assertEquals(owner, TokenStore.storageScope(second))
        assertEquals("account-token-two", second.token)
        assertEquals(first.generation + 1, second.generation)
        assertNull(TokenStore.withSnapshotLease(first) { error("An old credential cannot authorize an action") })
        assertEquals(owner, TokenStore.withSnapshotLease(second) { TokenStore.storageScope() })
        TokenStore.set("unassociated-token")
        assertNull(TokenStore.snapshot().accountScope)
        assertNotEquals(owner, TokenStore.storageScope())
    }

    @Test fun scopedPublicationRejectsInvalidBindingsAndAnAlteredExpectedOwner() = runTest {
        TokenStore.set("bound-account-token")
        val before = TokenStore.snapshot()
        val owner = TokenStore.storageScope(before)
        val current = requireNotNull(TokenStore.publishAccountIfCurrent(before, "bound-account-token", owner))
        for (invalid in listOf("local", "", "a".repeat(63), "G".repeat(64))) {
            assertTrue(runCatching { TokenStore.publishAccountIfCurrent(current, "new-token", invalid) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(current, TokenStore.snapshot())
        }
        val other = TokenStore.storageScope(TokenStore.Snapshot("other-owner-token", 0))
        assertNull(TokenStore.publishAccountIfCurrent(current.copy(accountScope = other), "new-token", other))
        assertNull(TokenStore.publishAccountIfCurrent(current.copy(accountScope = null), "new-token", owner))
        assertEquals(current, TokenStore.snapshot())
    }

    @Test fun aCancelledScopedPublisherCannotEscapeTheActualIdentityLease() = runTest {
        TokenStore.set("bound-account-one")
        val original = TokenStore.snapshot()
        val owner = TokenStore.storageScope(original)
        val current = requireNotNull(TokenStore.publishAccountIfCurrent(original, "bound-account-one", owner))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = async { TokenStore.withSnapshotLease(current) { entered.complete(Unit); release.await() } }
        entered.await()
        val waiting = launch { TokenStore.publishAccountIfCurrentCancellable(current, "bound-account-two", owner) }
        runCurrent()
        waiting.cancelAndJoin()
        release.complete(Unit)
        held.await()
        assertEquals(current, TokenStore.snapshot())
        assertEquals(owner, TokenStore.storageScope())
    }
}
