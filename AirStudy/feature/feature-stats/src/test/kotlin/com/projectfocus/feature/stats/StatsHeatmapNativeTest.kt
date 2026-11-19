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
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.ui.R as UiR
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

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="zh-rCN-w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsHeatmapNativeTest {
    private lateinit var controller: org.robolectric.android.controller.ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var panel: StatsHeatmapPanel
    private val zone=ZoneId.of("Asia/Shanghai")
    private val day=LocalDate.parse("2026-11-03")
    private val wall=day.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
    @Before fun open() {
        TokenStore.set("statistics-annual-render-owner")
        controller=Robolectric.buildActivity(Activity::class.java).also { it.get().setTheme(UiR.style.Theme_Pf) }
        activity=controller.setup().visible().get()
        panel=StatsHeatmapPanel(activity).apply { currentWallMs={wall} }
        root=FrameLayout(activity).apply {
            setBackgroundResource(UiR.color.stats_background)
            val visual=StatsVisual(activity)
            setPadding(visual.dp(16),visual.dp(12),visual.dp(16),visual.dp(12))
            addView(panel,FrameLayout.LayoutParams(-1,-2))
        }
        activity.setContentView(root)
    }
    @After fun close() { controller.pause().stop().destroy();TokenStore.clear() }
    private fun idle(ms: Long=16) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun measure() {
        val width=(activity.resources.configuration.screenWidthDp*activity.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((2000*activity.resources.displayMetrics.density).toInt(),View.MeasureSpec.AT_MOST))
        root.layout(0,0,width,root.measuredHeight)
    }
    private fun layout() { repeat(2) { measure();idle() } }
    private fun data(year: Int=2026): StatsAnnualHeatmap {
        val date=if(year==2024)LocalDate.parse("2024-02-29") else day
        val at=date.atStartOfDay(zone).toInstant().toEpochMilli()
        return StatsAnnualHeatmap.calculate(listOf(HeatmapTestRecords.countdown("today",at,if(year==2024)61 else 30),
            HeatmapTestRecords.countdown("before",date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),94),
            HeatmapTestRecords.countUp("precise",date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())),year,zone)
    }
    private fun state(model: StatsAnnualHeatmap=data(), dates: StatsDateSelection=StatsDateSelection(day))=StatsUiState(
        account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation),isLoading=false,
        dates=dates,dateRange=dates.range(zone),nowWallMs=wall,heatmapYear=model.year,canNextHeatmapYear=model.year<2026,annualHeatmap=model)
    private fun bind(state: StatsUiState=state()) { panel.bind(state);layout() }
    private fun touch(date: LocalDate, action: Int=MotionEvent.ACTION_UP) {
        val cell=panel.chart.cellBounds(date)!!
        if(action==MotionEvent.ACTION_UP)event(MotionEvent.ACTION_DOWN,cell.centerX(),cell.centerY())
        event(action,cell.centerX(),cell.centerY())
    }
    private fun event(action: Int,x: Float,y: Float): Boolean = MotionEvent.obtain(0,0,action,x,y,0).let {
        try { panel.chart.onTouchEvent(it) } finally { it.recycle() }
    }
    private fun capture(name: String) {
        measure()
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/statistics/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }
    @Test fun `current year centers the query date and shows a bounded day tooltip`() {
        bind()
        assertEquals("2026 年",panel.yearLabel.text.toString())
        assertFalse(panel.next.isEnabled)
        assertEquals(820,panel.chart.width)
        assertEquals(136,panel.chart.height)
        assertTrue(panel.scroll.scrollX>0)
        assertEquals(View.GONE,panel.emptyLabel.visibility)
        capture("statistics-annual-current-year")
        touch(day)
        assertEquals(day,panel.chart.selectedDate)
        val bitmap=Bitmap.createBitmap(panel.chart.width,panel.chart.height,Bitmap.Config.ARGB_8888)
        panel.chart.draw(Canvas(bitmap));bitmap.recycle()
        val bounds=panel.chart.tooltipBounds!!
        assertTrue(bounds.left>=panel.scroll.scrollX+8)
        assertTrue(bounds.right<=panel.scroll.scrollX+panel.scroll.width-8)
        assertTrue(bounds.top>=8)
        capture("statistics-annual-day-tooltip")
        idle(2100);assertEquals(day,panel.chart.selectedDate)
        idle(101);assertNull(panel.chart.selectedDate)
    }
    @Test fun `every row in a full week paints without skipping weekdays`() {
        val first=LocalDate.parse("2026-01-05")
        val records=(0..6).map { HeatmapTestRecords.countdown("day-$it",first.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(),it+1) }
        bind(state(StatsAnnualHeatmap.calculate(records,2026,zone),StatsDateSelection(first)))
        val bitmap=Bitmap.createBitmap(panel.chart.width,panel.chart.height,Bitmap.Config.ARGB_8888)
        panel.chart.draw(Canvas(bitmap))
        for(index in 0..6) {
            val bounds=panel.chart.cellBounds(first.plusDays(index.toLong()))!!
            assertTrue("Weekday $index must be painted",Color.alpha(bitmap.getPixel(bounds.centerX().toInt(),bounds.centerY().toInt()))>40)
        }
        bitmap.recycle()
        capture("statistics-annual-seven-days")
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h740dp-night-mdpi")
    fun `leap day tooltip remains visible in a narrow night layout`() {
        bind(state(data(2024)))
        assertTrue(panel.next.isEnabled)
        val leap=LocalDate.parse("2024-02-29")
        panel.scroll.scrollTo(panel.chart.scrollFor(leap,panel.scroll.width),0)
        touch(leap)
        assertEquals(leap,panel.chart.selectedDate)
        assertTrue(panel.chart.dateLabel(leap,65556).contains("2024"))
        val bitmap=Bitmap.createBitmap(panel.chart.width,panel.chart.height,Bitmap.Config.ARGB_8888)
        panel.chart.draw(Canvas(bitmap));bitmap.recycle()
        assertTrue(panel.chart.tooltipBounds!!.left>=panel.scroll.scrollX+8)
        assertTrue(panel.chart.tooltipBounds!!.right<=panel.scroll.scrollX+panel.scroll.width-8)
        capture("statistics-annual-leap-night-narrow")
    }
    @Test fun `padding gaps outside year and future dates cannot be selected`() {
        bind()
        touch(day);assertEquals(day,panel.chart.selectedDate)
        event(MotionEvent.ACTION_DOWN,0f,0f);assertNull(panel.chart.selectedDate)
        val cell=panel.chart.cellBounds(day)!!
        event(MotionEvent.ACTION_DOWN,cell.right+1,cell.centerY());assertNull(panel.chart.selectedDate)
        touch(LocalDate.parse("2025-12-31"));assertNull(panel.chart.selectedDate)
        touch(day.plusDays(1));assertNull(panel.chart.selectedDate)
        touch(day.minusDays(1),MotionEvent.ACTION_DOWN)
        touch(day,MotionEvent.ACTION_MOVE);assertEquals(day,panel.chart.selectedDate)
        event(MotionEvent.ACTION_CANCEL,cell.centerX(),cell.centerY());assertNull(panel.chart.selectedDate)
        touch(day.minusDays(2));assertEquals(day.minusDays(2),panel.chart.selectedDate)
        capture("statistics-annual-empty-day-tooltip")
    }
    @Test fun `year changes and account changes clear selections and obsolete actions`() {
        val initial=state();bind(initial)
        var requests=0
        var captured: StatsUiState?=null
        panel.onYearRequested={direction,source -> assertEquals(-1,direction);requests++;captured=source }
        panel.previous.performClick();assertEquals(1,requests);assertSame(initial,captured)
        touch(day)
        val changed=initial.copy(annualHeatmap=data(2024),heatmapYear=2024,canNextHeatmapYear=true,heatmapRevision=1)
        bind(changed);assertNull(panel.chart.selectedDate)
        TokenStore.set("statistics-annual-next-owner")
        val bitmap=Bitmap.createBitmap(panel.chart.width,panel.chart.height,Bitmap.Config.ARGB_8888)
        panel.chart.draw(Canvas(bitmap));bitmap.recycle()
        panel.previous.performClick();assertEquals(1,requests)
        assertFalse(event(MotionEvent.ACTION_DOWN,80f,80f))
        bind(state())
        panel.interactionAllowed={false}
        panel.previous.performClick();assertEquals(1,requests)
        assertFalse(event(MotionEvent.ACTION_DOWN,80f,80f))
        panel.release();assertNull(panel.chart.selectedDate)
        panel.previous.performClick();assertEquals(1,requests)
    }
    @Test fun `empty years zero minutes and unavailable data use different states`() {
        bind(state(StatsAnnualHeatmap.calculate(emptyList(),2025,zone)))
        assertEquals(View.GONE,panel.scroll.visibility)
        assertEquals("暂无数据",panel.emptyLabel.text.toString())
        capture("statistics-annual-empty-year")
        val zero=StatsAnnualHeatmap.calculate(listOf(HeatmapTestRecords.countdown("zero",day.atStartOfDay(zone).toInstant().toEpochMilli(),0)),2026,zone)
        bind(state(zero));assertEquals(View.VISIBLE,panel.scroll.visibility)
        touch(day);assertEquals(day,panel.chart.selectedDate)
        capture("statistics-annual-zero-day")
        bind(state().copy(isLoading=true,readFailed=true,annualHeatmap=null))
        assertEquals(View.GONE,panel.scroll.visibility)
        assertEquals("热力图暂时无法加载",panel.emptyLabel.text.toString())
        assertTrue(panel.previous.isEnabled)
        capture("statistics-annual-unavailable")
        bind();assertEquals(View.VISIBLE,panel.scroll.visibility);assertEquals(View.GONE,panel.emptyLabel.visibility)
    }
    @Test @Config(qualifiers="zh-rCN-w432dp-h960dp-440dpi")
    fun `fractional screen density keeps the original dimensions and readable controls`() {
        bind()
        val density=activity.resources.displayMetrics.density
        assertEquals(2.75f,density,0f)
        assertEquals(49,StatsVisual(activity).dp(18))
        assertEquals((820*density).toInt(),panel.chart.width)
        assertEquals((136*density).toInt(),panel.chart.height)
        assertEquals((28*density).toInt(),panel.previous.width)
        assertEquals((32*density).toInt(),panel.previous.height)
        assertEquals((9*density).toInt(),panel.previous.paddingLeft)
        val label=panel.yearLabel.paint
        assertTrue(label.measureText(panel.yearLabel.text.toString())<=panel.yearLabel.width)
        capture("statistics-annual-fractional-density")
    }
}
