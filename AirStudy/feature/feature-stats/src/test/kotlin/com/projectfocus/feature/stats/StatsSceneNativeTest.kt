package com.projectfocus.feature.stats

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
class StatsSceneNativeTest {
    private var controller: org.robolectric.android.controller.ActivityController<Activity>? = null
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var panel: StatsScenePanel
    private lateinit var controls: StatsDateControls
    private val zone=ZoneId.of("Asia/Shanghai")
    private val date=LocalDate.parse("2026-11-03")
    private val rows=listOf(
        StatsSceneEntry("reading","reading","reading",90.0,3),
        StatsSceneEntry("work","work","work",45.0,2),
        StatsSceneEntry("学习","study","study",30.0,1),
        StatsSceneEntry("自己的项目","自己的项目","programming",15.0,1),
        StatsSceneEntry("music","music","music",10.0,1),
        StatsSceneEntry("gaming","gaming","gaming",5.0,1),
        StatsSceneEntry("exercise","exercise","exercise",4.0,1),
        StatsSceneEntry("relax","relax","relax",1.0,1))
    @Before fun open() {
        TokenStore.set("statistics-scenes-owner")
        controller=Robolectric.buildActivity(Activity::class.java).also { it.get().setTheme(UiR.style.Theme_Pf) }
        activity=controller!!.setup().visible().get()
        controls=StatsDateControls(activity)
        panel=StatsScenePanel(activity)
        content=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            addView(controls,LinearLayout.LayoutParams(-1,-2))
            addView(panel,LinearLayout.LayoutParams(-1,-2).apply { topMargin=14 })
        }
        root=FrameLayout(activity).apply { setBackgroundResource(UiR.color.stats_background);setPadding(16,12,16,12);addView(content,FrameLayout.LayoutParams(-1,-2)) }
        activity.setContentView(root)
        layout()
    }
    @After fun close() { controller?.pause()?.stop()?.destroy();TokenStore.clear() }
    private fun state()=StatsUiState(isLoading=false,account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation),
        dates=StatsDateSelection(date),dateRange=StatsDateSelection(date).range(zone),periodScenes=rows,
        allTimeScenes=rows+StatsSceneEntry("Earlier project","Earlier project","creative",0.0,1),nowWallMs=date.atStartOfDay(zone).toInstant().toEpochMilli())
    private fun idle(ms: Long=2_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun layout() {
        val width=activity.resources.configuration.screenWidthDp
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.AT_MOST))
            root.layout(0,0,width,root.measuredHeight)
            idle()
        }
    }
    private fun bind(state: StatsUiState) { controls.bind(state,zone);panel.bind(state);layout() }
    private fun capture(name: String) {
        layout()
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/statistics/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }
    private fun texts(view: View): List<String> = when(view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun `period chart includes every positive scene while the legend shows the six leading rows`() {
        bind(state())
        assertEquals(8,panel.chart.slices.size)
        assertEquals(6,(0 until panel.legend.childCount).count { panel.legend.getChildAt(it) is LinearLayout })
        assertEquals(200.0,panel.chart.totalMinutes,0.0)
        assertEquals(300,panel.chart.height)
        assertEquals("专注场景",panel.title.text.toString())
        assertTrue(texts(panel.legend).contains("1h 30m · 3次 · 45%"))
        assertFalse(panel.chart.isAnimating)
        capture("statistics-scenes-period")
        panel.toggle.performClick();idle()
        assertTrue(panel.allTime)
        assertEquals(9,panel.displayedRows.size)
        assertEquals(8,panel.chart.slices.size)
        assertEquals("全历史场景",panel.title.text.toString())
        panel.toggle.performClick();idle()
        assertFalse(panel.allTime)
    }
    @Test fun `touching the ring shows duration without applying a scene filter`() {
        val requested=mutableListOf<String>()
        panel.onSceneRequested={ key,_->requested+=key }
        bind(state())
        val chart=panel.chart
        val x=chart.width/2f+85f;val y=chart.height/2f
        assertEquals(0,chart.hit(x,y))
        fun touch(action: Int) { MotionEvent.obtain(0,0,action,x,y,0).also { chart.onTouchEvent(it);it.recycle() } }
        touch(MotionEvent.ACTION_DOWN);touch(MotionEvent.ACTION_UP)
        assertEquals(0,chart.touchedIndex)
        assertTrue(requested.isEmpty())
        val bitmap=Bitmap.createBitmap(chart.width,chart.height,Bitmap.Config.ARGB_8888)
        chart.draw(Canvas(bitmap))
        File("build/reports/statistics/statistics-scenes-tooltip.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        idle(2_201)
        assertEquals(-1,chart.touchedIndex)
        panel.legend.getChildAt(0).performClick()
        assertEquals(listOf("reading"),requested)
        assertEquals(-1,chart.hit(chart.width/2f,chart.height/2f))
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h640dp-night-mdpi")
    fun `narrow night display fits a selected custom scene and exposes a separate clear action`() {
        val scene=StatsSceneEntry("一个较长的自定义专注场景名称","custom","programming",123456.0,20000)
        val selected=state().copy(selectedSceneKey=scene.key,periodScenes=listOf(scene),allTimeScenes=listOf(scene))
        var clears=0
        controls.onClearSceneRequested={ _,_->clears++ }
        bind(selected)
        assertEquals(View.VISIBLE,controls.sceneFilter.visibility)
        assertEquals(scene.key,controls.sceneFilterLabel.text.toString())
        assertEquals(scene.key,panel.legend.getChildAt(0).tag)
        assertTrue(panel.legend.getChildAt(0).isSelected)
        assertEquals("一个较长的自定…",panel.chart.slices.single().label)
        assertTrue(controls.sceneFilterLabel.width>0)
        capture("statistics-scenes-night-narrow")
        controls.sceneFilter.performClick()
        assertEquals(1,clears)
    }
    @Test fun `an account change clears old rows and cancels old clicks before the next state arrives`() {
        var clicks=0
        panel.onSceneRequested={ _,_->clicks++ }
        controls.onClearSceneRequested={ _,_->clicks++ }
        bind(state().copy(selectedSceneKey="reading"))
        val old=panel.legend.getChildAt(0)
        panel.toggle.performClick();idle()
        TokenStore.set("statistics-scenes-next-owner")
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap));bitmap.recycle()
        old.performClick();controls.sceneFilter.performClick()
        assertEquals(0,clicks)
        assertFalse(panel.allTime)
        assertTrue(panel.chart.slices.isEmpty())
        assertEquals(0,panel.legend.childCount)
        assertEquals(View.GONE,controls.sceneFilter.visibility)
        assertFalse(panel.chart.isAnimating)
    }
    @Test fun `read failure retains a clearable filter and uses an error instead of successful zero data`() {
        bind(state().copy(selectedSceneKey="reading",isLoading=true,readFailed=true,periodScenes=emptyList(),allTimeScenes=emptyList()))
        assertEquals(View.GONE,panel.chart.visibility)
        assertFalse(panel.toggle.isEnabled)
        assertEquals(listOf(activity.getString(R.string.stats_read_failed)),texts(panel.legend))
        assertEquals(View.VISIBLE,controls.sceneFilter.visibility)
        assertEquals("阅读",controls.sceneFilterLabel.text.toString())
        bind(state().copy(periodScenes=emptyList(),allTimeScenes=emptyList()))
        assertEquals(View.VISIBLE,panel.chart.visibility)
        assertEquals(listOf("暂无专注数据"),texts(panel.legend))
        capture("statistics-scenes-empty")
    }

    @Test fun `zero duration still shows the completed flight and leaves the ring unsegmented`() {
        val zero=StatsSceneEntry("study","study","study",0.0,1)
        bind(state().copy(periodScenes=listOf(zero),allTimeScenes=listOf(zero)))
        assertTrue(panel.chart.slices.isEmpty())
        assertTrue(texts(panel.legend).contains("0m · 1次 · 0%"))
        val bitmap=Bitmap.createBitmap(panel.chart.width,panel.chart.height,Bitmap.Config.ARGB_8888)
        panel.chart.draw(Canvas(bitmap))
        assertEquals(android.graphics.Color.BLACK,bitmap.getPixel(panel.chart.width/2+95,panel.chart.height/2))
        bitmap.recycle()
    }
}
