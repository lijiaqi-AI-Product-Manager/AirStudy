package com.projectfocus.feature.stats

import android.content.Context
import java.time.ZoneId
import java.util.Locale

internal data class StatsShareMetric(val label: String,val value: String,val unit: String,val available: Boolean) {
    val spoken: String get()="$label：$value${if(unit.isEmpty()) "" else " $unit"}"
}

/** Immutable display values for one account's selected period and scene. */
internal data class StatsShareSpec(
    val period: String,
    val scene: String,
    val focusMinutes: Int?,
    val focusLabel: String,
    val metrics: List<StatsShareMetric>,
    val note: String,
    val partial: Boolean,
    val summary: String,
) {
    companion object {
        fun from(context: Context,state: StatsUiState,zone: ZoneId): StatsShareSpec {
            require(!state.isLoading)
            val dash=context.getString(R.string.stats_placeholder)
            val period=state.dates?.let { StatsDateLabels.label(context,it,zone,state.nowWallMs,false) }
                ?: context.getString(R.string.stats_share_artwork_current_period)
            val scene=if(state.selectedSceneKey.isEmpty())context.getString(R.string.stats_share_artwork_all_scenes)
                else StatsSceneLabels(context).label(state.selectedSceneKey)
            fun metric(label: Int,value: String,unit: Int,available: Boolean)=StatsShareMetric(
                context.getString(label),if(available)value else dash,if(available)context.getString(unit) else "",available)
            fun count(label: Int,value: Int,unit: Int,available: Boolean)=StatsShareMetric(
                context.getString(label),if(available)value.toString() else dash,
                if(available)context.resources.getQuantityString(unit,value) else "",available)
            val metrics=listOf(
                count(R.string.stats_total_flights,state.flights,R.plurals.stats_share_unit_flights,state.coverage.periodFlights),
                metric(R.string.stats_total_distance,String.format(context.resources.configuration.locales[0] ?: Locale.getDefault(),"%.1f",state.distanceKm),R.string.stats_unit_km,state.coverage.periodDistance),
                count(R.string.stats_airports,state.airports,R.plurals.stats_share_unit_airports,state.coverage.periodTravel),
                count(R.string.stats_countries,state.countries,R.plurals.stats_share_unit_countries,state.coverage.periodTravel),
            )
            val focus=state.focusMinutes.takeIf { state.coverage.periodFocus }
            val focusLabel=context.getString(R.string.stats_share_artwork_focus)
            val partial=focus==null || metrics.any { !it.available }
            val note=context.getString(if(partial)R.string.stats_history_incomplete else R.string.stats_history_source_note)
            val summary=buildString {
                append(context.getString(R.string.stats_share_artwork_title));append('\n');append(period);append('\n');append(scene)
                append('\n');append(focusLabel);append('：')
                append(focus?.let { context.getString(R.string.stats_focus_minutes_only,it) } ?: dash)
                metrics.forEach { append('\n');append(it.spoken) }
                append('\n');append(note)
            }
            return StatsShareSpec(period,scene,focus,focusLabel,metrics,note,partial,summary)
        }
    }
}
