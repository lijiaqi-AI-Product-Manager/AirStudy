package com.projectfocus.feature.stats

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.HistoryAccount
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.projectfocus.core.ui.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="zh-rCN-w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsHistoryStateNativeTest {
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private var controller: org.robolectric.android.controller.ActivityController<Activity>?=null
    private lateinit var account: HistoryAccount
    private val date=LocalDate.of(2026,11,10)
    private val zone=ZoneId.of("UTC")
    private val wall=date.atStartOfDay(zone).toInstant().toEpochMilli()
    @Before fun open() {
        TokenStore.set("historical-statistics-native")
        account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation)
        controller=Robolectric.buildActivity(Activity::class.java).also { it.get().setTheme(UiR.style.Theme_Pf) }
        activity=controller!!.setup().visible().get()
        root=FrameLayout(activity).apply { setPadding(16,12,16,12);setBackgroundResource(UiR.color.stats_background) }
        activity.setContentView(root)
    }
    @After fun close() { controller?.pause()?.stop()?.destroy();TokenStore.clear() }
    private fun state()=StatsUiState(account=account,isLoading=false,dates=StatsDateSelection(date,StatsPeriod.DAY),
        dateRange=StatsDateSelection(date,StatsPeriod.DAY).range(zone),nowWallMs=wall,overview=StatsOverviewMetrics(5.5,2,1,3.5,1))
    private fun show(view: View) {
        root.removeAllViews();root.addView(view,FrameLayout.LayoutParams(-1,-2));layout()
    }
    private fun layout() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
        root.measure(View.MeasureSpec.makeMeasureSpec(432,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.AT_MOST))
        root.layout(0,0,432,root.measuredHeight)
    }
    private fun capture(name: String) {
        layout();val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        File("build/reports/statistics/$name.png").apply { parentFile.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }
    @Test fun missingFocusCancelsOldAnimationsWhileKnownCountsStayVisibleAndCanRecover() {
        val view=StatsHeadlineView(activity);show(view)
        val complete=state();view.bind(complete,zone);layout()
        val partial=complete.copy(coverage=StatsCoverage(periodFocus=false,comparison=false))
        view.bind(partial,zone);layout()
        assertEquals("—",view.focus.text.toString());assertEquals("2",view.metrics[0].text.toString())
        assertEquals("1",view.metrics[1].text.toString());assertEquals("—",view.metrics[2].text.toString())
        assertFalse(view.focus.isAnimating);assertEquals("—",view.comparisonLabel.text.toString())
        capture("statistics-history-partial-headline")
        view.bind(complete,zone);layout();assertFalse(view.focus.text.toString()=="—")
        TokenStore.set("historical-statistics-native-next")
        val switched=Bitmap.createBitmap(432,960,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(switched));switched.recycle()
        assertEquals("—",view.focus.text.toString())
    }
    @Test fun sceneTotalsCanShowAllTimeWhenTheSelectedPeriodsFactsAreIncomplete() {
        val panel=StatsScenePanel(activity);show(panel)
        val scene=StatsSceneEntry("阅读","reading","reading",5.5,2)
        val partial=state().copy(periodScenes=listOf(scene),allTimeScenes=listOf(scene),coverage=StatsCoverage(periodScenes=false))
        panel.bind(partial);layout()
        assertEquals(View.GONE,panel.chart.visibility);assertTrue(panel.displayedRows.isEmpty())
        panel.toggle.performClick();layout()
        assertTrue(panel.allTime);assertEquals(View.VISIBLE,panel.chart.visibility);assertEquals(listOf(scene),panel.displayedRows)
        TokenStore.set("historical-statistics-native-next");panel.toggle.performClick()
        assertTrue(panel.allTime)
    }
    @Test fun switchingTrendMetricsNeverDrawsUnknownFocusAsAZeroSeries() {
        val panel=StatsTrendPanel(activity);show(panel)
        val partial=state().copy(trend=StatsTrendSeries(StatsTrendGranularity.HOUR,listOf(StatsTrendBucket(wall,wall+3_600_000,2,0.0,emptyList()))),
            coverage=StatsCoverage(periodFocus=false))
        panel.bind(partial,zone);layout()
        assertEquals(View.VISIBLE,panel.completedChart.visibility)
        panel.metricButtons[StatsTrendMetric.FOCUS.ordinal].performClick();layout()
        assertEquals("—",panel.totalText.text.toString());assertEquals("—",panel.peakText.text.toString())
        assertEquals(View.GONE,panel.focusChart.visibility);assertEquals(View.VISIBLE,panel.incompleteLabel.visibility)
        assertEquals(View.GONE,(panel.focusScroll.parent as View).visibility)
        assertFalse(panel.lineButton.isEnabled);assertFalse(panel.barButton.isEnabled)
        capture("statistics-history-partial-trend")
        panel.metricButtons[StatsTrendMetric.COMPLETED.ordinal].performClick();layout()
        assertTrue(panel.totalText.text.toString().contains("2"));assertEquals(View.GONE,panel.incompleteLabel.visibility)
        assertEquals(View.VISIBLE,(panel.completedScroll.parent as View).visibility)
        assertTrue(panel.lineButton.isEnabled);assertTrue(panel.barButton.isEnabled)
    }
}
