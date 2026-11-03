package com.projectfocus.app.route

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.app.MainActivity
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.stats.StatsDateControls
import com.projectfocus.feature.stats.StatsDateRangeDialog
import com.projectfocus.feature.stats.StatsPeriod
import com.projectfocus.feature.stats.StatsFragment
import com.projectfocus.feature.stats.StatsViewModel
import com.projectfocus.service.focus.freeflight.RoomFreeFlightArchiveStore
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import com.projectfocus.feature.stats.R as StatsR

@RunWith(RobolectricTestRunner::class)
@Config(application=ProjectFocusApp::class,sdk=[34],qualifiers="zh-rCN-w432dp-h936dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatisticsDateFlowTest {
    @get:Rule val lifetime = object : org.junit.rules.ExternalResource() {
        override fun before() { Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher()) }
        override fun after() {
            try { shutdownRealApplicationAfterTest(ApplicationProvider.getApplicationContext()) }
            finally { Dispatchers.resetMain() }
        }
    }
    private lateinit var app: ProjectFocusApp
    private lateinit var activity: MainActivity
    private var scenario: ActivityScenario<MainActivity>? = null
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val wall = today.atStartOfDay(zone).plusHours(1).toInstant().toEpochMilli()
    @Before fun open() = runBlocking(Dispatchers.IO) {
        app=ApplicationProvider.getApplicationContext()
        withTimeout(15_000) { app.awaitInitialAccountRestore(); app.awaitInitialAirportSeed() }
        TokenStore.setCancellable("statistics-route-owner-one")
        app.database.flightHistoryDao().insert(legacy("shared-id",wall))
        app.database.flightHistoryDao().insert(legacy("yesterday",today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()))
        app.database.flightHistoryDao().insert(legacy("cancelled",wall,status="CANCELLED"))
        val engine=FreeFlightEngine()
        val now=FreeFlightClockReading(0,0,wall,"statistics-route-clock")
        val start=engine.start(FreeFlightStartRequest("shared-id",TokenStore.storageScope(),
            PlannedAirport(1,"PEK","Beijing Airport","北京","CN",40.0,116.0),90.0,"01A",FreeFlightScene(3,"阅读","reading")),now)
        val at=now.copy(elapsedRealtimeMs=90_001,uptimeMillis=90_001,wallMs=wall+90_001)
        val selection=engine.prepareLanding(start,at)
        val picked=engine.selectLanding(selection,PlannedAirport(2,"TSN","Tianjin Airport","天津","CN",39.0,117.0),selection.landingSelectionGeneration)
        val complete=engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(picked,picked.landingSelectionGeneration,at),at).copy(revision=1)
        val store=RoomFreeFlightArchiveStore(app.database.freeFlightArchiveDao())
        assertTrue(store.commit(null,start,null));assertTrue(store.commit(0,complete,null))
    }
    @After fun close() { scenario?.close(); TokenStore.clear() }
    private fun legacy(id: String, at: Long, owner: String=TokenStore.storageScope(),status: String="COMPLETED") = FlightHistoryEntity(
        id,null,"SOLO",null,"PEK","Beijing Airport","北京","CN",40.0,116.0,
        "TSN","Tianjin Airport","天津","CN",39.0,117.0,at,at+300_000,25,5,99.25,status,
        null,"12A",false,false,false,null,false,at,at,ownerScope=owner)
    private fun launch() {
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { actual ->
            activity=actual
            val host=actual.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
            host.navController.navigate(Uri.parse("projectfocus://stats"))
        }
        await { activity.findViewById<StatsDateControls>(StatsR.id.statsDateControls)?.periodButtons?.firstOrNull()?.isEnabled==true }
        assertTrue(controls().rangeLabel.text.isNotBlank())
        assertEquals(activity.getString(StatsR.string.stats_period_day),controls().periodButtons[StatsPeriod.DAY.ordinal].text.toString())
    }
    private fun controls()=activity.findViewById<StatsDateControls>(StatsR.id.statsDateControls)
    private fun count()=activity.findViewById<TextView>(StatsR.id.statsFlights)?.text?.toString()
    private fun expected(count: Int)="$count ${activity.getString(StatsR.string.stats_unit_flights)}"
    private fun custom(): StatsDateRangeDialog {
        controls().periodButtons[StatsPeriod.CUSTOM.ordinal].performClick()
        await { ShadowDialog.getLatestDialog() is StatsDateRangeDialog && ShadowDialog.getLatestDialog().isShowing }
        return ShadowDialog.getLatestDialog() as StatsDateRangeDialog
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16)) }
    private fun await(condition: () -> Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(!condition() && System.nanoTime()<end) { idle();Thread.sleep(10) }
        idle();assertTrue("Statistics date state did not reach the expected page result",condition());layout()
    }
    private fun layout() {
        val root=activity.findViewById<ViewGroup>(android.R.id.content)
        val density=activity.resources.displayMetrics.density
        val width=(activity.resources.configuration.screenWidthDp*density+0.5f).toInt()
        val height=(activity.resources.configuration.screenHeightDp*density+0.5f).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        root.layout(0,0,width,height)
        idle()
    }
    private fun capture(name: String) {
        layout()
        val root=activity.findViewById<ViewGroup>(android.R.id.content)
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/statistics/$name.png").apply { parentFile.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }

    @Test fun actualDateControlsFilterBothModesAndDiscardAnOldAccountConfirmation() {
        launch()
        controls().periodButtons[StatsPeriod.DAY.ordinal].performClick()
        await { count()==expected(2) && controls().periodButtons[StatsPeriod.DAY.ordinal].isSelected }
        assertFalse(controls().next.isEnabled)
        controls().previous.performClick()
        await { count()==expected(1) && controls().next.isEnabled }
        controls().next.performClick()
        await { count()==expected(2) && !controls().next.isEnabled }
        controls().periodButtons[StatsPeriod.MONTH.ordinal].performClick()
        await { controls().periodButtons[StatsPeriod.MONTH.ordinal].isSelected }
        val original=count()
        val cancelled=custom()
        assertTrue(controls().periodButtons[StatsPeriod.MONTH.ordinal].isSelected)
        cancelled.window!!.decorView.findViewWithTag<View>("stats-date-cancel").performClick()
        assertEquals(original,count())
        val selected=custom()
        selected.window!!.decorView.findViewWithTag<View>("stats-date:$today").performClick()
        selected.window!!.decorView.findViewWithTag<View>("stats-date-confirm").performClick()
        await { controls().periodButtons[StatsPeriod.CUSTOM.ordinal].isSelected && count()==expected(2) }
        assertEquals(View.INVISIBLE,controls().previous.visibility)
        capture("statistics-actual-custom-day")
        val obsolete=custom()
        val staleConfirm=obsolete.window!!.decorView.findViewWithTag<View>("stats-date-confirm")
        val nextToken="statistics-route-owner-two"
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(legacy("other-account",wall,TokenStore.storageScope(TokenStore.Snapshot(nextToken,0))))
            TokenStore.setCancellable(nextToken)
        }
        await { !obsolete.isShowing && count()==expected(1) && controls().periodButtons[StatsPeriod.WEEK.ordinal].isSelected }
        staleConfirm.performClick()
        idle()
        assertTrue(controls().periodButtons[StatsPeriod.WEEK.ordinal].isSelected)
        assertEquals(expected(1),count())
    }

    @Test fun recreationRetainsTheDateQueryAndBackgroundCancelsAnUnconfirmedDraft() {
        launch()
        controls().periodButtons[StatsPeriod.DAY.ordinal].performClick()
        await { count()==expected(2) && controls().periodButtons[StatsPeriod.DAY.ordinal].isSelected }
        val oldControls=controls()
        scenario!!.recreate()
        scenario!!.onActivity { activity=it }
        await { count()==expected(2) && controls().periodButtons[StatsPeriod.DAY.ordinal].isSelected }
        oldControls.periodButtons[StatsPeriod.MONTH.ordinal].performClick()
        idle()
        assertTrue(controls().periodButtons[StatsPeriod.DAY.ordinal].isSelected)
        val pending=custom()
        val backgroundControls=controls()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        idle()
        assertFalse(pending.isShowing)
        backgroundControls.periodButtons[StatsPeriod.MONTH.ordinal].performClick()
        idle()
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        await { controls().periodButtons[StatsPeriod.DAY.ordinal].isSelected && count()==expected(2) }
    }

    @Test fun anActualReadFailureShowsRetryAndANewPeriodReloadsAfterRecovery() {
        launch()
        controls().periodButtons[StatsPeriod.DAY.ordinal].performClick()
        await { count()==expected(2) && controls().periodButtons[StatsPeriod.DAY.ordinal].isSelected }
        val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        val page=host.childFragmentManager.primaryNavigationFragment as StatsFragment
        val model=ViewModelProvider(page)[StatsViewModel::class.java]
        var renamed=false
        try {
            runBlocking(Dispatchers.IO) {
                app.database.openHelper.writableDatabase.execSQL("ALTER TABLE flight_history RENAME TO statistics_unavailable_history")
            }
            renamed=true
            model.retry()
            await {
                val message=activity.findViewById<TextView>(StatsR.id.statsCountryEmpty)
                model.state.value.readFailed && message?.visibility==View.VISIBLE &&
                    message.text.toString()==activity.getString(StatsR.string.stats_read_failed)
            }
            assertEquals(activity.getString(StatsR.string.stats_placeholder),count())
            assertFalse(activity.findViewById<View>(StatsR.id.statsShare).isEnabled)
            runBlocking(Dispatchers.IO) {
                app.database.openHelper.writableDatabase.execSQL("ALTER TABLE statistics_unavailable_history RENAME TO flight_history")
            }
            renamed=false
            controls().periodButtons[StatsPeriod.MONTH.ordinal].performClick()
            await { controls().periodButtons[StatsPeriod.MONTH.ordinal].isSelected && model.state.value.readFailed.not() && model.state.value.isLoading.not() }
            assertTrue(activity.findViewById<View>(StatsR.id.statsShare).isEnabled)
            assertNotEquals(activity.getString(StatsR.string.stats_placeholder),count())
        } finally {
            if (renamed) runBlocking(Dispatchers.IO) {
                app.database.openHelper.writableDatabase.execSQL("ALTER TABLE statistics_unavailable_history RENAME TO flight_history")
            }
        }
    }
}
