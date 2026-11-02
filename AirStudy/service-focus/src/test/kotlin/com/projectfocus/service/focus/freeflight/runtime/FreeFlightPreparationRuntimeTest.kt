package com.projectfocus.service.focus.freeflight.runtime

import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Job completion and nonce transitions; no Android/permission/admission fixture can grant a flight. */
@OptIn(ExperimentalCoroutinesApi::class)
class FreeFlightPreparationRuntimeTest {
    private val owner = FreeFlightOwnerToken("prep-component", 1)

    @Test fun registeredRootAndExactNonceAreRequiredBeforeForegroundConfirmation() = runTest {
        val registry = FreeFlightPreparationRuntime(backgroundScope)
        val key = registry.stage("prep", "sid", owner)
        registry.foreground(key)
        assertFalse(registry.observation.value.foregroundConfirmed)
        assertNull(registry.resolve("sid", "invented"))
        val root = SupervisorJob()
        assertTrue(registry.attach(key, root))
        assertFalse(registry.attach(key, SupervisorJob().apply { cancel() }))
        registry.foreground(key)
        assertEquals(FreeFlightPreparationDriverState.FOREGROUND, registry.observation.value.state)
        assertSame(key, registry.stage("prep", "sid", owner))
        root.cancel();registry.destroyed(key, root);runCurrent()
    }

    @Test fun stoppedIsPublishedAfterTheActualRootAndItsNonCancellableChildJoin() = runTest {
        val registry = FreeFlightPreparationRuntime(backgroundScope)
        val key = registry.stage("prep", "sid", owner)
        val root = SupervisorJob();assertTrue(registry.attach(key, root))
        val barrier = CompletableDeferred<Unit>()
        backgroundScope.launch(root) { withContext(NonCancellable) { barrier.await() } }
        runCurrent()
        registry.foreground(key);registry.requestStop("prep", "sid", owner)
        root.cancel();registry.destroyed(key, root);runCurrent()
        assertEquals(FreeFlightPreparationDriverState.STOP_REQUESTED, registry.observation.value.state)
        assertFalse(root.isCompleted)
        assertThrows(IllegalStateException::class.java) { registry.stage("next", "next-sid", owner) }
        barrier.complete(Unit);runCurrent()
        assertTrue(root.isCompleted)
        assertEquals(FreeFlightPreparationDriverState.STOPPED, registry.observation.value.state)
        val next = registry.stage("next", "next-sid", owner)
        assertNotEquals(key.driverId, next.driverId);assertNull(registry.resolve(key.sessionId, key.driverId))
    }

    @Test fun handoffRequiresTheSameOwnerSessionRootAndDoesNotClaimTheRootStopped() = runTest {
        val registry = FreeFlightPreparationRuntime(backgroundScope)
        val key = registry.stage("prep", "sid", owner);val root = SupervisorJob()
        registry.attach(key, root);registry.foreground(key)
        assertFalse(registry.handOff(key, root, FreeFlightRuntimeKey("other", owner, "real-flight-driver")))
        assertFalse(registry.handOff(key, root, FreeFlightRuntimeKey("sid", owner.copy(generation = 9), "real-flight-driver")))
        val foreign = SupervisorJob()
        assertFalse(registry.handOff(key, foreign, FreeFlightRuntimeKey("sid", owner, "real-flight-driver")));foreign.cancel()
        assertTrue(registry.handOff(key, root, FreeFlightRuntimeKey("sid", owner, "real-flight-driver")))
        registry.requestStop("prep", "sid", owner)
        assertTrue(root.isActive)
        assertEquals(FreeFlightPreparationDriverState.HANDED_OFF, registry.observation.value.state)
        assertNull(registry.resolve("sid", key.driverId))
        assertThrows(IllegalStateException::class.java) { registry.stage("prep", "sid", owner) }
        root.cancel();registry.destroyed(key, root);runCurrent()
        assertEquals(FreeFlightPreparationDriverState.HANDED_OFF, registry.observation.value.state)
    }

    @Test fun unattachedCancellationRevokesTheOldIntentWithoutInventingAStoppedService() = runTest {
        val registry = FreeFlightPreparationRuntime(backgroundScope)
        val old = registry.stage("old", "sid", owner)
        registry.requestStop("old", "sid", owner)
        assertEquals(FreeFlightPreparationDriverState.CANCELLED_UNATTACHED, registry.observation.value.state)
        val root = SupervisorJob()
        assertFalse(registry.attach(old, root))
        val actual = registry.stage("new", "new-sid", owner)
        assertNull(registry.resolve(old.sessionId, old.driverId));assertNotEquals(old.driverId, actual.driverId)
        assertTrue(registry.attach(actual, root));root.cancel();registry.destroyed(actual, root);runCurrent()
    }

    @Test fun failureWithAnAttachedRootCannotBeReplacedUntilItsActualDestruction() = runTest {
        val registry = FreeFlightPreparationRuntime(backgroundScope)
        val key = registry.stage("prep", "sid", owner);val root = SupervisorJob()
        registry.attach(key, root);registry.failure(key, IllegalStateException("Explicit failure"))
        assertNull(registry.resolve("sid", key.driverId))
        assertThrows(IllegalStateException::class.java) { registry.stage("prep", "sid", owner) }
        root.cancel();registry.destroyed(key, root);runCurrent()
        val next = registry.stage("prep", "sid", owner)
        registry.failure(key, IllegalStateException("Late old callback"))
        assertEquals(next, registry.observation.value.key)
        assertEquals(FreeFlightPreparationDriverState.REQUESTED, registry.observation.value.state)
    }

    @Test fun unrelatedCancellationCannotStopTheCurrentPreparation() = runTest {
        val registry = FreeFlightPreparationRuntime(backgroundScope)
        val key = registry.stage("prep", "sid", owner);val root = SupervisorJob()
        registry.attach(key, root);registry.foreground(key)
        registry.requestStop("another", "sid", owner)
        registry.requestStop("prep", "another", owner)
        registry.requestStop("prep", "sid", owner.copy(generation = 2))
        assertEquals(FreeFlightPreparationDriverState.FOREGROUND, registry.observation.value.state)
        assertTrue(root.isActive)
        root.cancel();registry.destroyed(key, root);runCurrent()
    }
}
