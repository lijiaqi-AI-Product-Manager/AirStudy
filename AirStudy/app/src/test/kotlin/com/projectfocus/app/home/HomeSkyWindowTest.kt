package com.projectfocus.app.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.projectfocus.app.MainActivity
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.route.RecordCloudHttpFixture
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.profile.ui.PrefRowView
import com.projectfocus.service.focus.session.SessionState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.projectfocus.feature.profile.R as ProfileR
import com.projectfocus.core.ui.R as UiR

/** Native page rendering, real selections and saved preferences in the normal application. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [34], qualifiers = "zh-rCN-w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeSkyWindowTest {
    private lateinit var app: ProjectFocusApp
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var records: RecordCloudHttpFixture

    @Before fun open() {
        app = ApplicationProvider.getApplicationContext()
        if (app.resources.configuration.screenWidthDp <= 320) RuntimeEnvironment.setFontScale(1.6f)
        runBlocking(Dispatchers.IO) { withTimeout(20_000) { app.awaitInitialAccountRestore(); app.awaitInitialAirportSeed() } }
        records = RecordCloudHttpFixture(app.recordCloudMutations, app.recordCloudSync)
        runBlocking(Dispatchers.IO) { TokenStore.setCancellable("home-design-window-account") }
        app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, android.content.Context.MODE_PRIVATE).edit()
            .clear().putString("origin_code", "PEK").putString("destination_code", "TSN").commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        activity = controller.get()
        activity.supportFragmentManager.executePendingTransactions()
        host().navController.navigate(R.id.action_permission_to_home)
        host().childFragmentManager.executePendingTransactions()
        await("Actual airport snapshots on Home") {
            activity.findViewById<TextView>(R.id.homeOriginCode)?.text?.toString() == "PEK" &&
                activity.findViewById<TextView>(R.id.homeDestinationCode)?.text?.toString() == "TSN"
        }
    }

    @After fun close() {
        try {
            if (::controller.isInitialized) controller.pause().stop().destroy()
            TokenStore.clear()
        } finally {
            try { shutdownRealApplicationAfterTest(app) }
            finally { if (::records.isInitialized) records.close() }
        }
    }

    @Test fun realAirportChoicesAndEmptyStateNeverInventAFlight() {
        assertEquals(SessionState.Idle, activity.sessionRepository.state.value)
        assertTrue(activity.findViewById<View>(R.id.homeOrigin).contentDescription.contains("PEK"))
        assertTrue(activity.findViewById<View>(R.id.homeDestination).contentDescription.contains("TSN"))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, activity.findViewById<View>(R.id.homeFlightHero).importantForAccessibility)
        assertReadableHeading()
        capture("home-day-selected")
        app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        await("Selection cleared") { activity.findViewById<TextView>(R.id.homeOriginCode).text.toString() == "—" }
        assertEquals("—", activity.findViewById<TextView>(R.id.homeDestinationCode).text.toString())
        assertEquals(activity.getString(R.string.home_action_search_airport), activity.findViewById<TextView>(R.id.btnStartFlight).text.toString())
        assertEquals(SessionState.Idle, activity.sessionRepository.state.value)
        assertTrue(runBlocking(Dispatchers.IO) { app.database.flightHistoryDao().getAll().isEmpty() })
        capture("home-day-empty")
        activity.findViewById<View>(R.id.homeOrigin).performClick()
        await("Actual airport picker") { host().navController.currentDestination?.id == R.id.airportSearchFragment }
        val input = activity.findViewById<android.widget.EditText>(com.projectfocus.feature.airport.R.id.searchInput)
        input.setText("PEK")
        await("PEK from the packaged airport catalogue") {
            activity.findViewById<androidx.recyclerview.widget.RecyclerView>(com.projectfocus.feature.airport.R.id.resultList)?.let { list ->
                walk(list).filterIsInstance<TextView>().any {
                    it.id == com.projectfocus.feature.airport.R.id.pf_airport_airport_code && it.text.toString() == "PEK"
                }
            } == true
        }
        capture("airport-actual-catalogue-day", "ui-airport")
        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(com.projectfocus.feature.airport.R.id.resultList)
        val row = walk(list).filterIsInstance<com.projectfocus.feature.airport.ui.AirportRowView>().first {
            it.findViewById<TextView>(com.projectfocus.feature.airport.R.id.pf_airport_airport_code).text.toString() == "PEK"
        }
        assertTrue(row.performClick())
        await("Actual airport selection returned to Home") {
            host().navController.currentDestination?.id == R.id.homeFragment &&
                activity.findViewById<TextView>(R.id.homeOriginCode)?.text?.toString() == "PEK"
        }
        assertEquals(SessionState.Idle, activity.sessionRepository.state.value)
    }

    @Test fun mapPreviewReturnsToTheSamePreparationWithoutStartingAFlight() {
        activity.findViewById<View>(R.id.menuMap).performClick()
        await("Map preview") { activity.findViewById<View>(R.id.homeMapClose).isShown }
        assertEquals(View.GONE, activity.findViewById<View>(R.id.groundHomeContent).visibility)
        capture("home-map-unavailable")
        activity.onBackPressedDispatcher.onBackPressed()
        await("Map back to Home") { activity.findViewById<View>(R.id.groundHomeContent).isShown }
        assertEquals(View.GONE, activity.findViewById<View>(R.id.mapStyleSelectorContainer).visibility)
        assertEquals("PEK", activity.findViewById<TextView>(R.id.homeOriginCode).text.toString())
        assertEquals(SessionState.Idle, activity.sessionRepository.state.value)
    }

    @Test fun settingsRemainReadableAndChangesPersistAcrossNormalNavigation() {
        openSettings()
        val row = walk(activity.window.decorView).filterIsInstance<PrefRowView>().first { it.tag == PrefKey.WhiteNoiseEnabled }
        val switch = walk(row).filterIsInstance<MaterialSwitch>().single()
        await("Preference row is interactive") { row.isEnabled }
        val before = switch.isChecked
        assertTrue(row.performClick())
        await("Preference saved") { runBlocking(Dispatchers.IO) { activity.settingsRepository.observe(PrefKey.WhiteNoiseEnabled).first() } != before }
        capture("settings-day")
        activity.findViewById<View>(ProfileR.id.btn_back).performClick()
        await("Settings back to Home") { host().navController.currentDestination?.id == R.id.homeFragment }
        openSettings()
        val reopened = walk(activity.window.decorView).filterIsInstance<PrefRowView>().first { it.tag == PrefKey.WhiteNoiseEnabled }
        assertEquals(!before, walk(reopened).filterIsInstance<MaterialSwitch>().single().isChecked)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w432dp-h960dp-night-mdpi")
    fun nightMaterialsKeepReadableTextAndSettingsControls() {
        assertTrue(ColorUtils.calculateContrast(activity.getColor(UiR.color.sky_ink), activity.getColor(UiR.color.sky_canvas)) > 7)
        assertTrue(ColorUtils.calculateContrast(activity.getColor(UiR.color.sky_ink_secondary), activity.getColor(UiR.color.sky_card_solid)) > 4.5)
        capture("home-night-selected")
        openSettings()
        capture("settings-night")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    fun smallWindowAndLargeTextKeepTheActionReachableAboveTheDock() {
        advance(250)
        val scroll = activity.findViewById<NestedScrollView>(R.id.homeScroll)
        val action = activity.findViewById<View>(R.id.btnStartFlight)
        assertReadableHeading()
        capture("home-small-large-text-top")
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        advance(50)
        val actionRect = Rect(); val dockRect = Rect()
        assertTrue(action.getGlobalVisibleRect(actionRect))
        assertTrue(activity.findViewById<View>(R.id.right_menu_container).getGlobalVisibleRect(dockRect))
        assertTrue("Action must not be covered by navigation", actionRect.bottom <= dockRect.top)
        assertTrue(actionRect.height() >= 48)
        capture("home-small-large-text")
        openSettings()
        val segments = walk(activity.window.decorView)
            .filterIsInstance<com.projectfocus.feature.profile.ui.SegmentedControlView>().first()
        for (index in 0 until segments.childCount) {
            val label = segments.getChildAt(index) as TextView
            val layout = requireNotNull(label.layout)
            assertEquals(label.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
            assertTrue(label.height >= layout.height)
        }
        capture("settings-small-large-text")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h400dp-land-mdpi")
    fun wideWindowCentersThePageAndStillScrollsToTheAction() {
        val page = activity.findViewById<View>(R.id.homePageContent)
        assertEquals(560, page.width)
        capture("home-wide-top")
        val scroll = activity.findViewById<NestedScrollView>(R.id.homeScroll)
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        advance(50)
        assertTrue(activity.findViewById<View>(R.id.btnStartFlight).getGlobalVisibleRect(Rect()))
        capture("home-wide-action")
    }

    private fun assertReadableHeading() {
        val heading = activity.findViewById<TextView>(R.id.homeHeading)
        val layout = requireNotNull(heading.layout)
        assertTrue(layout.lineCount >= 2)
        for (line in 0 until layout.lineCount - 1) {
            val spacing = layout.getLineBaseline(line + 1) - layout.getLineBaseline(line)
            assertTrue("Title lines overlap at font size ${heading.textSize}: $spacing", spacing >= heading.textSize * 1.25f)
        }
        assertTrue(heading.height >= layout.height)
    }

    private fun openSettings() {
        activity.findViewById<View>(R.id.menuSettings).performClick()
        await("Actual settings rows") { activity.findViewById<View>(ProfileR.id.btn_back)?.isShown == true &&
            walk(activity.window.decorView).filterIsInstance<PrefRowView>().any { it.tag == PrefKey.WhiteNoiseEnabled } }
        advance(150)
    }

    @Test fun systemAreasProtectHomeMapCloseAndSettingsWithoutAccumulatingOffsets() {
        val container = activity.findViewById<View>(R.id.nav_host_container)
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(0, 36, 0, 28))
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout(), androidx.core.graphics.Insets.of(48, 0, 0, 0))
            .build()
        repeat(3) { androidx.core.view.ViewCompat.dispatchApplyWindowInsets(container, insets); advance(32) }
        assertEquals(0, container.paddingTop)
        val action = Rect()
        assertTrue(activity.findViewById<View>(R.id.btnFlightPlan).getGlobalVisibleRect(action))
        assertTrue(action.top >= 36 && action.left >= 48)
        val dock = Rect()
        assertTrue(activity.findViewById<View>(R.id.right_menu_container).getGlobalVisibleRect(dock))
        assertTrue(dock.left >= 48 && dock.bottom <= container.height - 28)
        val backdrop = activity.findViewById<View>(R.id.homeSkyBackdrop)
        assertEquals(container.width, backdrop.width)
        assertEquals(container.height, backdrop.height)
        assertEquals(0, backdrop.left + (backdrop.parent as View).left)
        capture("home-system-areas", "ui-insets")
        activity.findViewById<View>(R.id.menuMap).performClick()
        advance(50)
        val close = Rect()
        assertTrue(activity.findViewById<View>(R.id.homeMapClose).getGlobalVisibleRect(close))
        assertTrue(close.top >= 36 && close.left >= 48)
        activity.findViewById<View>(R.id.homeMapClose).performClick()
        openSettings()
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(container, insets)
        advance(32)
        assertEquals(36, container.paddingTop)
        assertEquals(28, container.paddingBottom)
        capture("settings-system-areas", "ui-insets")
        activity.findViewById<View>(ProfileR.id.btn_back).performClick()
        await("Home after protected settings") { activity.findViewById<View>(R.id.homeOrigin)?.isShown == true }
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(container, insets)
        advance(32)
        assertTrue(activity.findViewById<View>(R.id.btnFlightPlan).getGlobalVisibleRect(action))
        assertTrue(action.top >= 36 && action.left >= 48)
        assertEquals(SessionState.Idle, activity.sessionRepository.state.value)
    }
    private fun host() = activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
    private fun walk(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(walk(view.getChildAt(index)))
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    private fun await(label: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) { check(System.nanoTime() < until) { "Timed out: $label" }; advance(16); Thread.sleep(3) }
        advance(32)
    }
    private fun capture(name: String, folder: String = "ui-redesign") {
        advance(32)
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("build/reports/$folder/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
