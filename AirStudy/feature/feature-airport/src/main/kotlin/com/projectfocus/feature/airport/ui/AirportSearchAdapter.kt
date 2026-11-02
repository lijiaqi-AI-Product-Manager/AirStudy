package com.projectfocus.feature.airport.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.projectfocus.feature.airport.search.AirportSearchIndex
import com.projectfocus.feature.airport.search.AirportSearchState
import com.projectfocus.feature.airport.search.ResultGroup

























class AirportSearchAdapter(
    private val onClick: (AirportSearchIndex.Hit) -> Unit,
    /**
     * 长按结果行：打开机场详情。
     *
     * 不改行内布局：卡片已有代码、名称和航时，塞按钮会
     * 挤压命中区；长按是列表既有手势，也不影响“单击=选中”的主路径。
     */
    private val onLongClick: ((AirportSearchIndex.Hit) -> Unit)? = null,
    /** Frozen opposite endpoint supplied by this product's picker host, not an original GPS policy. */
    private val referencePoint: AirportReferencePoint? = null,
) : ListAdapter<AirportSearchAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    /** 扁平化后的行 */
    sealed interface Row {
        data class Header(val group: ResultGroup) : Row
        data class AirportRow(
            val hit: AirportSearchIndex.Hit,
            /** 当前高亮用的关键词（与 state 一起变化） */
            val keyword: String,
            /** 是否是该分组里的最后一条（最后一条不画分割线） */
            val isLastInGroup: Boolean,
            val flightMinutes: Int? = null,
        ) : Row
    }

    fun submit(state: AirportSearchState) {
        submitList(flatten(state, referencePoint))
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Header -> TYPE_HEADER
        is Row.AirportRow -> TYPE_AIRPORT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_HEADER -> HeaderHolder(AirportGroupHeaderView(parent.context))
            else -> RowHolder(AirportRowView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderHolder).bind(row.group)
            is Row.AirportRow -> (holder as RowHolder).bind(row)
        }
    }

    private class HeaderHolder(private val v: AirportGroupHeaderView) :
        RecyclerView.ViewHolder(v) {
        fun bind(g: ResultGroup) = v.bind(g.title, g.hits.size)
    }

    private inner class RowHolder(private val v: AirportRowView) :
        RecyclerView.ViewHolder(v) {
        private var current: Row.AirportRow? = null

        init {
            v.isClickable = true
            v.isFocusable = true
            v.setOnClickListener { current?.let { onClick(it.hit) } }
            if (onLongClick != null) {
                v.setOnLongClickListener {
                    val hit = current?.hit
                    if (hit == null) {
                        false
                    } else {
                        onLongClick?.invoke(hit)
                        true
                    }
                }
            }
        }

        fun bind(row: Row.AirportRow) {
            current = row

            //   机场名和码的极少数情况下才需要，而这个参数会让每条都多一段。
            //   等有真实数据反馈说"用户需要知道这是直升机场"再加。
            v.bind(row.hit, row.keyword, typeLabel = null, flightMinutes = row.flightMinutes)
            v.showDivider = !row.isLastInGroup
        }
    }

    companion object {
        const val TYPE_HEADER = 1
        const val TYPE_AIRPORT = 2












        internal fun flatten(
            state: AirportSearchState,
            referencePoint: AirportReferencePoint? = null,
        ): List<Row> {
            if (state !is AirportSearchState.Content) return emptyList()

            val showHeaders = state.groups.size > 1
            val rows = ArrayList<Row>(state.total + state.groups.size)
            for (g in state.groups) {
                if (showHeaders) rows += Row.Header(g)
                g.hits.forEachIndexed { i, h ->
                    rows += Row.AirportRow(
                        hit = h,
                        keyword = state.keyword,
                        isLastInGroup = i == g.hits.lastIndex,
                        flightMinutes = AirportCardFlightTime.minutes(referencePoint, h.airport),
                    )
                }
            }
            return rows
        }

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row): Boolean = when {
                a is Row.Header && b is Row.Header -> a.group.tier == b.group.tier
                a is Row.AirportRow && b is Row.AirportRow -> a.hit.airport.id == b.hit.airport.id
                else -> false
            }







            override fun areContentsTheSame(a: Row, b: Row): Boolean = when {
                a is Row.Header && b is Row.Header ->
                    a.group.title == b.group.title && a.group.hits.size == b.group.hits.size
                a is Row.AirportRow && b is Row.AirportRow ->
                    a.hit.airport.id == b.hit.airport.id &&
                        a.hit.tier == b.hit.tier &&
                        a.hit.exactCode == b.hit.exactCode &&
                        a.keyword == b.keyword &&
                        a.isLastInGroup == b.isLastInGroup &&
                        a.flightMinutes == b.flightMinutes &&
                        // 标题/副标题的展示文本变了也必须重绘
                        a.hit.airport.cityZh == b.hit.airport.cityZh &&
                        a.hit.airport.city == b.hit.airport.city &&
                        a.hit.airport.nameZh == b.hit.airport.nameZh &&
                        a.hit.airport.name == b.hit.airport.name &&
                        a.hit.airport.iataCode == b.hit.airport.iataCode &&
                        a.hit.airport.icaoCode == b.hit.airport.icaoCode
                else -> false
            }
        }
    }
}
