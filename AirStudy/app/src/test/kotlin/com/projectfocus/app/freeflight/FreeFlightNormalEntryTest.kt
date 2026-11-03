package com.projectfocus.app.freeflight

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.app.MainActivity
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.app.home.HomeOriginSelection
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightKeepAliveNotification
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightNotificationRequirement
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

/** Actual normal App/Main/retainer and Android prompts. No configured map or real grant is claimed.
 * Prompt-only owner predicates below are explicit component fixtures, never production authority. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [34])
class FreeFlightNormalEntryTest {
    private lateinit var app: ProjectFocusApp
    private lateinit var activity: MainActivity
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var graph: FreeFlightRuntimeEntryPoint
    private lateinit var scope: CoroutineScope
    private var prompts: AndroidFreeFlightPreparationPrompts? = null

    @Before fun open() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) { withTimeout(15_000) { app.awaitInitialAccountRestore(); app.awaitInitialAirportSeed() } }
        graph = EntryPointAccessors.fromApplication(app, FreeFlightRuntimeEntryPoint::class.java)
        app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE).edit().remove(HomeOriginSelection.CODE).commit()
        app.getSharedPreferences(AndroidFreeFlightPreparationPrompts.DEVICE_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        activity = controller.get()
        activity.supportFragmentManager.executePendingTransactions()
        val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        host.navController.navigate(R.id.action_permission_to_home)
        host.childFragmentManager.executePendingTransactions()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        idle()
    }
    @After fun close() {
        prompts?.close(); scope.cancel(); controller.pause().stop().destroy()
        shutdownRealApplicationAfterTest(app)
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(32, TimeUnit.MILLISECONDS)
    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            idle(); if (predicate()) return; Thread.sleep(10)
        }
        throw AssertionError(description)
    }
    private fun prompt() = AndroidFreeFlightPreparationPrompts(activity).also { prompts = it }
    private fun latest() = ShadowDialog.getLatestDialog() as? AlertDialog

    @Test fun normalMainEntryUsesProductionProviderAndRejectsMissingOriginWithoutCreatingAFlight() {
        val first = app.freeFlightPreparationActions(activity, null)!!
        assertTrue(first is AndroidFreeFlightPreparationActions)
        assertSame(graph.preparations(), ReflectionHelpers.getField<Any>(first, "retained"))
        (first as java.io.Closeable).close()
        assertTrue(activity.openFreeFlightPreparation())
        val actual: FreeFlightPreparationController = ReflectionHelpers.getField(activity, "freeFlightPreparationController")
        await("Actual missing origin rejected") { actual.coordinator.state.value.step == FreeFlightPreparationStep.REJECTED }
        assertEquals("请先选择出发机场", actual.coordinator.state.value.message)
        assertNull(activity.findViewById<View>(R.id.pf_ff_root))
        assertNull(graph.repository().state.value)
        val store: com.projectfocus.service.focus.freeflight.FreeFlightArchiveStore = ReflectionHelpers.getField(graph.repository(), "store")
        runBlocking(Dispatchers.IO) { assertNull(store.active()); assertNull(graph.owners().captureHostAccount()) }
        assertNull(graph.registry().observation.value.key)
    }

    @Test fun inventedSavedPreparationCannotOpenANewHostThroughTheInstalledProvider() {
        assertNull(app.freeFlightPreparationActions(activity, "invented-saved-preparation"))
        runBlocking(Dispatchers.IO) { assertNull(graph.owners().captureHostAccount()) }
        assertNull(graph.repository().state.value)
    }

    @Test fun actualFirstHintPersistsOnlyOnConfirmationAndDoesNotRequireBatteryExemption() {
        val actual = prompt()
        val prefs = app.getSharedPreferences(AndroidFreeFlightPreparationPrompts.DEVICE_FILE, Context.MODE_PRIVATE)
        val result = scope.async { actual.firstHint { true } }
        await("Actual first battery hint") { latest()?.isShowing == true }
        val dialog = latest()!!
        assertTrue(dialog.findViewById<TextView>(android.R.id.message)!!.text.toString().contains(activity.getString(R.string.pf_ff_battery_opt_warning_guide_action)))
        assertFalse(prefs.getBoolean(AndroidFreeFlightPreparationPrompts.HINT_SHOWN, false))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await("Actual hint continuation") { result.isCompleted }
        assertTrue(runBlocking { result.await() })
        assertTrue(prefs.getBoolean(AndroidFreeFlightPreparationPrompts.HINT_SHOWN, false))
        assertNull(shadowOf(activity).nextStartedActivity)
        val next = scope.async { actual.firstHint { true } }
        await("Confirmed hint not shown twice") { next.isCompleted }
        assertTrue(runBlocking { next.await() }); assertFalse(dialog.isShowing)
    }

    @Test fun obsoleteHintConfirmationDoesNotPersistOrContinue() {
        var current = true
        val result = scope.async { prompt().firstHint { current } }
        await("Actual obsolete hint") { latest()?.isShowing == true }
        current = false
        latest()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await("Actual obsolete hint rejected") { result.isCompleted }
        assertFalse(runBlocking { result.await() })
        assertFalse(app.getSharedPreferences(AndroidFreeFlightPreparationPrompts.DEVICE_FILE, Context.MODE_PRIVATE).getBoolean(AndroidFreeFlightPreparationPrompts.HINT_SHOWN, false))
    }

    @Test fun notificationPromptDoesNotTreatSystemRequestAsGranted() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val result = scope.async { prompt().notification { true } }
        await("Actual notification explanation") { latest()?.isShowing == true }
        latest()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await("Actual permission request") { result.isCompleted }
        assertTrue(runBlocking { result.await() } is FreeFlightPermissionDecision.NeedsAction)
        assertEquals(FreeFlightNotificationRequirement.POST_NOTIFICATIONS, FreeFlightKeepAliveNotification.access(app).requirement)
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), shadowOf(activity).lastRequestedPermission.requestedPermissions)
        assertNull(graph.preparationRuntime().observation.value.key)
    }

    @Test fun blockedChannelOpensOnlyItsActualSystemSettingsAndDoesNotClaimReadiness() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(FreeFlightKeepAliveNotification.CHANNEL_ID, "Actual blocked fixture", NotificationManager.IMPORTANCE_NONE))
        val result = scope.async { prompt().notification { true } }
        await("Actual channel explanation") { latest()?.isShowing == true }
        latest()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await("Actual channel settings intent") { result.isCompleted }
        assertTrue(runBlocking { result.await() } is FreeFlightPermissionDecision.NeedsAction)
        val intent = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(activity.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(FreeFlightKeepAliveNotification.CHANNEL_ID, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(FreeFlightNotificationRequirement.CHANNEL, FreeFlightKeepAliveNotification.access(app).requirement)
        assertNull(graph.preparationRuntime().observation.value.key)
    }

    @Test fun detachedPromptClosesItsActualWindowAndDoesNotMarkTheHintSeen() {
        val actual = prompt()
        val result = scope.async { actual.firstHint { true } }
        await("Actual detachable hint") { latest()?.isShowing == true }
        val dialog = latest()!!; actual.close()
        await("Actual detached hint stopped") { result.isCompleted }
        assertFalse(runBlocking { result.await() }); assertFalse(dialog.isShowing)
        assertFalse(app.getSharedPreferences(AndroidFreeFlightPreparationPrompts.DEVICE_FILE, Context.MODE_PRIVATE).getBoolean(AndroidFreeFlightPreparationPrompts.HINT_SHOWN, false))
    }
}
