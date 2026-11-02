package com.projectfocus.core.network.plan

import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.CancelBatchDeleteFlightPlanRequest
import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import com.projectfocus.core.network.dto.UpdateFlightPlanLegStatusRequest

interface FlightPlanGateway {
    suspend fun get(request: GetFlightPlanRequest): DailyFlightPlanSnapshot?
    suspend fun upsert(request: UpsertFlightPlanRequest): DailyFlightPlanSnapshot?
    suspend fun history(request: FlightPlanHistoryRequest): List<DailyFlightPlanSnapshot>
    suspend fun deleteCanceled(request: CancelBatchDeleteFlightPlanRequest): DailyFlightPlanSnapshot? =
        throw UnsupportedOperationException("Canceled-plan transport is unavailable")
    suspend fun updateLegStatus(request: UpdateFlightPlanLegStatusRequest): DailyFlightPlanSnapshot? =
        throw UnsupportedOperationException("Planned-leg status transport is unavailable")
}
