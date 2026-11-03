package com.projectfocus.feature.record.ui

import android.view.ViewGroup
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.projectfocus.feature.record.list.RecordItem

internal class RecordPageAdapter(
    private val onOpen: (RecordCardView, RecordItem) -> Unit,
    private val onSceneEdit: ((View, RecordItem) -> Unit)? = null,
    private val canEdit: () -> Boolean = { true },
) :
    RecyclerView.Adapter<RecordPageAdapter.Holder>() {
    private var items = emptyList<RecordItem>()
    class Holder(val card: RecordCardView) : RecyclerView.ViewHolder(card)
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(RecordCardView(parent.context))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.card.bind(item, onSceneEdit, canEdit())
        holder.card.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION && items.getOrNull(index)?.key == item.key) onOpen(holder.card, item)
        }
    }
    override fun onViewRecycled(holder: Holder) {
        holder.card.setOnClickListener(null)
        holder.card.bind(null)
        super.onViewRecycled(holder)
    }
    fun replace(rows: List<RecordItem>) {
        items = rows.toList()
        notifyDataSetChanged()
    }
}
