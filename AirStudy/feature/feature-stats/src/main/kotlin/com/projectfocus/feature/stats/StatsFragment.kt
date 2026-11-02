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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.HistoryAccount
import java.util.Locale


@AndroidEntryPoint
class StatsFragment : Fragment(R.layout.fragment_stats) {

    private val viewModel: StatsViewModel by viewModels()
    private var lastState: StatsUiState? = null
    private var renderRevision = 0L

    @Inject
    lateinit var airportDao: AirportDao

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
            val account = lastState?.account ?: return@setFragmentResultListener
            if (!account.isCurrent() || bundle.getString(CountryAirportsDialogFragment.RESULT_OWNER) != account.scope ||
                bundle.getLong(CountryAirportsDialogFragment.RESULT_GENERATION, -1) != account.generation) return@setFragmentResultListener
            withAccount(account) { openAirportDetail(bundle.getString(CountryAirportsDialogFragment.RESULT_CODE).orEmpty()) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TokenStore.identities.collect { observed ->
                    if (observed == TokenStore.snapshot() && viewModel.state.value.account?.isCurrent() != true) render(StatsUiState())
                }
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
        val revision = ++renderRevision
        val actual = if (state.account?.isCurrent() == true) state else StatsUiState()
        withAccount(actual.account, { renderRevision == revision }) { renderCurrent(actual) }
    }

    private fun withAccount(account: HistoryAccount?, current: () -> Boolean = { true }, action: () -> Unit) {
        val target = view ?: return
        val expected = TokenStore.snapshot()
        if (account != null && (account.generation != expected.generation || account.scope != TokenStore.storageScope(expected))) return
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && view === target && current()) {
                when (TokenStore.tryWithSnapshotLease(expected) { if (view === target && current()) action() }) {
                    is TokenStore.ImmediateSnapshot.Value -> return@launch
                    TokenStore.ImmediateSnapshot.Obsolete -> return@launch
                    TokenStore.ImmediateSnapshot.Busy -> delay(16)
                }
            }
        }
    }

    private fun renderCurrent(state: StatsUiState) {
        lastState = state
        val root = view ?: return
        val current = state.account?.isCurrent() == true
        root.findViewById<View>(R.id.statsShare).isEnabled = !state.isLoading && current

        if (state.isLoading || !current) {
            val placeholder = getString(R.string.stats_placeholder)
            root.findViewById<TextView>(R.id.statsFlights).text = placeholder
            root.findViewById<TextView>(R.id.statsDistance).text = placeholder
            root.findViewById<TextView>(R.id.statsFocus).text = placeholder
            root.findViewById<TextView>(R.id.statsAirports).text = placeholder
            root.findViewById<TextView>(R.id.statsCountries).text = placeholder
            root.findViewById<View>(R.id.statsCountryList).visibility = View.GONE
            root.findViewById<TextView>(R.id.statsCountryEmpty).apply {
                visibility = if (state.readFailed && current) View.VISIBLE else View.GONE
                setText(R.string.stats_read_failed)
                setOnClickListener { viewModel.retry() }
            }
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
        (empty as TextView).setText(R.string.stats_country_empty)
        empty.setOnClickListener(null)
        list.visibility = View.VISIBLE
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
        val captured = lastState?.takeIf { !it.isLoading && it.account?.isCurrent() == true } ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = runCatching {
                val airports = airportDao.byCountry(country)
                val destinations = captured.destinations.filter { it.country == country }
                val visited = destinations.map { it.code }.toSet()
                val knownCodes = airports.map { it.iataCode.ifBlank { it.icaoCode } }.toSet()
                ArrayList(
                    airports.map { airport ->
                        CountryAirportRow(
                            code = airport.iataCode.ifBlank { airport.icaoCode },
                            name = airport.name,
                            city = airport.city,
                            visited = airport.iataCode.ifBlank { airport.icaoCode } in visited,
                        )
                    } + destinations.filter { it.code !in knownCodes }.map {
                        CountryAirportRow(it.code, it.name, it.city, true)
                    },
                )
            }
            if (!isAdded || view == null || captured.account?.isCurrent() != true) return@launch
            loaded.onSuccess { rows ->
                withAccount(captured.account) {
                    CountryAirportsDialogFragment.newInstance(country, rows, captured.account)
                        .show(childFragmentManager, "country_airports")
                }
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
        if (state.isLoading || state.account?.isCurrent() != true) return
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
        withAccount(state.account) { startActivity(Intent.createChooser(send, getString(R.string.stats_share_title))) }
    }
}
