package com.projectfocus.app.freeflight

import android.os.Looper
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.feature.home.map.MapAirport
import com.projectfocus.feature.home.map.MapHostState
import com.projectfocus.feature.home.map.MapPhase
import com.projectfocus.feature.home.map.SourceMapHostView
import com.projectfocus.feature.home.map.FreeFlightMapPose
import com.projectfocus.feature.home.map.FreeFlightMapVisual
import com.projectfocus.service.focus.freeflight.FreeFlightMapAvailability
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
import com.projectfocus.service.focus.freeflight.FreeFlightPaintAttempt
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightArrivalSurface
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightCruiseReturnSurface
import java.io.Closeable
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Reads the actual Home renderer lifecycle; no unbound/default-absent map state exists. */
@Singleton
class FreeFlightMapRuntime @Inject constructor(
    private val owners: SharedFlightRuntimeOwnership,
    settings: SettingsRepository,
    @FreeFlightApplicationScope scope: CoroutineScope,
) : FreeFlightArrivalSurface,FreeFlightCruiseReturnSurface {
    private class Binding(val host: WeakReference<SourceMapHostView>, val state: StateFlow<MapHostState>)
    private class VisualAttempt(val visual: FreeFlightMapVisual?)
    private val lock = Any()
    private var binding: Binding? = null
    @Volatile private var cruiseZoom: Float? = null
    @Volatile private var routeColor: Int? = null
    init {
        scope.launch { settings.observe(PrefKey.CruiseZoom).collect { cruiseZoom = it } }
        scope.launch { settings.observe(PrefKey.RouteColor).collect { routeColor = it } }
    }

    fun bind(host: SourceMapHostView): Closeable {
        check(Looper.myLooper() == Looper.getMainLooper())
        val entry = Binding(WeakReference(host), host.state)
        synchronized(lock) { binding = entry }
        return Closeable {
            synchronized(lock) {
                // Retain only the small actual state flow. Destroyed/failed means known absent;
                // an unobserved or prematurely detached renderer remains unknown.
                if (binding === entry) entry.host.clear()
            }
        }
    }

    /** Captured real preparation authority; opening a paint lease creates no archive or runtime slot. */
    suspend fun preparationMapReady(sessionId: String, owner: FreeFlightOwnerToken): Boolean =
        onUiOwner(sessionId, owner, preparation = true) { synchronized(lock) { binding?.host?.get()?.freeFlightPreparationReady == true } } ?: false

    /** Captured real preparation authority; opening a paint lease creates no archive or runtime slot. */
    suspend fun openPreparationVisual(sessionId: String, owner: FreeFlightOwnerToken, pose: FreeFlightMapPose): FreeFlightMapVisual? =
        onUiOwner(sessionId, owner, preparation = true) { VisualAttempt(openActualVisual(sessionId, owner, pose)) }?.visual

    /** A restored/started flight must still hold its actual runtime reservation. */
    suspend fun openFlightVisual(sessionId: String, owner: FreeFlightOwnerToken, pose: FreeFlightMapPose): FreeFlightMapVisual? =
        onUiOwner(sessionId, owner, preparation = false) { VisualAttempt(openActualVisual(sessionId, owner, pose)) }?.visual

    suspend fun paintPreparation(sessionId: String, owner: FreeFlightOwnerToken, visual: FreeFlightMapVisual, pose: FreeFlightMapPose): Boolean =
        onUiOwner(sessionId, owner, preparation = true) { paintBound(sessionId, owner, visual, pose) } ?: false

    suspend fun paintFlight(sessionId: String, owner: FreeFlightOwnerToken, visual: FreeFlightMapVisual, pose: FreeFlightMapPose,
                            archive: FreeFlightArchive? = null): Boolean =
        onUiOwner(sessionId, owner, preparation = false) {
            if (!paintBound(sessionId, owner, visual, pose)) false
            else if (archive == null) true
            else {
                require(archive.sessionId == sessionId && archive.ownerScope == owner.ownerScope)
                val scene = when (archive.phase) {
                    com.projectfocus.core.model.flight.freeflight.FreeFlightPhase.LANDING_SELECTION ->
                        com.projectfocus.feature.home.map.FreeFlightLandingMapScene(archive.position, archive.landingRangeMinutes,
                            archive.landingAirport?.let { MapAirport(it.code, it.latitude, it.longitude) }, true)
                    com.projectfocus.core.model.flight.freeflight.FreeFlightPhase.FINAL_APPROACH -> archive.approach?.let {
                        com.projectfocus.feature.home.map.FreeFlightLandingMapScene(it.departurePoint, null,
                            MapAirport(it.airport.code, it.airport.latitude, it.airport.longitude), false)
                    }
                    else -> null
                }
                val color = routeColor
                val rendered = if (color != null) synchronized(lock) { binding?.host?.get()?.renderFreeFlightLandingScene(scene, color) == true } else false
                scene == null || rendered
            }
        } ?: false

    suspend fun focusPreparationDeparture(sessionId: String, owner: FreeFlightOwnerToken,
                                          visual: FreeFlightMapVisual, departure: MapAirport): Boolean =
        onUiOwner(sessionId, owner, preparation = true) {
                (visual as? BoundVisual)?.takeIf { it.matches(this@FreeFlightMapRuntime, sessionId, owner) }
                    ?.focusDeparture(departure) == true
        } ?: false

    private suspend fun <T : Any> onUiOwner(sessionId: String, owner: FreeFlightOwnerToken, preparation: Boolean,
                                           action: () -> T): T? = withContext(Dispatchers.Main.immediate) {
        // Dispatch FIRST. Never hold the account/Core IO locks while waiting for Main, which
        // may itself be publishing an account or tearing down this View. Both real locks are
        // tried synchronously around the effect; contention yields without granting success.
        while (true) {
            when (val attempt = owners.tryWithUiOwner(sessionId, owner, preparation, action)) {
                is FreeFlightPaintAttempt.Applied -> return@withContext attempt.value
                FreeFlightPaintAttempt.Obsolete -> return@withContext null
                FreeFlightPaintAttempt.Busy -> delay(16L)
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    private fun paintBound(sessionId: String, owner: FreeFlightOwnerToken, visual: FreeFlightMapVisual, pose: FreeFlightMapPose): Boolean =
        (visual as? BoundVisual)?.takeIf { it.matches(this, sessionId, owner) }?.render(pose) == true

    private fun openActualVisual(sessionId: String, owner: FreeFlightOwnerToken, pose: FreeFlightMapPose): FreeFlightMapVisual? {
        val current = synchronized(lock) { binding } ?: return null
        val visual = current.host.get()?.openFreeFlightVisual(pose) ?: return null
        return BoundVisual(current, visual, sessionId, owner)
    }

    private inner class BoundVisual(private val captured: Binding, private val actual: FreeFlightMapVisual,
                                   private val sessionId: String, private val owner: FreeFlightOwnerToken) : FreeFlightMapVisual {
        fun matches(runtime: FreeFlightMapRuntime, sessionId: String, owner: FreeFlightOwnerToken): Boolean =
            runtime === this@FreeFlightMapRuntime && this.sessionId == sessionId && this.owner == owner
        override val isCurrent: Boolean get() = synchronized(lock) { binding === captured && captured.host.get() != null } && actual.isCurrent
        override fun render(pose: FreeFlightMapPose): Boolean = isCurrent && actual.render(pose)
        fun focusDeparture(departure: MapAirport): Boolean = isCurrent && captured.host.get()?.focusFreeFlightDeparture(departure) == true
        override fun close() = actual.close()
    }

    override fun current(sessionId: String, owner: FreeFlightOwnerToken): FreeFlightMapAvailability {
        if (owners.retainedOwner(sessionId) != owner) return FreeFlightMapAvailability.UNKNOWN
        return synchronized(lock) {
            val actual = binding ?: return@synchronized FreeFlightMapAvailability.UNKNOWN
            when (actual.state.value.phase) {
                MapPhase.MISSING_CONFIGURATION, MapPhase.FAILED, MapPhase.DESTROYED -> FreeFlightMapAvailability.ABSENT
                MapPhase.READY, MapPhase.LOADED -> if (actual.host.get() != null) FreeFlightMapAvailability.PRESENT else FreeFlightMapAvailability.UNKNOWN
                MapPhase.WAITING, MapPhase.CREATED -> FreeFlightMapAvailability.UNKNOWN
            }
        }
    }

    override fun animateFinalArrival(sessionId: String, owner: FreeFlightOwnerToken, archive: FreeFlightArchive, result: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(current(sessionId, owner) == FreeFlightMapAvailability.PRESENT)
        val host = synchronized(lock) { binding?.host?.get() } ?: error("The actual map host detached")
        val zoom = requireNotNull(cruiseZoom) { "The actual cruise zoom has not loaded" }
        require(zoom.isFinite())
        val airport = requireNotNull(archive.landingAirport)
        host.animateFreeFlightFinalArrival(MapAirport(airport.code, airport.latitude, airport.longitude), zoom, result)
    }

    override suspend fun animateCruiseReturn(sessionId:String,owner:FreeFlightOwnerToken,archive:FreeFlightArchive,
                                            stillCurrent:()->Boolean,result:()->Unit) {
        val dispatched=onUiOwner(sessionId,owner,preparation=false) {
            check(stillCurrent() && current(sessionId,owner)==FreeFlightMapAvailability.PRESENT)
            val captured=synchronized(lock) { requireNotNull(binding) }
            val host=requireNotNull(captured.host.get())
            val zoom=requireNotNull(cruiseZoom) { "Actual cruise zoom has not loaded" }
            val frameLease:((()->Unit)->Boolean)={ effect ->
                when(val attempt=owners.tryWithUiOwner(sessionId,owner,false) {
                    if(stillCurrent() && synchronized(lock) { binding===captured && captured.host.get()===host }) { effect();true } else false
                }) {
                    is FreeFlightPaintAttempt.Applied -> attempt.value
                    else -> false
                }
            }
            host.animateFreeFlightCruiseReturn(FreeFlightMapPose(archive.position.latitude,archive.position.longitude,archive.bearingDegrees.toFloat()),
                archive.clock.activeElapsedMs,zoom,stillCurrent,frameLease,result)
            true
        }
        check(dispatched==true) { "Actual cruise-return owner is obsolete" }
    }
}
