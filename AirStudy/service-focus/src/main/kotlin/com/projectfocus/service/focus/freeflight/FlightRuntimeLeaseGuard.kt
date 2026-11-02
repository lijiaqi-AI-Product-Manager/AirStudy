package com.projectfocus.service.focus.freeflight

import android.os.Looper
import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.core.network.di.TokenStore
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Coroutine-carried marker; changing threads never makes a reentry safe. */
internal object FlightRuntimeLeaseContext {
    private val held = ThreadLocal<Boolean>()
    fun assertNoReentry() { check(held.get() != true) { "Core commands/ownership cannot reenter a held flight runtime lease" } }
    fun requireHeld() { check(held.get() == true) { "The actual Core runtime lease is required" } }
    suspend fun <T> run(action: suspend () -> T): T {
        assertNoReentry()
        return withContext(held.asContextElement(true)) { action() }
    }
    fun <T> runImmediate(action: () -> T): T {
        assertNoReentry()
        val previous = held.get()
        held.set(true)
        try { return action() }
        finally { held.set(previous) }
    }
}

sealed interface FreeFlightPaintAttempt<out T> {
    data class Applied<T>(val value: T) : FreeFlightPaintAttempt<T>
    object Busy : FreeFlightPaintAttempt<Nothing>
    object Obsolete : FreeFlightPaintAttempt<Nothing>
}

/** Actual effects check this capability BEFORE each effect, including after suspension. */
class FlightRuntimeLeaseGuard private constructor() {
    fun check() {
        FlightRuntimeLeaseContext.requireHeld()
        TokenStore.requireSnapshotLease()
        check(Looper.getMainLooper()?.thread !== Thread.currentThread()) { "Flight lease effects must stay off Main" }
    }
    companion object {
        fun current(): FlightRuntimeLeaseGuard = FlightRuntimeLeaseGuard().also { it.check() }
    }
}

/** Root supplies actual IO-safe operations; no successful empty implementation exists. */
fun interface FreeFlightLeaseEffectOperations {
    suspend fun synchronize(archive: FreeFlightArchive, lease: FlightRuntimeLeaseGuard)
}

class LeaseBoundFreeFlightRuntimeEffects(private val operations: FreeFlightLeaseEffectOperations) : FreeFlightRuntimeEffects {
    override suspend fun synchronize(archive: FreeFlightArchive) {
        val guard = FlightRuntimeLeaseGuard.current()
        operations.synchronize(archive, guard)
        guard.check()
    }
}
