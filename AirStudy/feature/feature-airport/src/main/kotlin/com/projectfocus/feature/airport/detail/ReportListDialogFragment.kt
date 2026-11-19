package com.projectfocus.feature.airport.detail

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.projectfocus.feature.airport.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


class ReportListDialogFragment : DialogFragment() {

    private val airportCode: String
        get() = arguments?.getString(ARG_CODE).orEmpty()

    private val airportName: String
        get() = arguments?.getString(ARG_NAME).orEmpty()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val reports = AirportReportStore.list(requireContext(), airportCode)
        if (reports.isEmpty()) {
            return MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.airport_detail_report_list_title, airportName))
                .setMessage(R.string.airport_detail_report_list_empty)
                .setPositiveButton(android.R.string.ok, null)
                .create()
        }

        val labels = reports.map { report ->
            getString(
                R.string.airport_detail_report_list_item,
                shortReason(report.reason),
                formatTime(report.createdAt),
            )
        }.toTypedArray()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.airport_detail_report_list_title, airportName))
            .setItems(labels) { _, which -> confirmDelete(reports[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun confirmDelete(report: AirportReportStore.Report) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.airport_detail_report_delete_title)
            .setMessage(report.reason)
            .setPositiveButton(R.string.airport_detail_report_delete) { _, _ ->
                val deleted = AirportReportStore.delete(requireContext(), report.id)
                Toast.makeText(
                    requireContext(),
                    if (deleted) {
                        R.string.airport_detail_report_deleted
                    } else {
                        R.string.airport_detail_report_failed
                    },
                    Toast.LENGTH_SHORT,
                ).show()

                dismissNow()
                reopenList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reopenList() {
        val manager = parentFragmentManager
        if (manager.isStateSaved) return
        ReportListDialogFragment
            .newInstance(airportCode, airportName)
            .show(manager, TAG)
    }

    private fun formatTime(epochMillis: Long): String =
        DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    /** 报告原因最多 300 字，直接塞进 AlertDialog 列表会被截断/撑爆；列表只显示前 48 字。 */
    private fun shortReason(reason: String): String =
        if (reason.length <= MAX_REASON_CHARS) {
            reason
        } else {
            reason.take(MAX_REASON_CHARS) + "…"
        }

    companion object {
        private const val ARG_CODE = "airport_code"
        private const val MAX_REASON_CHARS = 48
        private const val ARG_NAME = "airport_name"
        private const val TAG = "report_list"

        fun newInstance(code: String, name: String): ReportListDialogFragment =
            ReportListDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CODE, code)
                    putString(ARG_NAME, name)
                }
            }
    }
}
