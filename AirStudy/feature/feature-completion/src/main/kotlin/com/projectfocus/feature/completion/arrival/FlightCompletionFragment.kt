package com.projectfocus.feature.completion.arrival

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.feature.completion.R
import com.projectfocus.service.focus.session.SessionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject


@AndroidEntryPoint
class FlightCompletionFragment : Fragment(R.layout.fragment_flight_completion) {

    @Inject
    lateinit var flightHistoryDao: FlightHistoryDao

    /**
     * R8：到站页的“完成飞行”必须调用 settle()，把 Arrived 写成 COMPLETED。
     * 这一步是“到站 → 历史记录 → 首页统计”的唯一收口。
     */
    @Inject
    lateinit var sessionRepository: SessionRepository

    private lateinit var binder: FlightCompletionBinder
    private var loadedRecord: FlightHistoryEntity? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binder = FlightCompletionBinder(view)
        binder.onComplete = {

            //   settle() 内部：写 flight_history(COMPLETED) → controller.settle()
            //   → 清 DataStore → 推小组件。写库失败会保留 Arrived 并返回 null。
            binder.setCompleteEnabled(false)
            viewLifecycleOwner.lifecycleScope.launch {
                val settled = runCatching { sessionRepository.settle() }.getOrNull()
                if (settled == null) {
                    binder.setCompleteEnabled(true)
                    Toast.makeText(
                        requireContext(),
                        R.string.flight_completion_settle_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                Toast.makeText(
                    requireContext(),
                    R.string.flight_completion_settled,
                    Toast.LENGTH_SHORT,
                ).show()

                //   否则返回键还能回到已经结算过的落地页（记录已 COMPLETED，
                //   再点一次“完成飞行”只会报“结算失败”）。
                //   走 projectfocus://home 深链，不引用 app 模块的 R.id
                //   （feature-completion 编译期看不到 app 的 R）。
                val nav = findNavController()
                nav.navigate(
                    Uri.parse("projectfocus://home"),
                    NavOptions.Builder()
                        .setPopUpTo(nav.graph.id, true)
                        .build(),
                )
            }
        }
        binder.onShare = { loadedRecord?.let(::shareRecord) }
        binder.onAirport = {
            loadedRecord?.destinationAirportCode
                ?.takeIf { it.isNotBlank() }
                ?.let(::navigateToAirport)
        }
        binder.onReport = {


            val code = loadedRecord?.destinationAirportCode
                ?.takeIf { it.isNotBlank() }
            if (code == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.arrival_report_coming_soon,
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                findNavController().navigate(
                    Uri.parse("projectfocus://airport/${Uri.encode(code)}?report=true"),
                )
            }
        }

        val recordId = arguments?.getString(ARG_RECORD_ID).orEmpty()
        if (recordId.isEmpty()) {
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val zone = ZoneId.systemDefault()
            val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val tomorrowStart = todayStart + DAY_MILLIS
            val (record, summary) = withContext(Dispatchers.IO) {
                flightHistoryDao.findById(recordId) to
                    flightHistoryDao.todaySummary(todayStart, tomorrowStart)
            }
            if (record == null) {
                return@launch
            }
            loadedRecord = record
            binder.bind(
                record = record,
                todayDistanceKm = summary?.distanceKm ?: 0.0,
                todayFocusMinutes = summary?.focusMinutes ?: 0,
            )
        }
    }

    private fun navigateToAirport(code: String) {
        findNavController().navigate(
            Uri.parse("projectfocus://airport/${Uri.encode(code)}"),
        )
    }


    private fun shareRecord(record: FlightHistoryEntity) {
        val from = record.departureCity.ifBlank { record.departureAirportCode }
        val to = record.destinationCity.ifBlank { record.destinationAirportCode }
        val distance = String.format(Locale.US, "%.1f", record.distanceKm)
        val quote = record.quote?.trim().orEmpty()
        val author = record.quoteAuthor?.trim().orEmpty()
        val quoteLine = when {
            quote.isEmpty() -> ""
            author.isEmpty() -> "\n“$quote”"
            else -> "\n“$quote” — $author"
        }
        val text = "我刚完成一次专注飞行：$from → $to，" +
            "${distance} km，${record.actualFocusMinutes} 分钟。$quoteLine"

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.flight_completion_share_subject))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(
            Intent.createChooser(send, getString(R.string.flight_completion_share_chooser)),
        )
    }

    companion object {
        const val ARG_RECORD_ID: String = "recordId"
        private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

        fun newInstance(recordId: String): FlightCompletionFragment =
            FlightCompletionFragment().apply {
                arguments = Bundle().apply { putString(ARG_RECORD_ID, recordId) }
            }
    }
}
