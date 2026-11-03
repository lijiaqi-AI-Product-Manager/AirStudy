package com.projectfocus.feature.stats

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
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
class StatsCareerNativeTest {
    private lateinit var controller: org.robolectric.android.controller.ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var panel: StatsCareerPanel
    private val zone=ZoneId.of("Asia/Shanghai")
    private val day=LocalDate.parse("2026-11-03")
    private val wall=day.atStartOfDay(zone).toInstant().toEpochMilli()
    @Before fun open() {
        TokenStore.set("statistics-career-render-owner")
        controller=Robolectric.buildActivity(Activity::class.java).also { it.get().setTheme(UiR.style.Theme_Pf) }
        activity=controller.setup().visible().get()
        panel=StatsCareerPanel(activity)
        root=FrameLayout(activity).apply {
            setBackgroundResource(UiR.color.stats_background)
            setPadding(16,12,16,12)
            addView(panel,FrameLayout.LayoutParams(-1,-2))
        }
        activity.setContentView(root)
    }
    @After fun close() { controller.pause().stop().destroy();TokenStore.clear() }
    private fun state() = StatsUiState(account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation),isLoading=false,
        dates=StatsDateSelection(day),dateRange=StatsDateSelection(day).range(zone),nowWallMs=wall,
        career=StatsCareer.calculate(listOf(HeatmapTestRecords.countdown("a",wall),HeatmapTestRecords.countdown("b",wall),HeatmapTestRecords.countUp("c",wall)),mapOf("CN" to 10),mapOf("CN" to setOf("TSN"))),
        flightStreak=StatsFlightStreak(12,38))
    private fun measure() {
        val width=(activity.resources.configuration.screenWidthDp*activity.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(3000,View.MeasureSpec.AT_MOST))
        root.layout(0,0,width,root.measuredHeight)
    }
    private fun layout() { repeat(2) { measure();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550)) };measure() }
    private fun capture(name: String) {
        measure()
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/statistics/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }
    @Test fun `career formats successful totals and keeps unknown arrival classifications unavailable`() {
        val data=state()
        panel.bind(data);layout()
        assertEquals(androidx.core.content.res.ResourcesCompat.getFont(activity,UiR.font.harmonyos_sans_sc_black),panel.distanceValue.typeface)
        assertEquals(activity.getString(R.string.stats_km_value_fmt,String.format(java.util.Locale.US,"%,d",Math.round(data.career!!.distanceKm))),panel.distanceValue.text.toString())
        assertEquals("3 次",panel.flightsValue.text.toString())
        assertEquals("—",panel.onTimeValue.text.toString())
        assertEquals("12 天",panel.streakValue.text.toString())
        assertEquals("历史最长 38 天",panel.bestValue.text.toString())
        assertEquals("1",panel.cityCountValue.text.toString())
        assertEquals(1,panel.countryRows.childCount)
        capture("statistics-career-day")
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h720dp-night-mdpi") fun `narrow night card fits five cities and country bars retain floating point ratios`() {
        val career=state().career!!.copy(distanceKm=123456.7,completedFlights=308,
            cities=(1..7).map { StatsCareerCity("很长的城市名称 $it","CN",8-it) },cityCount=7,
            countries=listOf(StatsCareerCountry("CN",100,200),StatsCareerCountry("US",50,150),StatsCareerCountry("GB",1,4),StatsCareerCountry("CA",1,0),StatsCareerCountry("FR",1,3),StatsCareerCountry("DE",1,3)))
        panel.bind(state().copy(career=career));layout()
        assertEquals(5,panel.cityRows.childCount);assertEquals(5,panel.countryRows.childCount)
        val second=(panel.countryRows.getChildAt(1) as LinearLayout).getChildAt(1) as LinearLayout
        assertEquals(.5f,(second.getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight,0f)
        val third=(panel.countryRows.getChildAt(2) as LinearLayout).getChildAt(1) as LinearLayout
        assertEquals(.04f,(third.getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight,0f)
        for(i in 0 until panel.cityRows.childCount) assertTrue(panel.cityRows.getChildAt(i).right<=panel.cityRows.width)
        assertTrue(panel.cityScroll.canScrollHorizontally(1))
        val caption=(panel.cityRows.getChildAt(0) as LinearLayout).getChildAt(1) as android.widget.TextView
        assertTrue(caption.layout.getEllipsisStart(0)>0 || caption.paint.measureText(caption.text.toString())<=caption.width)
        panel.cityScroll.scrollTo(panel.cityRows.width,0);layout()
        assertTrue(panel.cityScroll.scrollX>0)
        panel.cityScroll.scrollTo(0,0)
        capture("statistics-career-night-narrow")
    }
    @Test @Config(qualifiers="en-rUS-w360dp-h800dp-mdpi") fun `large text remains inside the career card and does not truncate its total`() {
        val config=android.content.res.Configuration(activity.resources.configuration).apply { fontScale=1.5f }
        panel=StatsCareerPanel(activity.createConfigurationContext(config))
        root.removeAllViews();root.addView(panel,FrameLayout.LayoutParams(-1,-2))
        panel.bind(state());layout()
        assertEquals("3 times",panel.flightsValue.text.toString())
        assertEquals("Career data",activity.getString(R.string.stats_career_title))
        assertTrue(panel.distanceValue.layout.getLineWidth(0)<=panel.distanceValue.width)
        assertTrue(panel.streakValue.right<=panel.width)
        assertTrue(panel.bestValue.right<=panel.width)
        capture("statistics-career-large-text")
    }
    @Test fun `empty success failure and loading are separate and retry needs the current account`() {
        val valid=state()
        panel.bind(valid.copy(career=StatsCareer.calculate(emptyList(),emptyMap(),emptyMap()),flightStreak=StatsFlightStreak(0,0)));layout()
        assertEquals("0 次",panel.flightsValue.text.toString());assertEquals("0 km",panel.distanceValue.text.toString())
        assertEquals(View.GONE,panel.message.visibility)
        capture("statistics-career-empty")
        var retries=0
        panel.onRetryRequested={retries++}
        panel.bind(valid.copy(career=null,flightStreak=null,isLoading=true,readFailed=true));layout()
        assertEquals("—",panel.distanceValue.text.toString())
        assertEquals(activity.getString(R.string.stats_read_failed),panel.message.text.toString())
        panel.message.performClick();assertEquals(1,retries)
        capture("statistics-career-unavailable")
        panel.bind(valid.copy(career=null,flightStreak=null,isLoading=true));layout()
        panel.message.performClick();assertEquals(1,retries)
        assertEquals("—",panel.message.text.toString())
    }
    @Test fun `an obsolete account is scrubbed before drawing and retained country actions are rejected`() {
        panel.bind(state());layout()
        var countries=0
        panel.onCountryRequested={_,_->countries++}
        val old=panel.countryRows.getChildAt(0)
        old.performClick();assertEquals(1,countries)
        panel.interactionAllowed={false};old.performClick();assertEquals(1,countries)
        panel.interactionAllowed={true}
        TokenStore.set("statistics-career-render-next-owner")
        old.performClick();assertEquals(1,countries)
        capture("statistics-career-obsolete-account")
        assertEquals("—",panel.distanceValue.text.toString());assertEquals("—",panel.streakValue.text.toString())
        assertEquals(0,panel.cityRows.childCount);assertEquals(0,panel.countryRows.childCount)
        panel.bind(state());layout();panel.release();old.performClick();assertEquals(1,countries)
        assertEquals("—",panel.flightsValue.text.toString())
    }
}
