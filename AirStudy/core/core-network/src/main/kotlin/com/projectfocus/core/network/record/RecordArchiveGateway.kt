package com.projectfocus.core.network.record

import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*

sealed interface RecordArchiveReadResult {
    data class Loaded(val page: CloudFlightRecordPage) : RecordArchiveReadResult
    data class Failure(val reason: RecordMutationOutcome) : RecordArchiveReadResult
}

interface RecordArchiveGateway {
    suspend fun upload(identity: TokenStore.Snapshot, record: FlightRecordArchiveUpload,
                       options: FlightRecordMutationOptions = FlightRecordMutationOptions()): RecordMutationOutcome
    suspend fun changes(identity: TokenStore.Snapshot, after: Long, limit: Int = 50): RecordArchiveReadResult
}
