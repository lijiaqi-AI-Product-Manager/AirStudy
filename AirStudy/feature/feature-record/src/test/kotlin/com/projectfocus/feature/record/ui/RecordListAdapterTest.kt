package com.projectfocus.feature.record.ui

import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.record.list.DayGroup
import com.projectfocus.feature.record.list.FlightStatus
import com.projectfocus.feature.record.list.RecordItem
import com.projectfocus.feature.record.list.RecordListState
import com.projectfocus.feature.record.list.RecordSummary
import com.projectfocus.feature.record.ui.RecordListAdapter.Row
import org.junit.Test
import java.time.LocalDate



























class RecordListAdapterTest {

    // ═══════════════════════════════════════════════════════════
    // 样本
    // ═══════════════════════════════════════════════════════════

    private fun item(id: String, city: String = "大阪") = RecordItem(
        id = id,
        fromCode = "PEK",
        toCode = "KIX",
        fromCity = "北京",
        toCity = city,
        startTime = "08:30",
        durationText = "2 小时 15 分",
        distanceText = "1800.0 km",
        focusText = "2 小时",
        status = FlightStatus.COMPLETED,
        completionRatio = 0.9f,
    )

    private fun group(date: String, title: String, items: List<RecordItem>) = DayGroup(
        date = LocalDate.parse(date),
        title = title,
        summary = "${items.size} 趟 · 2 小时",
        items = items,
    )

    private val summary = RecordSummary(
        flightCount = 12,
        totalHoursText = "8 小时 30 分",
        totalDistanceText = "9800.0",
        countryCount = 5,
    )

    private fun content(groups: List<DayGroup>, s: RecordSummary = summary) =
        RecordListState.Content(summary = s, groups = groups)

    // ═══════════════════════════════════════════════════════════
    // 非 Content 状态
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `Loading 状态扁平化为空列表`() {

        //   如果做成 Row，RecyclerView 在"空态 → 有内容"时会先删一条再插 N 条，
        //   DiffUtil 的动画会让人看到"一条东西飞走"。
        assertThat(RecordListAdapter.flatten(RecordListState.Loading)).isEmpty()
    }

    @Test
    fun `Empty 状态扁平化为空列表`() {
        assertThat(RecordListAdapter.flatten(RecordListState.Empty)).isEmpty()
    }

    @Test
    fun `NoResult 状态扁平化为空列表`() {
        assertThat(RecordListAdapter.flatten(RecordListState.NoResult("广州"))).isEmpty()
    }

    // ═══════════════════════════════════════════════════════════
    // 汇总
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `有内容时第一行是汇总`() {
        val rows = RecordListAdapter.flatten(
            content(listOf(group("2026-10-26", "今天", listOf(item("a"), item("b"))))),
        )
        assertThat(rows.first()).isInstanceOf(Row.Summary::class.java)
    }

    @Test
    fun `汇总为空时不插入汇总行`() {

        //   而 summary.isEmpty 的判定已经在 Builder 里做过（只统计 COMPLETED），
        //   这里只负责"尊重那个判定"。
        val rows = RecordListAdapter.flatten(
            content(
                listOf(group("2026-10-26", "今天", listOf(item("a")))),
                s = RecordSummary.EMPTY,
            ),
        )
        assertThat(rows.none { it is Row.Summary }).isTrue()
    }

    // ═══════════════════════════════════════════════════════════
    // 分组标题的显示 / 隐藏
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `多组时每组都显示标题`() {
        val rows = RecordListAdapter.flatten(
            content(
                listOf(
                    group("2026-10-26", "今天", listOf(item("a"))),
                    group("2026-10-25", "昨天", listOf(item("b"))),
                ),
            ),
        )
        assertThat(rows.filterIsInstance<Row.Header>()).hasSize(2)
    }

    @Test
    fun `单组单条时不显示分组标题`() {

        //   "今天 · 1 趟 · 2 小时"是纯噪音 —— 卡片上已有 `08:30 起飞`。
        val rows = RecordListAdapter.flatten(
            content(listOf(group("2026-10-26", "今天", listOf(item("a"))))),
        )
        assertThat(rows.filterIsInstance<Row.Header>()).isEmpty()
    }

    @Test
    fun `单组多条时显示分组标题`() {

        //   它告诉用户"这 3 条是同一天的"。
        val rows = RecordListAdapter.flatten(
            content(
                listOf(group("2026-10-26", "今天", listOf(item("a"), item("b"), item("c")))),
            ),
        )
        assertThat(rows.filterIsInstance<Row.Header>()).hasSize(1)
    }

    @Test
    fun `同一天飞三次不会因为没标题而分不清是三次还是一次`() {



        val rows = RecordListAdapter.flatten(
            content(
                listOf(group("2026-10-26", "今天", listOf(item("a"), item("b"), item("c")))),
            ),
        )
        val cards = rows.filterIsInstance<Row.Card>()
        assertThat(cards).hasSize(3)
        assertThat(cards.map { it.item.id }).containsExactly("a", "b", "c").inOrder()
    }

    // ═══════════════════════════════════════════════════════════
    // 分割线标志
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每组最后一张卡片不画分割线`() {
        val rows = RecordListAdapter.flatten(
            content(listOf(group("2026-10-26", "今天", listOf(item("a"), item("b"))))),
        )
        val cards = rows.filterIsInstance<Row.Card>()
        assertThat(cards[0].isLastInGroup).isFalse()
        assertThat(cards[1].isLastInGroup).isTrue()
    }

    @Test
    fun `跨组时上一组的最后一张和下一组的第一张都按组内位置标记`() {

        //   那么组与组之间就没有分隔了 —— 两组的卡片会糊成一片。
        val rows = RecordListAdapter.flatten(
            content(
                listOf(
                    group("2026-10-26", "今天", listOf(item("a"), item("b"))),
                    group("2026-10-25", "昨天", listOf(item("c"))),
                ),
            ),
        )
        val cards = rows.filterIsInstance<Row.Card>()
        assertThat(cards.map { it.item.id to it.isLastInGroup })
            .containsExactly("a" to false, "b" to true, "c" to true).inOrder()
    }

    // ═══════════════════════════════════════════════════════════
    // 顺序
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `行顺序是汇总 标题 卡片 标题 卡片`() {
        val rows = RecordListAdapter.flatten(
            content(
                listOf(
                    group("2026-10-26", "今天", listOf(item("a"), item("b"))),
                    group("2026-10-25", "昨天", listOf(item("c"))),
                ),
            ),
        )
        assertThat(rows.map { it::class.simpleName }).containsExactly(
            "Summary", "Header", "Card", "Card", "Header", "Card",
        ).inOrder()
    }

    // ═══════════════════════════════════════════════════════════
    // DiffUtil 的量化比较
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `完成度差千分之一视为内容相同`() {

        //   重绘了但屏幕上一模一样 —— 而重绘会让整张卡片闪一次。
        val a = Row.Card(item("a").copy(completionRatio = 0.900f), isLastInGroup = false)
        val b = Row.Card(item("a").copy(completionRatio = 0.9009f), isLastInGroup = false)
        assertThat(RecordListAdapter.DIFF.areContentsTheSame(a, b)).isTrue()
    }

    @Test
    fun `完成度差百分之二视为内容不同`() {
        val a = Row.Card(item("a").copy(completionRatio = 0.90f), isLastInGroup = false)
        val b = Row.Card(item("a").copy(completionRatio = 0.92f), isLastInGroup = false)
        assertThat(RecordListAdapter.DIFF.areContentsTheSame(a, b)).isFalse()
    }

    @Test
    fun `状态变化视为内容不同`() {
        val a = Row.Card(item("a").copy(status = FlightStatus.ONGOING), isLastInGroup = false)
        val b = Row.Card(item("a").copy(status = FlightStatus.COMPLETED), isLastInGroup = false)
        assertThat(RecordListAdapter.DIFF.areContentsTheSame(a, b)).isFalse()
    }

    @Test
    fun `分割线标志变化视为内容不同`() {

        //   而那张卡片不会重绘 —— 分割线留在错误的位置。
        val a = Row.Card(item("a"), isLastInGroup = false)
        val b = Row.Card(item("a"), isLastInGroup = true)
        assertThat(RecordListAdapter.DIFF.areContentsTheSame(a, b)).isFalse()
    }

    @Test
    fun `id 相同视为同一项`() {
        val a = Row.Card(item("a", city = "大阪"), isLastInGroup = false)
        val b = Row.Card(item("a", city = "东京"), isLastInGroup = false)
        assertThat(RecordListAdapter.DIFF.areItemsTheSame(a, b)).isTrue()
    }

    @Test
    fun `id 不同视为不同项`() {
        assertThat(
            RecordListAdapter.DIFF.areItemsTheSame(
                Row.Card(item("a"), false),
                Row.Card(item("b"), false),
            ),
        ).isFalse()
    }

    @Test
    fun `不同日期是不同分组`() {
        assertThat(
            RecordListAdapter.DIFF.areItemsTheSame(
                Row.Header(group("2026-10-26", "今天", emptyList())),
                Row.Header(group("2026-10-25", "昨天", emptyList())),
            ),
        ).isFalse()
    }

    @Test
    fun `汇总永远视为同一项`() {

        //   数据变化时走"内容变化"而不是"删一条插一条"，
        //   视觉上就是数字原地跳变，而不是整块飞走再飞回。
        assertThat(RecordListAdapter.DIFF.areItemsTheSame(Row.Summary(summary), Row.Summary(RecordSummary.EMPTY)))
            .isTrue()
    }

    // ═══════════════════════════════════════════════════════════
    // ViewType
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `三种行的 ViewType 互不相同`() {

        //   而报错位置在 RecyclerView 内部，看不出是 ViewType 撞了。
        val types = setOf(
            RecordListAdapter.TYPE_SUMMARY,
            RecordListAdapter.TYPE_HEADER,
            RecordListAdapter.TYPE_CARD,
        )
        assertThat(types).hasSize(3)
    }

    @Test
    fun `ViewType 不为零`() {


        assertThat(RecordListAdapter.TYPE_SUMMARY).isNotEqualTo(0)
        assertThat(RecordListAdapter.TYPE_HEADER).isNotEqualTo(0)
        assertThat(RecordListAdapter.TYPE_CARD).isNotEqualTo(0)
    }
}
