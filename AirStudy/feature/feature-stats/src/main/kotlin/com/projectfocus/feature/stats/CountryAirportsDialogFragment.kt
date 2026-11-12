package com.projectfocus.feature.stats

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.HistoryAccount
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    private val account: HistoryAccount?
        get() = arguments?.getString(RESULT_OWNER)?.let { HistoryAccount(it, requireArguments().getLong(RESULT_GENERATION, -1)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TokenStore.identities.collect {
                    if (account?.isCurrent() != true) dismissAllowingStateLoss()
                }
            }
        }
    }

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
                val current = account?.takeIf { it.isCurrent() } ?: return@setOnClickListener
                setFragmentResult(
                    RESULT_KEY,
                    Bundle().apply {
                        putString(RESULT_CODE, row.code)
                        putString(RESULT_OWNER, current.scope)
                        putLong(RESULT_GENERATION, current.generation)
                    },
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
        const val RESULT_OWNER = "ownerScope"
        const val RESULT_GENERATION = "accountGeneration"

        private const val ARG_COUNTRY = "country"
        private const val ARG_ROWS = "rows"

        fun newInstance(
            country: String,
            rows: ArrayList<CountryAirportRow>,
            account: HistoryAccount? = null,
        ): CountryAirportsDialogFragment = CountryAirportsDialogFragment().apply {
            val captured = account ?: TokenStore.snapshot().let { HistoryAccount(TokenStore.storageScope(it), it.generation) }
            arguments = Bundle().apply {
                putString(ARG_COUNTRY, country)
                putSerializable(ARG_ROWS, rows)
                putString(RESULT_OWNER, captured.scope)
                putLong(RESULT_GENERATION, captured.generation)
            }
        }
    }
}
