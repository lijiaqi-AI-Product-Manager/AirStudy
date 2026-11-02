package com.projectfocus.feature.stats

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR

/**
 * 统计页国家行下钻：该国机场列表（已访问/未访问）。
 *
 * 数据由 StatsFragment 先查好再传进来，Dialog 不碰数据库。
 */
class CountryAirportsDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_country_airports, null)
        val country = arguments?.getString(ARG_COUNTRY).orEmpty()
        @Suppress("DEPRECATION")
        val rows = arguments?.getSerializable(ARG_ROWS) as? ArrayList<CountryAirportRow>
            ?: arrayListOf()

        view.findViewById<TextView>(R.id.countryAirportsTitle).text =
            getString(R.string.stats_country_airports_title, country)
        view.findViewById<TextView>(R.id.countryAirportsSubtitle).text = getString(
            R.string.stats_country_airports_subtitle,
            rows.count { it.visited },
            rows.size,
        )

        val list = view.findViewById<LinearLayout>(R.id.countryAirportsList)
        val empty = view.findViewById<View>(R.id.countryAirportsEmpty)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        rows.forEach { row ->
            val item = layoutInflater.inflate(R.layout.item_country_airport, list, false)
            item.findViewById<TextView>(R.id.countryAirportCode).text = row.code
            item.findViewById<TextView>(R.id.countryAirportName).text = row.name
            item.findViewById<TextView>(R.id.countryAirportCity).text = row.city
            item.findViewById<TextView>(R.id.countryAirportStatus).apply {
                text = getString(
                    if (row.visited) {
                        R.string.stats_country_airport_visited
                    } else {
                        R.string.stats_country_airport_locked
                    },
                )
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        // colorPrimary 由 appcompat 声明；非传递 R 下 Material 的 R 里没有它。
                        if (row.visited) AppCompatR.attr.colorPrimary else MaterialR.attr.colorOnSurfaceVariant,
                    ),
                )
            }
            item.setOnClickListener {
                setFragmentResult(
                    RESULT_KEY,
                    Bundle().apply { putString(RESULT_CODE, row.code) },
                )
                dismiss()
            }
            list.addView(item)
        }

        view.findViewById<View>(R.id.countryAirportsClose).setOnClickListener { dismiss() }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
    }

    override fun onStart() {
        super.onStart()
        val width = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        dialog?.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    companion object {
        const val RESULT_KEY = "country_airport_open"
        const val RESULT_CODE = "code"

        private const val ARG_COUNTRY = "country"
        private const val ARG_ROWS = "rows"

        fun newInstance(
            country: String,
            rows: ArrayList<CountryAirportRow>,
        ): CountryAirportsDialogFragment = CountryAirportsDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_COUNTRY, country)
                putSerializable(ARG_ROWS, rows)
            }
        }
    }
}
