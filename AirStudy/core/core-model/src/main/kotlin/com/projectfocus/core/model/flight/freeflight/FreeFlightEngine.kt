package com.projectfocus.core.model.flight.freeflight

import com.projectfocus.core.model.flight.PlannedAirport
import kotlin.math.ceil
import kotlin.math.max

class FreeFlightClockChanged : IllegalStateException("Free-flight clock epoch changed; restore the saved checkpoint")

/** Pure domain transitions. No Android, server requests, timer loop, or persistence. */
class FreeFlightEngine {
    fun start(request: FreeFlightStartRequest, now: FreeFlightClockReading): FreeFlightArchive {
        val normalized = FreeFlightNavigation.normalize(
            FreeFlightPoint(request.departure.latitude, request.departure.longitude), request.headingDegrees,
        )
        // Source m(0) resets the last-uptime observation; d() starts adjusting
        // sleep after its first observed sample.
        val clock = FreeFlightClock(0L, now.elapsedRealtimeMs, 0L, now.epochId, null)
        return FreeFlightArchive(
            sessionId = request.sessionId, ownerScope = request.ownerScope, revision = 0L,
            departure = request.departure, seat = request.seat, scene = request.scene,
            flightNumber = request.flightNumber, shieldingCategoryId = request.shieldingCategoryId,
            startedAtWallMs = now.wallMs, updatedAtWallMs = now.wallMs, endedAtWallMs = null,
            status = FreeFlightStatus.ONGOING, phase = FreeFlightPhase.CRUISING, clock = clock,
            position = normalized.point,
            bearingDegrees = normalized.bearingDegrees,
            requestedHeadingDegrees = request.headingDegrees,
            cumulativeDistanceKm = 0.0,
            cruiseAnchor = FreeFlightCruiseAnchor(
                normalized.point, normalized.bearingDegrees, 0L, 0.0, 0L,
            ),
        ).validated()
    }

    /** Reading/materializing does not turn arrival into a fabricated successful record. */
    fun sample(saved: FreeFlightArchive, now: FreeFlightClockReading): FreeFlightArchive {
        saved.validated()
        if (saved.status != FreeFlightStatus.ONGOING) return saved
        val clock = sampleClock(saved.clock, now)
        var next = saved.copy(clock = clock, updatedAtWallMs = now.wallMs)
        next = when (saved.phase) {
            FreeFlightPhase.CRUISING -> {
                val anchor = saved.cruiseAnchor
                val added = FreeFlightNavigation.SPEED_KM_PER_HOUR *
                    max(0L, clock.activeElapsedMs - anchor.activeElapsedMs).toDouble() / 3_600_000.0
                val projection = FreeFlightNavigation.advance(anchor.point, anchor.bearingDegrees, added)
                next.copy(
                    position = projection.point, bearingDegrees = projection.bearingDegrees,
                    cumulativeDistanceKm = anchor.cumulativeDistanceKm + added,
                )
            }
            FreeFlightPhase.LANDING_SELECTION -> next
            // Actual no-map result callback is pending. Time remains effective until callback;
            // no final route was flown, so position and cumulative distance stay actual.
            FreeFlightPhase.NO_MAP_ARRIVAL -> next
            FreeFlightPhase.FINAL_APPROACH -> {
                val approach = requireNotNull(saved.approach)
                val progress = approachProgress(next)
                val projection = FreeFlightNavigation.approach(
                    approach.departurePoint,
                    FreeFlightPoint(approach.airport.latitude, approach.airport.longitude), progress,
                )
                next.copy(
                    position = projection.point, bearingDegrees = projection.bearingDegrees,
                    cumulativeDistanceKm = approach.cumulativeDistanceAtStartKm + approach.routeDistanceKm * progress,
                )
            }
        }
        return next.validated()
    }

    fun pause(saved: FreeFlightArchive, now: FreeFlightClockReading): FreeFlightArchive {
        requireActive(saved)
        require(saved.phase != FreeFlightPhase.LANDING_SELECTION && saved.clock.pause == null)
        val next = sample(saved, now)
        return next.copy(clock = next.clock.copy(pause = FreeFlightPause.USER)).validated()
    }

    fun resume(saved: FreeFlightArchive, now: FreeFlightClockReading): FreeFlightArchive {
        requireActive(saved)
        require(saved.phase != FreeFlightPhase.LANDING_SELECTION && saved.clock.pause != null)
        if (saved.cruiseReturnPending && saved.clock.pause==FreeFlightPause.RECOVERY_REQUIRES_RESUME) return saved.copy(
            clock = saved.clock.copy(lastElapsedRealtimeMs = now.elapsedRealtimeMs, lastUptimeMillis = now.uptimeMillis,
                epochId = now.epochId, pause = FreeFlightPause.RETURN_CAMERA), updatedAtWallMs = now.wallMs,
        ).validated()
        return saved.copy(clock = runningClock(saved.clock, now), updatedAtWallMs = now.wallMs).validated()
    }

    fun prepareLanding(saved: FreeFlightArchive, now: FreeFlightClockReading): FreeFlightArchive {
        requireActive(saved)
        require(saved.phase == FreeFlightPhase.CRUISING)
        val next = sample(saved, now)
        return next.copy(
            phase = FreeFlightPhase.LANDING_SELECTION,
            clock = next.clock.copy(pause = FreeFlightPause.LANDING_SELECTION),
            landingRangeMinutes = 10, landingAirport = null,
            cruiseReturnPending = false,
            landingSelectionGeneration = Math.addExact(next.landingSelectionGeneration, 1L),
        ).validated()
    }

    fun changeLandingRange(saved: FreeFlightArchive, minutes: Int): FreeFlightArchive {
        requireActive(saved)
        require(saved.phase == FreeFlightPhase.LANDING_SELECTION && minutes >= 0)
        return saved.copy(
            landingRangeMinutes = minutes, landingAirport = null,
            landingSelectionGeneration = Math.addExact(saved.landingSelectionGeneration, 1L),
        ).validated()
    }

    /** The search owner must discard results from an older generation. */
    fun selectLanding(saved: FreeFlightArchive, airport: PlannedAirport, selectionGeneration: Long): FreeFlightArchive {
        requireActive(saved)
        require(saved.phase == FreeFlightPhase.LANDING_SELECTION)
        require(selectionGeneration == saved.landingSelectionGeneration)
        return saved.copy(landingAirport = airport).validated()
    }

    fun returnToCruise(saved: FreeFlightArchive, now: FreeFlightClockReading): FreeFlightArchive {
        requireActive(saved)
        require(saved.phase == FreeFlightPhase.LANDING_SELECTION)
        return saved.copy(
            phase = FreeFlightPhase.CRUISING, clock = saved.clock.copy(pause = FreeFlightPause.RETURN_CAMERA),
            cruiseReturnPending = true,
            cruiseAnchor = FreeFlightCruiseAnchor(saved.position, saved.bearingDegrees, saved.clock.activeElapsedMs, saved.cumulativeDistanceKm),
            landingAirport = null, landingRangeMinutes = null,
            landingSelectionGeneration = Math.addExact(saved.landingSelectionGeneration, 1L),
            updatedAtWallMs = now.wallMs,
        ).validated()
    }

    /** Called only by the repository's actual captured camera/no-map callback permit. */
    fun completeReturnToCruise(saved: FreeFlightArchive, callback: FreeFlightClockReading): FreeFlightArchive {
        requireActive(saved)
        require(saved.phase == FreeFlightPhase.CRUISING && saved.cruiseReturnPending && saved.clock.pause != FreeFlightPause.RECOVERY_REQUIRES_RESUME)
        require(saved.clock.epochId == callback.epochId && callback.elapsedRealtimeMs >= saved.clock.lastElapsedRealtimeMs && callback.uptimeMillis >= saved.clock.lastUptimeMillis)
        // Source explicit g3.B/E can resume early; the still-pending u callback samples that
        // actual running interval and invokes E/K once, without starting a second clock.
        val actual=sample(saved,callback)
        return actual.copy(cruiseReturnPending = false, clock = runningClock(actual.clock, callback), updatedAtWallMs = callback.wallMs).validated()
    }


    fun confirmLanding(
        saved: FreeFlightArchive,
        selectionGeneration: Long,
        distanceKm: Double,
        now: FreeFlightClockReading,
    ): FreeFlightArchive {
        requireActive(saved)
        require(saved.phase == FreeFlightPhase.LANDING_SELECTION)
        require(selectionGeneration == saved.landingSelectionGeneration)
        require(distanceKm.isFinite() && distanceKm >= 0.0)
        val airport = requireNotNull(saved.landingAirport)
        val duration = ceil(distanceKm / FreeFlightNavigation.SPEED_KM_PER_HOUR * 3_600_000.0)
        require(duration.isFinite() && duration < Long.MAX_VALUE.toDouble())
        val target = FreeFlightPoint(airport.latitude, airport.longitude)
        return saved.copy(
            phase = FreeFlightPhase.FINAL_APPROACH, clock = runningClock(saved.clock, now),
            landingRangeMinutes = null,
            approach = FreeFlightApproach(
                saved.position, airport, saved.clock.activeElapsedMs, saved.cumulativeDistanceKm,
                distanceKm, duration.toLong(), FreeFlightNavigation.routeBearing(saved.position, target),
            ),
            updatedAtWallMs = now.wallMs,
        ).validated()
    }

    fun landingReached(archive: FreeFlightArchive): Boolean =
        archive.status == FreeFlightStatus.ONGOING &&
            archive.phase == FreeFlightPhase.FINAL_APPROACH && approachProgress(archive) >= 1.0


    fun beginLandingWithoutMap(saved: FreeFlightArchive, selectionGeneration: Long, now: FreeFlightClockReading): FreeFlightArchive {
        requireActive(saved)
        require(saved.phase == FreeFlightPhase.LANDING_SELECTION && saved.landingSelectionGeneration == selectionGeneration)
        requireNotNull(saved.landingAirport)
        return saved.copy(
            phase = FreeFlightPhase.NO_MAP_ARRIVAL,
            clock = runningClock(saved.clock, now),
            landingRangeMinutes = null,
            updatedAtWallMs = now.wallMs,
        ).validated()
    }

    /** Actual posted no-map result callback freezes sampled time without granting a leg. */
    fun completeLandingWithoutMap(saved: FreeFlightArchive, now: FreeFlightClockReading): FreeFlightArchive {
        requireActive(saved)
        require(saved.phase == FreeFlightPhase.NO_MAP_ARRIVAL)
        return terminal(sample(saved, now), FreeFlightStatus.COMPLETED, now)
    }

    /**
     * Explicit local arrival callback, after the host finishes its arrival phase.
     * It does not claim any remote completion ACK. Arrival animation time is not
     * silently clamped to the leg's planned duration; the effective clock is authoritative.
     */
    fun completeLanding(saved: FreeFlightArchive, now: FreeFlightClockReading): FreeFlightArchive {
        val next = sample(saved, now)
        require(landingReached(next))
        return terminal(next, FreeFlightStatus.COMPLETED, now)
    }

    fun cancel(saved: FreeFlightArchive, now: FreeFlightClockReading): FreeFlightArchive {
        requireActive(saved)
        return terminal(sample(saved, now), FreeFlightStatus.CANCELLED, now)
    }

    /**
     * Own recovery policy until original cold restoration is proved: recover the
     * committed checkpoint paused; never grant wall-clock/offline focus or guess a destination.
     */
    fun restoreCheckpoint(saved: FreeFlightArchive, now: FreeFlightClockReading): FreeFlightArchive {
        saved.validated()
        if (saved.status != FreeFlightStatus.ONGOING) return saved
        return saved.copy(
            clock = saved.clock.copy(
                lastElapsedRealtimeMs = now.elapsedRealtimeMs, lastUptimeMillis = now.uptimeMillis,
                epochId = now.epochId,
                pause = if (saved.phase == FreeFlightPhase.LANDING_SELECTION) FreeFlightPause.LANDING_SELECTION else FreeFlightPause.RECOVERY_REQUIRES_RESUME,
            ),
            updatedAtWallMs = now.wallMs,
        ).validated()
    }

    fun landingRadiusKm(archive: FreeFlightArchive): Double {
        require(archive.phase == FreeFlightPhase.LANDING_SELECTION)
        return requireNotNull(archive.landingRangeMinutes).toDouble() / 60.0 * 800.0
    }

    fun approachProgress(archive: FreeFlightArchive): Double {
        val approach = requireNotNull(archive.approach)
        val elapsed = max(0L, archive.clock.activeElapsedMs - approach.activeElapsedAtStartMs)
        return if (approach.durationMs <= 0L) 1.0 else
            elapsed.toDouble().div(approach.durationMs.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun terminal(saved: FreeFlightArchive, status: FreeFlightStatus, now: FreeFlightClockReading): FreeFlightArchive =
        saved.copy(
            status = status, endedAtWallMs = now.wallMs, updatedAtWallMs = now.wallMs,
            cruiseReturnPending = false,
            clock = saved.clock.copy(pause = FreeFlightPause.TERMINAL),
        ).validated()

    private fun requireActive(saved: FreeFlightArchive) {
        saved.validated()
        require(saved.status == FreeFlightStatus.ONGOING)
    }

    private fun runningClock(saved: FreeFlightClock, now: FreeFlightClockReading): FreeFlightClock =
        saved.copy(
            lastElapsedRealtimeMs = now.elapsedRealtimeMs, lastUptimeMillis = now.uptimeMillis,
            epochId = now.epochId, pause = null,
        )

    private fun sampleClock(saved: FreeFlightClock, now: FreeFlightClockReading): FreeFlightClock {
        if (saved.epochId != now.epochId ||
            now.elapsedRealtimeMs < saved.lastElapsedRealtimeMs || now.uptimeMillis < saved.lastUptimeMillis) {
            throw FreeFlightClockChanged()
        }
        val deltaElapsed = now.elapsedRealtimeMs - saved.lastElapsedRealtimeMs
        val deltaUptime = now.uptimeMillis - saved.lastUptimeMillis
        val sleep = if (saved.lastUptimeMillis > 0L) max(0L, deltaElapsed - deltaUptime) else 0L
        val effective = if (saved.pause == null) max(0L, deltaElapsed - sleep) else 0L
        return saved.copy(
            activeElapsedMs = Math.addExact(saved.activeElapsedMs, effective),
            lastElapsedRealtimeMs = now.elapsedRealtimeMs, lastUptimeMillis = now.uptimeMillis,
        )
    }
}
