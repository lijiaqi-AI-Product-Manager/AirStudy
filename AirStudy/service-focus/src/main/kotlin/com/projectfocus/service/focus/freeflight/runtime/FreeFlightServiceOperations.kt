package com.projectfocus.service.focus.freeflight.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.service.focus.freeflight.FlightRuntimeLeaseGuard
import com.projectfocus.service.focus.freeflight.FreeFlightLeaseEffectOperations
import com.projectfocus.service.focus.freeflight.FreeFlightSessionRepository
import com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
import javax.inject.Provider

/** Actual IO command + dedicated-service dispatch. Root composes/binds this with real ports. */
class FreeFlightServiceOperations(
    private val context: Context,
    private val settings: SettingsRepository,
    private val owners: SharedFlightRuntimeOwnership,
    private val registry: FreeFlightRuntimeRegistry,
    private val repository: Provider<FreeFlightSessionRepository>,
    private val arrival: FreeFlightArrivalCoordinator,
) : FreeFlightLeaseEffectOperations {
    override suspend fun synchronize(archive: FreeFlightArchive, lease: FlightRuntimeLeaseGuard) {
        lease.check()
        val owner = requireNotNull(owners.retainedOwner(archive.sessionId)) { "The actual runtime owner was not registered" }
        require(owner.ownerScope == archive.ownerScope)
        val target = registry.stage(archive, owner, repository.get(), arrival)
        val ongoing = archive.status == FreeFlightStatus.ONGOING
        settings.writeAll(listOf(
            PrefKey.ActiveSessionCategoryId to if (ongoing) archive.shieldingCategoryId ?: -2L else -1L,
            PrefKey.SessionPaused to (ongoing && archive.clock.pause != null),
        ))
        lease.check()
        if (if (ongoing) registry.isRunning(target.key) else registry.isStopped(target.key)) return
        try {
            ContextCompat.startForegroundService(context.applicationContext, Intent(context, InjectedFreeFlightRuntimeService::class.java)
                .putExtra(FreeFlightRuntimeService.SESSION_ID, target.key.sessionId)
                .putExtra(FreeFlightRuntimeService.DRIVER_ID, target.key.driverId))
        } catch (error: Exception) { registry.failure(target.key, error); throw error }
        // Never wait for Android Main/service callbacks while holding account/Core leases.
        // Actual callback confirmation triggers the independent application retry bridge.
        if (!(if (ongoing) registry.isRunning(target.key) else registry.isStopped(target.key)))
            throw FreeFlightRuntimePending(if (ongoing) "Foreground service start awaits actual confirmation" else "Service stop awaits actual confirmation")
    }
}
