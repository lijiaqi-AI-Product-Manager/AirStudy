package com.projectfocus.feature.completion.arrival

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.feature.completion.R
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R7 落地页静态验收：36 节点布局 + 绑定器 + 方形容器。
 *
 * 这不是像素级截图测试；本机没有设备/基线图。它证明的是：
 * - 36 个节点能 inflate；
 * - 绑定器把记录写进正确的 id；
 * - 今日汇总为 0 时统计区隐藏；
 * - 三个按钮回调不吞事件；
 * - 方形容器确实按宽定高。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlightCompletionBinderTest {

    private lateinit var context: Context
    private lateinit var root: View
    private lateinit var binder: FlightCompletionBinder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        root = LayoutInflater.from(context).inflate(R.layout.fragment_flight_completion, null)
        binder = FlightCompletionBinder(root)
    }

    @Test
    fun `root is gone by default and becomes visible after bind`() {
        assertThat(root.visibility).isEqualTo(View.GONE)
        binder.bind(record(), 0.0, 0)
        assertThat(root.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `binds destination code city and airport link`() {
        binder.bind(record(), 0.0, 0)

        assertThat(text(R.id.arrival_airport_code_on_media)).isEqualTo("PVG")
        assertThat(text(R.id.arrival_city_title)).isEqualTo("上海")
        assertThat(text(R.id.arrival_airport_link)).isEqualTo("上海浦东国际机场 · PVG")
    }

    @Test
    fun `binds today summary and shows stats`() {
        binder.bind(record(), 1234.5, 90)

        assertThat(text(R.id.nsv_mileage)).isEqualTo("1234.5")
        assertThat(text(R.id.nsv_focus)).isEqualTo("90")
        assertThat(root.findViewById<LinearLayout>(R.id.stats_container).visibility)
            .isEqualTo(View.VISIBLE)
    }

    @Test
    fun `hides stats when today has no completed flight`() {
        binder.bind(record(), 0.0, 0)

        assertThat(root.findViewById<LinearLayout>(R.id.stats_container).visibility)
            .isEqualTo(View.GONE)
    }

    @Test
    fun `four action buttons invoke callbacks`() {
        var share = 0
        var report = 0
        var complete = 0
        var airport = 0
        binder.onShare = { share++ }
        binder.onReport = { report++ }
        binder.onComplete = { complete++ }
        binder.onAirport = { airport++ }
        binder.bind(record(), 0.0, 0)

        root.findViewById<View>(R.id.restart_flight_button).performClick()
        root.findViewById<View>(R.id.report_airport_button).performClick()
        root.findViewById<View>(R.id.complete_flight_button).performClick()
        root.findViewById<View>(R.id.arrival_airport_link).performClick()

        assertThat(share).isEqualTo(1)
        assertThat(report).isEqualTo(1)
        assertThat(complete).isEqualTo(1)
        assertThat(airport).isEqualTo(1)
    }

    @Test
    fun `square frame measures height equal to width`() {
        val square = root.findViewById<ArrivalSquareFrameLayout>(R.id.arrival_media_frame)
        val width = MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY)
        val height = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        square.measure(width, height)

        assertThat(square.measuredWidth).isEqualTo(1080)
        assertThat(square.measuredHeight).isEqualTo(1080)
    }

    private fun text(id: Int): String =
        root.findViewById<TextView>(id).text.toString()

    private fun record(): FlightHistoryEntity = FlightHistoryEntity(
        id = "r7",
        roomId = null,
        focusType = "SOLO",
        roomParticipantCount = null,
        departureAirportCode = "PEK",
        departureAirportName = "北京首都国际机场",
        departureCity = "北京",
        departureCountry = "中国",
        departureLatitude = 40.08,
        departureLongitude = 116.58,
        destinationAirportCode = "PVG",
        destinationAirportName = "上海浦东国际机场",
        destinationCity = "上海",
        destinationCountry = "中国",
        destinationLatitude = 31.14,
        destinationLongitude = 121.81,
        startTimeMillis = 1_700_000_000_000L,
        endTimeMillis = 1_700_002_700_000L,
        durationMinutes = 45,
        actualFocusMinutes = 45,
        distanceKm = 600.0,
        status = "COMPLETED",
        categoryId = null,
        seat = "12A",
        hasAnnouncedDeparture = true,
        hasAnnouncedDescent = true,
        hasAnnouncedArrival = true,
        logJson = null,
        isSynced = true,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_002_700_000L,
        quote = "The journey is the reward.",
        quoteAuthor = "Steve Jobs",
    )
}
