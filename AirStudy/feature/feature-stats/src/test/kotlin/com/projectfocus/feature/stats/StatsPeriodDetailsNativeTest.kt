package com.projectfocus.feature.stats

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.service.focus.history.HistoryReadState
import com.projectfocus.service.focus.history.RecordedFlight
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
class StatsPeriodDetailsNativeTest {
    private lateinit var controller: org.robolectric.android.controller.ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var insights: StatsInsightsPanel
    private lateinit var highlights: StatsHighlightsPanel
    private val zone=ZoneId.of("Asia/Shanghai")
    private val day=LocalDate.parse("2026-11-03")
    private val wall=day.atStartOfDay(zone).toInstant().toEpochMilli()
    @Before fun open() {
        TokenStore.set("statistics-period-details-owner")
        controller=Robolectric.buildActivity(Activity::class.java).also { it.get().setTheme(UiR.style.Theme_Pf) }
        activity=controller.setup().visible().get();install(activity)
    }
    private fun install(context: Context) {
        val visual=StatsVisual(context)
        insights=StatsInsightsPanel(context);highlights=StatsHighlightsPanel(context)
        val column=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            addView(insights,LinearLayout.LayoutParams(-1,-2))
            addView(highlights,LinearLayout.LayoutParams(-1,-2).apply { topMargin=visual.dp(14) })
        }
        root=FrameLayout(context).apply {
            setBackgroundResource(UiR.color.stats_background);setPadding(visual.dp(16),visual.dp(12),visual.dp(16),visual.dp(12))
            addView(column,FrameLayout.LayoutParams(-1,-2))
        }
        activity.setContentView(root)
    }
    @After fun close() { controller.pause().stop().destroy();TokenStore.clear() }
    private fun state(records: List<RecordedFlight> = PeriodDetailsTestRecords.sample(day,zone),date: LocalDate=day,local: ZoneId=zone): StatsUiState {
        val account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation)
        return StatsProjection.build(HistoryReadState.Loaded(account,records,emptyMap(),emptyMap()),
            StatsQuery(account.generation,StatsDateSelection(date,StatsPeriod.WEEK)),wall,local)
    }
    private fun measure() {
        val width=(activity.resources.configuration.screenWidthDp*activity.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(5000,View.MeasureSpec.AT_MOST))
        root.layout(0,0,width,root.measuredHeight)
    }
    private fun bind(state: StatsUiState=state(),local: ZoneId=zone) {
        insights.bind(state,local);highlights.bind(state,local)
        repeat(2) { measure();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700)) };measure()
    }
    private fun value(key: String)=insights.rows.findViewWithTag<TextView>("$key-value")
    private fun capture(name: String) {
        measure();val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        File("build/reports/statistics/$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
        };bitmap.recycle()
    }
    private fun assertReadable() {
        for(key in listOf("stats-insight-best-day","stats-insight-top-scene","stats-insight-top-period")) {
            val text=value(key);assertTrue(text.width>0);assertNotNull(text.layout)
            assertTrue(text.layout.getLineWidth(0)<=text.width+1f)
            if(text.layout.getEllipsisCount(0)>0)assertTrue(text.layout.getEllipsisStart(0)>0)
        }
        for(index in 0 until highlights.rows.childCount) {
            val row=highlights.rows.getChildAt(index)
            val scene=row.findViewWithTag<TextView>("stats-highlight-scene")
            val duration=row.findViewWithTag<TextView>("stats-highlight-duration")
            assertTrue(scene.width>0);assertTrue(duration.right<=row.width-row.paddingRight)
        }
    }
    @Test fun `real projection builds all insights and five ranked cards with source geometry`() {
        bind();assertEquals(3,insights.rows.childCount);assertEquals(5,highlights.rows.childCount)
        assertTrue(value("stats-insight-best-day").text.contains("2026"))
        assertTrue(value("stats-insight-top-scene").text.contains("学习"));assertEquals("上午",value("stats-insight-top-period").text.toString())
        val row=insights.rows.getChildAt(0) as LinearLayout
        assertEquals(12,row.paddingLeft);assertEquals(11,row.paddingTop);assertEquals(40,row.getChildAt(0).width)
        val first=highlights.rows.getChildAt(0) as LinearLayout
        assertEquals("1",(first.getChildAt(0) as TextView).text.toString());assertEquals(38,first.getChildAt(0).width)
        assertEquals(34,first.getChildAt(1).width)
        assertEquals("1h 30m",first.findViewWithTag<TextView>("stats-highlight-duration").text.toString())
        assertEquals(androidx.core.content.res.ResourcesCompat.getFont(activity,UiR.font.harmonyos_sans_sc_black),(first.getChildAt(0) as TextView).typeface)
        assertReadable();capture("statistics-period-details-day")
    }
    @Test fun `empty data and a failed read have distinct content and guarded retry actions`() {
        bind(state(emptyList()));assertEquals(3,insights.rows.childCount);assertEquals(0,highlights.rows.childCount)
        assertEquals("暂无数据",value("stats-insight-best-day").text.toString());assertEquals("暂无数据",highlights.message.text.toString())
        capture("statistics-period-details-empty")
        val failed=state().copy(periodDetails=null,isLoading=true,readFailed=true)
        bind(failed);assertEquals(0,insights.rows.childCount);assertEquals(0,highlights.rows.childCount)
        assertEquals(activity.getString(R.string.stats_read_failed),insights.message.text.toString())
        var retries=0;insights.onRetryRequested={retries++};highlights.onRetryRequested={retries++}
        insights.message.performClick();highlights.message.performClick();assertEquals(2,retries)
        insights.interactionAllowed={false};insights.message.performClick();assertEquals(2,retries)
        capture("statistics-period-details-unavailable")
        TokenStore.set("statistics-period-details-retry-next-owner")
        highlights.message.performClick();assertEquals(2,retries)
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h720dp-night-mdpi") fun `narrow night cards keep titles scene prefixes and duration values readable`() {
        bind();assertReadable();capture("statistics-period-details-night-narrow")
    }
    @Test @Config(qualifiers="en-rUS-w320dp-h720dp-mdpi") fun `large fonts constrain long scene names without losing ranking values`() {
        val configuration=Configuration(activity.resources.configuration).apply { fontScale=1.5f }
        install(activity.createConfigurationContext(configuration))
        val records=PeriodDetailsTestRecords.sample(day,zone).map { record ->
            if(record is RecordedFlight.Countdown)PeriodDetailsTestRecords.countdown(record.key.id,record.recordedAtWallMs,record.record.actualFocusMinutes,"work","A long personal work scenario") else record
        }
        bind(state(records));assertReadable();capture("statistics-period-details-large-text")
    }
    @Test fun `an owner change clears both cards before drawing and release disables retained retries`() {
        bind();assertTrue(highlights.rows.childCount>0)
        TokenStore.set("statistics-period-details-next-owner")
        capture("statistics-period-details-obsolete-account")
        assertEquals(0,insights.rows.childCount);assertEquals(0,highlights.rows.childCount)
        assertEquals("—",highlights.message.text.toString())
        var retries=0;insights.onRetryRequested={retries++};highlights.onRetryRequested={retries++}
        insights.release();highlights.release();insights.message.performClick();highlights.message.performClick();assertEquals(0,retries)
    }
    @Test @Config(qualifiers="en-rUS-w432dp-h960dp-mdpi") fun `previous year dates retain their year and follow the selected timezone`() {
        val local=ZoneId.of("America/New_York");val date=day.minusYears(1)
        bind(state(PeriodDetailsTestRecords.sample(date,local),date,local),local)
        assertTrue(value("stats-insight-best-day").text.contains("2025"))
        val first=highlights.rows.getChildAt(0)
        assertTrue(first.findViewWithTag<TextView>("stats-highlight-date").text.contains("2025"))
        capture("statistics-period-details-prior-year-zone")
    }
    @Test @Config(qualifiers="zh-rCN-w432dp-h960dp-440dpi") fun `fractional density truncates row geometry and keeps the final icon inside its tile`() {
        bind();val row=insights.rows.getChildAt(0) as LinearLayout
        assertEquals(2.75f,activity.resources.displayMetrics.density,0f);assertEquals(33,row.paddingLeft);assertEquals(30,row.paddingTop)
        assertEquals(110,row.getChildAt(0).width)
        val first=highlights.rows.getChildAt(0) as LinearLayout
        assertEquals(104,first.getChildAt(0).width);assertEquals(93,first.getChildAt(1).width)
        assertReadable();capture("statistics-period-details-fractional-density")
    }
}
