package com.projectfocus.feature.record.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.projectfocus.feature.record.list.DayGroup
import com.projectfocus.feature.record.list.RecordItem
import com.projectfocus.feature.record.list.RecordListState
import com.projectfocus.feature.record.list.RecordSummary





























class RecordListAdapter(
    private val onClick: (RecordItem) -> Unit,
) : ListAdapter<RecordListAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    /** 扁平化后的行 */
    sealed interface Row {
        /** 顶部汇总 */
        data class Summary(val summary: RecordSummary) : Row

        /** 日期分组标题：`今天` / `3 趟 · 2 小时 15 分` */
        data class Header(val group: DayGroup) : Row

        data class Card(
            val item: RecordItem,
            /** 分组内最后一张不画分割线 */
            val isLastInGroup: Boolean,
        ) : Row
    }

    fun submit(state: RecordListState) {
        submitList(flatten(state))
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Summary -> TYPE_SUMMARY
        is Row.Header -> TYPE_HEADER
        is Row.Card -> TYPE_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_SUMMARY -> SummaryHolder(RecordSummaryView(parent.context))
            TYPE_HEADER -> HeaderHolder(RecordDayHeaderView(parent.context))
            else -> CardHolder(RecordCardView(parent.context))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Summary -> (holder as SummaryHolder).bind(row.summary)
            is Row.Header -> (holder as HeaderHolder).bind(row.group)
            is Row.Card -> (holder as CardHolder).bind(row)
        }
    }

    private class SummaryHolder(private val v: RecordSummaryView) :
        RecyclerView.ViewHolder(v) {
        fun bind(s: RecordSummary) = v.bind(s)
    }

    private class HeaderHolder(private val v: RecordDayHeaderView) :
        RecyclerView.ViewHolder(v) {
        fun bind(g: DayGroup) = v.bind(g.title, g.summary)
    }

    private inner class CardHolder(private val v: RecordCardView) :
        RecyclerView.ViewHolder(v) {
        private var current: RecordItem? = null

        init {
            v.isClickable = true
            v.setOnClickListener { current?.let { onClick(it) } }
        }

        fun bind(row: Row.Card) {
            current = row.item
            v.bind(row.item)
        }
    }

    companion object {
        const val TYPE_SUMMARY = 1
        const val TYPE_HEADER = 2
        const val TYPE_CARD = 3












        internal fun flatten(state: RecordListState): List<Row> {
            if (state !is RecordListState.Content) return emptyList()

            val rows = ArrayList<Row>(state.groups.sumOf { it.items.size } + state.groups.size + 1)
            if (!state.summary.isEmpty) rows += Row.Summary(state.summary)

            for (g in state.groups) {
                val showHeader = g.items.size > 1 || state.groups.size > 1
                if (showHeader) rows += Row.Header(g)
                g.items.forEachIndexed { i, item ->
                    rows += Row.Card(item = item, isLastInGroup = i == g.items.lastIndex)
                }
            }
            return rows
        }

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row): Boolean = when {
                a is Row.Summary && b is Row.Summary -> true
                a is Row.Header && b is Row.Header -> a.group.date == b.group.date
                a is Row.Card && b is Row.Card -> a.item.key == b.item.key
                else -> false
            }










            override fun areContentsTheSame(a: Row, b: Row): Boolean = when {
                a is Row.Summary && b is Row.Summary -> a.summary == b.summary
                a is Row.Header && b is Row.Header ->
                    a.group.title == b.group.title && a.group.summary == b.group.summary
                a is Row.Card && b is Row.Card ->
                    a.isLastInGroup == b.isLastInGroup &&
                        a.item.status == b.item.status &&
                        a.item.startTime == b.item.startTime &&
                        a.item.fromCode == b.item.fromCode &&
                        a.item.toCode == b.item.toCode &&
                        a.item.fromCity == b.item.fromCity &&
                        a.item.toCity == b.item.toCity &&
                        a.item.durationText == b.item.durationText &&
                        a.item.distanceText == b.item.distanceText &&
                        (a.item.completionRatio * 100).toInt() ==
                        (b.item.completionRatio * 100).toInt()
                else -> false
            }
        }
    }
}

/** 供 Fragment 判断是否需要滚动到顶 */
internal fun List<RecordListAdapter.Row>.firstCardIndex(): Int =
    indexOfFirst { it is RecordListAdapter.Row.Card }
