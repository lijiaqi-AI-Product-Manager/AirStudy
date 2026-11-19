package com.projectfocus.service.focus.freeflight.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.projectfocus.service.focus.freeflight.FlightRuntimeLeaseGuard
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

sealed interface FreeFlightPreparationDispatch {
    /** Actual request dispatched or this exact preparation is already foreground; not flight readiness. */
    data class Dispatched(val key: FreeFlightPreparationRuntimeKey) : FreeFlightPreparationDispatch
    data class Rejected(val reason: String) : FreeFlightPreparationDispatch
    data class Failed(val error: Throwable) : FreeFlightPreparationDispatch
}

@Singleton
class FreeFlightPreparationServiceOperations @Inject constructor(
    @ApplicationContext private val context: Context,
    private val owners: SharedFlightRuntimeOwnership,
    private val preparation: FreeFlightPreparationRuntime,
    private val runtime: FreeFlightRuntimeRegistry,
) {
    val observation get() = preparation.observation
    suspend fun start(preparationId: String, sessionId: String, owner: FreeFlightOwnerToken): FreeFlightPreparationDispatch = try {
        owners.withPreparationOwner(sessionId, owner) {
            FlightRuntimeLeaseGuard.current().check()
            if (runtime.target.value?.key?.let { it.owner == owner && it.sessionId == sessionId } == true)
                return@withPreparationOwner FreeFlightPreparationDispatch.Rejected("本次准备已经交给飞行运行")
            if (FreeFlightKeepAliveNotification.access(context).requirement != FreeFlightNotificationRequirement.READY)
                return@withPreparationOwner FreeFlightPreparationDispatch.Rejected("请先开启通知权限和后台保活通知")
            FreeFlightKeepAliveNotification.ensureChannel(context)
            val key = preparation.stage(preparationId, sessionId, owner)
            if (preparation.observation.value.let { it.key == key && it.state == FreeFlightPreparationDriverState.FOREGROUND && it.foregroundConfirmed })
                return@withPreparationOwner FreeFlightPreparationDispatch.Dispatched(key)
            try {
                ContextCompat.startForegroundService(context, Intent(context, InjectedFreeFlightRuntimeService::class.java)
                    .setAction(FreeFlightRuntimeService.PREPARE)
                    .putExtra(FreeFlightRuntimeService.SESSION_ID, key.sessionId)
                    .putExtra(FreeFlightRuntimeService.PREPARATION_DRIVER_ID, key.driverId))
                FreeFlightPreparationDispatch.Dispatched(key)
            } catch (error: Exception) {
                preparation.failure(key, error)
                FreeFlightPreparationDispatch.Failed(error)
            }
        } ?: FreeFlightPreparationDispatch.Rejected("当前准备所属账号或会话已变化")
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { FreeFlightPreparationDispatch.Failed(error) }

    fun stop(preparationId: String, sessionId: String, owner: FreeFlightOwnerToken) =
        preparation.requestStop(preparationId, sessionId, owner)
}
