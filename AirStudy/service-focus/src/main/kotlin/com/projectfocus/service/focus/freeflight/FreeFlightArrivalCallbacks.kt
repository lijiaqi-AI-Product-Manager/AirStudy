package com.projectfocus.service.focus.freeflight

import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.core.model.flight.freeflight.FreeFlightClockReading
import com.projectfocus.core.model.flight.freeflight.FreeFlightPhase
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus

/** Opaque in-process permit, created only after the repository's actual owner lease. */
class FreeFlightArrivalPermit internal constructor(
    val owner: FreeFlightOwnerToken,
    val sessionId: String,
    val phase: FreeFlightPhase,
)

/**
 * Captures the actual result callback's clock. Sampling and capture serialize only
 * their short synchronous clock reads; no suspension/IO happens under this lock.
 * A delayed SQL command or another tick cannot grant time after that callback.
 * This in-memory fence does not claim crash durability before a successful write.
 */
internal class FreeFlightArrivalClockGate(private val time: FreeFlightTimeSource) {
    private class Capture(val permit: FreeFlightArrivalPermit, var clock: FreeFlightClockReading? = null)
    private val lock = Any()
    private var current: Capture? = null

    fun prepare(owner: FreeFlightOwnerToken, archive: FreeFlightArchive): FreeFlightArrivalPermit = synchronized(lock) {
        current?.takeIf { matches(it.permit, owner, archive) }?.permit
            ?: FreeFlightArrivalPermit(owner, archive.sessionId, archive.phase).also { current = Capture(it) }
    }

    fun capture(permit: FreeFlightArrivalPermit, stillCurrent: () -> Boolean): Boolean = synchronized(lock) {
        val saved = current?.takeIf { it.permit === permit } ?: return false
        if (!stillCurrent()) return false
        if (saved.clock == null) saved.clock = time.read()
        true
    }

    fun captured(permit: FreeFlightArrivalPermit): Boolean = synchronized(lock) {
        current?.let { it.permit === permit && it.clock != null } == true
    }

    fun capturedReading(permit: FreeFlightArrivalPermit, owner: FreeFlightOwnerToken,
                        archive: FreeFlightArchive): FreeFlightClockReading? = synchronized(lock) {
        current?.takeIf { it.permit === permit && matches(permit, owner, archive) }?.clock
    }

    fun hasCaptured(owner: FreeFlightOwnerToken, archive: FreeFlightArchive): Boolean = synchronized(lock) {
        current?.let { it.clock != null && matches(it.permit, owner, archive) } == true
    }

    fun read(owner: FreeFlightOwnerToken?, archive: FreeFlightArchive?): FreeFlightClockReading = synchronized(lock) {
        current?.takeIf { owner != null && archive != null && matches(it.permit, owner, archive) }
            ?.clock ?: time.read()
    }

    fun afterCommit(owner: FreeFlightOwnerToken, archive: FreeFlightArchive) = synchronized(lock) {
        if (archive.status != FreeFlightStatus.ONGOING || current?.let { matches(it.permit, owner, archive) } != true)
            current = null
    }

    private fun matches(permit: FreeFlightArrivalPermit, owner: FreeFlightOwnerToken, archive: FreeFlightArchive) =
        permit.owner == owner && permit.sessionId == archive.sessionId && permit.phase == archive.phase &&
            archive.ownerScope == owner.ownerScope && archive.status == FreeFlightStatus.ONGOING
}
