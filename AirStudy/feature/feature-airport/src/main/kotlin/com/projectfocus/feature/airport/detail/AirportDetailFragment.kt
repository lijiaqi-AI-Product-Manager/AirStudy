package com.projectfocus.feature.airport.detail

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.feature.airport.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


@AndroidEntryPoint
class AirportDetailFragment : Fragment(R.layout.fragment_airport_detail) {

    @Inject
    lateinit var airportDao: AirportDao

    private val code: String
        get() = arguments?.getString(ARG_CODE).orEmpty().trim().uppercase()


    private var reportShown: Boolean = false


    private val hintPrefs by lazy {
        requireContext().getSharedPreferences("airport_report_hint", Context.MODE_PRIVATE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val airportCode = code
        if (airportCode.isEmpty()) {
            renderNotFound(view, "")
            return
        }

        view.findViewById<View>(R.id.arrival_detail_back_button).setOnClickListener {
            findNavController().popBackStack()
        }
        view.findViewById<View>(R.id.arrival_detail_restart_button).setOnClickListener {
            findNavController().navigate(
                Uri.parse("projectfocus://home"),
                NavOptions.Builder()
                    .setPopUpTo(findNavController().graph.id, true)
                    .build(),
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val airport = withContext(Dispatchers.IO) { airportDao.findByCode(airportCode) }
            if (airport == null) {
                renderNotFound(view, airportCode)
                return@launch
            }
            bind(view, airport)
            maybeShowReportHint()
            view.findViewById<View>(R.id.arrival_detail_report_button).apply {
                setOnClickListener { showReportDialog(airport) }
                setOnLongClickListener {
                    ReportListDialogFragment
                        .newInstance(airport.iataCode, airport.nameZh ?: airport.name)
                        .show(childFragmentManager, "report_list")
                    true
                }
            }
            if (!reportShown &&
                savedInstanceState == null &&
                arguments?.getBoolean(ARG_REPORT, false) == true
            ) {
                reportShown = true
                showReportDialog(airport)
            }
        }
    }

    private fun bind(view: View, airport: AirportEntity) {
        val displayName = airport.nameZh?.takeIf { it.isNotBlank() } ?: airport.name
        val displayCity = airport.cityZh?.takeIf { it.isNotBlank() } ?: airport.city
        view.findViewById<TextView>(R.id.arrival_detail_airport_name).text = displayName
        view.findViewById<TextView>(R.id.arrival_detail_airport_code).text = airport.iataCode
        view.findViewById<TextView>(R.id.arrival_detail_flag).text = listOf(
            airport.country,
            airport.continent,
        ).filter { it.isNotBlank() }.joinToString(" 路 ")
        view.findViewById<TextView>(R.id.arrival_detail_description).text = getString(
            R.string.airport_detail_description,
            displayName,
            airport.iataCode,
            displayCity.ifBlank { airport.country },
            airport.elevation,
            typeLabel(airport.type),
            airport.latitude,
            airport.longitude,
            airport.country,
            airport.continent,
        )
    }

    private fun renderNotFound(view: View, code: String) {
        view.findViewById<TextView>(R.id.arrival_detail_airport_name).text =
            getString(R.string.airport_detail_not_found)
        view.findViewById<TextView>(R.id.arrival_detail_airport_code).text = code
        view.findViewById<TextView>(R.id.arrival_detail_description).text =
            getString(R.string.airport_detail_not_found)
        view.findViewById<View>(R.id.arrival_detail_report_button).setOnClickListener {
            Toast.makeText(
                requireContext(),
                R.string.airport_detail_not_found,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun maybeShowReportHint() {
        if (hintPrefs.getBoolean(HINT_SEEN, false)) return
        hintPrefs.edit().putBoolean(HINT_SEEN, true).apply()
        Toast.makeText(
            requireContext(),
            R.string.airport_detail_report_long_press_hint,
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun showReportDialog(airport: AirportEntity) {
        val name = airport.nameZh?.takeIf { it.isNotBlank() } ?: airport.name
        ReportAirportDialogFragment
            .newInstance(airport.iataCode, name)
            .show(childFragmentManager, "report_airport")
    }

    private fun typeLabel(type: String): String = when (type) {
        "large_airport", "large" -> "大型机场"
        "medium_airport", "medium" -> "中型机场"
        "small_airport", "small" -> "小型机场"
        "seaplane_base", "seaplane" -> "水上飞机基地"
        "heliport" -> "直升机场"
        "closed" -> "已关闭"
        else -> type
    }

    companion object {
        private const val HINT_SEEN = "long_press_hint_seen"
        const val ARG_CODE: String = "code"
        const val ARG_REPORT: String = "report"

        fun newInstance(code: String, report: Boolean = false): AirportDetailFragment =
            AirportDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CODE, code)
                    putBoolean(ARG_REPORT, report)
                }
            }
    }
}
