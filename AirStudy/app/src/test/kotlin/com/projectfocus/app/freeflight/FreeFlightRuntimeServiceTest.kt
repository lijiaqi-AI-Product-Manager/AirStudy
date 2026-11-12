package com.projectfocus.app.freeflight

import android.content.Intent
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.app.BuildConfig
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.booking.FreeFlightHostOwnerFactory
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.home.map.MapConfiguration
import com.projectfocus.feature.home.map.SourceMapHostView
import com.projectfocus.service.focus.freeflight.*
import com.projectfocus.service.focus.freeflight.runtime.*
import com.projectfocus.service.focus.session.SessionRepository
import dagger.hilt.android.EntryPointAccessors
import java.io.Closeable
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

/** Real production graph/Room/DataStore/Service, restored from an explicitly seeded archive.
 * This verifies runtime recovery, not normal UI entry or a fabricated admission grant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [34])
class FreeFlightRuntimeServiceTest {
    private lateinit var app: ProjectFocusApp
    private lateinit var graph: FreeFlightRuntimeEntryPoint
    private lateinit var owner: FreeFlightOwnerToken
    private lateinit var store: FreeFlightArchiveStore
    private lateinit var settings: SettingsRepository
    private var service: ServiceController<InjectedFreeFlightRuntimeService>? = null
    private var mapBinding: Closeable? = null
    private var mapOwner: MapOwner? = null
    private class MapOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun open() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) { withTimeout(15_000L) { app.awaitInitialAirportSeed(); app.awaitInitialAccountRestore() } }
        graph = EntryPointAccessors.fromApplication(app, FreeFlightRuntimeEntryPoint::class.java)
        store = ReflectionHelpers.getField(graph.repository(), "store")
        settings = ReflectionHelpers.getField(graph.operations(), "settings")
        runBlocking(Dispatchers.IO) {
            TokenStore.setCancellable("runtime-recovery-fixture-account")
            owner = FreeFlightHostOwnerFactory(app, graph.owners()).open()
            settings.write(PrefKey.BlacklistEnabled, false)
            val clock: AndroidFreeFlightTimeSource = ReflectionHelpers.getField(graph.repository(), "time")
            val request = FreeFlightStartRequest("restored-runtime-fixture", owner.ownerScope,
                PlannedAirport(1L, "AAA", "Departure", "City", "Country", 0.0, 0.0),
                90.0, "1A", FreeFlightScene(1, "Reading", null))
            assertTrue(store.commit(null, FreeFlightEngine().start(request, clock.read()), null))
            val restored = graph.repository().restoreActive(owner)
            assertTrue(restored is FreeFlightCommandResult.Committed)
            assertFalse((restored as FreeFlightCommandResult.Committed).runtimeSynchronized)
        }
    }

    @After fun close() {
        destroyService()
        mapOwner?.registry?.currentState = Lifecycle.State.DESTROYED
        mapBinding?.close()
        TokenStore.clear()
        shutdownRealApplicationAfterTest(app)
    }

    private fun startActualService(): InjectedFreeFlightRuntimeService {
        val key = requireNotNull(graph.registry().target.value).key
        val intent = Intent(app, InjectedFreeFlightRuntimeService::class.java)
            .putExtra(FreeFlightRuntimeService.SESSION_ID, key.sessionId)
            .putExtra(FreeFlightRuntimeService.DRIVER_ID, key.driverId)
        val controller = Robolectric.buildService(InjectedFreeFlightRuntimeService::class.java).create()
        service = controller
        controller.get().onStartCommand(intent, 0, 1)
        await("Actual foreground service start") { graph.registry().isRunning(key) }
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        return controller.get()
    }

    private fun destroyService() {
        val controller = service ?: return
        service = null
        val scope = ReflectionHelpers.getField<CoroutineScope>(controller.get(), "serviceScope")
        controller.destroy()
        runBlocking(Dispatchers.IO) { withTimeout(10_000L) { requireNotNull(scope.coroutineContext[Job]).join() } }
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(15)
        }
        throw AssertionError("$description; actual runtime=${graph.registry().observation.value}; archive=${graph.repository().state.value?.status}")
    }

    @Test fun realTerminalArchiveWaitsForActualServiceDestroyBeforeReleasingTheSharedSlot() {
        val actual = startActualService()
        val key = requireNotNull(graph.registry().target.value).key
        val cancelled = runBlocking(Dispatchers.IO) { graph.repository().cancel(owner) } as FreeFlightCommandResult.Committed
        assertEquals(FreeFlightStatus.CANCELLED, cancelled.archive.status)
        assertFalse(cancelled.runtimeSynchronized)
        await("Actual service requested stop") { shadowOf(actual).isStoppedBySelf }
        assertEquals(owner, graph.owners().retainedOwner(key.sessionId))
        assertFalse(graph.registry().isStopped(key))
        destroyService()
        await("Joined service and exact reservation release") { graph.registry().isStopped(key) && graph.owners().retainedOwner(key.sessionId) == null }
        runBlocking(Dispatchers.IO) {
            assertEquals(-1L, settings.read(PrefKey.ActiveSessionCategoryId))
            assertFalse(settings.read(PrefKey.SessionPaused))
            assertEquals(FreeFlightStatus.CANCELLED, store.load(key.sessionId, owner.ownerScope)?.status)
        }
    }

    @Test fun republishedAccountRetiresOnlyTheActuallyStoppedOwnerAndNeverCompletesItsArchive() {
        val actual = startActualService()
        val key = requireNotNull(graph.registry().target.value).key
        runBlocking(Dispatchers.IO) { TokenStore.setCancellable("runtime-recovery-fixture-account") }
        await("Actual account invalidation stops the driver") { shadowOf(actual).isStoppedBySelf }
        assertEquals(owner, graph.owners().retainedOwner(key.sessionId))
        destroyService()
        await("Obsolete exact owner cleanup") { graph.owners().retainedOwner(key.sessionId) == null }
        runBlocking(Dispatchers.IO) {
            assertEquals(FreeFlightStatus.ONGOING, store.load(key.sessionId, owner.ownerScope)?.status)
            assertEquals(-1L, settings.read(PrefKey.ActiveSessionCategoryId))
            assertFalse(settings.read(PrefKey.SessionPaused))
            val sessions: SessionRepository = ReflectionHelpers.getField(graph.owners(), "sessions")
            assertTrue(sessions.startPlannedFlight(sessions.preparePlan("PEK", "PVG"), "after-actual-retirement"))
            assertTrue(sessions.abandon())
        }
    }

    @Test fun actualMissingConfigurationSurfaceDrivesTheRealNoMapResultCallbackAndStoresNoUnflownLeg() {
        assertTrue("This configured run explicitly exercises the real no-map product state", BuildConfig.HMS_MAP_API_KEY.isBlank())
        val lifecycle = MapOwner().also { mapOwner = it; it.registry.currentState = Lifecycle.State.RESUMED }
        val host = SourceMapHostView(app)
        host.bindLifecycle(lifecycle, MapConfiguration(BuildConfig.HMS_MAP_API_KEY, BuildConfig.HMS_MAP_APP_ID))
        mapBinding = graph.maps().bind(host)
        val actual = startActualService()
        val key = requireNotNull(graph.registry().target.value).key
        val before = runBlocking(Dispatchers.IO) {
            graph.repository().resume(owner)
            graph.repository().prepareLanding(owner)
            val saved = requireNotNull(graph.repository().state.value)
            val airport = PlannedAirport(2L, "BBB", "Landing", "Other", "Country", 10.0, 10.0)
            graph.repository().selectLanding(owner, key.sessionId, saved.landingSelectionGeneration, airport)
            val result = graph.repository().confirmLanding(owner, saved.landingSelectionGeneration) as FreeFlightCommandResult.Committed
            assertEquals(FreeFlightPhase.NO_MAP_ARRIVAL, result.archive.phase)
            result.archive
        }
        await("Actual posted result is committed by the real driver") { graph.repository().state.value?.status == FreeFlightStatus.COMPLETED }
        val completed = requireNotNull(graph.repository().state.value)
        assertEquals(before.position, completed.position)
        assertEquals(before.cumulativeDistanceKm, completed.cumulativeDistanceKm, 0.0)
        assertNotNull(completed.endedAtWallMs)
        await("Real arrival stopped the service") { shadowOf(actual).isStoppedBySelf }
        destroyService()
        await("Actual arrival cleanup releases its reservation") { graph.owners().retainedOwner(key.sessionId) == null }
        runBlocking(Dispatchers.IO) { assertEquals(FreeFlightStatus.COMPLETED, store.load(key.sessionId, owner.ownerScope)?.status) }
    }
}
