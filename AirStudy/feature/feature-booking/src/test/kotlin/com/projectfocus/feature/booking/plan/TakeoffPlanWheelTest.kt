package com.projectfocus.feature.booking.plan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuffColorFilter
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.booking.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration
import com.projectfocus.core.ui.R as CoreUiR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TakeoffPlanWheelTest {
    private fun context() = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), CoreUiR.style.Theme_Pf)

    @Test
    fun `actual slow drag selects once while programmatic binding and end stops stay silent`() {
        val wheel = SourceStringWheelPicker(context())
        val changes = mutableListOf<Int>()
        wheel.onItemSelected = { changes += it }
        wheel.setData((0..23).map { "%02d".format(it) })
        wheel.setSelectedIndex(9)
        layoutWheel(wheel)
        assertThat(changes).isEmpty()
        drag(wheel, -36f)
        assertThat(wheel.getSelectedItem()).isEqualTo("10")
        assertThat(changes).containsExactly(10)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        wheel.computeScroll()
        assertThat(changes).containsExactly(10)
        wheel.setSelectedIndex(23)
        drag(wheel, -36f)
        assertThat(wheel.getSelectedItem()).isEqualTo("23")
        wheel.setSelectedIndex(0)
        drag(wheel, 36f)
        assertThat(wheel.getSelectedItem()).isEqualTo("00")
        assertThat(changes).containsExactly(10)
        wheel.isEnabled = false
        drag(wheel, -36f)
        assertThat(wheel.getSelectedItem()).isEqualTo("00")
        assertThat(changes).containsExactly(10)
    }

    @Test
    fun `native canvas renders adjacent rows and a distinct selected center rather than one text proxy`() {
        val wheel = SourceStringWheelPicker(context())
        wheel.setData((0..23).map { "%02d".format(it) })
        wheel.setSelectedIndex(12)
        wheel.setColors(Color.BLUE, Color.RED)
        layoutWheel(wheel)
        val bitmap = Bitmap.createBitmap(60, 120, Bitmap.Config.ARGB_8888)
        try {
            wheel.draw(Canvas(bitmap))
            fun pixels(start: Int, end: Int, red: Boolean): Int {
                var count = 0
                for (y in start until end) for (x in 0 until 60) {
                    val color = bitmap.getPixel(x, y)
                    val matches = if (red) Color.red(color) > Color.blue(color) else Color.blue(color) > Color.red(color)
                    if (Color.alpha(color) > 0 && matches) count++
                }
                return count
            }
            assertThat(pixels(0, 40, false)).isGreaterThan(0)
            assertThat(pixels(40, 80, true)).isGreaterThan(0)
            assertThat(pixels(80, 120, false)).isGreaterThan(0)
        } finally { bitmap.recycle() }
    }

    @Test
    fun `typed render is silent actual wheel gesture returns both values and scene styling resets`() {
        val view = TakeoffPlanView(context())
        val changes = mutableListOf<Pair<Int,Int>>()
        var popups = 0
        view.callbacks = TakeoffPlanCallbacks(onDepartureTimeChanged = { hour, minute -> changes += hour to minute },
            onSelectTime = { popups++ })
        val state = TakeoffPlanUiState(editorStep = TakeoffPlanEditorStep.DEPARTURE_TIME, canSave = true,
            departureHour = 4, departureMinute = 10, departureHourText = "09", departureMinuteText = "35",
            sceneText = "", sceneIconRes = R.drawable.pf_tp_ic_focus_study, sceneColor = Color.MAGENTA)
        view.render(state)
        layoutPlan(view)
        view.render(state)
        layoutPlan(view)
        val hour = view.findViewById<SourceStringWheelPicker>(R.id.pf_tp_floating_time_hour)
        assertThat(hour.getSelectedItem()).isEqualTo("04")
        assertThat(changes).isEmpty()
        drag(hour, -36f)
        assertThat(changes).containsExactly(5 to 10)
        assertThat(popups).isEqualTo(0)
        val icon = view.findViewById<ImageView>(R.id.pf_tp_floating_scene_icon)
        assertThat(icon.visibility).isEqualTo(View.VISIBLE)
        assertThat(icon.drawable).isNotNull()
        assertThat(shadowOf(icon.colorFilter as PorterDuffColorFilter).color).isEqualTo(Color.MAGENTA)
        assertThat(view.findViewById<TextView>(R.id.pf_tp_floating_scene_text).text.toString()).isEmpty()
        assertThat(view.findViewById<TextView>(R.id.pf_tp_floating_time_save).text.toString()).isEqualTo("预览")
        view.render(state.copy(canSave = false, sceneText = null, sceneIconRes = null, sceneColor = null,
            departureHour = 99, timeActionText = "最早 04:15"))
        assertThat(hour.getSelectedItem()).isNull()
        assertThat(icon.visibility).isEqualTo(View.GONE)
        assertThat(icon.drawable).isNull()
        assertThat(view.findViewById<TextView>(R.id.pf_tp_floating_time_save).text.toString()).isEqualTo("最早 04:15")
        assertThat(view.findViewById<View>(R.id.pf_tp_floating_time_save).alpha).isWithin(.001f).of(.45f)
        assertThat(view.findViewById<View>(R.id.pf_tp_floating_time_save).isEnabled).isFalse()
        assertThat(changes).containsExactly(5 to 10)
    }

    @Test
    fun `compact confirm mode has distinct host action and host can allow source next with no route or scene`() {
        val view = TakeoffPlanView(context())
        val actions = mutableListOf<String>()
        view.callbacks = TakeoffPlanCallbacks(onSelectDestination = { actions += "edit" },
            onConfirmPlan = { actions += "confirm-plan" }, onConfirmDestination = { actions += "next" })
        val base = TakeoffPlanUiState(panel = TakeoffPlanPanel.ADD_SEGMENT_COMPACT, canSave = true)
        view.render(base)
        layoutPlan(view)
        val action = view.findViewById<TextView>(R.id.pf_tp_set_destination_action)
        assertThat(action.text.toString()).isEqualTo("1/3 设置终点")
        action.performClick()
        view.render(base.copy(confirmMode = true))
        assertThat(action.text.toString()).isEqualTo("保存")
        assertThat(actions).containsExactly("edit")
        action.performClick()
        view.render(TakeoffPlanUiState(canSave = true))
        view.findViewById<View>(R.id.pf_tp_floating_save).performClick()
        assertThat(actions).containsExactly("edit", "confirm-plan", "next").inOrder()
        view.render(base.copy(confirmMode = true, canSave = false))
        action.performClick()
        assertThat(actions).containsExactly("edit", "confirm-plan", "next").inOrder()
    }

    @Test
    fun `compact scene rebind replaces template icon and tint and clears absent metadata`() {
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        try {
            val view = TakeoffPlanView(context())
            controller.get().setContentView(view)
            val first = TakeoffPlanSegmentUi("draft", null, null, sceneText = "A",
                sceneIconRes = R.drawable.pf_tp_ic_focus_study, sceneColor = Color.RED)
            val state = TakeoffPlanUiState(panel = TakeoffPlanPanel.ADD_SEGMENT_COMPACT, segments = listOf(first))
            view.render(state)
            layoutPlan(view)
            val card = view.findViewById<View>(R.id.pf_tp_add_segment_compact_container)
            val icon = card.findViewById<ImageView>(R.id.pf_tp_focus_icon)
            val title = card.findViewById<TextView>(R.id.pf_tp_focus_scene)
            assertThat(icon.imageTintList?.defaultColor).isEqualTo(Color.RED)
            view.render(state.copy(segments = listOf(first.copy(sceneText = "B",
                sceneIconRes = CoreUiR.drawable.pf_scene_ic_edit_card, sceneColor = Color.BLUE))))
            layoutPlan(view)
            assertThat(icon.isShown).isTrue()
            assertThat(shadowOf(icon.drawable).createdFromResId).isEqualTo(CoreUiR.drawable.pf_scene_ic_edit_card)
            assertThat(icon.imageTintList?.defaultColor).isEqualTo(Color.BLUE)
            assertThat(title.currentTextColor).isEqualTo(Color.BLUE)
            assertThat(title.text.toString()).isEqualTo("B")
            view.render(state.copy(segments = listOf(first.copy(sceneText = null, sceneIconRes = null, sceneColor = null))))
            assertThat(icon.visibility).isEqualTo(View.VISIBLE)
            assertThat(shadowOf(icon.drawable).createdFromResId).isEqualTo(R.drawable.pf_tp_ic_focus_study)
            assertThat(icon.imageTintList).isNull()
            assertThat(title.currentTextColor).isEqualTo(Color.WHITE)
            assertThat(title.text.toString()).isEqualTo(view.context.getString(R.string.pf_tp_common_not_set))
        } finally { controller.pause().stop().destroy() }
    }

    private fun layoutWheel(view: SourceStringWheelPicker) {
        view.measure(View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
        view.layout(0,0,60,120)
    }

    private fun layoutPlan(view: TakeoffPlanView) {
        view.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0,0,view.measuredWidth,view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun drag(view: View, delta: Float) {
        val start = SystemClock.uptimeMillis()
        listOf(Triple(MotionEvent.ACTION_DOWN,0L,60f),Triple(MotionEvent.ACTION_MOVE,400L,60f+delta),
            Triple(MotionEvent.ACTION_UP,800L,60f+delta)).forEach { (action,elapsed,y) ->
            val event = MotionEvent.obtain(start,start+elapsed,action,30f,y,0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
}
