package com.projectfocus.feature.completion.pass

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.completion.R
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 登机牌 37 节点布局的静态验收。
 *
 * 这不是像素/截图测试，而是“节点表是否真的落地”：
 * 数量、ID、尺寸、padding、字号、可见性、elevation 任何一项漂移都会红。
 * 像素级 SSIM 需要设备/基线图，当前环境做不到，单独留在 R4。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoardingPassLayoutTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            context.resources.displayMetrics,
        )

    private fun inflate(): View =
        LayoutInflater.from(context).inflate(R.layout.boarding_pass_layout, null)

    private fun measureAndLayout(root: View) {
        val width = dp(300)
        val height = dp(400)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
    }

    private fun countNodes(view: View): Int {
        var count = 1
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                count += countNodes(view.getChildAt(i))
            }
        }
        return count
    }

    @Test
    fun `节点数与根尺寸与 APK2 一致`() {
        val root = inflate()
        assertThat(root).isInstanceOf(BoardingPassPaperLayout::class.java)
        assertThat(countNodes(root)).isEqualTo(37)

        measureAndLayout(root)
        assertThat(root.measuredWidth).isEqualTo(dp(300))
        assertThat(root.measuredHeight).isEqualTo(dp(400))
    }

    @Test
    fun `内容层 padding 与顶部留白与 APK2 一致`() {
        val root = inflate()
        measureAndLayout(root)

        val content = (root as ViewGroup).getChildAt(1) as ViewGroup
        assertThat(content.paddingLeft).isEqualTo(dp(30))
        assertThat(content.paddingTop).isEqualTo(dp(30))
        assertThat(content.paddingRight).isEqualTo(dp(30))
        assertThat(content.paddingBottom).isEqualTo(dp(30))

        val spacer = content.getChildAt(0)
        assertThat(spacer.height).isEqualTo(dp(40))
    }

    @Test
    fun `字号与 APK2 节点表一致`() {
        val root = inflate()
        measureAndLayout(root)

        val departure = root.findViewById<TextView>(R.id.tv_departure)
        val departureCity = root.findViewById<TextView>(R.id.tv_departure_city)
        val flightCode = root.findViewById<TextView>(R.id.tv_flight_code)
        val date = root.findViewById<TextView>(R.id.tv_date)

        assertThat(departure.textSize).isWithin(0.5f).of(sp(25f))
        assertThat(departureCity.textSize).isWithin(0.5f).of(sp(10f))
        assertThat(flightCode.textSize).isWithin(0.5f).of(sp(13f))
        assertThat(date.textSize).isWithin(0.5f).of(sp(13f))
    }

    @Test
    fun `隐藏节点初值与 APK2 一致`() {
        val root = inflate()

        assertThat(root.findViewById<View>(R.id.right_stub_section).visibility)
            .isEqualTo(View.GONE)
        assertThat(root.findViewById<View>(R.id.tv_flight_number).visibility)
            .isEqualTo(View.GONE)
        assertThat(root.findViewById<View>(R.id.divider_line).visibility)
            .isEqualTo(View.GONE)
        assertThat(root.findViewById<View>(R.id.hint_circle_view).visibility)
            .isEqualTo(View.INVISIBLE)
    }

    @Test
    fun `撕票命中区与提示圈尺寸 elevation 与 APK2 一致`() {
        val root = inflate()
        measureAndLayout(root)

        val tear = root.findViewById<View>(R.id.tear_touch_band)
        val hint = root.findViewById<View>(R.id.hint_circle_view)

        assertThat(tear.height).isEqualTo(dp(40))
        assertThat(tear.elevation).isWithin(0.5f).of(dp(5).toFloat())
        assertThat(hint.measuredWidth).isEqualTo(dp(60))
        assertThat(hint.measuredHeight).isEqualTo(dp(60))
        assertThat(hint.elevation).isWithin(0.5f).of(dp(11).toFloat())
    }

    @Test
    fun `绑定数据后主票面字段正确`() {
        val root = inflate()
        measureAndLayout(root)

        BoardingPassBinder(root).bind(
            data = sample(),
            passengerName = "LI JIA",
            boardingTime = "08:20",
            stubVisible = true,
        )

        assertThat(root.findViewById<TextView>(R.id.tv_departure).text.toString())
            .isEqualTo("PEK")
        assertThat(root.findViewById<TextView>(R.id.tv_destination).text.toString())
            .isEqualTo("KIX")
        assertThat(root.findViewById<TextView>(R.id.tv_flight_code).text.toString())
            .isEqualTo("PF2645")
        assertThat(root.findViewById<TextView>(R.id.tv_boarding_time).text.toString())
            .isEqualTo("08:20")
        assertThat(root.findViewById<TextView>(R.id.tv_date).text.toString())
            .isEqualTo("26OCT")
        assertThat(root.findViewById<TextView>(R.id.tv_passenger_name).text.toString())
            .isEqualTo("LI JIA")
        assertThat(root.findViewById<TextView>(R.id.tv_seat).text.toString())
            .isEqualTo("12A")
    }

    @Test
    fun `右侧存根与分隔线在 stubVisible 时显示且用同一组值`() {
        val root = inflate()
        measureAndLayout(root)

        BoardingPassBinder(root).bind(
            data = sample(),
            passengerName = "LI JIA",
            boardingTime = "08:20",
            stubVisible = true,
        )

        val stub = root.findViewById<ViewGroup>(R.id.right_stub_section)
        assertThat(stub.visibility).isEqualTo(View.VISIBLE)
        assertThat(root.findViewById<View>(R.id.divider_line).visibility)
            .isEqualTo(View.VISIBLE)
        assertThat((stub.getChildAt(0) as TextView).text.toString()).isEqualTo("PEK")
        assertThat((stub.getChildAt(1) as TextView).text.toString()).isEqualTo("KIX")
        assertThat((stub.getChildAt(2) as TextView).text.toString()).isEqualTo("PF2645")
        assertThat((stub.getChildAt(3) as TextView).text.toString()).isEqualTo("26OCT")
        assertThat((stub.getChildAt(4) as TextView).text.toString()).isEqualTo("08:20")
    }

    @Test
    fun `默认绑定不伪造乘客姓名也不显示存根`() {
        val root = inflate()
        measureAndLayout(root)

        val data = sample()
        BoardingPassBinder(root).bind(data)

        assertThat(root.findViewById<TextView>(R.id.tv_passenger_name).text.toString())
            .isEqualTo(context.getString(R.string.passenger_label))
        assertThat(root.findViewById<TextView>(R.id.tv_boarding_time).text.toString())
            .isEqualTo(data.departureTime)
        assertThat(root.findViewById<View>(R.id.right_stub_section).visibility)
            .isEqualTo(View.GONE)
    }

    @Test
    fun `数据对象自带登机时间与旅客姓名时优先于默认标签`() {
        val root = inflate()
        measureAndLayout(root)

        val data = sample().copy(passengerName = "LI JIA", boardingTime = "08:20")
        BoardingPassBinder(root).bind(data)

        assertThat(root.findViewById<TextView>(R.id.tv_boarding_time).text.toString())
            .isEqualTo("08:20")
        assertThat(root.findViewById<TextView>(R.id.tv_passenger_name).text.toString())
            .isEqualTo("LI JIA")
    }

    private fun sample(): BoardingPassData = BoardingPassData.from(
        departureCode = "PEK",
        departureCity = "北京",
        arrivalCode = "KIX",
        arrivalCity = "大阪",
        startWallMs = 1_792_972_800_000L, // 2026-10-26 00:00 UTC = 08:00 CST
        endWallMs = 1_792_972_800_000L + 45 * 60_000L,
        zone = ZoneId.of("Asia/Shanghai"),
        plannedMinutes = 45,
        actualFocusMinutes = 45,
        distanceKm = 600.0,
        seat = "12A",
        abandoned = false,
    )
}
