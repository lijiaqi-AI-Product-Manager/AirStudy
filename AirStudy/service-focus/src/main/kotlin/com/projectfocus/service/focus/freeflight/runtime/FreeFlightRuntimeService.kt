package com.projectfocus.service.focus.freeflight.runtime

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.projectfocus.core.model.flight.freeflight.FreeFlightEngine
import com.projectfocus.core.model.flight.freeflight.FreeFlightPhase
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.service.focus.freeflight.FlightRuntimeLeaseGuard
import com.projectfocus.service.focus.freeflight.FreeFlightCommandResult
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Dedicated mode1 driver. It never starts/ticks/stops the Core countdown service. */
open class FreeFlightRuntimeService : Service() {
    @Inject lateinit var registry: FreeFlightRuntimeRegistry
    @Inject lateinit var owners: FreeFlightRuntimeOwners
    @Inject lateinit var shielding: FreeFlightShielding
    @Inject lateinit var preparation: FreeFlightPreparationRuntime
    private val serviceLifetime = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceLifetime + Dispatchers.IO)
    private val lock = Any()
    private var current: FreeFlightRuntimeKey? = null
    private var currentStartId = 0
    private var stopReason = "Service destroyed before a terminal confirmation"
    private val dispatching = AtomicBoolean(false)
    private val promoting = AtomicBoolean(false)
    private var ticker: Job? = null
    private var controls: Job? = null
    private var invalidations: Job? = null
    private var arrival: Job? = null
    private var cruiseReturn:Job? = null
    private var returnAttempt:Pair<FreeFlightRuntimeKey,Long>? = null
    private var shieldJobs: List<Job> = emptyList()
    private var arrivalAttempt: Pair<FreeFlightRuntimeKey, Long>? = null
    private var currentPreparation: FreeFlightPreparationRuntimeKey? = null
    private var preparationPromotion: Job? = null
    private var preparationChildren: List<Job> = emptyList()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!serviceLifetime.isActive) { stopSelfResult(startId);return START_NOT_STICKY }
        if (intent?.action == PREPARE) return acceptPreparation(intent, startId)
        val target = registry.resolve(intent?.getStringExtra(SESSION_ID), intent?.getStringExtra(DRIVER_ID))
        if (target == null) {
            stopUnbound(startId)
            return START_NOT_STICKY
        }
        val allowed = synchronized(lock) {
            (current == null || current == target.key || !(dispatching.get() || promoting.get())) &&
                currentPreparation?.let { it.owner == target.key.owner && it.sessionId == target.key.sessionId } != false
        }
        if (!allowed) return START_NOT_STICKY
        if (!registry.attachDriver(target.key, serviceLifetime)) {
            stopUnbound(startId)
            return START_NOT_STICKY
        }
        val existing = synchronized(lock) {
            if (current != null && current != target.key && (dispatching.get() || promoting.get())) return START_NOT_STICKY
            if (current != target.key) { arrivalAttempt = null; stopReason = "Service destroyed before a terminal confirmation" }
            current = target.key; currentStartId = startId
            dispatching.get()
        }
        if (intent?.action in setOf(PAUSE, RESUME) && existing) {
            serviceScope.launch {
                val result = if (intent?.action == PAUSE) target.repository.pause(target.key.owner) else target.repository.resume(target.key.owner)
                if (result is FreeFlightCommandResult.Rejected) registry.arrivalFailure(target.key, IllegalStateException(result.reason))
            }
        }
        if (!existing && promoting.compareAndSet(false, true)) serviceScope.launch {
            try {
                if (handOffPreparation(target)) promoteAndInstall(target)
                else { registry.failure(target.key, IllegalStateException("The actual preparation could not hand off"));stopMatched(target.key, "Preparation handoff failed") }
            } finally { promoting.set(false) }
        }
        return START_NOT_STICKY // Cold restart requires Root's real account/host/committed archive restore.
    }

    private fun stopUnbound(startId: Int) {
        if (synchronized(lock) { current == null && currentPreparation == null }) stopSelfResult(startId)
    }

    private fun acceptPreparation(intent: Intent, startId: Int): Int {
        val key = preparation.resolve(intent.getStringExtra(SESSION_ID), intent.getStringExtra(PREPARATION_DRIVER_ID))
        if (key == null) { stopUnbound(startId);return START_NOT_STICKY }
        synchronized(lock) {
            if (current != null || (currentPreparation != null && currentPreparation != key)) return START_NOT_STICKY
            if (!preparation.attach(key, serviceLifetime)) { stopUnbound(startId);return START_NOT_STICKY }
            currentPreparation = key;currentStartId = startId
            if (preparationPromotion?.isActive == true || preparation.observation.value.let {
                    it.key == key && it.state == FreeFlightPreparationDriverState.FOREGROUND && it.foregroundConfirmed }) return START_NOT_STICKY
            preparationPromotion = serviceScope.launch { promotePreparation(key) }
        }
        return START_NOT_STICKY
    }

    private suspend fun promotePreparation(key: FreeFlightPreparationRuntimeKey) {
        try {
            val promoted = owners.withPreparationOwner(key.sessionId, key.owner) {
                FlightRuntimeLeaseGuard.current().check()
                synchronized(lock) {
                    if (current != null || currentPreparation != key || !serviceLifetime.isActive || !preparation.canPromote(key)) false
                    else {
                        val notification = FreeFlightKeepAliveNotification.build(this)
                        if (Build.VERSION.SDK_INT >= 34) startForeground(FreeFlightRuntimeNotification.ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                        else startForeground(FreeFlightRuntimeNotification.ID, notification)
                        true
                    }
                }
            } ?: false
            if (!promoted) {
                if (synchronized(lock) { current == null }) {
                    preparation.failure(key, IllegalStateException("The actual preparation owner is obsolete"))
                    stopPreparation(key)
                }
                return
            }
            preparation.foreground(key)
            preparationChildren = listOf(
                serviceScope.launch { preparation.observation.collect { observed ->
                    if (observed.key == key && observed.state in setOf(FreeFlightPreparationDriverState.STOP_REQUESTED,
                            FreeFlightPreparationDriverState.FAILED)) stopPreparation(key)
                } },
                serviceScope.launch { owners.currentHosts.collect { observed ->
                    // A delayed observation is only a trigger; check the actual host lease again.
                    if (observed != key.owner && owners.withPreparationOwner(key.sessionId, key.owner) { true } == null)
                        stopPreparation(key)
                } },
            )
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { preparation.failure(key, error);stopPreparation(key) }
    }

    private suspend fun handOffPreparation(target: FreeFlightRuntimeTarget): Boolean {
        val key = synchronized(lock) { currentPreparation } ?: return true
        if (key.sessionId != target.key.sessionId || key.owner != target.key.owner) return false
        // No repository/account/Core lease is held while joining the actual old phase's jobs.
        preparationPromotion?.cancelAndJoin()
        preparationChildren.forEach { it.cancelAndJoin() }
        if (!preparation.handOff(key, serviceLifetime, target.key)) return false
        synchronized(lock) { if (currentPreparation == key) currentPreparation = null }
        return true
    }

    private fun stopPreparation(key: FreeFlightPreparationRuntimeKey) = synchronized(lock) {
        // A late preparation callback cannot stop the real flight after handoff has begun.
        if (current != null || currentPreparation != key) return@synchronized
        serviceLifetime.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(currentStartId)
    }

    private suspend fun promoteAndInstall(target: FreeFlightRuntimeTarget) {
        try {
            val started = owners.withCurrentOwner(target.key.sessionId, target.key.owner) {
                FlightRuntimeLeaseGuard.current().check()
                val notification = FreeFlightRuntimeNotification.build(this, target)
                if (Build.VERSION.SDK_INT >= 34) startForeground(FreeFlightRuntimeNotification.ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(FreeFlightRuntimeNotification.ID, notification)
                dispatching.set(true)
                true
            } ?: false
            if (!started) { registry.failure(target.key, IllegalStateException("The actual service owner is obsolete")); stopMatched(target.key, "Owner obsolete before foreground confirmation"); return }
            val latest = registry.resolve(target.key.sessionId, target.key.driverId)
            if (latest == null || latest.archive.status != FreeFlightStatus.ONGOING) {
                stopMatched(target.key, "Actual terminal archive")
                return
            }
            installDrivers(latest)
            registry.driverReady(target.key) // Actual foreground promotion + independent timer installed.
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { registry.failure(target.key, error); stopMatched(target.key, "Foreground promotion failed") }
    }

    private fun installDrivers(initial: FreeFlightRuntimeTarget) {
        controls = serviceScope.launch {
            registry.target.collect { target ->
                if (target?.key != initial.key) return@collect
                if (target.archive.status != FreeFlightStatus.ONGOING) { stopMatched(initial.key, "Actual terminal archive"); return@collect }
                try {
                    owners.withCurrentOwner(target.key.sessionId, target.key.owner) {
                        FlightRuntimeLeaseGuard.current().check()
                        getSystemService(android.app.NotificationManager::class.java)?.notify(FreeFlightRuntimeNotification.ID,
                            FreeFlightRuntimeNotification.build(this@FreeFlightRuntimeService, target))
                        true
                    } ?: stopMatched(initial.key, "Actual owner invalidated")
                    scheduleArrival(target)
                    scheduleCruiseReturn(target)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { registry.arrivalFailure(initial.key, error) }
            }
        }
        invalidations = serviceScope.launch {
            owners.invalidations.collect { if (it == initial.key.owner) stopMatched(initial.key, "Actual account/host invalidation; archive retained") }
        }
        shieldJobs = shielding.start(this, serviceScope, initial, registry)
        ticker = serviceScope.launch {
            while (isActive && dispatching.get()) {
                val target = registry.resolve(initial.key.sessionId, initial.key.driverId) ?: break
                if (target.archive.status != FreeFlightStatus.ONGOING) { stopMatched(initial.key, "Actual terminal archive"); break }
                when (val result = target.repository.tick(target.key.owner)) {
                    is FreeFlightCommandResult.Committed -> { scheduleArrival(target);scheduleCruiseReturn(target) }
                    is FreeFlightCommandResult.Rejected -> { registry.arrivalFailure(initial.key, IllegalStateException(result.reason)); stopMatched(initial.key, "Repository rejected obsolete driver"); break }
                    is FreeFlightCommandResult.Failed -> registry.arrivalFailure(initial.key, result.error)
                }
                delay(1_000L) // Own durable sample cadence, not the original 16ms map paint callback.
            }
        }
        scheduleArrival(initial)
        scheduleCruiseReturn(initial)
    }

    private fun scheduleCruiseReturn(target:FreeFlightRuntimeTarget) {
        val archive=target.archive
        if(archive.sessionId!=target.key.sessionId || !archive.cruiseReturnPending ||
            archive.clock.pause==com.projectfocus.core.model.flight.freeflight.FreeFlightPause.RECOVERY_REQUIRES_RESUME) {
            synchronized(lock) { cruiseReturn?.cancel();cruiseReturn=null;returnAttempt=null }
            return
        }
        val attempt=target.key to archive.landingSelectionGeneration
        synchronized(lock) {
            if(returnAttempt==attempt || current!=target.key || !dispatching.get()) return
            returnAttempt=attempt
            cruiseReturn=serviceScope.launch {
                try {
                    val permit=target.arrival.awaitActualReturnCallback(target.key,archive,target.repository)
                    when(val result=target.repository.completeReturnToCruise(target.key.owner,permit)) {
                        is FreeFlightCommandResult.Failed -> registry.arrivalFailure(target.key,result.error)
                        is FreeFlightCommandResult.Rejected -> registry.arrivalFailure(target.key,IllegalStateException(result.reason))
                        is FreeFlightCommandResult.Committed -> Unit
                    }
                } catch(cancel:CancellationException) { throw cancel }
                catch(error:Exception) { registry.arrivalFailure(target.key,error) }
                finally { synchronized(lock) { if(returnAttempt==attempt) { returnAttempt=null;cruiseReturn=null } } }
            }
        }
    }

    private fun scheduleArrival(target: FreeFlightRuntimeTarget) {
        val noMap = target.archive.phase == FreeFlightPhase.NO_MAP_ARRIVAL
        if (!noMap && !FreeFlightEngine().landingReached(target.archive)) return
        val attempt = target.key to registry.arrivalRetryGeneration()
        synchronized(lock) {
            if (arrivalAttempt == attempt || current != target.key || !dispatching.get()) return
            arrivalAttempt = attempt
            arrival = serviceScope.launch {
                try {
                    // These callbacks run OUTSIDE repository/account/Core leases.
                    val permit = target.arrival.awaitActualResultCallback(target.key, target.archive, target.repository)
                    val result = if (noMap) target.repository.completeLandingWithoutMap(target.key.owner, permit)
                        else target.repository.completeLanding(target.key.owner, permit)
                    if (result is FreeFlightCommandResult.Rejected) registry.arrivalFailure(target.key, IllegalStateException(result.reason))
                    if (result is FreeFlightCommandResult.Failed) registry.arrivalFailure(target.key, result.error)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { registry.arrivalFailure(target.key, error) }
            }
        }
    }

    private fun stopMatched(key: FreeFlightRuntimeKey, reason: String) = synchronized(lock) {
        if (current != key) return@synchronized
        stopReason = reason; dispatching.set(false)
        ticker?.cancel(); controls?.cancel(); invalidations?.cancel(); arrival?.cancel(); cruiseReturn?.cancel(); shieldJobs.forEach { it.cancel() }
        serviceLifetime.cancel()
        // Only this matching instance/startId stops. No global settings/archive/refund mutation.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(currentStartId)
    }

    override fun onDestroy() {
        val key = synchronized(lock) { dispatching.set(false); current }
        val preparationKey = synchronized(lock) { currentPreparation }
        serviceLifetime.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (key != null && this::registry.isInitialized) registry.driverDestroyed(key, serviceLifetime, stopReason)
        if (preparationKey != null && this::preparation.isInitialized) preparation.destroyed(preparationKey, serviceLifetime)
        // The Application joins every child before acknowledging; Main never waits for Core.
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) } // Detach/swipe is not a terminal command.
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val SESSION_ID = "projectfocus.freeflight.session"
        const val DRIVER_ID = "projectfocus.freeflight.driver"
        const val PAUSE = "com.projectfocus.freeflight.PAUSE"
        const val RESUME = "com.projectfocus.freeflight.RESUME"
        const val PREPARE = "com.projectfocus.freeflight.PREPARE"
        const val PREPARATION_DRIVER_ID = "projectfocus.freeflight.preparation-driver"
    }
}

/** Actual manifest entry; inherited Inject fields are populated by the real Hilt graph. */
@AndroidEntryPoint
class InjectedFreeFlightRuntimeService : FreeFlightRuntimeService()
