package com.projectfocus.feature.record.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.projectfocus.feature.record.R
import com.projectfocus.core.ui.R as UiR
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="zh-rCN-w320dp-h568dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordSkyNativeTest {
    private fun context(scale: Float): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply { fontScale=scale }
        return ContextThemeWrapper(base.createConfigurationContext(configuration), UiR.style.Theme_Pf)
    }

    private fun component(context: Context): ViewGroup {
        val inflater = LayoutInflater.from(context)
        val host = inflater.inflate(R.layout.pf_record_screen, FrameLayout(context), false) as ViewGroup
        val pager = host.findViewById<ViewPager2>(R.id.pf_record_page_main_view_pager)
        val owner = pager.parent as ViewGroup
        val index = owner.indexOfChild(pager)
        val params = pager.layoutParams
        owner.removeView(pager)
        val page = inflater.inflate(R.layout.pf_record_page, owner, false)
        owner.addView(page,index,params)
        page.findViewById<View>(R.id.pf_record_page_empty_records_hint).visibility=View.VISIBLE
        page.findViewById<TextView>(R.id.pf_record_page_spinner_status).setText(R.string.pf_record_page_status_all)
        page.findViewById<TabLayout>(R.id.pf_record_page_record_tabs).getTabAt(3)!!.select()
        page.findViewById<View>(R.id.pf_record_page_page_indicator_container).visibility=View.GONE
        RecordSkyPresentation.host(host)
        RecordSkyPresentation.page(page)
        return host
    }

    private fun measure(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        root.layout(0,0,width,height)
    }

    private fun bounds(root: ViewGroup, child: View): Rect = Rect(0,0,child.width,child.height).also {
        root.offsetDescendantRectToMyCoords(child,it)
    }

    private fun capture(root: View, name: String) {
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val file=File("build/reports/records/$name.png")
            checkNotNull(file.parentFile).mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }

    @Test fun twoHundredPercentTextKeepsNarrowFiltersAndBackActionReachable() {
        val root=component(context(2f))
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            controller.get().setContentView(root)
            root.findViewById<TextView>(R.id.pf_record_page_empty_records_hint).setText(R.string.pf_record_page_loading)
            RecordSkyPresentation.state(root,false,false)
            measure(root,320,568)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            measure(root,320,568)
            val back=root.findViewById<View>(R.id.pf_record_page_btn_back)
            assertTrue(back.width>=48 && back.height>=48)
            val title=root.findViewById<TextView>(R.id.pf_record_page_text_title)
            assertEquals(2f,title.resources.configuration.fontScale,.01f)
            assertEquals(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,22f,title.resources.displayMetrics)).toFloat(),title.textSize,.01f)
            assertTrue(title.textSize>22f)
            val input=root.findViewById<View>(R.id.pf_record_page_spinner_status)
            val tabs=root.findViewById<TabLayout>(R.id.pf_record_page_record_tabs)
            assertTrue(bounds(root,input).bottom<=bounds(root,tabs).top)
            assertTrue(bounds(root,tabs).right<=320)
            for(index in 0 until tabs.tabCount) {
                val tab=tabs.getTabAt(index)!!.view
                assertTrue(tab.width>=48 && tab.height>=48)
                val labels=ArrayList<View>()
                tab.findViewsWithText(labels,tabs.getTabAt(index)!!.text,View.FIND_VIEWS_WITH_TEXT)
                for(label in labels.filterIsInstance<TextView>())
                    for(line in 0 until label.layout.lineCount)assertEquals(0,label.layout.getEllipsisCount(line))
            }
            capture(root,"records-component-small-2.0-loading")
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(qualifiers="zh-rCN-w432dp-h960dp-night-mdpi")
    fun nightLoadingRetryAndEmptyRemainDistinctAndReadable() {
        val root=component(context(1f))
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            controller.get().setContentView(root)
            val hint=root.findViewById<TextView>(R.id.pf_record_page_empty_records_hint)
            hint.setText(R.string.pf_record_page_loading)
            RecordSkyPresentation.state(root,false,false)
            measure(root,432,960)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(View.VISIBLE,root.findViewById<View>(R.id.pf_record_page_loading_icon).visibility)
            capture(root,"records-component-night-loading")
            var retries=0
            hint.setOnClickListener { retries++ }
            hint.setText(R.string.pf_record_page_read_failed)
            RecordSkyPresentation.state(root,false,true)
            measure(root,432,960)
            assertEquals(View.GONE,root.findViewById<View>(R.id.pf_record_page_loading_icon).visibility)
            assertTrue(hint.height>=48)
            assertTrue(ColorUtils.calculateContrast(hint.currentTextColor,root.context.getColor(UiR.color.sky_card_solid))>=4.5)
            hint.performClick()
            assertEquals(1,retries)
            capture(root,"records-component-night-retry")
            hint.setOnClickListener(null)
            hint.setText(R.string.pf_record_page_no_records)
            RecordSkyPresentation.state(root,true,false)
            measure(root,432,960)
            assertEquals(View.GONE,root.findViewById<View>(R.id.pf_record_page_loading_icon).visibility)
            assertFalse(hint.isClickable)
            capture(root,"records-component-night-empty")
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(qualifiers="zh-rCN-w720dp-h600dp-notnight-mdpi")
    fun wideFiltersAndDecoratedNavigationKeepTheirNativeActions() {
        val root=component(context(1f))
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            controller.get().setContentView(root)
            RecordSkyPresentation.state(root,true,false)
            measure(root,720,600)
            val input=root.findViewById<View>(R.id.pf_record_page_spinner_status)
            val tabs=root.findViewById<TabLayout>(R.id.pf_record_page_record_tabs)
            assertTrue(bounds(root,input).right<=bounds(root,tabs).left)
            var backCalls=0
            root.findViewById<View>(R.id.pf_record_page_btn_back).setOnClickListener { backCalls++ }
            root.findViewById<View>(R.id.pf_record_page_btn_back).performClick()
            assertEquals(1,backCalls)
            tabs.getTabAt(1)!!.view.performClick()
            assertEquals(1,tabs.selectedTabPosition)
            val main=root.findViewById<TabLayout>(R.id.pf_record_page_main_tabs)
            main.getTabAt(1)!!.view.performClick()
            assertEquals(1,main.selectedTabPosition)
            assertTrue("Wide navigation must fill its shared surface",main.getTabAt(0)!!.view.width>=(main.width-16)/2-1)
            main.getTabAt(0)!!.view.performClick()
            assertEquals(0,main.selectedTabPosition)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            capture(root,"records-component-wide-day")
        } finally { controller.pause().stop().destroy() }
    }
}
