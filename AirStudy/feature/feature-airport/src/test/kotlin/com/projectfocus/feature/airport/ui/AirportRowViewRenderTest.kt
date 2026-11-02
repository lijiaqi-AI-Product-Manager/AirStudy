package com.projectfocus.feature.airport.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.core.model.airport.AirportType
import com.projectfocus.core.common.flight.FlightMath
import com.projectfocus.feature.airport.R
import com.projectfocus.feature.airport.search.AirportSearchIndex
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment





















@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirportRowViewRenderTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun view(): AirportRowView = AirportRowView(context)

    private fun layout(v: View, w: Int = 720, h: Int = 160) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, w, h)
    }

    private fun render(v: View, w: Int = 720, h: Int = 160): Bitmap {
        layout(v, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    private fun renderWithBorder(v: View, w: Int = 720, h: Int = 160): Bitmap {
        layout(v, w, h)
        return Bitmap.createBitmap(w + 16, h + 16, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.translate(8f, 8f)
            v.draw(canvas)
        }
    }

    /** 非透明像素占比 —— 用来证明"真的画了东西" */
    private fun opaqueRatio(bmp: Bitmap): Float {
        var n = 0
        var total = 0
        for (y in 0 until bmp.height step 3) {
            for (x in 0 until bmp.width step 3) {
                total++
                if (bmp.getPixel(x, y) ushr 24 != 0) n++
            }
        }
        return if (total == 0) 0f else n.toFloat() / total
    }

    private fun airport(
        id: Long = 1,
        iata: String? = "PEK",
        icao: String? = "ZBAA",
        name: String = "Beijing Capital International",
        city: String? = "Beijing",
        nameZh: String? = null,
        cityZh: String? = null,
    ) = Airport(
        id = id, iataCode = iata, icaoCode = icao, name = name, city = city,
        country = "CN", latitude = 40.08, longitude = 116.58, elevation = 35,
        continent = "AS", type = AirportType.LARGE_AIRPORT, popularity = 95,
        isPremiumOnly = false, nameZh = nameZh, cityZh = cityZh,
    )

    private fun hit(
        a: Airport = airport(),
        tier: AirportSearchIndex.Tier = AirportSearchIndex.Tier.CODE_EXACT,
    ) = AirportSearchIndex.Hit(a, tier, emptySet())

    // ══════════════════════════════════════════════════════
    // 不崩溃 / 真的画了
    // ══════════════════════════════════════════════════════

    @Test
    fun `绑定数据后绘制不抛异常`() {
        val v = view()
        v.bind(hit(), "PEK")
        render(v)
        assertThat(v.findViewById<android.widget.TextView>(R.id.pf_airport_airport_code).text.toString()).isEqualTo("PEK")
        assertThat(v.findViewById<android.widget.TextView>(R.id.pf_airport_tv_airport_name).text.toString()).isEqualTo("Beijing Capital International")
        assertThat(v.findViewById<TextView>(R.id.pf_airport_tv_airport_city).text.toString()).isEqualTo("--")
    }

    @Test
    fun `real 100 km route displays eight minutes instead of a city`() {
        val target = airport(city = "This city stays in accessibility").copy(
            latitude = 0.0, longitude = Math.toDegrees(100.0 / FlightMath.EARTH_RADIUS_KM),
        )
        val minutes = AirportCardFlightTime.minutes(AirportReferencePoint.from(0.0, 0.0), target)
        assertThat(minutes).isEqualTo(8)
        val v = view().apply { bind(hit(target), "PEK", flightMinutes = minutes) }
        render(v)
        assertThat(v.findViewById<TextView>(R.id.pf_airport_tv_airport_city).text.toString()).isEqualTo("8m")
        val info = AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)
        assertThat(info.contentDescription.toString()).contains("This city stays in accessibility")
        assertThat(info.contentDescription.toString()).contains("8m")
        info.recycle()
    }

    @Test
    fun `duration rebind keeps source hour zero suffix and clears absent reference`() {
        val v = view()
        for ((minutes, expected) in listOf(8 to "8m", 60 to "1h0m", 125 to "2h5m", null to "--")) {
            v.bind(hit(), "PEK", flightMinutes = minutes)
            assertThat(v.findViewById<TextView>(R.id.pf_airport_tv_airport_city).text.toString()).isEqualTo(expected)
        }
    }

    @Test
    fun `normal bound card stays dark with raw pixel corners in both themes even for exact query`() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night
                densityDpi = 320
            })
            val v = AirportRowView(themed).apply { bind(hit(), "PEK", flightMinutes = 8) }
            val card = v.findViewById<View>(R.id.pf_airport_airportCardVertical)
            val background = card.background as GradientDrawable
            assertThat(background.cornerRadius).isEqualTo(24f)
            assertThat(background.color!!.defaultColor).isEqualTo(Color.rgb(15, 15, 15))
            assertThat(card.isSelected).isFalse()
            assertThat(v.findViewById<TextView>(R.id.pf_airport_tv_airport_name).currentTextColor).isEqualTo(Color.WHITE)
            assertThat(v.findViewById<TextView>(R.id.pf_airport_tv_airport_city).currentTextColor).isEqualTo(Color.rgb(204, 204, 204))
        }
    }

    @Test
    fun `未绑定数据时绘制也不抛异常`() {

        //   如果 onDraw 里 `hit!!` 就崩了。
        render(view())
    }

    @Test
    fun `绑定数据后确实画出了内容`() {

        //   `setWillNotDraw(true)` 或 LEGACY 模式下静默通过。
        val v = view()
        v.bind(hit(), "PEK")
        val bmp = render(v)
        assertThat(opaqueRatio(bmp)).isGreaterThan(0.03f)
    }

    @Test
    fun `没有绘制的 View 占比接近零`() {

        //   如果这条也 > 0.03，说明度量方法有问题（比如 Bitmap 预填充了白色）。
        val bmp = Bitmap.createBitmap(720, 160, Bitmap.Config.ARGB_8888)
        assertThat(opaqueRatio(bmp)).isWithin(0.001f).of(0f)
    }

    // ══════════════════════════════════════════════════════
    // 边界
    // ══════════════════════════════════════════════════════

    @Test
    fun `绘制内容不越出 View 边界`() {
        val v = view()
        v.bind(hit(), "PEK")
        val bmp = renderWithBorder(v)

        // The untouched eight-pixel margin surrounds the measured product view.
        for (x in 0 until bmp.width) {
            assertThat(bmp.getPixel(x, 0) ushr 24).isEqualTo(0)
        }
        for (y in 0 until bmp.height) {
            assertThat(bmp.getPixel(0, y) ushr 24).isEqualTo(0)

            assertThat(bmp.getPixel(bmp.width - 1, y) ushr 24).isEqualTo(0)
        }
    }

    @Test
    fun `长文本被截断而不是画出边界`() {

        //   超长文本会一直画到屏幕外，在 RecyclerView 里表现为
        //   "文字溢出到下一列/被父容器裁成半截"。
        val v = view()
        v.bind(
            hit(airport(name = "A".repeat(200), city = "B".repeat(200))),
            "A",
        )
        val bmp = renderWithBorder(v)
        for (y in 0 until bmp.height) {
            assertThat(bmp.getPixel(bmp.width - 1, y) ushr 24).isEqualTo(0)
        }
        assertThat(opaqueRatio(bmp)).isGreaterThan(0.02f)
        val name = v.findViewById<TextView>(R.id.pf_airport_tv_airport_name)
        assertThat(name.layout.lineCount).isEqualTo(2)
        assertThat(name.layout.getEllipsisCount(1)).isGreaterThan(0)
    }

    @Test
    fun `极窄宽度下不崩溃`() {
        val v = view()
        v.bind(hit(), "PEK")
        render(v, w = 40, h = 160)
    }

    // ══════════════════════════════════════════════════════
    // wrap_content
    // ══════════════════════════════════════════════════════

    @Test
    fun `wrap_content 宽度不会塌成 0`() {

        //   量出 0，在 LinearLayout 里这一行就"消失"了。
        val v = view()
        v.bind(hit(), "PEK")
        v.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertThat(v.measuredWidth).isGreaterThan(0)
        assertThat(v.measuredHeight).isGreaterThan(0)
    }

    @Test
    fun `字体放大到 200 百分比时高度跟着变高而不是裁掉第二行`() {

        //   如果 onMeasure 用固定 64dp，副标题会被裁掉一半。
        val v = view()
        v.bind(hit(), "PEK")
        v.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val normal = v.measuredHeight

        // 依赖 Robolectric 的 fontScale 设置不一定可靠，所以这里只验证
        // "高度是随内容算出来的，不是硬编码 64dp"（>= minHeight 且 > 0）
        assertThat(normal).isAtLeast(1)
        val scaledContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            fontScale = 2f
        })
        val large = AirportRowView(scaledContext).apply { bind(hit(), "PEK") }
        large.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertThat(large.measuredHeight).isGreaterThan(normal)
    }

    // ══════════════════════════════════════════════════════
    // 无障碍
    // ══════════════════════════════════════════════════════

    @Test
    fun `无障碍描述包含城市与三字码`() {

        val v = view()
        v.bind(hit(airport(cityZh = "北京", nameZh = "首都国际机场")), "PEK")
        val info = AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)

        val desc = info.contentDescription?.toString().orEmpty()
        assertThat(desc).contains("北京")

        //   TalkBack 读到连写的 "PEK" 会念成 /pek/ 一个词；
        //   拆成单字母才会念 "P, E, K"。这里断言拆开后的形态。
        assertThat(desc).contains("P E K")
        info.recycle()
    }

    @Test
    fun `未绑定数据时无障碍描述为空`() {
        val v = view()
        val info = AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)
        assertThat(info.contentDescription).isNull()
        info.recycle()
    }

    @Test
    fun `精确匹配时无障碍描述里带精确匹配`() {
        val v = view()
        v.bind(hit(tier = AirportSearchIndex.Tier.CODE_EXACT), "PEK")
        val info = AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)
        assertThat(info.contentDescription?.toString()).contains("精确匹配")
        info.recycle()
    }

    @Test
    fun `非精确匹配时无障碍描述里不带精确匹配`() {
        val v = view()
        v.bind(hit(tier = AirportSearchIndex.Tier.NAME_CONTAINS), "Capital")
        val info = AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)
        assertThat(info.contentDescription?.toString()).doesNotContain("精确匹配")
        info.recycle()
    }

    @Test
    fun `无障碍类名声明为 Button`() {

        val v = view()
        v.isClickable = true
        v.bind(hit(), "PEK")
        val info = AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)
        assertThat(info.className).isEqualTo("android.widget.Button")
        assertThat(info.isClickable).isTrue()
        info.recycle()
    }

    // ══════════════════════════════════════════════════════
    // 数据切换（RecyclerView 复用）
    // ══════════════════════════════════════════════════════

    @Test
    fun `换一条数据后绘制结果与首条不同`() {

        //   自绘 View 改了数据不 invalidate，屏幕上还是旧内容 ——
        //   在 RecyclerView 里表现为"滚动后每行的城市名都不对"。
        //   这个 bug 只在滚动超过一屏时出现，很容易漏测。
        val v = view()
        v.bind(hit(airport(cityZh = "北京", nameZh = "首都国际机场")), "北京")
        val first = render(v)

        v.bind(
            hit(airport(id = 2, iata = "ICN", icao = "RKSI", name = "Incheon", cityZh = "首尔"), tier = AirportSearchIndex.Tier.CITY_CONTAINS),
            "首尔",
        )
        val second = render(v)

        // 两条数据的"精确匹配"不同（第一条 exactCode=true 会画码条 + 角标），
        // 所以像素占比必然不同。
        assertThat(first.sameAs(second)).isFalse()
    }

    @Test
    fun `bind null 后不再绘制内容`() {
        val v = view()
        v.bind(hit(), "PEK")
        render(v)
        v.bind(null, "")
        val bmp = render(v)
        // 只剩分割线（1px，占比极低）
        assertThat(opaqueRatio(bmp)).isLessThan(0.01f)
    }

    // ══════════════════════════════════════════════════════
    // 数据缺失
    // ══════════════════════════════════════════════════════

    @Test
    fun `三字码缺失时仍能绘制且不显示空码`() {
        val v = view()
        v.bind(hit(airport(iata = null, icao = "RJOO", name = "Osaka Itami", city = "Osaka")), "Osaka")
        val bmp = render(v)
        assertThat(opaqueRatio(bmp)).isGreaterThan(0.02f)
    }

    @Test
    fun `城市与机场名全缺失时不崩溃`() {
        val v = view()
        v.bind(hit(airport(iata = null, icao = null, name = "", city = null)), "")
        render(v)
        assertThat(v.findViewById<TextView>(R.id.pf_airport_tv_airport_name).text.toString()).isEqualTo("--")
    }

    @Test
    fun `中文数据优先显示中文`() {
        val v = view()
        v.bind(hit(airport(cityZh = "北京", nameZh = "首都国际机场")), "北京")
        val info = AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)
        val desc = info.contentDescription?.toString().orEmpty()
        assertThat(desc).startsWith("北京")
        assertThat(desc).contains("首都国际机场")
        info.recycle()
    }
}
