package com.projectfocus.service.focus.freeflight

import com.projectfocus.core.model.flight.freeflight.*

/** No caller-supplied timestamp or reconstructible saved authority. */
class FreeFlightReturnPermit internal constructor(
    val owner: FreeFlightOwnerToken,
    val sessionId: String,
    val generation: Long,
)

internal class FreeFlightReturnClockGate(private val time: FreeFlightTimeSource) {
    private class Capture(val permit: FreeFlightReturnPermit, var clock: FreeFlightClockReading? = null)
    private val lock = Any()
    private var current: Capture? = null
    fun prepare(owner: FreeFlightOwnerToken, archive: FreeFlightArchive): FreeFlightReturnPermit = synchronized(lock) {
        check(waiting(archive))
        current?.takeIf { matches(it.permit, owner, archive) }?.permit ?: FreeFlightReturnPermit(owner,archive.sessionId,archive.landingSelectionGeneration).also { current=Capture(it) }
    }
    fun capture(permit: FreeFlightReturnPermit, stillCurrent: () -> Boolean): Boolean = synchronized(lock) {
        val saved=current?.takeIf { it.permit===permit } ?: return false
        if (!stillCurrent()) return false
        if (saved.clock==null) saved.clock=time.read()
        true
    }
    fun captured(permit: FreeFlightReturnPermit): Boolean = synchronized(lock) { current?.let { it.permit===permit && it.clock!=null }==true }
    fun capturedReading(permit: FreeFlightReturnPermit,owner:FreeFlightOwnerToken,archive:FreeFlightArchive):FreeFlightClockReading? = synchronized(lock) {
        current?.takeIf { it.permit===permit && matches(permit,owner,archive) }?.clock
    }
    fun hasCaptured(owner:FreeFlightOwnerToken,archive:FreeFlightArchive):Boolean = synchronized(lock) { current?.let { it.clock!=null && matches(it.permit,owner,archive) }==true }
    fun read(owner:FreeFlightOwnerToken?,archive:FreeFlightArchive?,fallback:()->FreeFlightClockReading):FreeFlightClockReading = synchronized(lock) {
        current?.takeIf { owner!=null && archive!=null && matches(it.permit,owner,archive) }?.clock ?: fallback()
    }
    fun afterCommit(owner:FreeFlightOwnerToken,archive:FreeFlightArchive) = synchronized(lock) { if(current?.let { matches(it.permit,owner,archive) }!=true) current=null }
    private fun matches(permit:FreeFlightReturnPermit,owner:FreeFlightOwnerToken,archive:FreeFlightArchive)=
        permit.owner==owner && permit.sessionId==archive.sessionId && permit.generation==archive.landingSelectionGeneration && archive.ownerScope==owner.ownerScope && waiting(archive)
    private fun waiting(archive:FreeFlightArchive)=archive.status==FreeFlightStatus.ONGOING && archive.phase==FreeFlightPhase.CRUISING && archive.cruiseReturnPending && archive.clock.pause!=FreeFlightPause.RECOVERY_REQUIRES_RESUME
}
