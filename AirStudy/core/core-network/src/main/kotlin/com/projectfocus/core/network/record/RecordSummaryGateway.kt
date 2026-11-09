package com.projectfocus.core.network.record

import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightRecordSummary
import com.projectfocus.core.network.dto.FlightRecordSummaryQuery

data class RecordSummaryPage(val records: List<FlightRecordSummary>, val page: Int,
    val totalPages: Int, val totalCount: Int?)

sealed interface RecordSummaryReadResult {
    data class Loaded(val page: RecordSummaryPage) : RecordSummaryReadResult
    data class Failure(val reason: RecordMutationOutcome) : RecordSummaryReadResult
}

interface RecordSummaryGateway {
    suspend fun page(identity: TokenStore.Snapshot, query: FlightRecordSummaryQuery): RecordSummaryReadResult
}
