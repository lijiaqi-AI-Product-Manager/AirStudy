package com.projectfocus.feature.focus.selection

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.projectfocus.feature.focus.R
import com.projectfocus.core.ui.nav.OrdinaryFlightPreparationHost
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class FlightModeSelectionFragment : Fragment(R.layout.fragment_flight_mode_selection) {

    private val origin: String
        get() = arguments?.getString(ARG_ORIGIN).orEmpty()

    private val destination: String
        get() = arguments?.getString(ARG_DESTINATION).orEmpty()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val pager = view.findViewById<ViewPager2>(R.id.flight_mode_pager)
        val back = view.findViewById<ImageButton>(R.id.flight_mode_back)
        val indicators = listOf(
            view.findViewById<View>(R.id.flight_mode_indicator_0),
            view.findViewById<View>(R.id.flight_mode_indicator_1),
            view.findViewById<View>(R.id.flight_mode_indicator_2),
        )

        val pages = listOf(
            FlightModePage(
                englishRes = R.string.flight_mode_countdown_english,
                titleRes = R.string.flight_mode_countdown,
                betaRes = null,
                optionTitleRes = R.string.flight_mode_option_countdown,
                optionSubtitleRes = R.string.flight_mode_option_countdown_subtitle,
                ctaRes = R.string.flight_mode_select_countdown,
                mode = FlightMode.COUNTDOWN,
            ),
            FlightModePage(
                englishRes = R.string.flight_mode_count_up_english,
                titleRes = R.string.flight_mode_count_up,
                betaRes = null,
                optionTitleRes = R.string.flight_mode_option_countup_beta,
                optionSubtitleRes = R.string.flight_mode_option_countup_subtitle,
                ctaRes = R.string.flight_mode_select_count_up,
                mode = FlightMode.COUNTUP,
            ),
            FlightModePage(
                englishRes = R.string.flight_mode_group_english,
                titleRes = R.string.flight_mode_group,
                betaRes = R.string.flight_mode_group_beta,
                optionTitleRes = R.string.flight_mode_group,
                optionSubtitleRes = null,
                ctaRes = R.string.flight_mode_select_group,
                mode = FlightMode.GROUP,
            ),
        )

        pager.adapter = FlightModePageAdapter(pages) { page -> onPageSelected(page) }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                indicators.forEachIndexed { index, indicator ->
                    indicator.setBackgroundResource(
                        if (index == position) {
                            R.drawable.bg_flight_mode_indicator_active
                        } else {
                            R.drawable.bg_flight_mode_indicator_inactive
                        },
                    )
                }
            }
        })

        back.setOnClickListener { findNavController().navigateUp() }
    }

    private fun onPageSelected(page: FlightModePage) {
        when (page.mode) {
            FlightMode.COUNTDOWN -> startCountdown()
            FlightMode.COUNTUP -> {
                if ((activity as? com.projectfocus.core.ui.nav.FreeFlightPreparationHost)?.openFreeFlightPreparation() != true) {
                    Toast.makeText(requireContext(), getString(R.string.flight_mode_not_available, getString(page.titleRes)), Toast.LENGTH_SHORT).show()
                }
            }
            FlightMode.GROUP -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.flight_mode_not_available, getString(page.titleRes)),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun startCountdown() {
        if ((activity as? OrdinaryFlightPreparationHost)?.openOrdinaryFlightPreparation() != true) {
            Toast.makeText(requireContext(), getString(R.string.flight_mode_not_available,
                getString(R.string.flight_mode_countdown)), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ARG_MINUTES = "minutes"
        const val ARG_ORIGIN = "origin"
        const val ARG_DESTINATION = "destination"
    }
}
