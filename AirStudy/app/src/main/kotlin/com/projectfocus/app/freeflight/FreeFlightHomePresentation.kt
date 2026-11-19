package com.projectfocus.app.freeflight

import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.core.model.flight.freeflight.FreeFlightPause
import com.projectfocus.core.model.flight.freeflight.FreeFlightPhase
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.feature.home.map.FreeFlightMapPose
import com.projectfocus.feature.home.map.FreeFlightMapVisual
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.FreeFlightSessionRepository
import com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightDriverState
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeRegistry
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class FreeFlightHomeFrame(val archive: FreeFlightArchive, val owner: FreeFlightOwnerToken)

/** View-owned projection only. The actual service/repository remain the sole clock and writer. */
class FreeFlightHomePresentation(
    private val repository: FreeFlightSessionRepository,
    private val owners: SharedFlightRuntimeOwnership,
    runtime: FreeFlightRuntimeRegistry,
    private val maps: FreeFlightMapRuntime,
    scope: CoroutineScope,
    private val draw: (FreeFlightHomeFrame?) -> Unit,
) : Closeable {
    private data class Identity(val sessionId: String, val ownerScope: String, val status: FreeFlightStatus,
                                val phase: FreeFlightPhase, val pause: FreeFlightPause?)
    private data class Binding(val identity: Identity?, val owner: FreeFlightOwnerToken?, val running: Boolean)
    private data class HudRevision(val revision: Long, val second: Long)
    private var visual: FreeFlightMapVisual? = null
    private var visualOwner: Pair<String, FreeFlightOwnerToken>? = null
    private var closed = false
    private val job = scope.launch {
        try {
            val identity = repository.state.map { it?.let { a -> Identity(a.sessionId, a.ownerScope, a.status, a.phase, a.clock.pause) } }.distinctUntilChanged()
            combine(identity, owners.currentHosts, runtime.observation) { archive, owner, driver ->
                Binding(archive, owner, driver.key?.let { it.sessionId == archive?.sessionId && it.owner == owner } == true &&
                    driver.state == FreeFlightDriverState.RUNNING && driver.foregroundConfirmed && driver.dispatching)
            }.distinctUntilChanged().collectLatest { bound ->
                val identity = bound.identity
                val owner = bound.owner
                if (closed || identity == null || identity.status != FreeFlightStatus.ONGOING || owner == null ||
                    identity.ownerScope != owner.ownerScope || owners.retainedOwner(identity.sessionId) != owner || !bound.running) {
                    clearVisual(); if (!closed) draw(null); return@collectLatest
                }
                val key = identity.sessionId to owner
                if (visualOwner != key) clearVisual()
                var lastHud: HudRevision? = null
                var nextMapAttempt = 0L
                while (isActive && !closed) {
                    val archive = try { repository.preview(owner) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { clearVisual(); if (!closed) draw(null); break }
                    if (archive.sessionId != identity.sessionId || archive.ownerScope != owner.ownerScope || archive.status != FreeFlightStatus.ONGOING) break
                    val pose = FreeFlightMapPose(archive.position.latitude, archive.position.longitude, archive.bearingDegrees.toFloat())
                    val now = android.os.SystemClock.uptimeMillis()
                    if (visual?.isCurrent != true && now >= nextMapAttempt) {
                        clearVisual()
                        visual = maps.openFlightVisual(archive.sessionId, owner, pose)
                        visualOwner = if (visual == null) null else key
                        nextMapAttempt = now + 1_000L
                    }
                    visual?.let { actual ->
                        if (!maps.paintFlight(archive.sessionId, owner, actual, pose, archive)) clearVisual()
                    }
                    // Counters update on the source second/revision cadence; only the actual plane
                    // pose uses 16ms sampling. Preview never writes another archive or starts a clock.
                    val revision = HudRevision(archive.revision, archive.clock.activeElapsedMs / 1_000L)
                    if (revision != lastHud) { lastHud = revision; if (!closed) draw(FreeFlightHomeFrame(archive, owner)) }
                    delay(if (archive.clock.pause == null) 16L else 250L)
                }
            }
        } finally { clearVisual(); if (!closed) draw(null) }
    }

    private fun clearVisual() { visual?.close(); visual = null; visualOwner = null }
    override fun close() {
        if (closed) return
        closed = true; job.cancel(); clearVisual()
    }
}
