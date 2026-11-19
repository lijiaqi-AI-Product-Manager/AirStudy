package com.projectfocus.feature.booking.plan

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.card.MaterialCardView
import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.booking.R
import java.io.File
import java.security.MessageDigest
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
class TakeoffPlanViewTest {

    private val pek = TakeoffPlanAirportUi("PEK", "北京")
    private val sha = TakeoffPlanAirportUi("SHA", "上海")
    private fun segment(id: String = "leg-a") = TakeoffPlanSegmentUi(
        id, pek, sha, departureTimeText = "09:10", arrivalTimeText = "10:28",
        distanceText = "1,035.4 km", sceneText = "学习", statusText = "等待起飞",
    )
    private fun selected(panel: TakeoffPlanPanel = TakeoffPlanPanel.FLOATING_DESTINATION) = TakeoffPlanUiState(
        panel = panel, origin = pek, destination = sha, segments = listOf(segment()),
        dateText = "10月30日", totalDistanceText = "1,035.4 km", seatText = "12A",
        canSave = true, canAddSegment = true,
    )

    private fun newView(night: Boolean = false, fontScale: Float = 1f): TakeoffPlanView {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            this.fontScale = fontScale
        }
        return TakeoffPlanView(ContextThemeWrapper(
            app.createConfigurationContext(configuration), com.projectfocus.core.ui.R.style.Theme_Pf,
        ))
    }

    private fun layout(view: TakeoffPlanView, widthDp: Int = 390) {
        val width = (widthDp * view.resources.displayMetrics.density).toInt()
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun texts(view: View): List<String> {
        val own = if (view is TextView) listOf(view.text.toString()) else emptyList()
        return own + if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    }

    @Test
    fun `empty input contains no source example airports or times and respects host save denial`() {
        val view = newView()
        var saves = 0
        view.callbacks = TakeoffPlanCallbacks(onConfirmDestination = { saves++ })
        view.render(TakeoffPlanUiState(canSave = false))
        layout(view)
        val floating = view.findViewById<View>(R.id.pf_tp_floating_destination_container)
        assertThat(floating.visibility).isEqualTo(View.VISIBLE)
        assertThat(view.findViewById<TextView>(R.id.pf_tp_floating_dep_code).text.toString()).isEqualTo("N/A")
        assertThat(view.findViewById<TextView>(R.id.pf_tp_floating_arr_code).text.toString()).isEqualTo("N/A")
        assertThat(texts(view)).containsNoneOf("HSC", "FOC", "8:00", "8:45")
        val save = floating.findViewById<View>(R.id.pf_tp_floating_save)
        assertThat(save.isEnabled).isFalse()
        save.performClick()
        assertThat(saves).isEqualTo(0)
        assertThat(view.measuredHeight).isGreaterThan(0)
    }

    @Test
    fun `four panels are mutually exclusive across repeated rebinding`() {
        val view = newView()
        val panels = mapOf(
            TakeoffPlanPanel.FLOATING_DESTINATION to R.id.pf_tp_floating_destination_container,
            TakeoffPlanPanel.SET_DESTINATION_COMPACT to R.id.pf_tp_set_destination_compact_container,
            TakeoffPlanPanel.ADD_SEGMENT_COMPACT to R.id.pf_tp_add_segment_compact_container,
            TakeoffPlanPanel.EXPANDED to R.id.pf_tp_expanded_panel,
        )
        (panels.keys + panels.keys.reversed()).forEach { current ->
            view.render(selected(current))
            layout(view)
            panels.forEach { (panel, id) ->
                assertThat(view.findViewById<View>(id).visibility)
                    .isEqualTo(if (current == panel) View.VISIBLE else View.GONE)
            }
        }
    }

    @Test
    fun `selected route edits and save dispatch only their explicit host actions`() {
        val view = newView()
        val actions = mutableListOf<String>()
        view.callbacks = TakeoffPlanCallbacks(
            onSelectOrigin = { actions += "origin" }, onSelectDestination = { actions += "destination" },
            onConfirmDestination = { actions += "save" }, onExpand = { actions += "expand:$it" },
        )
        view.render(selected())
        layout(view)
        view.findViewById<View>(R.id.pf_tp_floating_origin).performClick()
        view.findViewById<View>(R.id.pf_tp_floating_destination).performClick()
        view.findViewById<View>(R.id.pf_tp_floating_save).performClick()
        assertThat(actions).containsExactly("origin", "destination", "save").inOrder()
        view.render(selected(TakeoffPlanPanel.SET_DESTINATION_COMPACT))
        view.findViewById<View>(R.id.pf_tp_header).performClick()
        assertThat(actions.last()).isEqualTo("expand:true")
        assertThat(view.findViewById<TextView>(R.id.pf_tp_set_destination_route).text.toString())
            .isEqualTo("北京 PEK → 上海 SHA")
    }

    @Test
    fun `binding is silent and loading blocks actions until resolved`() {
        val view = newView()
        val actions = mutableListOf<String>()
        view.callbacks = TakeoffPlanCallbacks(
            onConfirmDestination = { actions += "save" }, onSelectDestination = { actions += "destination" },
            onPlanEnabled = { actions += "toggle:$it" }, onSeat = { actions += "seat" },
            onAddSegment = { actions += "add" }, onDateOffset = { actions += "date:$it" },
        )
        view.render(selected(TakeoffPlanPanel.EXPANDED).copy(planEnabled = true, loading = true))
        layout(view)
        assertThat(actions).isEmpty()
        listOf(R.id.pf_tp_prev_date, R.id.pf_tp_next_date, R.id.pf_tp_add_segment, R.id.pf_tp_flight_seat)
            .forEach { view.findViewById<View>(it).performClick() }
        assertThat(actions).isEmpty()
        assertThat(view.findViewById<View>(R.id.pf_tp_loading).visibility).isEqualTo(View.VISIBLE)
        view.render(selected(TakeoffPlanPanel.EXPANDED).copy(planEnabled = true))
        layout(view)
        assertThat(actions).isEmpty()
        view.findViewById<View>(R.id.pf_tp_prev_date).performClick()
        view.findViewById<View>(R.id.pf_tp_next_date).performClick()
        view.findViewById<View>(R.id.pf_tp_add_segment).performClick()
        view.findViewById<View>(R.id.pf_tp_flight_seat).performClick()
        view.findViewById<SwitchCompat>(R.id.pf_tp_switch).isChecked = false
        assertThat(actions).containsExactly("date:-1", "date:1", "add", "seat", "toggle:false").inOrder()
    }

    @Test
    fun `expanded rows use supplied values and clear after empty update`() {
        val view = newView()
        val second = segment("leg-b").copy(distanceText = null, departureTimeText = null, arrivalTimeText = null)
        view.render(selected(TakeoffPlanPanel.EXPANDED).copy(segments = listOf(segment(), second)))
        layout(view)
        val list = view.findViewById<RecyclerView>(R.id.pf_tp_segment_list)
        assertThat(list.adapter!!.itemCount).isEqualTo(2)
        assertThat(texts(list)).contains("1,035.4 km")
        assertThat(texts(list)).contains("09:10")
        assertThat(texts(list)).contains("--")
        assertThat(texts(list)).containsNoneOf("HSC", "FOC", "8:00", "8:45")
        view.render(TakeoffPlanUiState(panel = TakeoffPlanPanel.EXPANDED))
        layout(view)
        assertThat(list.adapter!!.itemCount).isEqualTo(0)
        assertThat(texts(list)).doesNotContain("1,035.4 km")
        assertThat(view.findViewById<TextView>(R.id.pf_tp_total_distance).text.toString()).isEqualTo("N/A")
    }

    @Test
    fun `removal is an explicit accessible action for a real removable segment`() {
        val view = newView()
        val removed = mutableListOf<String>()
        view.callbacks = TakeoffPlanCallbacks(onRemoveSegment = { removed += it })
        view.render(selected(TakeoffPlanPanel.EXPANDED).copy(segments = listOf(segment().copy(removable = true))))
        layout(view)
        val list = view.findViewById<RecyclerView>(R.id.pf_tp_segment_list)
        val row = list.findViewHolderForAdapterPosition(0)!!.itemView
        assertThat(row.contentDescription.toString()).contains("北京 PEK")
        assertThat(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null)).isTrue()
        assertThat(removed).containsExactly("leg-a")
        view.render(selected(TakeoffPlanPanel.EXPANDED).copy(loading = true))
        row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null)
        assertThat(removed).containsExactly("leg-a")
        view.render(TakeoffPlanUiState(panel = TakeoffPlanPanel.EXPANDED))
        row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null)
        assertThat(removed).containsExactly("leg-a")
    }

    @Test
    fun `scene selection and source time preview retain separate actions`() {
        val view = newView()
        val actions = mutableListOf<String>()
        view.callbacks = TakeoffPlanCallbacks(
            onSelectScene = { actions += "select-scene" }, onConfirmScene = { actions += "save-scene" },
            onSelectTime = { actions += "unexpected-popup" }, onConfirmTime = { actions += "preview-time" },
        )
        view.render(selected().copy(editorStep = TakeoffPlanEditorStep.SCENE, sceneText = "阅读"))
        layout(view)
        view.findViewById<View>(R.id.pf_tp_floating_scene_selector).performClick()
        view.findViewById<View>(R.id.pf_tp_floating_save).performClick()
        assertThat(view.findViewById<TextView>(R.id.pf_tp_floating_title).text.toString()).isEqualTo("2/3 设置场景")
        view.render(selected().copy(editorStep = TakeoffPlanEditorStep.DEPARTURE_TIME,
            departureHourText = "09", departureMinuteText = "35", etaText = "最早 09:35"))
        layout(view)
        view.findViewById<View>(R.id.pf_tp_floating_time_hour).performClick()
        view.findViewById<View>(R.id.pf_tp_floating_time_save).performClick()
        assertThat(view.findViewById<SourceStringWheelPicker>(R.id.pf_tp_floating_time_hour).getSelectedItem()).isEqualTo("09")
        assertThat(view.findViewById<TextView>(R.id.pf_tp_floating_title).text.toString()).isEqualTo("3/3 设置起飞时间")
        assertThat(actions).containsExactly("select-scene", "save-scene", "preview-time").inOrder()
    }

    @Test
    fun `source dimensions and day night card colors survive host theme`() {
        listOf(false, true).forEach { night ->
            val view = newView(night)
            view.render(selected())
            layout(view)
            val density = view.resources.displayMetrics.density
            val card = view.findViewById<MaterialCardView>(R.id.pf_tp_floating_card)
            assertThat(card.radius).isWithin(0.01f).of(12 * density)
            assertThat(card.cardBackgroundColor.defaultColor).isEqualTo(if (night) 0x4D000000 else 0xE8FFFFFF.toInt())
            assertThat(view.findViewById<TextView>(R.id.pf_tp_floating_dep_code).textSize)
                .isWithin(0.01f).of(16 * view.resources.displayMetrics.scaledDensity)
            assertThat(view.findViewById<View>(R.id.pf_tp_floating_route_container).minimumHeight)
                .isEqualTo((48 * density).toInt())
            assertThat(view.findViewById<View>(R.id.pf_tp_floating_origin).contentDescription.toString())
                .isEqualTo("选择出发机场，PEK，北京")
            view.render(selected(TakeoffPlanPanel.SET_DESTINATION_COMPACT))
            layout(view)
            val compact = view.findViewById<ViewGroup>(R.id.pf_tp_set_destination_compact_container)
            val compactCard = compact.getChildAt(0) as MaterialCardView
            // Actual API31 source variant, rather than the pre-31 56dp/12dp card.
            assertThat(compactCard.measuredHeight).isEqualTo((52 * density).toInt())
            assertThat(compactCard.radius).isWithin(0.01f).of(14 * density)
            assertThat(view.findViewById<View>(R.id.pf_tp_set_destination_save).layoutParams.width)
                .isEqualTo((84 * density).toInt())
        }
    }

    @Test
    fun `fully offscreen button is excluded from expanded touch targets`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = newView()
            var returns = 0
            view.callbacks = TakeoffPlanCallbacks(onExpand = { returns++ })
            activity.get().setContentView(view, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            view.render(selected())
            layout(view)
            assertThat(view.isAttachedToWindow).isTrue()
            val back = view.findViewById<View>(R.id.pf_tp_floating_back)

            fun ownerBounds(): Rect = Rect(0, 0, back.width, back.height).also {
                view.offsetDescendantRectToMyCoords(back, it)
            }

            fun tapDelegateAt(bounds: Rect): Boolean {
                val delegate = checkNotNull(view.touchDelegate)
                val time = SystemClock.uptimeMillis()
                var handled = false
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                    val event = MotionEvent.obtain(time, time + index * 10L, action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    try { handled = delegate.onTouchEvent(event) || handled } finally { event.recycle() }
                }
                shadowOf(Looper.getMainLooper()).idle()
                return handled
            }

            // Positive control: use the real measured button and installed event path.
            assertThat(tapDelegateAt(ownerBounds())).isTrue()
            assertThat(returns).isEqualTo(1)
            returns = 0
            val previousDelegate = view.touchDelegate

            // Keep the actual descendant VISIBLE and measured, but lay it entirely
            // outside its owner. A failed intersect must not register the old rect.
            val params = back.layoutParams as ViewGroup.MarginLayoutParams
            params.leftMargin = -2 * view.width
            back.layoutParams = params
            layout(view)
            view.render(selected())
            shadowOf(Looper.getMainLooper()).idle()
            val outside = ownerBounds()
            assertThat(back.visibility).isEqualTo(View.VISIBLE)
            assertThat(back.width).isGreaterThan(0)
            assertThat(outside.right).isAtMost(0)
            assertThat(view.touchDelegate).isNotSameInstanceAs(previousDelegate)
            assertThat(tapDelegateAt(outside)).isFalse()
            assertThat(returns).isEqualTo(0)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `prefixed fonts resolve original bytes and actual labels load original weights`() {
        val view = newView()
        val fonts = listOf(
            Triple(CoreUiR.font.pf_apk2_harmonyos_sans_sc_light, "harmonyos_sans_sc_light.ttf", "deb6dfbca0645b4aedf7bae3d8c28ac894fc161d67063bec642f4fd72152ea8e"),
            Triple(CoreUiR.font.pf_apk2_harmonyos_sans_sc_medium, "harmonyos_sans_sc_medium.ttf", "6ed1553edccddc48eb27ff25d134a4a715cf54211238d4840b3038576cba1944"),
            Triple(CoreUiR.font.pf_apk2_harmonyos_sans_sc_bold, "harmonyos_sans_sc_bold.ttf", "43a424b85e47fb53a17b3b32026a71801f86f8e022ca6798d186b47d39fa5f01"),
            Triple(CoreUiR.font.pf_apk2_harmonyos_sans_sc_black, "harmonyos_sans_sc_black.ttf", "5de9560908e88820df2e0a5ed9465bc44644ae2ce1cd6c194b76f2ed8e8f186e"),
            Triple(CoreUiR.font.pf_apk2_jetbrainsmono_medium, "jetbrainsmono_medium.ttf", "d16e6dc99672734698d629705f617c79f6eb6040f5113efe3a145204dc988109"),
            Triple(CoreUiR.font.pf_apk2_montserrat_bold, "montserrat_bold.otf", "aa55324cee4ecbeff6daf0705e44c1667c0d4fe84b9edc57df45d38e1205f17e"),
            Triple(CoreUiR.font.pf_apk2_montserrat_extra_bold, "montserrat_extra_bold.otf", "17ae49f737f0b36ce87dd587a7fda35a7d95eb330d175f27e70062905894b486"),
            Triple(CoreUiR.font.pf_apk2_segment, "segment.ttf", "404499c9f23028bb3800b63e58febd01cb3eaf0b53fc58e61f7a350e2c964b3a"),
        )
        fonts.forEach { (id, filename, expectedSha) ->
            val value = TypedValue()
            view.resources.getValue(id, value, true)
            assertThat(value.string.toString()).isEqualTo("res/font/$filename")
            val bytes = view.resources.openRawResource(id).use { it.readBytes() }
            val actualSha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertThat(actualSha).isEqualTo(expectedSha)
            assertThat(ResourcesCompat.getFont(view.context, id)).isNotNull()
        }
        // The original family explicitly declares 400 for a file whose internal
        // OS/2 weight is 500. Loading the family must preserve that distinction.
        assertThat(ResourcesCompat.getFont(view.context, CoreUiR.font.pf_apk2_harmonyos_sans)!!.weight).isEqualTo(400)
        view.render(selected())
        layout(view)
        assertThat(view.findViewById<TextView>(R.id.pf_tp_floating_dep_code).typeface.weight).isEqualTo(700)
        assertThat(view.findViewById<TextView>(R.id.pf_tp_floating_dep_city).typeface.weight).isEqualTo(500)
        assertThat(view.findViewById<TextView>(R.id.pf_tp_header).typeface.weight).isEqualTo(700)
        view.render(selected(TakeoffPlanPanel.EXPANDED))
        layout(view)
        val list = view.findViewById<RecyclerView>(R.id.pf_tp_segment_list)
        val row = list.findViewHolderForAdapterPosition(0)!!.itemView
        assertThat(row.findViewById<TextView>(R.id.pf_tp_departure_time).typeface.weight).isEqualTo(436)
        assertThat(row.findViewById<TextView>(R.id.pf_tp_distance).typeface.weight).isEqualTo(700)
    }

    @Test
    fun `actual native rendered previews contain pixels at phone widths and large text`() {
        val output = File("build/reports/r158-takeoff-plan-preview").apply { mkdirs() }
        val samples = linkedMapOf(
            "empty" to TakeoffPlanUiState(),
            "selected" to selected(TakeoffPlanPanel.SET_DESTINATION_COMPACT),
            "adding" to selected(TakeoffPlanPanel.ADD_SEGMENT_COMPACT),
            "expanded" to selected(TakeoffPlanPanel.EXPANDED),
        )
        listOf(false, true).forEach { night ->
            samples.forEach { (name, state) ->
                val view = newView(night)
                view.render(state)
                layout(view, 390)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                // Fixed preview backdrop, outside the component; not a claimed reference screenshot.
                val backdrop = if (night) Color.rgb(24, 30, 40) else Color.rgb(209, 221, 231)
                canvas.drawColor(backdrop)
                view.draw(canvas)
                assertThat(bitmap.width).isGreaterThan(0)
                assertThat(bitmap.height).isGreaterThan(0)
                var changed = 0
                for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                    if (bitmap.getPixel(x, y) != backdrop) changed++
                }
                assertThat(changed).isGreaterThan(50)
                File(output, "$name-${if (night) "night" else "day"}.png").outputStream().use {
                    assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue()
                }
            }
        }
        val large = newView(fontScale = 1.6f)
        large.render(selected().copy(origin = TakeoffPlanAirportUi("PEK", "北京首都国际机场长名称样本")))
        layout(large, 360)
        val bitmap = Bitmap.createBitmap(large.width, large.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(209, 221, 231))
        large.draw(canvas)
        File(output, "large-text-360.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(output, "README.txt").writeText(
            "Actual TakeoffPlanView native Robolectric renders; fixed host sample data/backdrop.\n" +
                "No reference screenshot, SSIM, animation or runtime fidelity acceptance.\n",
        )
    }
}
