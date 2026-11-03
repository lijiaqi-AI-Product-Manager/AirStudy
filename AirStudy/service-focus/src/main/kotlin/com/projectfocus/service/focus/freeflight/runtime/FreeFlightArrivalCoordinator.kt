package com.projectfocus.service.focus.freeflight.runtime

import android.os.Handler
import android.os.Looper
import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.core.model.flight.freeflight.FreeFlightPhase
import com.projectfocus.service.focus.freeflight.FreeFlightArrivalPermit
import com.projectfocus.service.focus.freeflight.FreeFlightMapAvailability
import com.projectfocus.service.focus.freeflight.FreeFlightMapPresence
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.FreeFlightSessionRepository
import com.projectfocus.service.focus.freeflight.FreeFlightReturnPermit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException

/** The production surface dispatches a real camera operation on Main. */
interface FreeFlightArrivalSurface : FreeFlightMapPresence {
    fun animateFinalArrival(
        sessionId: String, owner: FreeFlightOwnerToken, archive: FreeFlightArchive, result: () -> Unit,
    )
}

/** A renderer cannot silently claim the source return camera capability. */
interface FreeFlightCruiseReturnSurface {
    suspend fun animateCruiseReturn(sessionId:String,owner:FreeFlightOwnerToken,archive:FreeFlightArchive,
                            stillCurrent:()->Boolean,result:()->Unit)
}

/** Source e4.p: actual posted result, or real 420ms camera with a 520ms once-only fallback. */
class FreeFlightArrivalCoordinator(private val maps: FreeFlightArrivalSurface) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private class Pending(
        val key: FreeFlightRuntimeKey,
        val repository: FreeFlightSessionRepository,
        val permit: FreeFlightArrivalPermit,
        val completion: CompletableDeferred<FreeFlightArrivalPermit> = CompletableDeferred(),
    )
    private var pending: Pending? = null
    private class ReturnPending(val key:FreeFlightRuntimeKey,val repository:FreeFlightSessionRepository,
        val permit:FreeFlightReturnPermit,val completion:CompletableDeferred<FreeFlightReturnPermit> = CompletableDeferred())
    private var returning:ReturnPending? = null


    suspend fun awaitActualReturnCallback(key:FreeFlightRuntimeKey,archive:FreeFlightArchive,
                                         repository:FreeFlightSessionRepository):FreeFlightReturnPermit = coroutineScope {
        require(archive.sessionId==key.sessionId && archive.ownerScope==key.owner.ownerScope)
        val permit=requireNotNull(repository.prepareReturnCallback(key.owner)) { "Actual return owner/phase is no longer current" }
        if(repository.hasReturnCallback(permit)) return@coroutineScope permit
        val entry=ReturnPending(key,repository,permit)
        synchronized(lock) { check(returning==null) { "An actual cruise-return callback is already pending" };returning=entry }
        fun current()=synchronized(lock) { returning===entry && !entry.completion.isCompleted } && repository.isReturnCallbackCurrent(permit)
        val result=Runnable {
            synchronized(lock) {
                if(returning!==entry || entry.completion.isCompleted) return@Runnable
                if(repository.captureReturnCallback(permit)) entry.completion.complete(permit)
                else entry.completion.completeExceptionally(IllegalStateException("Actual return callback is obsolete"))
            }
        }
        val dispatch=launch(Dispatchers.Main.immediate) {
            if(!current()) return@launch
            try {
                when(maps.current(key.sessionId,key.owner)) {
                    FreeFlightMapAvailability.ABSENT -> result.run()
                    FreeFlightMapAvailability.PRESENT -> (maps as? FreeFlightCruiseReturnSurface
                        ?: error("Actual source return camera capability is unavailable"))
                        .animateCruiseReturn(key.sessionId,key.owner,archive,::current) {
                            if(Looper.myLooper()==Looper.getMainLooper()) result.run()
                            else if(!main.post(result)) entry.completion.completeExceptionally(IllegalStateException("Actual return dispatcher unavailable"))
                        }
                    FreeFlightMapAvailability.UNKNOWN -> error("Actual return map state is unknown")
                }
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) { entry.completion.completeExceptionally(error) }
        }
        // Lifecycle observation only: an actually destroyed map may take the source map-null
        // callback path. Unknown/detached is never reported as a successful camera operation.
        val monitor=launch {
            while(isActive && !entry.completion.isCompleted) {
                delay(100L)
                if(!current()) { entry.completion.completeExceptionally(IllegalStateException("Return phase changed"));break }
                if(maps.current(key.sessionId,key.owner)==FreeFlightMapAvailability.ABSENT) main.post(result)
            }
        }
        try { entry.completion.await() }
        finally {
            monitor.cancel();synchronized(lock) { if(returning===entry) returning=null }
            dispatch.cancel();main.removeCallbacks(result)
        }
    }

    suspend fun awaitActualResultCallback(
        key: FreeFlightRuntimeKey, archive: FreeFlightArchive, repository: FreeFlightSessionRepository,
    ): FreeFlightArrivalPermit {
        require(archive.sessionId == key.sessionId && archive.ownerScope == key.owner.ownerScope)
        val permit = requireNotNull(repository.prepareArrivalCallback(key.owner)) {
            "The actual owner/arrival phase is no longer current"
        }
        // Retry the exact captured result after a failed save, without another camera or clock.
        if (repository.hasArrivalCallback(permit)) return permit
        val entry = Pending(key, repository, permit)
        synchronized(lock) {
            check(pending == null) { "An actual arrival callback is already pending" }
            pending = entry
        }
        val result = Runnable { actualResult(entry) }
        val dispatch = Runnable {
            if (!isPending(entry)) return@Runnable
            try {
                if (archive.phase == FreeFlightPhase.NO_MAP_ARRIVAL) {
                    check(main.post(result)) { "The actual result dispatcher is unavailable" }
                } else when (maps.current(key.sessionId, key.owner)) {
                    FreeFlightMapAvailability.ABSENT -> check(main.post(result)) {
                        "The actual result dispatcher is unavailable"
                    }
                    FreeFlightMapAvailability.PRESENT -> maps.animateFinalArrival(
                        key.sessionId, key.owner, archive,
                    ) {
                        if (Looper.myLooper() == Looper.getMainLooper()) result.run()
                        else if (!main.post(result)) fail(entry, IllegalStateException("The actual result dispatcher is unavailable"))
                    }
                    FreeFlightMapAvailability.UNKNOWN -> error("The actual arrival map state is unknown")
                }
            } catch (error: Exception) { fail(entry, error) }
        }
        try {
            check(main.post(dispatch)) { "The actual camera dispatcher is unavailable" }
            return entry.completion.await()
        } finally {
            synchronized(lock) { if (pending === entry) pending = null }
            main.removeCallbacks(dispatch)
            main.removeCallbacks(result)
        }
    }

    private fun isPending(entry: Pending) = synchronized(lock) { pending === entry && !entry.completion.isCompleted }
    private fun actualResult(entry: Pending) = synchronized(lock) {
        if (pending !== entry || entry.completion.isCompleted) return@synchronized
        if (entry.repository.captureArrivalCallback(entry.permit)) entry.completion.complete(entry.permit)
        else entry.completion.completeExceptionally(IllegalStateException("The actual arrival callback is obsolete"))
    }
    private fun fail(entry: Pending, error: Throwable) = synchronized(lock) {
        if (pending === entry) entry.completion.completeExceptionally(error)
    }
}
