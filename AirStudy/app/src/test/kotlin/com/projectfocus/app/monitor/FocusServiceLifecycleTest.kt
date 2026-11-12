package com.projectfocus.app.monitor

import android.app.AppOpsManager
import android.app.Notification
import android.os.Looper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.MainActivity
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.service.focus.monitor.FocusMonitorService
import com.projectfocus.service.focus.monitor.FocusPermissions
import com.projectfocus.service.focus.session.SessionState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Actual Hilt App, Core repository, Room, DataStore and service; no substitute ticker. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [34])
class FocusServiceLifecycleTest {
    private lateinit var app: ProjectFocusApp
    private var activity: ActivityController<MainActivity>? = null
    private var service: ServiceController<FocusMonitorService>? = null

    @Before fun open() {
        app = ApplicationProvider.getApplicationContext()
        try {
            runBlocking(Dispatchers.IO) { withTimeout(15_000) { app.awaitInitialAirportSeed() } }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val actualThreads = Thread.getAllStackTraces().entries.filter { (thread, frames) ->
                thread.name.startsWith("arch_disk_io") || frames.any {
                    it.className.contains("AirportSeeder") || it.className.contains("DatabaseBootstrap") ||
                        it.className.contains("TriggerBasedInvalidationTracker")
                }
            }.take(12).joinToString("\n") { (thread, frames) ->
                thread.name + " " + thread.state + "\n" + frames.take(24).joinToString("\n")
            }
            throw AssertionError("Actual initial airport seed exceeded the unchanged15s bound; bootstrap=${app.databaseBootstrapResult}\n$actualThreads", error)
        }
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
        val sessions = checkNotNull(activity).get().sessionRepository
        runBlocking(Dispatchers.IO) {
            val plan = sessions.preparePlan("PEK", "PVG")
            assertThat(sessions.startPlannedFlight(plan, "monitor-lifecycle")).isTrue()
        }
    }

    @After fun close() {
        service?.let { controller ->
            val scope = ReflectionHelpers.getField<CoroutineScope>(controller.get(), "serviceScope")
            controller.destroy()
            runBlocking(Dispatchers.IO) { withTimeout(10_000) { checkNotNull(scope.coroutineContext[Job]).join() } }
        }
        activity?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        shutdownRealApplicationAfterTest(app)
    }

    private fun usage(allowed: Boolean) {
        shadowOf(checkNotNull(app.getSystemService(AppOpsManager::class.java))).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName,
            if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED,
        )
        assertThat(FocusPermissions.hasUsageStats(app)).isEqualTo(allowed)
    }

    private fun startService(): FocusMonitorService {
        val controller = Robolectric.buildService(FocusMonitorService::class.java).create()
        service = controller
        return controller.get().also { it.onStartCommand(null, 0, 1) }
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(25)
        }
        val running = service?.get()
        val snapshot = running?.let {
            "stopped=${shadowOf(it).isStoppedBySelf}, notice=${shadowOf(it).lastForegroundNotification?.extras?.getCharSequence(Notification.EXTRA_TEXT)}, usage=${FocusPermissions.hasUsageStats(it)}"
        }
        throw AssertionError("$description; actual service: $snapshot")
    }

    @Test fun missingUsageDoesNotStopTheRealFlightTicker() {
        usage(false)
        val runningService = startService()
        await("The service must report that application shielding is unavailable") {
            shadowOf(runningService).isStoppedBySelf ||
                shadowOf(runningService).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TEXT) ==
                app.getString(com.projectfocus.service.focus.R.string.focus_perm_core_missing)
        }
        assertThat(shadowOf(runningService).isStoppedBySelf).isFalse()
        val sessions = checkNotNull(activity).get().sessionRepository
        await("The actual Core ticker must advance despite missing usage access") {
            (sessions.state.value as? SessionState.Running)?.accumulatedMs?.let { it >= 1_000L } == true
        }
        assertThat(shadowOf(runningService).isStoppedBySelf).isFalse()
    }

    @Test fun pausedPreferenceIsObservedAlongsideTheCategoryFlow() {
        usage(true)
        val runningService = startService()
        val sessions = checkNotNull(activity).get().sessionRepository
        // The real Core owns this preference and reasserts it on ticks. Pause the
        // actual flight rather than racing a manual true against a Running flight.
        assertThat(runBlocking(Dispatchers.IO) { sessions.pause() }).isTrue()
        assertThat(sessions.state.value).isInstanceOf(SessionState.Paused::class.java)
        await("The live notification must observe paused=true without ending the category collector") {
            shadowOf(runningService).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TEXT) ==
                app.getString(com.projectfocus.service.focus.R.string.focus_notification_text_paused)
        }
        assertThat(shadowOf(runningService).isStoppedBySelf).isFalse()
        assertThat(runBlocking(Dispatchers.IO) { sessions.resume() }).isTrue()
        assertThat(sessions.state.value).isInstanceOf(SessionState.Running::class.java)
        await("The same service must observe paused=false again") {
            shadowOf(runningService).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TEXT) !=
                app.getString(com.projectfocus.service.focus.R.string.focus_notification_text_paused)
        }
    }
}
