package com.projectfocus.feature.stats

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.ui.nav.AirportNavigator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.HistoryAccount

/** Statistics for the current account and selected local date range. */
@AndroidEntryPoint
class StatsFragment : Fragment(R.layout.fragment_stats) {

    private val viewModel: StatsViewModel by viewModels()
    private var lastState: StatsUiState? = null
    private var renderRevision = 0L
    private var dateDialog: StatsDateRangeDialog? = null

    @Inject
    lateinit var airportDao: AirportDao

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        StatsSkyPresentation.page(view)

        if (arguments?.getBoolean(ARG_EMBEDDED) == true) {
            (view.findViewById<View>(R.id.statsBack).parent as? View)?.visibility = View.GONE
        }

        view.findViewById<View>(R.id.statsBack).setOnClickListener {
            findNavController().popBackStack()
        }
        view.findViewById<View>(R.id.statsShare).setOnClickListener {
            shareStats()
        }
        fun dateControlsActive() = this.view === view && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        view.findViewById<StatsDateControls>(R.id.statsDateControls).apply {
            onPeriodRequested = action@{ period ->
                if (!dateControlsActive()) return@action
                if (period == StatsPeriod.CUSTOM) openDateRange()
                else lastState?.account?.let { account -> withAccount(account, ::dateControlsActive) { viewModel.selectPeriod(account, period) } }
            }
            onMoveRequested = action@{ direction ->
                if (!dateControlsActive()) return@action
                lastState?.account?.let { account -> withAccount(account, ::dateControlsActive) { viewModel.movePeriod(account, direction) } }
            }
            onClearSceneRequested = action@{ account, revision ->
                if (!dateControlsActive()) return@action
                withAccount(account, ::dateControlsActive) { viewModel.selectScene(account, revision, "") }
            }
        }
        view.findViewById<StatsScenePanel>(R.id.statsScenePanel).apply {
            interactionAllowed = ::dateControlsActive
            onSceneRequested = action@{ key, captured ->
                if (!dateControlsActive()) return@action
                val account = captured.account ?: return@action
                withAccount(account, ::dateControlsActive) { viewModel.selectScene(account, captured.queryRevision, key) }
            }
        }
        view.findViewById<StatsTrendPanel>(R.id.statsTrendPanel).interactionAllowed=::dateControlsActive
        view.findViewById<StatsHourPanel>(R.id.statsHourPanel).apply { interactionAllowed=::dateControlsActive; onRetryRequested=::retryData }
        view.findViewById<StatsInsightsPanel>(R.id.statsInsightsPanel).apply { interactionAllowed=::dateControlsActive; onRetryRequested=::retryData }
        view.findViewById<StatsHighlightsPanel>(R.id.statsHighlightsPanel).apply { interactionAllowed=::dateControlsActive; onRetryRequested=::retryData }
        view.findViewById<StatsHeatmapPanel>(R.id.statsHeatmapPanel).apply {
            interactionAllowed = ::dateControlsActive
            currentWallMs = viewModel::currentWallMs
            onYearRequested = action@{ direction, captured ->
                val account = captured.account ?: return@action
                if (!dateControlsActive() || !viewModel.isCurrentHeatmapQuery(account, captured.queryRevision, captured.heatmapRevision)) return@action
                withAccount(account, ::dateControlsActive) {
                    viewModel.moveHeatmapYear(account, captured.queryRevision, captured.heatmapRevision, direction)
                }
            }
        }

        view.findViewById<StatsCareerPanel>(R.id.statsCareerPanel).apply {
            interactionAllowed = ::dateControlsActive
            onRetryRequested = ::retryData
            onCountryRequested = action@{ country, captured ->
                val account = captured.account ?: return@action
                if (!dateControlsActive() || !viewModel.isCurrentQuery(account, captured.queryRevision)) return@action
                withAccount(account, ::dateControlsActive) { openCountryAirports(country) }
            }
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
                    if (observed == TokenStore.snapshot() && viewModel.state.value.account?.isCurrent() != true) {
                        closeDateRange()
                        render(StatsUiState())
                    }
                    if (observed == TokenStore.snapshot() && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) requestCloudHistory()
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
        if (lastState?.account != state.account || lastState?.queryRevision != state.queryRevision) {
            closeDateRange()
            childFragmentManager.fragments.filterIsInstance<CountryAirportsDialogFragment>().forEach { it.dismissAllowingStateLoss() }
        }
        lastState = state
        val root = view ?: return
        val current = state.account?.isCurrent() == true
        root.findViewById<TextView>(R.id.statsHistoryStatus).apply {
            visibility=if(current && !state.isLoading && state.coverage.incomplete)View.VISIBLE else View.GONE
            setOnClickListener {
                if (lastState === state && state.account?.isCurrent() == true && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    withAccount(state.account, { lastState === state }) { retryData() }
            }
        }
        root.findViewById<StatsDateControls>(R.id.statsDateControls).bind(state, viewModel.zone)
        root.findViewById<StatsHeadlineView>(R.id.statsHeadline).bind(state, viewModel.zone)
        root.findViewById<StatsTrendPanel>(R.id.statsTrendPanel).bind(state, viewModel.zone)
        root.findViewById<StatsScenePanel>(R.id.statsScenePanel).bind(state)
        root.findViewById<StatsHourPanel>(R.id.statsHourPanel).bind(state)
        root.findViewById<StatsInsightsPanel>(R.id.statsInsightsPanel).bind(state,viewModel.zone)
        root.findViewById<StatsHighlightsPanel>(R.id.statsHighlightsPanel).bind(state,viewModel.zone)
        root.findViewById<StatsHeatmapPanel>(R.id.statsHeatmapPanel).bind(state)
        root.findViewById<View>(R.id.statsShare).isEnabled = !state.isLoading && current

        root.findViewById<StatsCareerPanel>(R.id.statsCareerPanel).bind(state)
    }

    private fun openCountryAirports(country: String) {
        val captured = lastState?.takeIf { !it.isLoading && it.account?.isCurrent() == true } ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = runCatching {
                val airports = airportDao.byCountry(country)
                val destinations = captured.career?.destinations.orEmpty().filter { it.country == country }
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
            if (!isAdded || view == null || captured.account?.isCurrent() != true ||
                lastState?.queryRevision != captured.queryRevision) return@launch
            loaded.onSuccess { rows ->
                withAccount(captured.account, { lastState?.queryRevision == captured.queryRevision }) {
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

    private fun shareStats() {
        val state = lastState ?: return
        if (state.isLoading || state.account?.isCurrent() != true) return
        val partial = !state.coverage.periodFlights || !state.coverage.periodFocus || !state.coverage.periodDistance || !state.coverage.periodTravel
        val dash = getString(R.string.stats_placeholder)
        val summary = if (partial) getString(R.string.stats_share_partial,
            state.flights.toString().takeIf { state.coverage.periodFlights } ?: dash,
            java.lang.String.format(java.util.Locale.getDefault(),"%.1f",state.distanceKm).takeIf { state.coverage.periodDistance } ?: dash,
            state.focusMinutes.toString().takeIf { state.coverage.periodFocus } ?: dash,
            state.airports.toString().takeIf { state.coverage.periodTravel } ?: dash,
            state.countries.toString().takeIf { state.coverage.periodTravel } ?: dash) else getString(
            R.string.stats_share_text,
            state.flights,
            state.distanceKm,
            state.focusMinutes,
            state.airports,
            state.countries,
        )
        val dated = state.dates?.let {
            getString(R.string.stats_share_period, StatsDateLabels.label(requireContext(), it, viewModel.zone, state.nowWallMs, false), summary)
        } ?: summary
        val text = if (state.selectedSceneKey.isEmpty()) dated else
            StatsSceneLabels(requireContext()).label(state.selectedSceneKey) + "\n" + dated
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        withAccount(state.account, { lastState === state }) { startActivity(Intent.createChooser(send, getString(R.string.stats_share_title))) }
    }

    private fun openDateRange() {
        val captured = lastState ?: return
        val account = captured.account?.takeIf { it.isCurrent() } ?: return
        val range = captured.dateRange ?: return
        val origin = view ?: return
        fun valid() = view === origin && account.isCurrent() &&
            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            viewModel.isCurrentQuery(account, captured.queryRevision)
        withAccount(account, ::valid) {
            closeDateRange()
            val dialog = StatsDateRangeDialog(requireContext(), range, viewModel.zone, viewModel.today(), ::valid) { first, last ->
                if (valid()) withAccount(account, ::valid) { viewModel.selectCustom(account, captured.queryRevision, first, last) }
            }
            dateDialog = dialog
            dialog.setOnDismissListener { if (dateDialog === dialog) dateDialog = null }
            dialog.show()
        }
    }

    private fun closeDateRange() {
        val old = dateDialog
        dateDialog = null
        old?.setOnDismissListener(null)
        old?.dismiss()
    }

    private fun requestCloudHistory() {
        if (!TokenStore.snapshot().token.isNullOrBlank())
            (context?.applicationContext as? com.projectfocus.service.focus.history.RecordCloudWorkerOwner)?.requestRecordCloudSync()
    }

    private fun retryData() {
        viewModel.retry()
        val account = lastState?.account ?: return
        if (account.isCurrent()) (context?.applicationContext as? com.projectfocus.service.focus.history.RecordCloudWorkerOwner)?.retryRecordCloudSync(account)
    }

    override fun onResume() { super.onResume(); viewModel.refreshNow(); requestCloudHistory() }
    override fun onStop() {
        closeDateRange()
        view?.findViewById<StatsHeatmapPanel>(R.id.statsHeatmapPanel)?.chart?.clearSelection()
        view?.findViewById<StatsHourPanel>(R.id.statsHourPanel)?.chart?.clearSelection()
        super.onStop()
    }
    override fun onDestroyView() {
        closeDateRange()
        view?.findViewById<StatsDateControls>(R.id.statsDateControls)?.apply { onPeriodRequested = null; onMoveRequested = null; onClearSceneRequested = null }
        view?.findViewById<StatsScenePanel>(R.id.statsScenePanel)?.apply { onSceneRequested = null; interactionAllowed = { false } }
        view?.findViewById<StatsTrendPanel>(R.id.statsTrendPanel)?.interactionAllowed={ false }
        view?.findViewById<StatsHeatmapPanel>(R.id.statsHeatmapPanel)?.release()
        view?.findViewById<StatsCareerPanel>(R.id.statsCareerPanel)?.release()
        view?.findViewById<StatsHourPanel>(R.id.statsHourPanel)?.release()
        view?.findViewById<StatsInsightsPanel>(R.id.statsInsightsPanel)?.release()
        view?.findViewById<StatsHighlightsPanel>(R.id.statsHighlightsPanel)?.release()
        lastState = null
        renderRevision++
        super.onDestroyView()
    }

    companion object { const val ARG_EMBEDDED = "embedded_statistics" }
}
