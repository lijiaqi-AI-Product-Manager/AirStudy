package com.projectfocus.feature.focus.selection

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.projectfocus.feature.focus.R


enum class FlightMode {
    COUNTDOWN,
    COUNTUP,
    GROUP,
}

data class FlightModePage(
    @StringRes val englishRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val betaRes: Int?,
    @StringRes val optionTitleRes: Int,
    @StringRes val optionSubtitleRes: Int?,
    @StringRes val ctaRes: Int,
    val mode: FlightMode,
)

class FlightModePageAdapter(
    private val pages: List<FlightModePage>,
    private val onSelect: (FlightModePage) -> Unit,
) : RecyclerView.Adapter<FlightModePageAdapter.PageHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_flight_mode_page, parent, false)
        return PageHolder(view)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.bind(pages[position], onSelect)
    }

    override fun getItemCount(): Int = pages.size

    class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val english: TextView = itemView.findViewById(R.id.tvPageEnglish)
        private val title: TextView = itemView.findViewById(R.id.tvPageTitle)
        private val beta: TextView = itemView.findViewById(R.id.tvPageBeta)
        private val optionTitle: TextView = itemView.findViewById(R.id.tvPageOptionTitle)
        private val optionSubtitle: TextView = itemView.findViewById(R.id.tvPageOptionSubtitle)
        private val select: TextView = itemView.findViewById(R.id.btnPageSelect)

        fun bind(page: FlightModePage, onSelect: (FlightModePage) -> Unit) {
            val context = itemView.context
            english.text = context.getString(page.englishRes)
            title.text = context.getString(page.titleRes)
            beta.visibility = if (page.betaRes != null) {
                beta.text = context.getString(page.betaRes)
                View.VISIBLE
            } else {
                View.GONE
            }
            optionTitle.text = context.getString(page.optionTitleRes)
            optionSubtitle.visibility = if (page.optionSubtitleRes != null) {
                optionSubtitle.text = context.getString(page.optionSubtitleRes)
                View.VISIBLE
            } else {
                View.GONE
            }
            select.text = context.getString(page.ctaRes)
            select.setOnClickListener { onSelect(page) }
        }
    }
}
