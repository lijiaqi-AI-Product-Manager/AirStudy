package com.projectfocus.feature.stats

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.ui.nav.AirportNavigator
import com.projectfocus.core.database.dao.CountryTripCount
import com.projectfocus.core.database.dao.FlightHistoryDao
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale


@AndroidEntryPoint
class StatsFragment : Fragment(R.layout.fragment_stats) {

    private val viewModel: StatsViewModel by viewModels()
    private var lastState: StatsUiState? = null

    @Inject
    lateinit var airportDao: AirportDao

    @Inject
    lateinit var historyDao: FlightHistoryDao

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.statsBack).setOnClickListener {
            findNavController().popBackStack()
        }
        view.findViewById<View>(R.id.statsShare).setOnClickListener {
            shareStats()
        }

        // 下钻对话框用 childFragmentManager 弹出（见 showCountryAirports），
        // 它的 setFragmentResult 走的就是这个管理器，监听必须挂在同一个上。
        childFragmentManager.setFragmentResultListener(
            CountryAirportsDialogFragment.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            openAirportDetail(
                bundle.getString(CountryAirportsDialogFragment.RESULT_CODE).orEmpty(),
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun openAirportDetail(code: String) {
        if (!AirportNavigator.open(this, code)) {
            Toast.makeText(
                requireContext(),
                R.string.stats_country_airport_open_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun render(state: StatsUiState) {
        lastState = state
        val root = view ?: return
        root.findViewById<View>(R.id.statsShare).isEnabled = !state.isLoading

        if (state.isLoading) {
            val placeholder = getString(R.string.stats_placeholder)
            root.findViewById<TextView>(R.id.statsFlights).text = placeholder
            root.findViewById<TextView>(R.id.statsDistance).text = placeholder
            root.findViewById<TextView>(R.id.statsFocus).text = placeholder
            root.findViewById<TextView>(R.id.statsAirports).text = placeholder
            root.findViewById<TextView>(R.id.statsCountries).text = placeholder
            root.findViewById<View>(R.id.statsCountryList).visibility = View.GONE
            root.findViewById<View>(R.id.statsCountryEmpty).visibility = View.GONE
            return
        }

        root.findViewById<TextView>(R.id.statsFlights).text =
            "${state.flights} ${getString(R.string.stats_unit_flights)}"
        root.findViewById<TextView>(R.id.statsDistance).text = String.format(
            Locale.US,
            "%,.0f %s",
            state.distanceKm,
            getString(R.string.stats_unit_km),
        )
        root.findViewById<TextView>(R.id.statsFocus).text = formatFocus(state)
        root.findViewById<TextView>(R.id.statsAirports).text =
            "${state.airports} ${getString(R.string.stats_unit_airports)}"
        root.findViewById<TextView>(R.id.statsCountries).text =
            "${state.countries} ${getString(R.string.stats_unit_countries)}"
        renderCountryBreakdown(root, state.countryBreakdown)
    }

    private fun renderCountryBreakdown(root: View, rows: List<CountryTripCount>) {
        val list = root.findViewById<LinearLayout>(R.id.statsCountryList)
        val empty = root.findViewById<View>(R.id.statsCountryEmpty)
        list.removeAllViews()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        rows.forEach { row ->
            val item = layoutInflater.inflate(R.layout.item_stats_country, list, false)
            item.findViewById<TextView>(R.id.statsCountryName).text = row.country
            val progress = item.findViewById<TextView>(R.id.statsCountryProgress)
            if (row.totalAirports > 0) {
                progress.visibility = View.VISIBLE
                progress.text = getString(
                    R.string.stats_country_progress_fmt,
                    row.visitedAirports,
                    row.totalAirports,
                )
            } else {
                progress.visibility = View.GONE
            }
            item.findViewById<TextView>(R.id.statsCountryCount).text =
                getString(R.string.stats_country_count, row.tripCount)
            item.setOnClickListener { openCountryAirports(row.country) }
            list.addView(item)
        }
    }

    private fun openCountryAirports(country: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = runCatching {
                val airports = airportDao.byCountry(country)
                val visited = historyDao.visitedAirportCodesForCountry(country).toSet()
                ArrayList(
                    airports.map { airport ->
                        CountryAirportRow(
                            code = airport.iataCode,
                            name = airport.name,
                            city = airport.city,
                            visited = airport.iataCode in visited,
                        )
                    },
                )
            }
            if (!isAdded || view == null) return@launch
            loaded.onSuccess { rows ->
                CountryAirportsDialogFragment.newInstance(country, rows)
                    .show(childFragmentManager, "country_airports")
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Toast.makeText(
                    requireContext(),
                    R.string.stats_country_airports_load_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun formatFocus(state: StatsUiState): String = if (state.hasFocusHours) {
        getString(
            R.string.stats_focus_format,
            state.focusHours,
            state.focusRemainderMinutes,
        )
    } else {
        getString(R.string.stats_focus_minutes_only, state.focusRemainderMinutes)
    }

    private fun shareStats() {
        val state = lastState ?: return
        if (state.isLoading) return
        val text = getString(
            R.string.stats_share_text,
            state.flights,
            state.distanceKm,
            state.focusMinutes,
            state.airports,
            state.countries,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.stats_share_title)))
    }
}
