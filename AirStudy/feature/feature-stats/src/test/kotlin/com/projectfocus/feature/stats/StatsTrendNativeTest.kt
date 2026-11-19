package com.projectfocus.feature.stats

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.HistoryAccount
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
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
class StatsTrendNativeTest {
    private var controller: org.robolectric.android.controller.ActivityController<Activity>?=null
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var panel: StatsTrendPanel
    private val zone=ZoneId.of("Asia/Shanghai")
    private val day=LocalDate.parse("2026-11-03")
    @Before fun open() {
        TokenStore.set("statistics-trend-render-owner")
        controller=Robolectric.buildActivity(Activity::class.java).also { it.get().setTheme(UiR.style.Theme_Pf) }
        activity=controller!!.setup().visible().get()
        panel=StatsTrendPanel(activity)
        root=FrameLayout(activity).apply {
            setBackgroundResource(UiR.color.stats_background);setPadding(16,12,16,12)
            addView(panel,FrameLayout.LayoutParams(-1,-2))
        }
        activity.setContentView(root)
        layout()
    }
    @After fun close() { controller?.pause()?.stop()?.destroy();TokenStore.clear() }
    private fun idle(ms: Long=1500) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun layout() {
        val width=activity.resources.configuration.screenWidthDp
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.AT_MOST))
            root.layout(0,0,width,root.measuredHeight);idle()
        }
    }
    private fun series(dates: StatsDateSelection=StatsDateSelection(day)) : StatsTrendSeries {
        val range=dates.range(zone)
        val count=if(dates.period==StatsPeriod.YEAR)12 else 7
        val start=range.firstDate
        val buckets=(0 until count).map { index ->
            val first=if(count==12)start.plusMonths(index.toLong()) else start.plusDays(index.toLong())
            val last=if(count==12)first.plusMonths(1) else first.plusDays(1)
            val scenes=when(index) {
                0 -> listOf(StatsSceneEntry("reading","reading","reading",60.4,1),StatsSceneEntry("study","study","study",35.1,1))
                1 -> listOf(StatsSceneEntry("work","work","work",30.49,1))
                else -> emptyList()
            }
            StatsTrendBucket(first.atStartOfDay(zone).toInstant().toEpochMilli(),last.atStartOfDay(zone).toInstant().toEpochMilli(),
                scenes.sumOf { it.completedFlights },scenes.sumOf { it.focusMinutes },scenes)
        }
        return StatsTrendSeries(if(count==12)StatsTrendGranularity.MONTH else StatsTrendGranularity.DAY,buckets)
    }
    private fun state(dates: StatsDateSelection=StatsDateSelection(day),data: StatsTrendSeries=series(dates))=StatsUiState(
        isLoading=false,account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation),
        dates=dates,dateRange=dates.range(zone),nowWallMs=day.atStartOfDay(zone).toInstant().toEpochMilli(),trend=data,
        overview=StatsOverviewMetrics(data.buckets.sumOf { it.focusMinutes },data.buckets.sumOf { it.completedFlights },2,data.peak(StatsTrendMetric.FOCUS),1))
    private fun bind(state: StatsUiState=state()) { panel.bind(state,zone);layout();assertFalse(panel.isAnimating) }
    private fun capture(name: String) {
        measureWithoutAdvancingTime()
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/statistics/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }
    private fun measureWithoutAdvancingTime() {
        val width=activity.resources.configuration.screenWidthDp
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.AT_MOST))
        root.layout(0,0,width,root.measuredHeight)
    }
    private fun assertSelectedLabelVisible() {
        measureWithoutAdvancingTime()
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val label=panel.metricButtons[panel.metric.ordinal]
        val position=IntArray(2).also(label::getLocationInWindow)
        val origin=IntArray(2).also(root::getLocationInWindow)
        val expected=label.currentTextColor
        var ink=0
        val left=position[0]-origin[0]+12
        val top=position[1]-origin[1]+6
        for(y in top until top+label.height-12)for(x in left until left+label.width-24) {
            val pixel=bitmap.getPixel(x,y)
            if(Color.alpha(pixel)>200 && kotlin.math.abs(Color.red(pixel)-Color.red(expected))<30 &&
                kotlin.math.abs(Color.green(pixel)-Color.green(expected))<30 && kotlin.math.abs(Color.blue(pixel)-Color.blue(expected))<30)ink++
        }
        bitmap.recycle()
        assertTrue("The selected metric label must remain visible above its indicator",ink>10)
    }
    private fun touch(chart: StatsTrendChart,x: Float) {
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(0,0,action,x,100f,0).also { assertTrue(chart.onTouchEvent(it));it.recycle() }
        }
    }
    @Test fun `default completed line and focus bars show the correct totals and peaks`() {
        bind()
        assertEquals("3 次",panel.totalText.text.toString())
        assertEquals("2 次",panel.peakText.text.toString())
        assertEquals(StatsTrendMetric.COMPLETED,panel.metric)
        assertEquals(StatsTrendMode.LINE,panel.mode)
        assertTrue(panel.lineButton.isSelected)
        assertEquals(View.GONE,panel.focusScroll.visibility)
        assertEquals(224,panel.activeChart.height)
        assertSelectedLabelVisible()
        capture("statistics-trend-completed-line")
        panel.metricButtons[1].performClick();idle()
        assertEquals(StatsTrendMetric.FOCUS,panel.metric)
        assertEquals("2h 6m",panel.totalText.text.toString())
        assertEquals("1h 36m",panel.peakText.text.toString())
        panel.barButton.performClick();idle()
        assertTrue(panel.barButton.isSelected)
        assertEquals(StatsTrendMode.STACKED_BAR,panel.activeChart.mode)
        assertSelectedLabelVisible()
        capture("statistics-trend-focus-bars")
    }
    @Test fun `each metric retains its own scroll position while the selected date stays unchanged`() {
        val dates=StatsDateSelection(day,StatsPeriod.YEAR)
        bind(state(dates))
        val label=panel.rangeLabel.text.toString()
        panel.completedScroll.scrollTo(2000,0);layout()
        val position=panel.completedScroll.scrollX
        assertTrue(position>0)
        touch(panel.activeChart,panel.activeChart.width-15f)
        capture("statistics-trend-year-scroll-tooltip")
        assertEquals(11,panel.activeChart.selectedIndex)
        val bounds=panel.activeChart.tooltipBounds!!
        assertTrue(bounds.left>=position+8f-.1f)
        assertTrue(bounds.right<=position+panel.completedScroll.width-8f+.1f)
        panel.metricButtons[1].performClick();idle()
        assertEquals(0,panel.focusScroll.scrollX)
        panel.focusScroll.scrollTo(100,0)
        panel.metricButtons[0].performClick();idle()
        assertEquals(position,panel.completedScroll.scrollX)
        assertEquals(label,panel.rangeLabel.text.toString())
        idle(2201)
        assertEquals(-1,panel.completedChart.selectedIndex)
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h640dp-night-mdpi")
    fun `narrow night stacked tooltip stays visible and includes the remaining scenes`() {
        val names=listOf("study","work","reading","meditation","creative","relax","exercise")
        val scenes=names.mapIndexed { index,name ->StatsSceneEntry(name,name,name,(index+1).toDouble(),1) }
        val range=StatsDateSelection(day,StatsPeriod.DAY).range(zone)
        val data=StatsTrendSeries(StatsTrendGranularity.HOUR,listOf(StatsTrendBucket(range.fromWallMs,range.fromWallMs+3_600_000,7,28.0,scenes)))
        bind(state(StatsDateSelection(day,StatsPeriod.DAY),data))
        panel.metricButtons[1].performClick();panel.barButton.performClick();idle()
        assertSelectedLabelVisible()
        touch(panel.activeChart,panel.activeChart.width/2f)
        capture("statistics-trend-night-stacked-tooltip")
        assertEquals(0,panel.activeChart.selectedIndex)
        assertEquals("28m",panel.totalText.text.toString())
        val bounds=panel.activeChart.tooltipBounds!!
        assertTrue(bounds.left>=8f)
        assertTrue(bounds.right<=panel.activeScroll.width-8f+.1f)
        assertTrue(bounds.bottom<=panel.activeChart.height.toFloat())
        assertEquals("工作",StatsSceneLabels(activity).trendLabel("Custom project","work"))
    }
    @Test fun `account changes discard data timers animations and stale controls before the next bind`() {
        bind()
        panel.metricButtons[1].performClick();panel.barButton.performClick();idle(32)
        val old=panel.activeChart
        touch(old,old.width/2f)
        TokenStore.set("statistics-trend-next-owner")
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap));bitmap.recycle()
        panel.metricButtons[1].performClick();panel.barButton.performClick();idle()
        assertEquals(StatsTrendMetric.COMPLETED,panel.metric)
        assertEquals(StatsTrendMode.LINE,panel.mode)
        assertEquals(0,panel.completedChart.bucketCount)
        assertEquals(0,panel.focusChart.bucketCount)
        assertEquals(-1,old.selectedIndex)
        assertFalse(panel.isAnimating)
        assertEquals(activity.getString(R.string.stats_placeholder),panel.totalText.text.toString())
        assertFalse(panel.metricButtons.any { it.isEnabled })
        assertFalse(panel.lineButton.isEnabled)
    }
    @Test fun `read failure uses placeholders and recovery restores real chart data`() {
        bind()
        panel.bind(state().copy(isLoading=true,readFailed=true,overview=null,trend=null),zone)
        idle()
        assertEquals(activity.getString(R.string.stats_placeholder),panel.totalText.text.toString())
        assertEquals(0,panel.activeChart.bucketCount)
        assertFalse(panel.barButton.isEnabled)
        bind()
        assertEquals("3 次",panel.totalText.text.toString())
        assertTrue(panel.metricButtons.all { it.isEnabled })
    }
    @Test fun `sub minute tooltip formatting retains precise data while totals round after aggregation`() {
        val start=day.atStartOfDay(zone).toInstant().toEpochMilli()
        val data=StatsTrendSeries(StatsTrendGranularity.HOUR,listOf(StatsTrendBucket(start,start+3_600_000,1,.9,
            listOf(StatsSceneEntry("reading","reading","reading",.9,1)))))
        bind(state(data=data))
        panel.metricButtons[1].performClick();idle()
        assertEquals("1m",panel.totalText.text.toString())
        assertEquals("0m",StatsTrendText.value(activity,StatsTrendMetric.FOCUS,.9))
        touch(panel.activeChart,panel.activeChart.width/2f)
        capture("statistics-trend-short-focus-tooltip")
        idle(2201)
        assertEquals(-1,panel.activeChart.selectedIndex)
    }
}
