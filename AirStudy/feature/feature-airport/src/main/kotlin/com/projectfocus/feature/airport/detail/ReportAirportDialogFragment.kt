package com.projectfocus.feature.airport.detail

import android.app.Dialog
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.projectfocus.feature.airport.R


class ReportAirportDialogFragment : DialogFragment() {

    private val airportCode: String
        get() = arguments?.getString(ARG_CODE).orEmpty()

    private val airportName: String
        get() = arguments?.getString(ARG_NAME).orEmpty()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_report_airport, null)
        val input = view.findViewById<TextInputEditText>(R.id.report_reason_input)
        val counter = view.findViewById<TextView>(R.id.report_counter)
        val initial = getString(R.string.airport_detail_report_counter, 0)
        counter.text = initial
        input.doAfterTextChanged { text ->
            counter.text = getString(
                R.string.airport_detail_report_counter,
                text?.length ?: 0,
            )
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.airport_detail_report)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.airport_detail_report_submit, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reason = input.text?.toString()?.trim().orEmpty()
                if (reason.isBlank()) {
                    input.error = getString(R.string.airport_detail_report_reason_required)
                    return@setOnClickListener
                }
                if (saveLocally(reason)) {
                    Toast.makeText(
                        requireContext(),
                        R.string.airport_detail_report_saved,
                        Toast.LENGTH_SHORT,
                    ).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.airport_detail_report_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        return dialog
    }

    private fun saveLocally(reason: String): Boolean =
        AirportReportStore.save(requireContext(), airportCode, airportName, reason)

    companion object {
        private const val ARG_CODE = "airport_code"
        private const val ARG_NAME = "airport_name"

        fun newInstance(code: String, name: String): ReportAirportDialogFragment =
            ReportAirportDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CODE, code)
                    putString(ARG_NAME, name)
                }
            }
    }
}
