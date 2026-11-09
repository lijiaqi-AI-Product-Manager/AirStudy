package com.projectfocus.core.network.record

import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightRecordMode
import com.projectfocus.core.network.dto.FlightRecordMutationOptions
import com.projectfocus.core.network.dto.FlightRecordScenePatch

sealed interface RecordMutationOutcome {
    data class Acknowledged(val alreadyMissing: Boolean = false, val revision: Long? = null) : RecordMutationOutcome
    data class Rejected(val status: Int, val message: String = "", val retryable: Boolean = false) : RecordMutationOutcome
    data object InvalidResponse : RecordMutationOutcome
    data object NetworkFailure : RecordMutationOutcome
    data object Obsolete : RecordMutationOutcome
}

interface RecordMutationGateway {
    suspend fun delete(identity: TokenStore.Snapshot, mode: FlightRecordMode, recordId: String,
        options: FlightRecordMutationOptions = FlightRecordMutationOptions()): RecordMutationOutcome

    suspend fun changeScene(identity: TokenStore.Snapshot, mode: FlightRecordMode, recordId: String,
        scene: FlightRecordScenePatch,
        options: FlightRecordMutationOptions = FlightRecordMutationOptions()): RecordMutationOutcome
}
