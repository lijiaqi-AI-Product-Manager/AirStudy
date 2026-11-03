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
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.stats.StatsDateControls
import com.projectfocus.feature.stats.StatsDateRangeDialog
import com.projectfocus.feature.stats.StatsPeriod
import com.projectfocus.feature.stats.StatsFragment
import com.projectfocus.feature.stats.StatsHeadlineView
import com.projectfocus.feature.stats.StatsScenePanel
import com.projectfocus.feature.stats.StatsTrendPanel
import com.projectfocus.feature.stats.StatsTrendMetric
import com.projectfocus.feature.stats.StatsTrendMode
import com.projectfocus.feature.stats.StatsViewModel
import com.projectfocus.feature.stats.StatsHeatmapPanel
import com.projectfocus.feature.stats.StatsCareerPanel
import com.projectfocus.feature.stats.StatsHourPanel
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
    @Test fun hourHabitsUseRealAccountRecordsAndFollowSceneAndDateChanges() {
        launch()
        fun panel()=activity.findViewById<StatsHourPanel>(StatsR.id.statsHourPanel)
        fun model(): StatsViewModel {
            val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
            return ViewModelProvider(host.childFragmentManager.primaryNavigationFragment as StatsFragment)[StatsViewModel::class.java]
        }
        controls().periodButtons[StatsPeriod.DAY.ordinal].performClick()
        await { count()==expected(2) && model().state.value.hourHabits!=null }
        assertEquals(6.5+1.0/60_000,model().state.value.hourHabits!!.minutes[1],1e-10)
        assertEquals(1,model().state.value.hourHabits!!.activeHours)
        activity.findViewById<StatsScenePanel>(StatsR.id.statsScenePanel).legend.findViewWithTag<View>("阅读").performClick()
        await { count()==expected(1) && model().state.value.selectedSceneKey.isNotEmpty() }
        assertEquals(1.5+1.0/60_000,model().state.value.hourHabits!!.minutes[1],1e-10)
        scenario!!.recreate();scenario!!.onActivity { activity=it }
        await { count()==expected(1) && panel().chart.visibility==View.VISIBLE && model().state.value.hourHabits!!.activeHours==1 }
        val scroll=generateSequence(panel().parent) { it.parent }.filterIsInstance<android.widget.ScrollView>().first()
        layout();scroll.scrollTo(0,panel().top);idle();capture("statistics-actual-hours-scene")
        val prior=model().state.value.account
        val nextToken="statistics-hour-route-next-owner"
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(legacy("hour-next-owner",wall+2*3_600_000,TokenStore.storageScope(TokenStore.Snapshot(nextToken,0))))
            TokenStore.setCancellable(nextToken)
        }
        await { model().state.value.account!=prior && model().state.value.hourHabits?.minutes?.get(3)==5.0 && !model().state.value.isLoading }
        assertEquals(0.0,model().state.value.hourHabits!!.minutes[1],0.0)
        assertTrue(model().state.value.selectedSceneKey.isEmpty())
    }

    @Test fun careerUsesAllSuccessfulAccountRecordsAcrossDateAndSceneChanges() {
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(legacy("career-outside-current-range",today.minusYears(2).atStartOfDay(zone).toInstant().toEpochMilli()))
        }
        launch()
        fun panel()=activity.findViewById<StatsCareerPanel>(StatsR.id.statsCareerPanel)
        fun model(): StatsViewModel {
            val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
            return ViewModelProvider(host.childFragmentManager.primaryNavigationFragment as StatsFragment)[StatsViewModel::class.java]
        }
        await { model().state.value.career!=null && !model().state.value.isLoading && panel().countryRows.childCount==1 }
        val initial=model().state.value.career!!
        assertEquals(4,initial.completedFlights)
        assertNull(initial.onTimeRate)
        controls().periodButtons[StatsPeriod.DAY.ordinal].performClick()
        await { count()==expected(2) && model().state.value.dates!!.period==StatsPeriod.DAY }
        assertEquals(initial,model().state.value.career)
        activity.findViewById<StatsScenePanel>(StatsR.id.statsScenePanel).legend.findViewWithTag<View>("阅读").performClick()
        await { count()==expected(1) && model().state.value.selectedSceneKey.isNotEmpty() }
        assertEquals(initial,model().state.value.career)
        scenario!!.recreate();scenario!!.onActivity { activity=it }
        await { count()==expected(1) && panel().countryRows.childCount==1 && !model().state.value.isLoading }
        assertEquals(initial,model().state.value.career)
        val scroll=generateSequence(panel().parent) { it.parent }.filterIsInstance<android.widget.ScrollView>().first()
        layout();scroll.scrollTo(0,panel().top);idle()
        capture("statistics-actual-career-scene")
        val oldRow=panel().countryRows.getChildAt(0)
        val nextToken="statistics-career-route-next-owner"
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(legacy("career-next-owner",wall,TokenStore.storageScope(TokenStore.Snapshot(nextToken,0))))
            TokenStore.setCancellable(nextToken)
        }
        await { model().state.value.career?.completedFlights==1 && model().state.value.selectedSceneKey.isEmpty() && panel().countryRows.childCount==1 }
        oldRow.performClick();idle()
        val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        val page=host.childFragmentManager.primaryNavigationFragment as StatsFragment
        assertTrue(page.childFragmentManager.fragments.none { it is com.projectfocus.feature.stats.CountryAirportsDialogFragment })
        assertEquals(99.25,model().state.value.career!!.distanceKm,0.0)
    }

    @Test fun annualHeatmapKeepsItsOwnYearAndUsesRealFilteredAccountRecords() {
        launch()
        fun heatmap()=activity.findViewById<StatsHeatmapPanel>(StatsR.id.statsHeatmapPanel)
        fun model(): StatsViewModel {
            val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
            return ViewModelProvider(host.childFragmentManager.primaryNavigationFragment as StatsFragment)[StatsViewModel::class.java]
        }
        val year=today.year
        await { model().state.value.annualHeatmap!=null && !model().state.value.isLoading && count()!=null && heatmap().yearLabel.text.contains(year.toString()) }
        val originalDates=model().state.value.dates
        val originalRevision=model().state.value.queryRevision
        val originalCount=count()
        assertEquals(6.5+1.0/60_000,model().state.value.annualHeatmap!!.minutes(today),1e-10)
        assertFalse(heatmap().next.isEnabled)
        heatmap().previous.performClick()
        await { model().state.value.heatmapYear==year-1 && heatmap().yearLabel.text.contains((year-1).toString()) }
        assertEquals(originalDates,model().state.value.dates)
        assertEquals(originalRevision,model().state.value.queryRevision)
        assertEquals(originalCount,count())
        val obsoleteYearState=model().state.value
        val obsoleteYearAction=heatmap().onYearRequested!!
        heatmap().next.performClick()
        await { model().state.value.heatmapYear==year && heatmap().yearLabel.text.contains(year.toString()) }
        obsoleteYearAction(-1,obsoleteYearState);idle()
        assertEquals(year,model().state.value.heatmapYear)
        activity.findViewById<StatsScenePanel>(StatsR.id.statsScenePanel).legend.findViewWithTag<View>("阅读").performClick()
        await { count()==expected(1) && model().state.value.selectedSceneKey.isNotEmpty() }
        assertEquals(1.5+1.0/60_000,model().state.value.annualHeatmap!!.minutes(today),1e-10)
        heatmap().previous.performClick()
        await { model().state.value.heatmapYear==year-1 && heatmap().scroll.visibility==View.GONE }
        assertTrue(model().state.value.annualHeatmap!!.days.isEmpty())
        scenario!!.recreate();scenario!!.onActivity { activity=it }
        await { count()==expected(1) && heatmap().yearLabel.text.contains((year-1).toString()) && controls().sceneFilter.visibility==View.VISIBLE }
        assertEquals(year-1,model().state.value.heatmapYear)
        controls().periodButtons[StatsPeriod.DAY.ordinal].performClick()
        await { count()==expected(1) && model().state.value.heatmapYear==year && heatmap().scroll.visibility==View.VISIBLE }
        val scroll=generateSequence(heatmap().parent) { it.parent }.filterIsInstance<android.widget.ScrollView>().first()
        layout();scroll.scrollTo(0,heatmap().top);idle()
        capture("statistics-actual-annual-scene")
        scroll.scrollTo(0,0);layout()
        val pending=custom()
        val dateRevision=model().state.value.queryRevision
        heatmap().onYearRequested!!(-1,model().state.value)
        await { model().state.value.heatmapYear==year-1 }
        assertEquals(dateRevision,model().state.value.queryRevision)
        assertTrue(pending.isShowing)
        pending.dismiss()
        heatmap().next.performClick()
        await { model().state.value.heatmapYear==year }
        val oldAccountState=model().state.value
        val oldAccountAction=heatmap().onYearRequested!!
        val nextToken="statistics-annual-route-owner-two"
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(legacy("annual-next-owner",wall,TokenStore.storageScope(TokenStore.Snapshot(nextToken,0))))
            TokenStore.setCancellable(nextToken)
        }
        await { count()==expected(1) && model().state.value.selectedSceneKey.isEmpty() &&
            model().state.value.annualHeatmap?.minutes(today)==5.0 && model().state.value.heatmapYear==year }
        oldAccountAction(-1,oldAccountState);idle()
        assertEquals(year,model().state.value.heatmapYear)
        val backgroundAction=heatmap().onYearRequested!!
        val backgroundState=model().state.value
        scenario!!.moveToState(Lifecycle.State.CREATED)
        backgroundAction(-1,backgroundState);idle()
        assertEquals(year,model().state.value.heatmapYear)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        await { model().state.value.heatmapYear==year && !model().state.value.isLoading }
    }

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
        null,"12A",false,false,false,null,false,at,at,ownerScope=owner,
        sceneSnapshotJson=PlannedFlightMetadataCodec.encodeScene(PlannedScene("statistics-study","","study",0,false)))
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
    private fun count(): String? = if(activity.findViewById<StatsHeadlineView>(StatsR.id.statsHeadline)?.isAnimating==true)null
        else activity.findViewById<TextView>(StatsR.id.statsFlights)?.text?.toString()
    private fun expected(count: Int)=count.toString()
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
        assertEquals("7m",activity.findViewById<TextView>(StatsR.id.statsFocus).text.toString())
        assertEquals("1",activity.findViewById<TextView>(StatsR.id.statsActiveDays).text.toString())
        assertEquals("5m",activity.findViewById<TextView>(StatsR.id.statsLongestSession).text.toString())
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
        assertEquals("5m",activity.findViewById<TextView>(StatsR.id.statsFocus).text.toString())
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
            assertEquals(activity.getString(StatsR.string.stats_placeholder),activity.findViewById<StatsTrendPanel>(StatsR.id.statsTrendPanel).totalText.text.toString())
            assertEquals(activity.getString(StatsR.string.stats_heatmap_unavailable),activity.findViewById<StatsHeatmapPanel>(StatsR.id.statsHeatmapPanel).emptyLabel.text.toString())
            assertNull(model.state.value.annualHeatmap)
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

    @Test fun sceneSelectionSurvivesDatesAndRecreationThenClearsForANewAccount() {
        launch()
        controls().periodButtons[StatsPeriod.DAY.ordinal].performClick()
        await { count()==expected(2) && controls().periodButtons[StatsPeriod.DAY.ordinal].isSelected }
        fun panel()=activity.findViewById<StatsScenePanel>(StatsR.id.statsScenePanel)
        fun reading()=panel().legend.findViewWithTag<View>("阅读")
        await { reading()!=null }
        reading().performClick()
        await { count()==expected(1) && controls().sceneFilter.visibility==View.VISIBLE }
        assertEquals("阅读",controls().sceneFilterLabel.text.toString())
        assertEquals("2m",activity.findViewById<TextView>(StatsR.id.statsFocus).text.toString())
        assertEquals(2,(0 until panel().legend.childCount).count { panel().legend.getChildAt(it) is android.widget.LinearLayout })
        reading().performClick()
        await { count()==expected(2) && controls().sceneFilter.visibility==View.GONE }
        reading().performClick()
        await { count()==expected(1) && controls().sceneFilter.visibility==View.VISIBLE }
        panel().toggle.performClick()
        await { panel().allTime }
        assertEquals(expected(1),count())
        val staleRow=reading()
        scenario!!.recreate()
        scenario!!.onActivity { activity=it }
        await { count()==expected(1) && controls().sceneFilterLabel.text.toString()=="阅读" }
        staleRow.performClick();idle()
        assertEquals(View.VISIBLE,controls().sceneFilter.visibility)
        controls().previous.performClick()
        await { count()==expected(0) && controls().sceneFilter.visibility==View.VISIBLE && controls().next.isEnabled }
        controls().sceneFilter.performClick()
        await { count()==expected(1) && controls().sceneFilter.visibility==View.GONE }
        controls().next.performClick()
        await { count()==expected(2) }
        reading().performClick()
        await { count()==expected(1) && controls().sceneFilter.visibility==View.VISIBLE }
        capture("statistics-actual-scene-filter")
        val oldRow=reading()
        val oldClear=controls().sceneFilter
        val nextToken="statistics-scene-owner-two"
        runBlocking(Dispatchers.IO) {
            app.database.flightHistoryDao().insert(legacy("next-scene-owner",wall,TokenStore.storageScope(TokenStore.Snapshot(nextToken,0))))
            TokenStore.setCancellable(nextToken)
        }
        await { count()==expected(1) && controls().sceneFilter.visibility==View.GONE && controls().periodButtons[StatsPeriod.WEEK.ordinal].isSelected }
        oldRow.performClick();oldClear.performClick();idle()
        assertEquals(expected(1),count())
        assertEquals("5m",activity.findViewById<TextView>(StatsR.id.statsFocus).text.toString())
        assertEquals(View.GONE,controls().sceneFilter.visibility)
    }

    @Test fun trendModesUseRealDualModeRecordsAndKeepDatesAndSceneQueriesIndependent() {
        launch()
        fun panel()=activity.findViewById<StatsTrendPanel>(StatsR.id.statsTrendPanel)
        controls().periodButtons[StatsPeriod.DAY.ordinal].performClick()
        await { count()==expected(2) && !panel().isAnimating && panel().totalText.text.toString()=="2 次" }
        assertEquals("较上期 ↑ 30%",activity.findViewById<StatsHeadlineView>(StatsR.id.statsHeadline).comparisonLabel.text.toString())
        assertEquals(24,panel().activeChart.bucketCount)
        assertEquals("2 次",panel().peakText.text.toString())
        panel().metricButtons[1].performClick()
        await { panel().metric==StatsTrendMetric.FOCUS && !panel().isAnimating && panel().totalText.text.toString()=="7m" }
        panel().barButton.performClick()
        await { panel().mode==StatsTrendMode.STACKED_BAR && !panel().isAnimating }
        val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        val page=host.childFragmentManager.primaryNavigationFragment as StatsFragment
        val model=ViewModelProvider(page)[StatsViewModel::class.java]
        val revision=model.state.value.queryRevision
        val chart=panel().activeChart
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            android.view.MotionEvent.obtain(0,0,action,82f,100f,0).also { chart.onTouchEvent(it);it.recycle() }
        }
        idle()
        assertEquals(1,chart.selectedIndex)
        assertEquals(revision,model.state.value.queryRevision)
        controls().previous.performClick()
        await { count()==expected(1) && !panel().isAnimating && panel().totalText.text.toString()=="5m" }
        assertEquals(StatsTrendMetric.FOCUS,panel().metric)
        assertEquals(StatsTrendMode.STACKED_BAR,panel().mode)
        assertEquals(-1,panel().activeChart.selectedIndex)
        controls().next.performClick()
        await { count()==expected(2) && !panel().isAnimating }
        activity.findViewById<StatsScenePanel>(StatsR.id.statsScenePanel).legend.findViewWithTag<View>("阅读").performClick()
        await { count()==expected(1) && !panel().isAnimating && panel().totalText.text.toString()=="2m" }
        assertEquals("较上期 新增",activity.findViewById<StatsHeadlineView>(StatsR.id.statsHeadline).comparisonLabel.text.toString())
        panel().metricButtons[0].performClick()
        await { panel().metric==StatsTrendMetric.COMPLETED && !panel().isAnimating && panel().totalText.text.toString()=="1 次" }
        assertEquals("1 次",panel().peakText.text.toString())
        capture("statistics-actual-trend-filter")
        controls().sceneFilter.performClick()
        await { count()==expected(2) && !panel().isAnimating && panel().totalText.text.toString()=="2 次" }
        val old=panel()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        old.lineButton.performClick();idle()
        assertEquals(StatsTrendMode.STACKED_BAR,old.mode)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        await { panel().mode==StatsTrendMode.STACKED_BAR && !panel().isAnimating }
        scenario!!.recreate();scenario!!.onActivity { activity=it }
        await { count()==expected(2) && !panel().isAnimating && panel().mode==StatsTrendMode.LINE }
        old.lineButton.performClick();idle()
        assertEquals(StatsTrendMode.STACKED_BAR,old.mode)
        assertTrue(controls().periodButtons[StatsPeriod.DAY.ordinal].isSelected)
        assertEquals("2 次",panel().totalText.text.toString())
    }
}
