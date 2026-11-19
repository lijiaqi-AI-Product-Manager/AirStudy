package com.projectfocus.feature.stats

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
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
class StatsHourNativeTest {
    private lateinit var controller: org.robolectric.android.controller.ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var panel: StatsHourPanel
    private val zone=ZoneId.of("Asia/Shanghai")
    private val day=LocalDate.parse("2026-11-03")
    private val wall=day.atStartOfDay(zone).toInstant().toEpochMilli()
    @Before fun open() {
        TokenStore.set("statistics-hour-render-owner")
        controller=Robolectric.buildActivity(Activity::class.java).also { it.get().setTheme(UiR.style.Theme_Pf) }
        activity=controller.setup().visible().get();panel=StatsHourPanel(activity)
        root=FrameLayout(activity).apply { setBackgroundResource(UiR.color.stats_background);setPadding(16,12,16,12);addView(panel,FrameLayout.LayoutParams(-1,-2)) }
        activity.setContentView(root)
    }
    @After fun close() { controller.pause().stop().destroy();TokenStore.clear() }
    private fun state(empty: Boolean=false): StatsUiState {
        val dates=StatsDateSelection(day,StatsPeriod.DAY)
        val records=if(empty)emptyList() else listOf(HeatmapTestRecords.countdown("early",wall+3_600_000,5),HeatmapTestRecords.countdown("late",wall+23*3_600_000,90))
        return StatsUiState(account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation),isLoading=false,dates=dates,dateRange=dates.range(zone),nowWallMs=wall,
            hourHabits=StatsHourHabits.calculate(records,dates.range(zone),zone))
    }
    private fun idle(ms: Long=16) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun measure() {
        val width=(activity.resources.configuration.screenWidthDp*activity.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.AT_MOST))
        root.layout(0,0,width,root.measuredHeight)
    }
    private fun layout() { repeat(2) { measure();idle(700) };measure() }
    private fun bind(state: StatsUiState=state()) { panel.bind(state);layout() }
    private fun event(action: Int,x: Float,y: Float)=MotionEvent.obtain(0,0,action,x,y,0).let { try { panel.chart.onTouchEvent(it) } finally { it.recycle() } }
    private fun touch(hour: Int) { val b=panel.chart.barBounds(hour);event(MotionEvent.ACTION_DOWN,b.centerX(),b.centerY());event(MotionEvent.ACTION_UP,b.centerX(),b.centerY()) }
    private fun capture(name: String) {
        measure();val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        File("build/reports/statistics/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
    }
    @Test fun `actual hour bins draw proportional bars and a bounded selected hour tooltip`() {
        bind()
        assertEquals("23:00–00:00",panel.peakText.text.toString());assertEquals("3 小时",panel.activeHoursText.text.toString())
        assertEquals(androidx.core.content.res.ResourcesCompat.getFont(activity,UiR.font.harmonyos_sans_sc_black),panel.peakText.typeface)
        assertEquals(150,panel.chart.height)
        val bitmap=Bitmap.createBitmap(panel.chart.width,panel.chart.height,Bitmap.Config.ARGB_8888);panel.chart.draw(Canvas(bitmap))
        val visual=StatsVisual(activity);val selected=panel.chart.barBounds(23);val empty=panel.chart.barBounds(12)
        assertEquals(visual.accent,bitmap.getPixel(selected.centerX().toInt(),20))
        assertNotEquals(visual.accent,bitmap.getPixel(empty.centerX().toInt(),20));bitmap.recycle()
        capture("statistics-hours-day")
        touch(23);capture("statistics-hours-tooltip")
        val bounds=panel.chart.tooltipBounds!!
        assertEquals(23,panel.chart.selectedHour);assertTrue(bounds.left>=8);assertTrue(bounds.right<=panel.chart.width-8)
        assertTrue(bounds.top>=8);assertTrue(bounds.bottom<=panel.chart.height-8)
    }
    @Test fun `hour gaps remain selectable while labels and out of bounds input clear selection`() {
        bind()
        val first=panel.chart.barBounds(0)
        event(MotionEvent.ACTION_DOWN,first.right+.5f,10f);assertEquals(0,panel.chart.selectedHour)
        event(MotionEvent.ACTION_UP,first.right+.5f,10f)
        idle(2199);assertEquals(0,panel.chart.selectedHour);idle(1);assertNull(panel.chart.selectedHour)
        touch(12);assertEquals(12,panel.chart.selectedHour)
        event(MotionEvent.ACTION_MOVE,first.centerX(),panel.chart.height.toFloat());assertNull(panel.chart.selectedHour)
        touch(1);event(MotionEvent.ACTION_CANCEL,1f,1f);assertNull(panel.chart.selectedHour)
        event(MotionEvent.ACTION_DOWN,-1f,10f);assertNull(panel.chart.selectedHour)
    }
    @Test fun `zero data retains empty tracks but read failures hide the chart and clear its metrics`() {
        bind(state(true));assertEquals("—",panel.peakText.text.toString());assertEquals("0 小时",panel.activeHoursText.text.toString())
        assertEquals(View.VISIBLE,panel.chart.visibility);capture("statistics-hours-empty")
        bind(state().copy(hourHabits=null,isLoading=true,readFailed=true))
        assertEquals(View.GONE,panel.chart.visibility);assertEquals(View.VISIBLE,panel.message.visibility)
        assertEquals(activity.getString(R.string.stats_read_failed),panel.message.text.toString())
        var retries=0;panel.onRetryRequested={retries++};panel.message.performClick();assertEquals(1,retries)
        assertEquals("—",panel.activeHoursText.text.toString());capture("statistics-hours-unavailable")
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h720dp-night-mdpi") fun `narrow night chart keeps all twenty four tracks and zero duration tooltips`() {
        bind();assertTrue(panel.chart.barBounds(23).right<=panel.chart.width)
        for(hour in 0..23)assertTrue(panel.chart.barBounds(hour).width()>0f)
        touch(12);capture("statistics-hours-night-narrow")
        assertEquals(12,panel.chart.selectedHour)
        assertTrue(panel.chart.tooltipBounds!!.right<=panel.chart.width-8)
    }
    @Test @Config(qualifiers="en-rUS-w320dp-h720dp-mdpi") fun `large fonts keep peak values and tooltip text within the card`() {
        val config=android.content.res.Configuration(activity.resources.configuration).apply { fontScale=1.5f }
        panel=StatsHourPanel(activity.createConfigurationContext(config));root.removeAllViews();root.addView(panel,FrameLayout.LayoutParams(-1,-2))
        bind();assertTrue(panel.peakText.layout.getLineWidth(0)<=panel.peakText.width)
        touch(0);capture("statistics-hours-large-text")
        assertTrue(panel.chart.tooltipBounds!!.left>=8);assertTrue(panel.chart.tooltipBounds!!.bottom<=panel.chart.height-8)
    }
    @Test fun `account changes and disabled interactions cannot retain a selection or old totals`() {
        bind();touch(23);assertEquals(23,panel.chart.selectedHour)
        panel.interactionAllowed={false};assertFalse(event(MotionEvent.ACTION_DOWN,10f,10f));assertNull(panel.chart.selectedHour)
        panel.interactionAllowed={true};touch(23)
        TokenStore.set("statistics-hour-render-next-owner")
        capture("statistics-hours-obsolete-account")
        assertEquals("—",panel.peakText.text.toString());assertEquals("—",panel.activeHoursText.text.toString());assertNull(panel.chart.selectedHour)
        panel.release();assertFalse(event(MotionEvent.ACTION_DOWN,10f,10f))
    }
    @Test @Config(qualifiers="en-rUS-w432dp-h960dp-440dpi") fun `drawing gaps retain fractional pixels while panel dimensions use integer layout values`() {
        bind()
        assertEquals(2.75f,activity.resources.displayMetrics.density,0f)
        assertEquals(412,panel.chart.height)
        val first=panel.chart.barBounds(0);val second=panel.chart.barBounds(1)
        assertEquals(5.5f,second.left-first.right,.0001f)
        assertEquals(11f,first.top,0f);assertEquals(373.5f,first.bottom,.0001f)
        touch(23);capture("statistics-hours-fractional-density")
        assertTrue(panel.chart.tooltipBounds!!.right<=panel.chart.width-8*2.75f)
    }
}
