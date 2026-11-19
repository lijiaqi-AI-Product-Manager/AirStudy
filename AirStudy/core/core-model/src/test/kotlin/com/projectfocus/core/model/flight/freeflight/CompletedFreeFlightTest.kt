package com.projectfocus.core.model.flight.freeflight

import com.projectfocus.core.model.flight.PlannedAirport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompletedFreeFlightTest {
    private val engine = FreeFlightEngine()
    private val origin = PlannedAirport(1,"AAA","Departure","Origin","AA",0.0,0.0)
    private val destination = PlannedAirport(43341,"FR-0001","Emile Muller Hospital Heliport","Mulhouse","FR",47.725328,7.344995)
    private fun now(ms:Long) = FreeFlightClockReading(ms,ms,1_000_000+ms,"completion-test-boot")
    private fun start() = engine.start(FreeFlightStartRequest("completed-free","owner",origin,90.0,"01A",
        FreeFlightScene(2,"Reading","book")),now(0))
    private fun arrive(elapsedMs:Long):FreeFlightArchive {
        val selection = engine.prepareLanding(start(),now(elapsedMs))
        val picked = engine.selectLanding(selection,destination,selection.landingSelectionGeneration)
        val pending = engine.beginLandingWithoutMap(picked,picked.landingSelectionGeneration,now(elapsedMs))
        return engine.completeLandingWithoutMap(pending,now(elapsedMs))
    }

    @Test fun landingResultKeepsActualCruiseDistanceAndElapsedTimeWithoutInventingADirectRoute() {
        val arrived = arrive(73_001)
        val result = CompletedFreeFlight.from(FreeFlightArchiveCodec.decode(FreeFlightArchiveCodec.encode(arrived)))
        assertEquals(destination,result.destination)
        assertEquals(73_001L,result.elapsedMs)
        assertEquals(73L,result.durationSeconds)
        assertEquals(800.0*73_001/3_600_000.0,result.distanceKm,1e-10)
        assertEquals(1,result.displayFocusMinutes)
        assertEquals(arrived.endedAtWallMs,result.endedAtWallMs)
        assertNull(arrived.approach)
    }

    @Test fun displayedMinutesRoundTheActualClockWithTheMinimumOfOneMinute() {
        assertEquals(1,CompletedFreeFlight.from(arrive(0)).displayFocusMinutes)
        assertEquals(1,CompletedFreeFlight.from(arrive(89_999)).displayFocusMinutes)
        assertEquals(2,CompletedFreeFlight.from(arrive(90_000)).displayFocusMinutes)
    }

    @Test fun activeAndCancelledFlightsCannotBecomeArrivalResults() {
        assertThrows(IllegalArgumentException::class.java) { CompletedFreeFlight.from(start()) }
        assertThrows(IllegalArgumentException::class.java) { CompletedFreeFlight.from(engine.cancel(start(),now(20_000))) }
    }
}
