package com.projectfocus.app.freeflight

import android.Manifest
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.booking.FreeFlightHostOwnerFactory
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.freeflight.*
import com.projectfocus.service.focus.freeflight.runtime.*
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Actual App/OS notification/Service/Room/Core preparation and handoff. The handoff case restores
 * an explicitly seeded archive; it is not a real admission grant or normal UI entry acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [34])
class FreeFlightPreparationServiceTest {
    private lateinit var app: ProjectFocusApp
    private lateinit var graph: FreeFlightRuntimeEntryPoint
    private lateinit var owner: FreeFlightOwnerToken
    private lateinit var store: FreeFlightArchiveStore
    private lateinit var settings: SettingsRepository
    private var controller: ServiceController<InjectedFreeFlightRuntimeService>? = null
    private var heldChild: CompletableDeferred<Unit>? = null
    private var startId = 0

    @Before fun open() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) { withTimeout(15_000L) { app.awaitInitialAccountRestore();app.awaitInitialAirportSeed() } }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(FreeFlightNotificationRequirement.READY, FreeFlightKeepAliveNotification.access(app).requirement)
        graph = EntryPointAccessors.fromApplication(app, FreeFlightRuntimeEntryPoint::class.java)
        store = ReflectionHelpers.getField(graph.repository(), "store")
        settings = ReflectionHelpers.getField(graph.operations(), "settings")
        runBlocking(Dispatchers.IO) {
            TokenStore.setCancellable("actual-preparation-service-fixture-account")
            owner = FreeFlightHostOwnerFactory(app, graph.owners()).open()
            settings.write(PrefKey.BlacklistEnabled, false)
        }
    }

    @After fun close() {
        heldChild?.complete(Unit)
        destroy()
        TokenStore.clear()
        shutdownRealApplicationAfterTest(app)
    }

    private fun dispatch(preparationId: String = "actual-preparation", sessionId: String = "actual-prepared-session"): FreeFlightPreparationRuntimeKey =
        (runBlocking(Dispatchers.IO) { graph.preparationOperations().start(preparationId, sessionId, owner) }
            as FreeFlightPreparationDispatch.Dispatched).key

    private fun nextServiceIntent(): Intent {
        repeat(12) {
            val intent = shadowOf(app).nextStartedService ?: error("No actual dispatched service intent")
            if (intent.component?.className == InjectedFreeFlightRuntimeService::class.java.name) return intent
        }
        error("The actual dedicated service intent was not found")
    }

    private fun deliver(intent: Intent): InjectedFreeFlightRuntimeService {
        val actual = controller ?: Robolectric.buildService(InjectedFreeFlightRuntimeService::class.java).create().also { controller = it }
        actual.get().onStartCommand(intent, 0, ++startId)
        return actual.get()
    }

    private fun startPreparation(): Pair<FreeFlightPreparationRuntimeKey, Intent> {
        val key = dispatch();val intent = nextServiceIntent()
        assertEquals(FreeFlightRuntimeService.PREPARE, intent.action)
        assertEquals(key.driverId, intent.getStringExtra(FreeFlightRuntimeService.PREPARATION_DRIVER_ID))
        deliver(intent)
        await("Actual preparation foreground") {
            graph.preparationRuntime().observation.value.let { it.key == key && it.state == FreeFlightPreparationDriverState.FOREGROUND && it.foregroundConfirmed }
        }
        return key to intent
    }

    private fun destroy() {
        val actual = controller ?: return
        controller = null
        val scope: CoroutineScope = ReflectionHelpers.getField(actual.get(), "serviceScope")
        actual.destroy()
        runBlocking(Dispatchers.IO) { withTimeout(10_000L) { requireNotNull(scope.coroutineContext[Job]).join() } }
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(15)
        }
        throw AssertionError("$description; actualPrep=${graph.preparationRuntime().observation.value}; actualFlight=${graph.registry().observation.value}")
    }

    @Test fun actualPreparationForegroundDoesNotCreateAFlightReserveTheSlotOrClaimTimerReadiness() {
        val key = startPreparation().first
        val service = requireNotNull(controller).get()
        val notification = requireNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(FreeFlightKeepAliveNotification.CHANNEL_ID, notification.channelId)
        assertTrue(notification.actions.isNullOrEmpty())
        assertNull(graph.registry().observation.value.key);assertNull(graph.repository().state.value)
        runBlocking(Dispatchers.IO) {
            assertNull(store.active());assertNull(graph.owners().retainedOwner(key.sessionId))
            assertEquals(-1L, settings.read(PrefKey.ActiveSessionCategoryId))
            assertFalse(settings.read(PrefKey.SessionPaused))
            assertEquals(key, (graph.preparationOperations().start(key.preparationId, key.sessionId, key.owner) as FreeFlightPreparationDispatch.Dispatched).key)
        }
        assertNull(shadowOf(app).nextStartedService)
        graph.preparationOperations().stop(key.preparationId, key.sessionId, key.owner)
        await("Actual preparation stop request") { shadowOf(service).isStoppedBySelf }
        assertEquals(FreeFlightPreparationDriverState.STOP_REQUESTED, graph.preparationRuntime().observation.value.state)
        destroy()
        await("Actual preparation root joined") { graph.preparationRuntime().observation.value.state == FreeFlightPreparationDriverState.STOPPED }
    }

    @Test fun actualPreparationVisualCannotCreateAnArchiveOrAcceptAnUnboundSuccessfulRenderer() {
        val host = com.projectfocus.feature.home.map.SourceMapHostView(android.view.ContextThemeWrapper(app, com.projectfocus.core.ui.R.style.Theme_Pf))
        val binding = graph.maps().bind(host)
        try {
            val pose = com.projectfocus.feature.home.map.FreeFlightMapPose(31.0, 121.0, 0f)
            var fabricatedPaints = 0
            val unbound = object : com.projectfocus.feature.home.map.FreeFlightMapVisual {
                override val isCurrent = true
                override fun render(pose: com.projectfocus.feature.home.map.FreeFlightMapPose): Boolean { fabricatedPaints++;return true }
                override fun close() = Unit
            }
            val operation = app.admissionApplicationScope.async(Dispatchers.IO) {
                assertNull(graph.maps().openPreparationVisual("actual-paint-only-preparation", owner, pose))
                assertNull(graph.maps().openFlightVisual("actual-paint-only-preparation", owner, pose))
                assertFalse(graph.maps().paintPreparation("actual-paint-only-preparation", owner, unbound, pose))
                assertFalse(graph.maps().paintFlight("actual-paint-only-preparation", owner, unbound, pose))
                assertNull(store.active())
                assertNull(graph.owners().retainedOwner("actual-paint-only-preparation"))
                assertTrue(graph.owners().isHostCurrent(owner))
            }
            await("Actual map visual gates completed") { operation.isCompleted }
            runBlocking(Dispatchers.IO) { operation.await() }
            assertEquals(0, fabricatedPaints)
            assertNull(graph.registry().observation.value.key)
        } finally { binding.close() }
    }

    @Test fun actualUiPaintAttemptsNeverWaitForTheAccountLeaseOrAcceptAnObsoleteOwner() {
        val sessionId = "actual-immediate-paint-owner"
        var painted = 0
        fun attempt() = graph.owners().tryWithUiOwner(sessionId, owner, preparation = true) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            TokenStore.requireSnapshotLease()
            painted++
        }
        assertTrue(attempt() is FreeFlightPaintAttempt.Applied)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().also { heldChild = it }
        val held = app.admissionApplicationScope.async(Dispatchers.IO) {
            graph.owners().withPreparationOwner(sessionId, owner) { entered.complete(Unit); release.await() }
        }
        try {
            runBlocking(Dispatchers.IO) { withTimeout(5_000) { entered.await() } }
            assertSame(FreeFlightPaintAttempt.Busy, attempt())
            assertEquals(1, painted)
        } finally { release.complete(Unit) }
        runBlocking(Dispatchers.IO) { withTimeout(5_000) { held.await() } }
        assertTrue(attempt() is FreeFlightPaintAttempt.Applied)
        runBlocking(Dispatchers.IO) { TokenStore.setCancellable("actual-immediate-paint-owner-replacement") }
        assertSame(FreeFlightPaintAttempt.Obsolete, attempt())
        assertEquals(2, painted)
        assertNull(graph.owners().retainedOwner(sessionId))
    }

    @Test fun busyCorePaintAttemptReleasesTheAccountLeaseBeforeAnActualPublisherWaits() {
        val sessions: com.projectfocus.service.focus.session.SessionRepository = ReflectionHelpers.getField(graph.owners(), "sessions")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val held = app.admissionApplicationScope.async(Dispatchers.IO) {
            sessions.withStableSessionSnapshot { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            var painted = false
            val attempted = graph.owners().tryWithUiOwner("actual-core-busy-paint", owner, preparation = true) { painted = true }
            assertSame(FreeFlightPaintAttempt.Busy, attempted)
            assertFalse(painted)
            // Main is deliberately not pumped here. A busy paint must NOT retain the account
            // lock while awaiting Main/Core; the actual asynchronous publisher can finish now.
            runBlocking(Dispatchers.IO) { withTimeout(2_000) { TokenStore.setCancellable("actual-core-busy-paint-replacement") } }
        } finally { release.countDown() }
        runBlocking(Dispatchers.IO) { withTimeout(5_000) { held.await() } }
    }

    @Test fun actualObsoletePreparationOwnerCannotOpenOrPaintTheCurrentMapBinding() {
        val host = com.projectfocus.feature.home.map.SourceMapHostView(android.view.ContextThemeWrapper(app, com.projectfocus.core.ui.R.style.Theme_Pf))
        val binding = graph.maps().bind(host)
        try {
            val previous = owner
            val current = runBlocking(Dispatchers.IO) { FreeFlightHostOwnerFactory(app, graph.owners()).open() }
            val pose = com.projectfocus.feature.home.map.FreeFlightMapPose(31.0, 121.0, 0f)
            val operation = app.admissionApplicationScope.async(Dispatchers.IO) {
                assertNull(graph.maps().openPreparationVisual("obsolete-preparation-paint", previous, pose))
                assertNull(graph.maps().openFlightVisual("obsolete-preparation-paint", previous, pose))
                assertFalse(graph.owners().isHostCurrent(previous))
                assertTrue(graph.owners().isHostCurrent(current))
                assertNull(store.active())
            }
            await("Obsolete actual map owner rejected") { operation.isCompleted }
            runBlocking(Dispatchers.IO) { operation.await() }
        } finally { binding.close() }
    }

    @Test fun actualDestructionWaitsForTheRealRootChildAndRejectsOldIntentAfterRestart() {
        val (key, oldIntent) = startPreparation();val actual = requireNotNull(controller).get()
        val scope: CoroutineScope = ReflectionHelpers.getField(actual, "serviceScope")
        val barrier = CompletableDeferred<Unit>().also { heldChild = it }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { barrier.await() } }
        graph.preparationOperations().stop(key.preparationId, key.sessionId, key.owner)
        await("Stop requested for actual preparation") { shadowOf(actual).isStoppedBySelf }
        requireNotNull(controller).destroy();controller = null
        assertFalse(requireNotNull(scope.coroutineContext[Job]).isCompleted)
        assertEquals(FreeFlightPreparationDriverState.STOP_REQUESTED, graph.preparationRuntime().observation.value.state)
        val blocked = runBlocking(Dispatchers.IO) { graph.preparationOperations().start("next", "next-session", owner) }
        assertTrue(blocked is FreeFlightPreparationDispatch.Failed)
        barrier.complete(Unit)
        await("Actual non-cancellable child joined") { graph.preparationRuntime().observation.value.state == FreeFlightPreparationDriverState.STOPPED }
        val next = dispatch("next", "next-session");deliver(nextServiceIntent())
        await("Next actual preparation") { graph.preparationRuntime().observation.value.let { it.key == next && it.foregroundConfirmed } }
        assertNotEquals(key.driverId, next.driverId)
        deliver(oldIntent)
        assertEquals(next, graph.preparationRuntime().observation.value.key)
        assertFalse(shadowOf(requireNotNull(controller).get()).isStoppedBySelf)
    }

    @Test fun sameActualServiceHandsOffToTheRealArchivedFlightAndLatePreparationCannotStopIt() {
        val (prep, oldIntent) = startPreparation();val actual = requireNotNull(controller).get()
        val root = ReflectionHelpers.getField<CoroutineScope>(actual, "serviceScope").coroutineContext[Job]
        runBlocking(Dispatchers.IO) {
            val airport = requireNotNull(app.database.airportDao().findByCode("PEK"))
            val departure = PlannedAirport(airport.id, airport.iataCode, airport.name, airport.city, airport.country, airport.latitude, airport.longitude)
            val time: AndroidFreeFlightTimeSource = ReflectionHelpers.getField(graph.repository(), "time")
            val request = FreeFlightStartRequest(prep.sessionId, owner.ownerScope, departure, 90.0, "1A", FreeFlightScene(1, "Reading", "study"))
            assertTrue(store.commit(null, FreeFlightEngine().start(request, time.read()), null))
            val restored = graph.repository().restoreActive(owner) as FreeFlightCommandResult.Committed
            assertFalse(restored.runtimeSynchronized)
        }
        val flightIntent = nextServiceIntent()
        assertNull(flightIntent.action);assertNotNull(flightIntent.getStringExtra(FreeFlightRuntimeService.DRIVER_ID))
        assertSame(actual, deliver(flightIntent))
        val flight = requireNotNull(graph.registry().target.value).key
        await("Actual same-root flight handoff") { graph.registry().isRunning(flight) }
        assertEquals(FreeFlightPreparationDriverState.HANDED_OFF, graph.preparationRuntime().observation.value.state)
        assertTrue(requireNotNull(root).isActive)
        val driver: Any = requireNotNull(ReflectionHelpers.getField<Any?>(graph.registry(), "driver"))
        assertSame(root, ReflectionHelpers.getField<Job>(driver, "lifetime"))
        graph.preparationOperations().stop(prep.preparationId, prep.sessionId, prep.owner)
        deliver(oldIntent)
        assertTrue(graph.registry().isRunning(flight));assertFalse(shadowOf(actual).isStoppedBySelf)
        runBlocking(Dispatchers.IO) { assertTrue(graph.repository().cancel(owner) is FreeFlightCommandResult.Committed) }
        await("Actual terminal flight requests stop") { shadowOf(actual).isStoppedBySelf }
        assertEquals(owner, graph.owners().retainedOwner(prep.sessionId))
        destroy()
        await("Actual flight root joined and slot released") { graph.registry().isStopped(flight) && graph.owners().retainedOwner(prep.sessionId) == null }
        runBlocking(Dispatchers.IO) { assertEquals(FreeFlightStatus.CANCELLED, store.load(prep.sessionId, owner.ownerScope)?.status) }
    }

    @Test fun actualAccountPublicationStopsPreparationWithoutCompletingOrInventingAnArchive() {
        val key = startPreparation().first;val actual = requireNotNull(controller).get()
        runBlocking(Dispatchers.IO) { TokenStore.setCancellable("actual-preparation-service-fixture-account") }
        await("Actual account invalidation stops preparation") { shadowOf(actual).isStoppedBySelf }
        destroy()
        await("Actual invalidated preparation joined") { graph.preparationRuntime().observation.value.state == FreeFlightPreparationDriverState.STOPPED }
        runBlocking(Dispatchers.IO) {
            assertNull(store.active());assertNull(graph.owners().retainedOwner(key.sessionId))
            assertEquals(-1L, settings.read(PrefKey.ActiveSessionCategoryId))
        }
    }

    @Test fun actualDeniedNotificationPermissionNeverDispatchesOrStagesPreparation() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(FreeFlightNotificationRequirement.POST_NOTIFICATIONS, FreeFlightKeepAliveNotification.access(app).requirement)
        val result = runBlocking(Dispatchers.IO) { graph.preparationOperations().start("denied", "denied-session", owner) }
        assertTrue(result is FreeFlightPreparationDispatch.Rejected)
        assertNull(graph.preparationRuntime().observation.value.key);assertNull(shadowOf(app).nextStartedService)
        runBlocking(Dispatchers.IO) { assertNull(store.active());assertNull(graph.owners().retainedOwner("denied-session")) }
    }

    @Test fun rapidActualHostReplacementsCannotLeaveTheOldPreparationRunning() {
        val key = startPreparation().first;val actual = requireNotNull(controller).get()
        val latest = runBlocking(Dispatchers.IO) {
            val factory = FreeFlightHostOwnerFactory(app, graph.owners())
            factory.open();factory.open()
        }
        assertNotEquals(key.owner, latest)
        await("Latest actual host supersedes the old preparation") { shadowOf(actual).isStoppedBySelf }
        destroy()
        await("Actually superseded preparation joined") { graph.preparationRuntime().observation.value.state == FreeFlightPreparationDriverState.STOPPED }
        runBlocking(Dispatchers.IO) {
            assertTrue(graph.owners().isHostCurrent(latest));assertNull(store.active())
            assertNull(graph.owners().retainedOwner(key.sessionId))
        }
    }

    @Test fun aRealCountdownCanTakeTheUnreservedSlotAndPreparationCleanupCannotStopIt() {
        val key = startPreparation().first;val actual = requireNotNull(controller).get()
        val sessions: com.projectfocus.service.focus.session.SessionRepository = ReflectionHelpers.getField(graph.owners(), "sessions")
        runBlocking(Dispatchers.IO) {
            assertTrue(sessions.startPlannedFlight(sessions.preparePlan("PEK", "PVG"), "actual-countdown-after-unreserved-preparation"))
        }
        await("Actual Core state supersedes the unsubmitted preparation") { shadowOf(actual).isStoppedBySelf }
        destroy()
        await("Preparation stopped without owning Core cleanup") { graph.preparationRuntime().observation.value.state == FreeFlightPreparationDriverState.STOPPED }
        runBlocking(Dispatchers.IO) {
            assertNull(store.active());assertNull(graph.owners().retainedOwner(key.sessionId))
            assertTrue(sessions.abandon()) // Still active until this explicit actual Core command.
        }
    }

    @Test fun rapidActualCountdownStartAndAbandonCannotReviveTheOldPreparation() {
        val key = startPreparation().first;val actual = requireNotNull(controller).get()
        val sessions: com.projectfocus.service.focus.session.SessionRepository = ReflectionHelpers.getField(graph.owners(), "sessions")
        runBlocking(Dispatchers.IO) {
            assertTrue(sessions.startPlannedFlight(sessions.preparePlan("PEK", "PVG"), "actual-rapid-countdown-switch"))
            assertTrue(sessions.abandon())
            // The real cancellation remains Arrived until the actual result is acknowledged.
            // Complete that same Core command before checking the rapid return toIdle.
            val cancelled = sessions.settle()
            assertNotNull(cancelled)
            assertEquals("actual-rapid-countdown-switch", cancelled?.id)
            assertTrue(sessions.state.value is com.projectfocus.service.focus.session.SessionState.Idle)
            assertFalse(graph.owners().isHostCurrent(key.owner))
        }
        await("A conflated Idle state cannot revive the previous host") { shadowOf(actual).isStoppedBySelf }
        destroy()
        await("Actually revoked preparation joined") { graph.preparationRuntime().observation.value.state == FreeFlightPreparationDriverState.STOPPED }
        runBlocking(Dispatchers.IO) {
            val fresh = FreeFlightHostOwnerFactory(app, graph.owners()).open()
            assertNotEquals(key.owner, fresh);assertTrue(graph.owners().isHostCurrent(fresh));assertNull(store.active())
        }
    }
}
