package com.projectfocus.feature.stats

import java.io.Serializable

/**
 * 国家下钻的一行机场。
 *
 * 只从 `airports` + `flight_history` 派生；不伪造“未访问”状态。
 */
data class CountryAirportRow(
    val code: String,
    val name: String,
    val city: String,
    val visited: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
