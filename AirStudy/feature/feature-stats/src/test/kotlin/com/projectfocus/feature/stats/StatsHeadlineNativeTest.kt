package com.projectfocus.feature.stats

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.FrameLayout
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
class StatsHeadlineNativeTest {
    private var controller: org.robolectric.android.controller.ActivityController<Activity>? = null
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.parse("2026-11-03")
    private val wall = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var view: StatsHeadlineView
    @Before fun open() {
        TokenStore.set("statistics-headline-owner")
        controller = Robolectric.buildActivity(Activity::class.java).also { it.get().setTheme(UiR.style.Theme_Pf) }
        activity = controller!!.setup().visible().get()
        view = StatsHeadlineView(activity)
        root = FrameLayout(activity).apply {
            setBackgroundResource(UiR.color.stats_background)
            setPadding(16,12,16,0)
            addView(view,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        activity.setContentView(root)
        layout()
    }
    @After fun close() { controller?.pause()?.stop()?.destroy();TokenStore.clear() }
    private fun state(metrics: StatsOverviewMetrics=StatsOverviewMetrics(95.5,4,3,60.49,2)) = StatsUiState(
        isLoading=false,account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation),
        dates=StatsDateSelection(date),dateRange=StatsDateSelection(date).range(zone),nowWallMs=wall,overview=metrics)
    private fun idle(millis: Long=1_000) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private fun layout() {
        val width = activity.resources.configuration.screenWidthDp
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.AT_MOST))
            root.layout(0,0,width,root.measuredHeight)
            idle()
        }
    }
    private fun capture(name: String) {
        layout()
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val origin=IntArray(2).also(root::getLocationInWindow)
        val position=IntArray(2).also(view.focus::getLocationInWindow)
        val bounds=Rect(position[0]-origin[0],position[1]-origin[1],position[0]-origin[0]+view.focus.width,position[1]-origin[1]+view.focus.height)
        assertTrue("Focus value must remain in the image",Rect(0,0,root.width,root.height).contains(bounds))
        var ink=0
        val night=activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        for(y in bounds.top until bounds.bottom) for(x in bounds.left until bounds.right) {
            val color=bitmap.getPixel(x,y)
            if(Color.alpha(color)>200 && if(night)Color.red(color)>180 else Color.red(color)<130)ink++
        }
        assertTrue("Headline digits must appear in the image",ink>20)
        File("build/reports/statistics/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }

    @Test fun `headline renders rounded time and all four metrics with a real period label`() {
        view.bind(state(),zone)
        idle()
        assertFalse(view.isAnimating)
        assertEquals("1h 36m",view.focus.text.toString())
        assertEquals(listOf("4","3","1h","2 天"),view.metrics.map { it.text.toString() })
        assertTrue(view.periodLabel.text.toString().contains("2026"))
        assertEquals(View.GONE,view.comparisonLabel.visibility)
        capture("statistics-headline-day")
    }

    @Test @Config(qualifiers="zh-rCN-w320dp-h640dp-night-mdpi")
    fun `narrow night headline fits large totals and preserves source units`() {
        view.bind(state(StatsOverviewMetrics(123456.0,20000,365,12345.0,365)),zone)
        idle()
        capture("statistics-headline-night-narrow")
        assertEquals("2057h 36m",view.focus.text.toString())
        assertEquals("205h 45m",view.metrics[2].text.toString())
        assertEquals(1,view.focus.layout.lineCount)
        assertTrue(view.focus.layout.getLineWidth(0)<=view.focus.width+0.5f)
    }

    @Test fun `account change and read failure clear every value and cancel obsolete animation`() {
        view.bind(state(),zone)
        idle(32)
        TokenStore.set("statistics-headline-next-owner")
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap));bitmap.recycle()
        idle()
        val placeholder=activity.getString(R.string.stats_placeholder)
        assertEquals(placeholder,view.focus.text.toString())
        assertTrue(view.metrics.all { it.text.toString()==placeholder })
        assertFalse(view.isAnimating)
        view.bind(state(),zone)
        view.bind(state().copy(isLoading=true,readFailed=true,overview=null),zone)
        idle()
        assertEquals(placeholder,view.periodLabel.text.toString())
        assertTrue(view.metrics.all { it.text.toString()==placeholder })
        assertFalse(view.isAnimating)
    }

    @Test fun `comparison badge handles unchanged new and signed percentage values`() {
        for ((comparison,expected) in listOf(
            StatsFocusComparison(0.00001,0.6) to "较上期 持平",
            StatsFocusComparison(10.0,null) to "较上期 新增",
            StatsFocusComparison(10.0,0.255) to "较上期 ↑ 26%",
            StatsFocusComparison(-10.0,-0.255) to "较上期 ↓ 26%")) {
            view.bind(state().copy(focusComparison=comparison),zone)
            assertEquals(View.VISIBLE,view.comparisonLabel.visibility)
            assertEquals(expected,view.comparisonLabel.text.toString())
        }
        view.bind(state(),zone)
        assertEquals(View.GONE,view.comparisonLabel.visibility)
    }

    @Test fun `duration unit spans and number replacement preserve final values`() {
        val color=StatsVisual(activity).secondaryText
        for((minutes,expected) in listOf(0.0 to "0m",59.5 to "1h",60.49 to "1h",60.5 to "1h 1m")) {
            val text=StatsFocusText.rich(activity,minutes,color) as Spanned
            assertEquals(expected,text.toString())
            assertTrue(text.getSpans(0,text.length,RelativeSizeSpan::class.java).all { it.sizeChange==0.52f })
            assertTrue(text.getSpans(0,text.length,ForegroundColorSpan::class.java).all { it.foregroundColor==color })
        }
        view.focus.animateTo(100.0) { Math.round(it).toString() }
        idle(100)
        view.focus.animateTo(7.0) { Math.round(it).toString() }
        idle()
        assertEquals("7",view.focus.text.toString())
        assertFalse(view.focus.isAnimating)
        view.focus.animateTo(500.0) { Math.round(it).toString() }
        view.focus.showStatic("--")
        idle()
        assertEquals("--",view.focus.text.toString())
        assertFalse(view.focus.isAnimating)
    }
}
