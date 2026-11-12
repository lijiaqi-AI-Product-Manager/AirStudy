package com.projectfocus.feature.completion.pass

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.projectfocus.feature.completion.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale


class BoardingPassBinder(root: View) {

    private val context = root.context

    private val departureCode: TextView = root.findViewById(R.id.tv_departure)
    private val arrivalCode: TextView = root.findViewById(R.id.tv_destination)
    private val flightCode: TextView = root.findViewById(R.id.tv_flight_code)
    private val boardingTimeView: TextView = root.findViewById(R.id.tv_boarding_time)
    private val dateView: TextView = root.findViewById(R.id.tv_date)
    private val passengerView: TextView = root.findViewById(R.id.tv_passenger_name)
    private val seatView: TextView = root.findViewById(R.id.tv_seat)
    private val flightNumber: TextView = root.findViewById(R.id.tv_flight_number)

    private val stubSection: ViewGroup = root.findViewById(R.id.right_stub_section)
    private val divider: View = root.findViewById(R.id.divider_line)
    private val stubTexts: List<TextView> = List(5) { index ->
        stubSection.getChildAt(index) as TextView
    }

    /**
     * @param boardingTime 显式覆盖登机时间；null 时取 [BoardingPassData.boardingTime]。
     * @param passengerName 显式覆盖旅客姓名；null 时取数据对象，仍为空则回退标签。
     * @param stubVisible  是否显示右侧存根票型；默认 false。
     */
    fun bind(
        data: BoardingPassData,
        passengerName: String? = null,
        boardingTime: String? = null,
        stubVisible: Boolean = false,
    ) {
        val effectiveBoardingTime = boardingTime?.takeIf { it.isNotBlank() }
            ?: data.boardingTime.takeIf { it.isNotBlank() }
            ?: data.departureTime
        val effectivePassengerName = passengerName?.takeIf { it.isNotBlank() }
            ?: data.passengerName.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.passenger_label)

        departureCode.text = data.departureCode
        arrivalCode.text = data.arrivalCode
        flightCode.text = data.flightNo
        boardingTimeView.text = effectiveBoardingTime
        dateView.text = shortDate(data.date)
        passengerView.text = effectivePassengerName
        seatView.text = data.seat
        flightNumber.text = data.flightNo

        stubTexts[0].text = data.departureCode
        stubTexts[1].text = data.arrivalCode
        stubTexts[2].text = data.flightNo
        stubTexts[3].text = shortDate(data.date)
        stubTexts[4].text = effectiveBoardingTime

        stubSection.visibility = if (stubVisible) View.VISIBLE else View.GONE
        divider.visibility = if (stubVisible) View.VISIBLE else View.GONE
    }


    private fun shortDate(raw: String): String =
        runCatching { LocalDate.parse(raw).format(SHORT_DATE) }
            .getOrDefault(raw)
            .uppercase(Locale.ROOT)

    private companion object {
        private val SHORT_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("ddMMM", Locale.ENGLISH)
    }
}
