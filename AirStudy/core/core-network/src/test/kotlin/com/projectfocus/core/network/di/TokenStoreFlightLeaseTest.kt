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
}
