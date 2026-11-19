package com.projectfocus.feature.stats

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.ContextThemeWrapper
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
@Config(application = Application::class, sdk = [34], qualifiers = "zh-rCN-w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsDateNativeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.parse("2026-11-03")
    private val now = today.atStartOfDay(zone).toInstant().toEpochMilli()
    private var dialog: StatsDateRangeDialog? = null
    private var controller: org.robolectric.android.controller.ActivityController<Activity>? = null
    @Before fun start() { TokenStore.set("statistics-date-native-owner") }
    @After fun finish() { dialog?.dismiss(); controller?.pause()?.stop()?.destroy(); TokenStore.clear() }
    private fun context(): Context = ContextThemeWrapper(org.robolectric.RuntimeEnvironment.getApplication(), UiR.style.Theme_Pf)
    private fun state(dates: StatsDateSelection) = StatsUiState(isLoading=false,
        account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation),dates=dates,dateRange=dates.range(zone),
        nowWallMs=now,canNextPeriod=dates.canMoveForward(now,zone))

    @Test fun `period requests do not select custom before confirmation and future navigation remains disabled`() {
        val activity = activity()
        val control = StatsDateControls(activity)
        val container = FrameLayout(activity).apply {
            setPadding(16,12,16,0)
            addView(control,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        activity.setContentView(container)
        idle()
        val normal = state(StatsDateSelection(today))
        control.bind(normal,zone)
        assertTrue(control.rangeLabel.text.isNotBlank())
        assertEquals("周",control.periodButtons[StatsPeriod.WEEK.ordinal].text.toString())
        var requested: StatsPeriod? = null
        control.onPeriodRequested = { requested = it }
        control.periodButtons[StatsPeriod.CUSTOM.ordinal].performClick()
        assertEquals(StatsPeriod.CUSTOM,requested)
        assertTrue(control.periodButtons[StatsPeriod.WEEK.ordinal].isSelected)
        assertFalse(control.periodButtons[StatsPeriod.CUSTOM.ordinal].isSelected)
        assertFalse(control.next.isEnabled)
        var moved = 0
        control.onMoveRequested = { moved = it }
        control.next.performClick()
        assertEquals(0,moved)
        control.previous.performClick()
        assertEquals(-1,moved)
        capture(container,432,"statistics-period-week",control.periodButtons[StatsPeriod.WEEK.ordinal])
        control.bind(state(StatsDateSelection(today).custom(today.minusDays(1),today)),zone)
        assertEquals(View.INVISIBLE,control.previous.visibility)
        assertEquals(View.INVISIBLE,control.next.visibility)
        assertTrue(control.periodButtons[StatsPeriod.CUSTOM.ordinal].isSelected)
        capture(container,432,"statistics-period-custom",control.periodButtons[StatsPeriod.CUSTOM.ordinal])
        TokenStore.set("statistics-native-next-owner")
        control.bind(normal,zone)
        assertEquals(context().getString(R.string.stats_placeholder),control.rangeLabel.text.toString())
        assertTrue(control.periodButtons.none { it.isEnabled })
    }

    @Test fun `native calendar clamps seed disables future dates and confirms actual selected dates once`() {
        val activity = activity()
        var calls = 0
        var result: Pair<LocalDate,LocalDate>? = null
        dialog = StatsDateRangeDialog(activity,StatsDateSelection(today).range(zone),zone,today) { first,last -> calls++; result=first to last }
        val actual = dialog!!
        actual.show()
        idle()
        assertEquals(today,actual.selection.last)
        assertFalse(day(today.plusDays(1)).isEnabled)
        day(today.minusDays(2)).performClick()
        assertEquals(today.minusDays(2),actual.selection.first)
        assertFalse(actual.selection.editingFirst)
        day(today.minusDays(1)).performClick()
        assertEquals(today.minusDays(1),actual.selection.last)
        capture(actual.window!!.decorView,400,"statistics-date-calendar")
        val confirm = actual.window!!.decorView.findViewWithTag<View>("stats-date-confirm")
        confirm.performClick()
        confirm.performClick()
        assertEquals(1,calls)
        assertEquals(today.minusDays(2) to today.minusDays(1),result)
        assertFalse(actual.isShowing)
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-night-mdpi")
    fun `narrow night calendar preserves cancellation and account guard`() {
        val activity = activity()
        var valid = true
        var applied = false
        val original = StatsDateSelection(today)
        dialog = StatsDateRangeDialog(activity,original.range(zone),zone,today,{valid}) { _,_ -> applied=true }
        dialog!!.show()
        idle()
        assertEquals(288,dialog!!.window!!.attributes.width)
        capture(dialog!!.window!!.decorView,288,"statistics-date-calendar-night-narrow")
        for (tag in listOf("stats-date-first","stats-date-last")) {
            val card=dialog!!.window!!.decorView.findViewWithTag<android.widget.LinearLayout>(tag)
            val value=card.getChildAt(1) as android.widget.TextView
            val available=value.width-value.compoundPaddingLeft-value.compoundPaddingRight
            assertTrue("Date text ${value.text} must fit within $available pixels",value.paint.measureText(value.text.toString())<=available+0.5f)
        }
        valid = false
        dialog!!.window!!.decorView.findViewWithTag<View>("stats-date-confirm").performClick()
        assertFalse(applied)
        assertFalse(dialog!!.isShowing)
        assertEquals(StatsPeriod.WEEK,original.period)
    }

    @Test fun `cancel does not publish the calendar draft`() {
        var applied = false
        dialog = StatsDateRangeDialog(activity(),StatsDateSelection(today).range(zone),zone,today) { _,_ -> applied=true }
        dialog!!.show()
        day(today.minusDays(2)).performClick()
        dialog!!.window!!.decorView.findViewWithTag<View>("stats-date-cancel").performClick()
        assertFalse(applied)
        assertFalse(dialog!!.isShowing)
    }

    private fun activity(): Activity {
        val created = Robolectric.buildActivity(Activity::class.java)
        created.get().setTheme(UiR.style.Theme_Pf)
        controller = created
        return created.setup().visible().get()
    }
    private fun day(date: LocalDate) = dialog!!.window!!.decorView.findViewWithTag<View>("stats-date:$date")
    private fun idle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)) }
    private fun capture(view: View, width: Int, name: String, selected: View? = null) {
        idle()
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.AT_MOST))
            view.layout(0,0,width,view.measuredHeight)
            idle()
        }
        assertTrue(view.measuredHeight>0)
        val bitmap=Bitmap.createBitmap(width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val target=File("build/reports/statistics/$name.png").apply { parentFile?.mkdirs() }
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        if (selected != null) {
            val origin=IntArray(2).also(view::getLocationInWindow)
            val position=IntArray(2).also(selected::getLocationInWindow)
            val bounds=Rect(position[0]-origin[0],position[1]-origin[1],position[0]-origin[0]+selected.width,position[1]-origin[1]+selected.height)
            assertTrue("Selected period bounds $bounds must lie within ${width}x${view.height}",Rect(0,0,width,view.height).contains(bounds))
            var glyphPixels=0
            for(y in bounds.top+bounds.height()/4 until bounds.bottom-bounds.height()/4) {
                for(x in bounds.left+bounds.width()/4 until bounds.right-bounds.width()/4) {
                    val color=bitmap.getPixel(x,y)
                    if(Color.red(color)>230 && Color.green(color)>230 && Color.blue(color)>230) glyphPixels++
                }
            }
            assertTrue("Selected period label must appear in the rendered image",glyphPixels>2)
        }
        bitmap.recycle()
    }
}
