package com.projectfocus.core.model.flight.freeflight

import com.projectfocus.core.model.flight.PlannedAirport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Source-bound boundary/conservation contracts, including the actual initially published pose. */
class FreeFlightEngineTest {
    private val engine = FreeFlightEngine()
    private val departure = airport(1L, "AAA", 0.0, 0.0)
    private fun airport(id: Long, code: String, latitude: Double, longitude: Double) =
        PlannedAirport(id, code, code, "City", "Country", latitude, longitude)
    private fun now(elapsed: Long, uptime: Long = elapsed, wall: Long = 1_000_000L + elapsed, epoch: String = "boot-1") =
        FreeFlightClockReading(elapsed, uptime, wall, epoch)
    private fun start() = engine.start(
        FreeFlightStartRequest("free-1", "owner-1", departure, 90.0, "12A", FreeFlightScene(2, "Reading", "book")),
        now(100_000L),
    )

    @Test fun realOriginAndNoInventedDestination() {
        val flight = start()
        assertEquals(departure, flight.departure)
        assertNull(flight.landingAirport)
        assertNull(flight.approach)
        assertEquals(0L, flight.clock.activeElapsedMs)
        assertEquals(0.0, flight.cumulativeDistanceKm)
    }

    @Test fun polarDepartureFirstPoseMatchesTheSourceAndNeverJumpsOnAZeroTimeSample() {
        // Original l4.l.L publishes BOTH members returned by g1.g.N, before its first frame.
        // Keep the actual airport snapshot while drawing the reflected map position/heading.
        for ((origin, heading, expected, expectedHeading) in listOf(
            PolarCase(FreeFlightPoint(89.0, 179.0), 45.0, FreeFlightPoint(81.10225756, -1.0), 135.0),
            PolarCase(FreeFlightPoint(-89.0, -179.0), 225.0, FreeFlightPoint(-81.10225756, 1.0), 315.0),
        )) {
            val airport = airport(9L, "POLAR", origin.latitude, origin.longitude)
            val initial = engine.start(FreeFlightStartRequest("polar", "owner-1", airport, heading,
                "12A", FreeFlightScene(2, "Reading", "book")), now(100_000L))
            assertEquals(airport, initial.departure)
            assertEquals(expected.latitude, initial.position.latitude, 1.0e-10)
            assertEquals(expected.longitude, initial.position.longitude, 1.0e-10)
            assertEquals(expectedHeading, initial.bearingDegrees, 1.0e-10)
            val zero = engine.sample(initial, now(100_000L))
            assertEquals(initial.position, zero.position)
            assertEquals(initial.bearingDegrees, zero.bearingDegrees)
            assertEquals(0L, zero.clock.activeElapsedMs)
            assertEquals(0.0, zero.cumulativeDistanceKm)
        }
    }

    private data class PolarCase(val origin: FreeFlightPoint, val heading: Double,
                                 val expected: FreeFlightPoint, val expectedHeading: Double)

    @Test fun awakeTimeAndRouteAreIndependentOfTickPartition() {
        val started = start()
        val once = engine.sample(started, now(136_000L))
        var partitioned = started
        for (elapsed in 101_000L..136_000L step 1_000L) partitioned = engine.sample(partitioned, now(elapsed))
        assertEquals(36_000L, once.clock.activeElapsedMs)
        assertEquals(8.0, once.cumulativeDistanceKm, 1.0e-12)
        assertEquals(once.clock.activeElapsedMs, partitioned.clock.activeElapsedMs)
        assertEquals(once.cumulativeDistanceKm, partitioned.cumulativeDistanceKm, 1.0e-12)
        assertEquals(once.position.latitude, partitioned.position.latitude, 1.0e-10)
        assertEquals(once.position.longitude, partitioned.position.longitude, 1.0e-10)
    }

    @Test fun deepSleepAndWallClockChangesDoNotGrantFocus() {
        val primed = engine.sample(start(), now(101_000L))
        val asleep = engine.sample(primed, now(132_000L, 102_000L, 99_000_000L))
        assertEquals(2_000L, asleep.clock.activeElapsedMs)
        assertEquals(800.0 * 2_000.0 / 3_600_000.0, asleep.cumulativeDistanceKm, 1.0e-12)
        val wallBack = engine.sample(asleep, now(133_000L, 103_000L, 100L))
        assertEquals(3_000L, wallBack.clock.activeElapsedMs)
    }

    @Test fun pauseAndLandingSelectionConserveProgress() {
        val paused = engine.pause(start(), now(110_000L))
        val later = engine.sample(paused, now(710_000L))
        assertEquals(paused.clock.activeElapsedMs, later.clock.activeElapsedMs)
        assertEquals(paused.cumulativeDistanceKm, later.cumulativeDistanceKm)
        val resumed = engine.resume(later, now(710_000L))
        val choosing = engine.prepareLanding(resumed, now(720_000L))
        assertEquals(10, choosing.landingRangeMinutes)
        assertEquals(800.0 / 6.0, engine.landingRadiusKm(choosing), 1.0e-10)
        val generation = choosing.landingSelectionGeneration
        val changed = engine.changeLandingRange(choosing, 15)
        assertThrows(IllegalArgumentException::class.java) {
            engine.selectLanding(changed, airport(2L, "BBB", 1.0, 1.0), generation)
        }
        val returned = engine.returnToCruise(changed, now(900_000L))
        assertEquals(choosing.sessionId, returned.sessionId)
        assertEquals(choosing.clock.activeElapsedMs, returned.clock.activeElapsedMs)
        assertEquals(choosing.cumulativeDistanceKm, returned.cumulativeDistanceKm)
        assertNull(returned.landingAirport)
        assertEquals(FreeFlightPhase.CRUISING, returned.phase)
    }

    @Test fun arrivalDueIsNotPrematureSuccessfulSettlement() {
        val choosing = engine.prepareLanding(start(), now(109_000L))
        val selected = engine.selectLanding(choosing, airport(2L, "BBB", 0.0, 1.0), choosing.landingSelectionGeneration)
        // A measured source/vendor distance is an input; this test uses an exact 8km fixture.
        val approach = engine.confirmLanding(selected, selected.landingSelectionGeneration, 8.0, now(200_000L))
        assertEquals(36_000L, approach.approach?.durationMs)
        val halfway = engine.sample(approach, now(218_000L))
        assertEquals(choosing.cumulativeDistanceKm + 4.0, halfway.cumulativeDistanceKm, 1.0e-12)
        val due = engine.sample(halfway, now(236_000L))
        assertTrue(engine.landingReached(due))
        assertEquals(FreeFlightStatus.ONGOING, due.status)
        assertNull(due.endedAtWallMs)
        val complete = engine.completeLanding(due, now(237_000L))
        assertEquals(FreeFlightStatus.COMPLETED, complete.status)
        assertEquals(choosing.clock.activeElapsedMs + 37_000L, complete.clock.activeElapsedMs)
        assertEquals(choosing.cumulativeDistanceKm + 8.0, complete.cumulativeDistanceKm, 1.0e-12)
        val cancelled = engine.cancel(halfway, now(218_000L))
        assertEquals(FreeFlightStatus.CANCELLED, cancelled.status)
        assertEquals(halfway.cumulativeDistanceKm, cancelled.cumulativeDistanceKm)
        assertThrows(IllegalArgumentException::class.java) { engine.completeLanding(cancelled, now(240_000L)) }
    }

    @Test fun returnedCruiseDoesNotCountCameraTimeAndResumesAtItsActualCallback() {
        val choosing=engine.prepareLanding(start(),now(130_000L))
        val returning=engine.returnToCruise(choosing,now(200_000L))
        assertTrue(returning.cruiseReturnPending)
        assertEquals(FreeFlightPause.RETURN_CAMERA,returning.clock.pause)
        val during=engine.sample(returning,now(201_499L))
        assertEquals(choosing.clock.activeElapsedMs,during.clock.activeElapsedMs)
        assertEquals(choosing.cumulativeDistanceKm,during.cumulativeDistanceKm)
        val resumed=engine.completeReturnToCruise(during,now(201_500L))
        assertFalse(resumed.cruiseReturnPending);assertNull(resumed.clock.pause)
        val later=engine.sample(resumed,now(202_500L))
        assertEquals(choosing.clock.activeElapsedMs+1_000L,later.clock.activeElapsedMs)
        assertEquals(choosing.cumulativeDistanceKm+800.0/3600.0,later.cumulativeDistanceKm,1e-10)
        assertEquals(choosing.sessionId,later.sessionId)
    }

    @Test fun ownColdReturnRecoveryRequiresExplicitResumeAndOldArchivesDefaultToNoPendingCamera() {
        val returning=engine.returnToCruise(engine.prepareLanding(start(),now(130_000L)),now(200_000L))
        val read=FreeFlightArchiveCodec.decode(FreeFlightArchiveCodec.encode(returning))
        val restored=engine.restoreCheckpoint(read,now(1_000L,epoch="boot-2"))
        assertTrue(restored.cruiseReturnPending);assertEquals(FreeFlightPause.RECOVERY_REQUIRES_RESUME,restored.clock.pause)
        assertEquals(read.clock.activeElapsedMs,engine.sample(restored,now(61_000L,epoch="boot-2")).clock.activeElapsedMs)
        val requested=engine.resume(restored,now(61_000L,epoch="boot-2"))
        assertTrue(requested.cruiseReturnPending);assertEquals(FreeFlightPause.RETURN_CAMERA,requested.clock.pause)
        val old=FreeFlightArchiveCodec.encode(start()).replace(",\"cruiseReturnPending\":false","")
        assertFalse(FreeFlightArchiveCodec.decode(old).cruiseReturnPending)
        assertFalse(engine.cancel(returning,now(201_000L)).cruiseReturnPending)
    }

    @Test fun sourceExplicitResumeDuringTheCameraIsImmediateAndDoesNotReplaceThePendingCamera() {
        val returning=engine.returnToCruise(engine.prepareLanding(start(),now(130_000L)),now(200_000L))
        val explicit=engine.resume(returning,now(200_500L))
        assertTrue(explicit.cruiseReturnPending);assertNull(explicit.clock.pause)
        val pausedAgain=engine.pause(explicit,now(201_000L))
        assertEquals(returning.clock.activeElapsedMs+500L,pausedAgain.clock.activeElapsedMs)
        assertTrue(pausedAgain.cruiseReturnPending)
        val resumedByActualCallback=engine.completeReturnToCruise(pausedAgain,now(201_500L))
        assertNull(resumedByActualCallback.clock.pause);assertFalse(resumedByActualCallback.cruiseReturnPending)
        assertEquals(pausedAgain.clock.activeElapsedMs+1_000L,engine.sample(resumedByActualCallback,now(202_500L)).clock.activeElapsedMs)
    }

    @Test fun archiveRoundTripAndExplicitOwnRecoveryPolicy() {
        val active = engine.sample(start(), now(127_500L)).copy(revision = 3L, sampleSequence = 2L)
        val read = FreeFlightArchiveCodec.decode(FreeFlightArchiveCodec.encode(active))
        assertEquals(active, read)
        assertThrows(FreeFlightClockChanged::class.java) { engine.sample(read, now(1_000L, epoch = "boot-2")) }
        val restored = engine.restoreCheckpoint(read, now(1_000L, epoch = "boot-2"))
        assertEquals(active.clock.activeElapsedMs, restored.clock.activeElapsedMs)
        assertEquals(active.position, restored.position)
        assertEquals(FreeFlightPause.RECOVERY_REQUIRES_RESUME, restored.clock.pause)
        val notAutoCredited = engine.sample(restored, now(61_000L, epoch = "boot-2"))
        assertEquals(active.clock.activeElapsedMs, notAutoCredited.clock.activeElapsedMs)
    }

    @Test fun sourceDisplayThresholdsAreNotUsualMinuteThresholds() {
        val base = start()
        fun elapsed(seconds: Long, metres: Double) = FreeFlightDisplay.elapsed(base.copy(
            clock = base.clock.copy(activeElapsedMs = seconds * 1_000L), cumulativeDistanceKm = metres / 1_000.0,
        ))
        assertEquals(FreeFlightMetric(99, FreeFlightMetricUnit.SECONDS), elapsed(99, 9_999.0).time)
        assertEquals(FreeFlightMetric(1, FreeFlightMetricUnit.MINUTES), elapsed(100, 10_000.0).time)
        assertEquals(FreeFlightMetric(9_999, FreeFlightMetricUnit.METRES), elapsed(99, 9_999.0).distance)
        assertEquals(FreeFlightMetric(10, FreeFlightMetricUnit.KILOMETRES), elapsed(100, 10_000.0).distance)
    }

    @Test fun sourceGeographyKeepsPoleReflectionAndMapSeam() {
        val oneDegreeKm = FreeFlightNavigation.EARTH_RADIUS_KM * Math.PI / 180.0
        val east = FreeFlightNavigation.advance(FreeFlightPoint(0.0, 0.0), 90.0, oneDegreeKm)
        assertEquals(0.0, east.point.latitude, 1.0e-10)
        assertEquals(1.0, east.point.longitude, 1.0e-10)
        val reflected = FreeFlightNavigation.normalize(FreeFlightPoint(89.0, 10.0), 0.0)
        assertEquals(2.0 * FreeFlightNavigation.LATITUDE_BOUND - 89.0, reflected.point.latitude, 1.0e-10)
        assertEquals(-170.0, reflected.point.longitude, 1.0e-10)
        assertEquals(180.0, reflected.bearingDegrees, 1.0e-10)
        assertEquals(1L, reflected.boundaryCrossings)
        val a = FreeFlightPoint(0.0, 179.0)
        val b = FreeFlightPoint(0.0, -179.0)
        assertTrue(FreeFlightNavigation.approach(a, b, 0.5).point.longitude > 179.9)
        assertTrue(FreeFlightNavigation.approach(a, b, 0.5001).point.longitude < -179.9)
        assertEquals(a, FreeFlightNavigation.approach(a, b, 0.0).point)
        assertEquals(b, FreeFlightNavigation.approach(a, b, 1.0).point)
    }
}
