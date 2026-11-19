package com.projectfocus.feature.airport.ui

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.core.model.airport.AirportType
import com.projectfocus.core.common.flight.FlightMath
import com.projectfocus.feature.airport.R
import com.projectfocus.feature.airport.search.AirportSearchIndex
import com.projectfocus.feature.airport.search.AirportSearchState
import com.projectfocus.feature.airport.search.ResultGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf



























@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirportSearchAdapterTest {

    @Test
    fun `actual recycler binds a measured eight minute row and no reference stays unknown`() {
        val target = airport(10, "DUR").copy(
            name = "Real route", city = "City must not occupy the time field",
            longitude = Math.toDegrees(100.0 / FlightMath.EARTH_RADIUS_KM),
        )
        val state = AirportSearchState.Content("DUR", listOf(ResultGroup(null, "", listOf(
            AirportSearchIndex.Hit(target, AirportSearchIndex.Tier.CODE_EXACT, emptySet()),
        ))), truncated = false)
        for ((reference, expected) in listOf(AirportReferencePoint.from(0.0, 0.0) to "8m", null to "--")) {
            val context = RuntimeEnvironment.getApplication()
            val adapter = AirportSearchAdapter(onClick = {}, referencePoint = reference)
            val list = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                this.adapter = adapter
            }
            adapter.submit(state)
            shadowOf(Looper.getMainLooper()).idle()
            list.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 320, 500)
            assertThat(list.childCount).isEqualTo(1)
            assertThat(list.getChildAt(0).findViewById<TextView>(R.id.pf_airport_tv_airport_city).text.toString()).isEqualTo(expected)
        }
    }

    @Test
    fun `same airport reference change is a content change for its duration`() {
        val target = airport(10, "DUR").copy(longitude = Math.toDegrees(100.0 / FlightMath.EARTH_RADIUS_KM))
        val state = AirportSearchState.Content("DUR", listOf(ResultGroup(null, "", listOf(
            AirportSearchIndex.Hit(target, AirportSearchIndex.Tier.CODE_EXACT, emptySet()),
        ))), truncated = false)
        val withoutReference = AirportSearchAdapter.flatten(state).single()
        val withReference = AirportSearchAdapter.flatten(state, AirportReferencePoint.from(0.0, 0.0)).single()
        assertThat(AirportSearchAdapter.DIFF.areItemsTheSame(withoutReference, withReference)).isTrue()
        assertThat(AirportSearchAdapter.DIFF.areContentsTheSame(withoutReference, withReference)).isFalse()
    }

    @Test
    fun `source cards with different text fill the actual recycler width`() {
        val context = RuntimeEnvironment.getApplication()
        val list = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val adapter = AirportSearchAdapter(onClick = {})
        list.adapter = adapter
        val short = airport(1, "PEK").copy(name = "Short", city = "A")
        val long = airport(2, "PVG").copy(name = "A long international airport name", city = "A much longer city")
        adapter.submit(AirportSearchState.Content(
            keyword = "airport",
            groups = listOf(ResultGroup(null, "", listOf(
                AirportSearchIndex.Hit(short, AirportSearchIndex.Tier.NAME_CONTAINS, emptySet()),
                AirportSearchIndex.Hit(long, AirportSearchIndex.Tier.NAME_CONTAINS, emptySet()),
            ))),
            truncated = false,
        ))
        shadowOf(Looper.getMainLooper()).idle()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 320, 500)
        assertThat(list.childCount).isEqualTo(2)
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            assertThat(row.width).isEqualTo(320)
            assertThat(row.findViewById<View>(R.id.pf_airport_airportCardVertical).width).isEqualTo(320)
        }
    }

    private fun airport(id: Long, iata: String) = Airport(
        id = id, iataCode = iata, icaoCode = null, name = "A$id", city = "C$id",
        country = null, latitude = 0.0, longitude = 0.0, elevation = null,
        continent = null, type = AirportType.LARGE_AIRPORT, popularity = 50,
        isPremiumOnly = false,
    )

    private fun hit(id: Long, tier: AirportSearchIndex.Tier) =
        AirportSearchIndex.Hit(airport(id, "X%02d".format(id)), tier, emptySet())

    // ══════════════════════════════════════════════════════
    // 扁平化
    // ══════════════════════════════════════════════════════

    @Test
    fun `单组时不插入标题`() {

        //   列表里全是同一类结果，标题只会占掉 30dp 高度。
        val state = AirportSearchState.Content(
            keyword = "capital",
            groups = listOf(
                ResultGroup(AirportSearchIndex.Tier.NAME_PREFIX, "机场名匹配", listOf(hit(1, AirportSearchIndex.Tier.NAME_PREFIX))),
            ),
            truncated = false,
        )
        val rows = AirportSearchAdapter.flatten(state)
        assertThat(rows.filterIsInstance<AirportSearchAdapter.Row.Header>()).isEmpty()
        assertThat(rows.filterIsInstance<AirportSearchAdapter.Row.AirportRow>()).hasSize(1)
    }

    @Test
    fun `多组时每组都有标题`() {
        val state = AirportSearchState.Content(
            keyword = "pek",
            groups = listOf(
                ResultGroup(AirportSearchIndex.Tier.CODE_EXACT, "精确匹配", listOf(hit(1, AirportSearchIndex.Tier.CODE_EXACT))),
                ResultGroup(AirportSearchIndex.Tier.NAME_CONTAINS, "机场名包含", listOf(hit(2, AirportSearchIndex.Tier.NAME_CONTAINS))),
            ),
            truncated = false,
        )
        val rows = AirportSearchAdapter.flatten(state)
        val headers = rows.filterIsInstance<AirportSearchAdapter.Row.Header>()
        assertThat(headers).hasSize(2)
        assertThat(headers.map { it.group.title }).containsExactly("精确匹配", "机场名包含").inOrder()
    }

    @Test
    fun `标题在组内第一条之前`() {
        val state = AirportSearchState.Content(
            keyword = "x",
            groups = listOf(
                ResultGroup(AirportSearchIndex.Tier.CODE_EXACT, "精确匹配", listOf(hit(1, AirportSearchIndex.Tier.CODE_EXACT))),
                ResultGroup(AirportSearchIndex.Tier.NAME_CONTAINS, "机场名包含", listOf(hit(2, AirportSearchIndex.Tier.NAME_CONTAINS))),
            ),
            truncated = false,
        )
        val rows = AirportSearchAdapter.flatten(state)
        assertThat(rows[0]).isInstanceOf(AirportSearchAdapter.Row.Header::class.java)
        assertThat(rows[1]).isInstanceOf(AirportSearchAdapter.Row.AirportRow::class.java)
        assertThat(rows[2]).isInstanceOf(AirportSearchAdapter.Row.Header::class.java)
        assertThat(rows[3]).isInstanceOf(AirportSearchAdapter.Row.AirportRow::class.java)
    }

    @Test
    fun `组内最后一条被标记为 last`() {

        //   悬空的横线（下面没有内容了），视觉上像"还有更多"。
        val state = AirportSearchState.Content(
            keyword = "x",
            groups = listOf(
                ResultGroup(
                    AirportSearchIndex.Tier.NAME_CONTAINS, "机场名包含",
                    listOf(hit(1, AirportSearchIndex.Tier.NAME_CONTAINS), hit(2, AirportSearchIndex.Tier.NAME_CONTAINS)),
                ),
                ResultGroup(
                    AirportSearchIndex.Tier.CITY_CONTAINS, "城市包含",
                    listOf(hit(3, AirportSearchIndex.Tier.CITY_CONTAINS)),
                ),
            ),
            truncated = false,
        )
        val rows = AirportSearchAdapter.flatten(state).filterIsInstance<AirportSearchAdapter.Row.AirportRow>()
        assertThat(rows.map { it.hit.airport.id to it.isLastInGroup })
            .containsExactly(1L to false, 2L to true, 3L to true).inOrder()
    }

    @Test
    fun `扁平化后行数等于条目数加标题数`() {
        val state = AirportSearchState.Content(
            keyword = "x",
            groups = listOf(
                ResultGroup(AirportSearchIndex.Tier.CODE_EXACT, "精确匹配", listOf(hit(1, AirportSearchIndex.Tier.CODE_EXACT))),
                ResultGroup(AirportSearchIndex.Tier.NAME_CONTAINS, "机场名包含", listOf(hit(2, AirportSearchIndex.Tier.NAME_CONTAINS), hit(3, AirportSearchIndex.Tier.NAME_CONTAINS))),
            ),
            truncated = false,
        )
        val rows = AirportSearchAdapter.flatten(state)
        assertThat(rows).hasSize(2 + 3)
    }

    @Test
    fun `关键词被带到每一行`() {

        //   滚动复用后会用到错的词（高亮在错误的位置）。
        val state = AirportSearchState.Content(
            keyword = "PEK",
            groups = listOf(
                ResultGroup(AirportSearchIndex.Tier.CODE_EXACT, "精确匹配", listOf(hit(1, AirportSearchIndex.Tier.CODE_EXACT))),
            ),
            truncated = false,
        )
        val rows = AirportSearchAdapter.flatten(state).filterIsInstance<AirportSearchAdapter.Row.AirportRow>()
        assertThat(rows.map { it.keyword }).containsExactly("PEK")
    }

    // ══════════════════════════════════════════════════════
    // 非 Content 状态
    // ══════════════════════════════════════════════════════

    @Test
    fun `非 Content 状态扁平化为空列表`() {

        //   用户搜 "pek" 有 5 条，然后删到只剩 "p" → TooShort，
        //   如果不清空列表，屏幕上还留着那 5 条 —— 而搜索框写着 "p"。
        //   用户会以为这 5 条是 "p" 的结果。
        for (s in listOf(
            AirportSearchState.Loading,
            AirportSearchState.Empty,
            AirportSearchState.TooShort("p", 1),
            AirportSearchState.NoResult("zzz", false),
            AirportSearchState.Idle(emptyList(), emptyList()),
        )) {
            assertThat(AirportSearchAdapter.flatten(s)).isEmpty()
        }
    }

    @Test
    fun `Idle 状态带推荐时也不走列表`() {

        //   不走这个 Adapter —— 因为推荐的样式和搜索结果不同
        //   （推荐没有"匹配层级"，也没有码条）。
        val idle = AirportSearchState.Idle(
            recent = listOf(airport(1, "PEK")),
            popular = listOf(airport(2, "PVG")),
        )
        assertThat(AirportSearchAdapter.flatten(idle)).isEmpty()
    }
}
