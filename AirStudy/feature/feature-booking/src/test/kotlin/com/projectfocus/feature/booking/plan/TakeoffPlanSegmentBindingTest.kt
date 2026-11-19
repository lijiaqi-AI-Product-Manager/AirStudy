package com.projectfocus.feature.booking.plan

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.card.MaterialCardView
import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.booking.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.projectfocus.core.ui.R as CoreUiR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TakeoffPlanSegmentBindingTest {
    private val origin = TakeoffPlanAirportUi("PEK", "北京")
    private val destination = TakeoffPlanAirportUi("SHA", "上海")

    private fun segment(id: String = "leg-a") = TakeoffPlanSegmentUi(
        id, origin, destination, departureTimeText = "09:10", arrivalTimeText = "10:28",
        distanceText = "1,035.4 km", sceneText = "自定义标题", statusText = "等待起飞",
    )

    private fun state(segment: TakeoffPlanSegmentUi) = TakeoffPlanUiState(
        panel = TakeoffPlanPanel.EXPANDED, segments = listOf(segment),
    )

    private fun withView(night: Boolean = false, block: (TakeoffPlanView) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val config = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val view = TakeoffPlanView(ContextThemeWrapper(app.createConfigurationContext(config), CoreUiR.style.Theme_Pf))
            controller.get().setContentView(view, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            layout(view)
            assertThat(view.isAttachedToWindow).isTrue()
            block(view)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun layout(view: View) {
        val width = (390 * view.resources.displayMetrics.density).toInt()
        repeat(3) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, width, view.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun row(view: View, position: Int = 0): View = checkNotNull(
        view.findViewById<RecyclerView>(R.id.pf_tp_segment_list).findViewHolderForAdapterPosition(position),
    ).itemView

    private fun popup(): PopupWindow = checkNotNull(
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).latestPopupWindow,
    ).also { assertThat(it.isShowing).isTrue() }

    @Test
    fun `real recycled row clears source tint alpha cancellation time and delay while retaining raw title`() {
        listOf(false, true).forEach { night -> withView(night) { view ->
            val green = 0xff3cc612.toInt()
            val canceled = view.context.getColor(R.color.pf_tp_plan_status_canceled)
            val first = segment().copy(sceneColor = green, sceneIconRes = R.drawable.pf_tp_ic_focus_study,
                statusColor = canceled, timeColor = canceled, terminalAlpha = .45f, statusTextStyle = 3,
                departureDelayText = "+00:03", arrivalDelayText = "+00:03")
            view.render(state(first))
            layout(view)
            val list = view.findViewById<RecyclerView>(R.id.pf_tp_segment_list)
            val holder = checkNotNull(list.findViewHolderForAdapterPosition(0))
            val oldRow = holder.itemView
            val icon = oldRow.findViewById<ImageView>(R.id.pf_tp_focus_icon)
            assertThat(shadowOf(icon.colorFilter as PorterDuffColorFilter).color).isEqualTo(green)
            icon.isPressed = true
            icon.refreshDrawableState()
            assertThat(icon.imageTintList!!.defaultColor).isEqualTo(green)
            icon.isPressed = false
            assertThat(oldRow.alpha).isWithin(.0001f).of(.45f)
            assertThat(oldRow.findViewById<TextView>(R.id.pf_tp_departure_time).currentTextColor).isEqualTo(canceled)
            assertThat(oldRow.findViewById<TextView>(R.id.pf_tp_segment_status).currentTextColor).isEqualTo(canceled)
            assertThat(oldRow.findViewById<TextView>(R.id.pf_tp_segment_status).typeface.style).isEqualTo(3)
            assertThat(oldRow.findViewById<View>(R.id.pf_tp_departure_delay).visibility).isEqualTo(View.VISIBLE)

            // Rebind the same real holder through its adapter, rather than create
            // another view whose empty defaults could hide a recycled tint bug.
            view.render(state(first.copy(sceneText = "  \u00a0  ", sceneColor = 0, sceneIconRes = null,
                statusColor = null, timeColor = null, terminalAlpha = 1f, statusTextStyle = 0,
                departureDelayText = null, arrivalDelayText = null)))
            @Suppress("UNCHECKED_CAST")
            val adapter = list.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
            adapter.onBindViewHolder(holder, 0)
            assertThat(holder.itemView).isSameInstanceAs(oldRow)
            assertThat(icon.colorFilter).isNull()
            assertThat(icon.imageTintList).isNull()
            assertThat(icon.drawable).isNotNull()
            assertThat(icon.visibility).isEqualTo(View.VISIBLE)
            val primary = if (night) Color.WHITE else 0xff1c1c1e.toInt()
            assertThat(oldRow.findViewById<TextView>(R.id.pf_tp_focus_scene).currentTextColor).isEqualTo(primary)
            assertThat(oldRow.findViewById<TextView>(R.id.pf_tp_focus_scene).text.toString()).isEqualTo("\u00a0")
            assertThat(oldRow.findViewById<TextView>(R.id.pf_tp_departure_time).currentTextColor).isEqualTo(primary)
            assertThat(oldRow.findViewById<TextView>(R.id.pf_tp_segment_status).currentTextColor).isEqualTo(0xfff2bb31.toInt())
            assertThat(oldRow.findViewById<TextView>(R.id.pf_tp_segment_status).typeface.style).isEqualTo(0)
            assertThat(oldRow.alpha).isWithin(.0001f).of(1f)
            assertThat(oldRow.findViewById<View>(R.id.pf_tp_departure_delay).visibility).isEqualTo(View.GONE)
            assertThat((oldRow as MaterialCardView).cardBackgroundColor.defaultColor)
                .isEqualTo(if (night) 0x33ffffff else 0xebffffff.toInt())
        } }
    }

    @Test
    fun `draft white fallback and full list day theme remain separate for empty source scene`() {
        withView { view ->
            val item = segment().copy(sceneText = " \t ", sceneColor = 0)
            view.render(state(item).copy(panel = TakeoffPlanPanel.ADD_SEGMENT_COMPACT))
            layout(view)
            val draft = view.findViewById<View>(R.id.pf_tp_add_segment_compact_container)
            assertThat(draft.findViewById<TextView>(R.id.pf_tp_focus_scene).currentTextColor).isEqualTo(Color.WHITE)
            assertThat(draft.findViewById<TextView>(R.id.pf_tp_focus_scene).text.toString())
                .isEqualTo(view.context.getString(R.string.pf_tp_common_not_set))
            assertThat(draft.findViewById<ImageView>(R.id.pf_tp_focus_icon).colorFilter).isNull()
            view.render(state(item))
            layout(view)
            assertThat(row(view).findViewById<TextView>(R.id.pf_tp_focus_scene).currentTextColor).isEqualTo(0xff1c1c1e.toInt())
        }
    }

    @Test
    fun `real row long press shows source popup rows and dispatches only allowed current actions`() {
        withView { view ->
            val events = mutableListOf<String>()
            view.callbacks = TakeoffPlanCallbacks(onEditSegment = { events += "edit:$it" },
                onRemoveSegment = { events += "delete:$it" })
            val item = segment().copy(contextMenuEnabled = true)
            view.render(state(item))
            layout(view)
            assertThat(row(view).performLongClick()).isTrue()
            val disabled = popup()
            val content = disabled.contentView as LinearLayout
            assertThat(content.id).isEqualTo(R.id.pf_tp_segment_menu)
            assertThat(content.childCount).isEqualTo(2)
            assertThat(content.paddingLeft).isEqualTo((5 * view.resources.displayMetrics.density).toInt())
            assertThat((content.background as GradientDrawable).cornerRadius)
                .isWithin(.01f).of(10 * view.resources.displayMetrics.density)
            listOf(R.id.pf_tp_segment_menu_edit, R.id.pf_tp_segment_menu_delete).forEach { id ->
                val control = content.findViewById<LinearLayout>(id)
                assertThat(control.isEnabled).isFalse()
                assertThat((control.getChildAt(0) as TextView).textSize)
                    .isWithin(.01f).of(12 * view.resources.displayMetrics.scaledDensity)
                control.performClick()
            }
            assertThat(events).isEmpty()
            disabled.dismiss()
            assertThat(events).isEmpty()

            view.render(state(item.copy(editable = true, removable = true)))
            layout(view)
            assertThat(row(view).performLongClick()).isTrue()
            val editPopup = popup()
            editPopup.contentView.findViewById<View>(R.id.pf_tp_segment_menu_edit).performClick()
            assertThat(editPopup.isShowing).isFalse()
            assertThat(events).containsExactly("edit:leg-a")
            assertThat(row(view).performLongClick()).isTrue()
            val deletePopup = popup()
            deletePopup.contentView.findViewById<View>(R.id.pf_tp_segment_menu_delete).performClick()
            assertThat(deletePopup.isShowing).isFalse()
            assertThat(events).containsExactly("edit:leg-a", "delete:leg-a").inOrder()

            assertThat(row(view).performLongClick()).isTrue()
            val stale = popup()
            val oldDelete = stale.contentView.findViewById<View>(R.id.pf_tp_segment_menu_delete)
            val oldRow = row(view)
            view.render(state(item).copy(loading = true))
            assertThat(stale.isShowing).isFalse()
            oldDelete.performClick()
            assertThat(oldRow.performLongClick()).isFalse()
            assertThat(events).containsExactly("edit:leg-a", "delete:leg-a").inOrder()
        }
    }
}
