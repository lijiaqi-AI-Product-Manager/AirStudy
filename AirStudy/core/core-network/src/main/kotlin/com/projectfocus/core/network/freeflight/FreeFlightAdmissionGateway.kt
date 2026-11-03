package com.projectfocus.core.network.freeflight

import com.projectfocus.core.network.dto.*

/** Own durable admission protocol, separate from cached plan entitlement. */
interface FreeFlightAdmissionGateway {
    suspend fun prepare(request: FreeFlightDeviceRequest, bearer: String?): FreeFlightPrepareReply
    /** App-lifetime callback. The eight-second Pending timer must not cancel this call. */
    fun submit(request: FreeFlightFormalRequest, bearer: String?, callback: (Result<FreeFlightFormalReply>) -> Unit)
    suspend fun operation(request: FreeFlightOperationRequest, bearer: String?): FreeFlightOperationReply
    suspend fun localStartFailed(request: FreeFlightLocalFailureRequest, bearer: String?): FreeFlightLocalFailureReply
}
