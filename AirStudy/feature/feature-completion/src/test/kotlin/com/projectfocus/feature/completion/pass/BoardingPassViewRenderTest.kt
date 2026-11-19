package com.projectfocus.feature.completion.pass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId

















































@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class BoardingPassViewRenderTest {

    private fun sample(
        abandoned: Boolean = false,
        actualFocus: Int = 45,
        planned: Int = 45,
    ) = BoardingPassData.from(
        departureCode = "PEK",
        departureCity = "北京",
        arrivalCode = "KIX",
        arrivalCity = "大阪",

        //   后者会让"航班号里的日期"随跑测试的日期变化，
        //   断言就得写成"以 PF 开头"这种含糊形式。
        startWallMs = 1792972800_000L, // 2026-10-26 00:00 UTC（= 08:00 CST）
        endWallMs = 1792972800_000L + planned * 60_000L,
        zone = ZoneId.of("Asia/Shanghai"),
        plannedMinutes = planned,
        actualFocusMinutes = actualFocus,
        distanceKm = 600.0,
        seat = "12A",
        abandoned = abandoned,
    )

    private fun newView(data: BoardingPassData?): BoardingPassView {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themed = android.view.ContextThemeWrapper(ctx, com.projectfocus.core.ui.R.style.Theme_Pf)
        return BoardingPassView(themed).apply {
            this.data = data

            //   `if (cr.width() <= 0f) return` 会直接退出 —— 测试"通过"但什么都没画。
            measure(
                View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }

    private fun dp(v: Int) = (
        v * ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.displayMetrics.density
        ).toInt()

    /** 把 View 画到一张位图上，便于做像素级检查 */
    private fun BoardingPassView.render(): Bitmap {
        val bmp = Bitmap.createBitmap(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        draw(Canvas(bmp))
        shadowOf(Looper.getMainLooper()).idle()
        return bmp
    }

    // ── ① onDraw 不能抛 ────────────────────────────────────

    @Test
    fun `有数据时能完整绘制一次且不抛异常`() {
        val view = newView(sample())
        val bmp = view.render()
        assertThat(bmp.width).isGreaterThan(0)
        assertThat(bmp.height).isGreaterThan(0)
    }

    @Test
    fun `数据为 null 时绘制不抛异常`() {

        //   如果 onDraw 没做 null 判断，App 会在进入详情页的瞬间崩 ——
        //   而崩溃点在自绘 View 里，堆栈只指向 onDraw，很难定位。
        val view = newView(null)
        view.render()
    }

    @Test
    fun `空数据即使用空字符串也不崩`() {
        val d = BoardingPassData.from(
            departureCode = null, departureCity = null,
            arrivalCode = null, arrivalCity = null,
            startWallMs = 1792972800_000L,
            endWallMs = 1792972800_000L + 60_000L,
            zone = ZoneId.of("Asia/Shanghai"),
            plannedMinutes = 1,
            actualFocusMinutes = 1,
            distanceKm = 0.0,
        )
        val view = newView(d)
        view.render()
        // 缺码时必须显示占位符，而不是空白
        assertThat(d.departureCode).isEqualTo(BoardingPassData.UNKNOWN_CODE)
        assertThat(d.arrivalCode).isEqualTo(BoardingPassData.UNKNOWN_CODE)
    }

    // ── ② wrap_content 必须算得出高度 ──────────────────────

    @Test
    fun `wrap_content 下高度不为零`() {
        val view = newView(sample())
        assertThat(view.measuredHeight).isGreaterThan(dp(100))
    }

    @Test
    fun `wrap_content 下宽度会回退到设计宽度而不是零`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themed = android.view.ContextThemeWrapper(ctx, com.projectfocus.core.ui.R.style.Theme_Pf)
        val view = BoardingPassView(themed)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        //   而 0 宽的 View 在布局里表现为"整块消失"，且不报任何错。
        assertThat(view.measuredWidth).isGreaterThan(dp(200))
    }

    // ── ③ 绘制内容必须落在边界内 ───────────────────────────

    @Test
    fun `绘制内容不越出 View 边界`() {
        val view = newView(sample())
        val bmp = view.render()

        // 扫描四条边上的像素，全部应当是透明的（View 自身不画背景）

        //   而不是 canvas.rotate），这里会立刻抓到 —— 边缘会出现非透明像素。
        val w = bmp.width
        val h = bmp.height
        var edgeNonTransparent = 0
        for (x in 0 until w) {
            if (bmp.getPixel(x, 0) ushr 24 != 0) edgeNonTransparent++
            if (bmp.getPixel(x, h - 1) ushr 24 != 0) edgeNonTransparent++
        }
        for (y in 0 until h) {
            if (bmp.getPixel(0, y) ushr 24 != 0) edgeNonTransparent++
            if (bmp.getPixel(w - 1, y) ushr 24 != 0) edgeNonTransparent++
        }
        assertThat(edgeNonTransparent).isEqualTo(0)
    }

    @Test
    fun `纸面确实被画出来了且占满大部分面积`() {
        val view = newView(sample())
        val bmp = view.render()



        //   **与实现耦合**的断言：只要 tearRatio 调成 0.5，中心就变成撕口，
        //   测试会红而界面上没有任何变化。
        //   → 改成统计"非透明像素占比"，与任何单一坐标都无关。
        var opaque = 0
        val total = bmp.width * bmp.height
        for (y in 0 until bmp.height step 3) {
            for (x in 0 until bmp.width step 3) {
                if (bmp.getPixel(x, y) ushr 24 != 0) opaque++
            }
        }
        val ratio = opaque.toFloat() / (total / 9f)
        // 纸面是一整块圆角矩形减去两个小半圆，占比必然很高。
        // 下限取 0.85：即使考虑圆角与缺口，也远高于此。
        assertThat(ratio).isGreaterThan(0.85f)
    }

    @Test
    fun `撕票口的缺口是透明的`() {
        val view = newView(sample())
        val bmp = view.render()
        // 缺口位于上边缘、tearRatio 处。0.64 是这个 View 的设计常量。
        val tearX = (bmp.width * 0.64f).toInt()
        // 缺口圆心在上边缘，所以取 y=0 那一点应当是透明的
        val p = bmp.getPixel(tearX, 0)

        //   如果 op 失败退回整块矩形，这里会是纸面色（非透明）。
        assertThat(p ushr 24).isEqualTo(0)
    }

    // ── ④ 无障碍 ───────────────────────────────────────────

    @Test
    fun `无障碍描述包含航线与航班号`() {
        val view = newView(sample())
        val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
        view.onInitializeAccessibilityNodeInfo(info)
        val desc = info.contentDescription?.toString().orEmpty()


        //   不给 contentDescription 等于对视障用户彻底不可用。
        assertThat(desc).contains("北京")
        assertThat(desc).contains("大阪")
        assertThat(desc).contains(sample().flightNo)
        assertThat(desc).contains("12A")
        assertThat(desc).contains("已完成")
    }

    @Test
    fun `放弃的航班无障碍描述读作已取消`() {
        val view = newView(sample(abandoned = true))
        val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
        view.onInitializeAccessibilityNodeInfo(info)
        val desc = info.contentDescription?.toString().orEmpty()
        assertThat(desc).contains("已取消")
        assertThat(desc).doesNotContain("已完成")
    }

    // ── ⑤ 印章语义 ─────────────────────────────────────────

    @Test
    fun `放弃与完成的印章文字不同`() {
        // 这条不是渲染而是"语义"，但它决定了印章画什么字，
        // 放在这里是因为它和上一条无障碍断言是同一个契约的两面。
        assertThat(sample(abandoned = false).stampText).isEqualTo("COMPLETED")
        assertThat(sample(abandoned = true).stampText).isEqualTo("CANCELLED")
    }

    @Test
    fun `提前下机时完成度小于一但印章仍是完成`() {
        val d = sample(actualFocus = 30, planned = 45)
        assertThat(d.completionRatio).isLessThan(1f)

        assertThat(d.stampText).isEqualTo("COMPLETED")
        // 里程按计划算，不因提前下机缩短
        assertThat(d.distanceText).isEqualTo("600.0 km")
    }

    @Test
    fun `交换数据后重新测量`() {
        val view = newView(sample())
        val h1 = view.measuredHeight
        // 数据变化必须触发 requestLayout，否则布局不会重算
        view.data = sample(abandoned = true)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertThat(view.measuredHeight).isEqualTo(h1)
    }
}
