package com.projectfocus.feature.stats

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
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
class StatsSkyNativeTest {
    private val date=LocalDate.parse("2026-11-03")
    private val zone=ZoneId.of("Asia/Shanghai")
    @Before fun start() { TokenStore.set("statistics-sky-owner") }
    @After fun finish() { TokenStore.clear() }
    private fun state()=StatsUiState(isLoading=false,
        account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation),
        dates=StatsDateSelection(date),dateRange=StatsDateSelection(date).range(zone),
        nowWallMs=date.atStartOfDay(zone).toInstant().toEpochMilli(),
        overview=StatsOverviewMetrics(95.5,4,3,60.49,2),selectedSceneKey="reading")

    private fun fixture(scale: Float,width: Int,height: Int,action: (View)->Unit) {
        val controller=Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(UiR.style.Theme_Pf)
        val activity=controller.setup().visible().get()
        try {
            val configuration=Configuration(activity.resources.configuration).apply { fontScale=scale }
            val context=ContextThemeWrapper(activity.createConfigurationContext(configuration),UiR.style.Theme_Pf)
            val root=LayoutInflater.from(context).inflate(R.layout.fragment_stats,null)
            StatsSkyPresentation.page(root)
            activity.setContentView(root)
            root.findViewById<StatsDateControls>(R.id.statsDateControls).bind(state(),zone)
            root.findViewById<StatsHeadlineView>(R.id.statsHeadline).bind(state(),zone)
            root.setTag(R.id.statsRoot,intArrayOf(width,height))
            layout(root)
            action(root)
        } finally { controller.pause().stop().destroy() }
    }
    private fun layout(root: View) {
        val size=root.getTag(R.id.statsRoot) as IntArray
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY))
            root.layout(0,0,size[0],size[1])
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        }
    }
    private fun bounds(parent: ViewGroup,child: View)=Rect(0,0,child.width,child.height).also {
        parent.offsetDescendantRectToMyCoords(child,it)
    }
    private fun complete(view: TextView) {
        assertTrue(view.width>0 && view.height>0)
        for(line in 0 until view.layout.lineCount) {
            assertEquals("Truncated ${view.text}",0,view.layout.getEllipsisCount(line))
            assertTrue(view.layout.getLineWidth(line)<=view.width-view.paddingLeft-view.paddingRight+1f)
        }
    }
    private fun capture(root: View,name: String): Bitmap {
        layout(root)
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/statistics/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
        }
        return bitmap
    }

    @Test @Config(qualifiers="zh-rCN-w320dp-h936dp-mdpi")
    fun largeTypeReflowsPeriodsAndDatesWithoutShrinkingOrLosingActions() {
        for(scale in listOf(1.6f,2f)) fixture(scale,320,936) { root ->
            val controls=root.findViewById<StatsDateControls>(R.id.statsDateControls)
            val children=controls.periodButtons+listOf(controls.previous,controls.next,controls.rangeLabel,controls.sceneFilter)
            val rectangles=children.map { bounds(controls,it) }
            for(button in controls.periodButtons+listOf(controls.previous,controls.next,controls.sceneFilter)) {
                assertTrue(button.width>=48 && button.height>=48)
            }
            for(index in rectangles.indices)for(other in index+1 until rectangles.size)
                assertFalse("Controls overlap",Rect.intersects(rectangles[index],rectangles[other]))
            for(label in controls.periodButtons+listOf(controls.rangeLabel,controls.sceneFilterLabel))complete(label)
            val period=controls.periodButtons.last()
            assertEquals(scale,period.resources.configuration.fontScale,.01f)
            assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,12f,
                period.resources.displayMetrics),period.textSize,.01f)
            assertTrue(period.textSize>12f)
            assertTrue(controls.periodButtons.map { bounds(controls,it).top }.toSet().size>1)
            var requested: StatsPeriod?=null
            controls.onPeriodRequested={ requested=it }
            period.performClick()
            assertEquals(StatsPeriod.CUSTOM,requested)
            assertTrue(controls.periodButtons[StatsPeriod.WEEK.ordinal].isSelected)
            var moved=0;controls.onMoveRequested={ moved=it }
            controls.previous.performClick();assertEquals(-1,moved)
            controls.next.performClick();assertEquals(-1,moved)
            assertEquals("1h 36m",root.findViewById<TextView>(R.id.statsFocus).text.toString())
            complete(root.findViewById<StatsHeadlineView>(R.id.statsHeadline).periodLabel)
            capture(root,"statistics-sky-small-$scale").recycle()
        }
    }

    @Test @Config(qualifiers="zh-rCN-w432dp-h960dp-night-mdpi")
    fun nightSceneAndPeriodLabelsStayReadableAndObsoleteCallbacksExpire() {
        fixture(1f,432,960) { root ->
            val controls=root.findViewById<StatsDateControls>(R.id.statsDateControls)
            var cleared=0;controls.onClearSceneRequested={ _,_ -> cleared++ }
            controls.sceneFilter.performClick();assertEquals(1,cleared)
            val bitmap=capture(root,"statistics-sky-night")
            try {
                val selected=controls.periodButtons[StatsPeriod.WEEK.ordinal]
                val rect=bounds(root as ViewGroup,selected)
                assertTrue(ColorUtils.calculateContrast(selected.currentTextColor,bitmap.getPixel(rect.left+8,rect.top+8))>=4.5)
                val scene=bounds(root,controls.sceneFilter)
                assertTrue(ColorUtils.calculateContrast(controls.sceneFilterLabel.currentTextColor,
                    bitmap.getPixel(scene.left+8,scene.top+8))>=4.5)
            } finally { bitmap.recycle() }
            TokenStore.set("statistics-sky-other-owner")
            capture(root,"statistics-sky-obsolete-scene").recycle()
            controls.sceneFilter.performClick();assertEquals(1,cleared)
            assertEquals(View.GONE,controls.sceneFilter.visibility)
        }
    }

    @Test @Config(qualifiers="zh-rCN-w800dp-h600dp-land-mdpi")
    fun wideLayoutKeepsAllPeriodsHeaderActionsAndEmbeddedHeaderPolicy() {
        fixture(1f,800,600) { root ->
            val controls=root.findViewById<StatsDateControls>(R.id.statsDateControls)
            assertEquals(1,controls.periodButtons.map { bounds(controls,it).top }.toSet().size)
            for(button in controls.periodButtons) { complete(button);assertTrue(button.width>=48 && button.height>=48) }
            var back=0;var share=0
            root.findViewById<View>(R.id.statsBack).apply {
                assertTrue(width>=48 && height>=48);setOnClickListener { back++ };performClick()
            }
            root.findViewById<View>(R.id.statsShare).apply {
                assertTrue(width>=48 && height>=48);setOnClickListener { share++ };performClick()
            }
            assertEquals(1,back);assertEquals(1,share)
            capture(root,"statistics-sky-wide").recycle()
            root.findViewById<View>(R.id.statsTitleBar).visibility=View.GONE
            StatsSkyPresentation.page(root)
            assertEquals(View.GONE,root.findViewById<View>(R.id.statsTitleBar).visibility)
            capture(root,"statistics-sky-embedded-wide").recycle()
        }
    }
}
