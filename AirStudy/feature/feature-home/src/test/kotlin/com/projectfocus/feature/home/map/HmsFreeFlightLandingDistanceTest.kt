package com.projectfocus.feature.home.map

import com.huawei.hms.maps.common.util.DistanceCalculator
import com.huawei.hms.maps.model.LatLng
import com.huawei.hms.maps.utils.MapsAdvUtil
import com.projectfocus.core.model.flight.freeflight.FreeFlightPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual public SDK calls, with no map key, map initialization, delegate stub or fallback. */
@RunWith(RobolectricTestRunner::class)
// The Android vendor AAR omits JVM StackMapTable frames. Let the real sandbox
// instrument these provider classes and compute valid frames; no SDK shadow is supplied.
@Config(sdk = [34], instrumentedPackages = ["com.huawei.hms.maps.provider"])
class HmsFreeFlightLandingDistanceTest {
    private val distance = HmsFreeFlightLandingDistance()

    @Test fun realEmbeddedProviderReturnsNonzeroMetresAndPreservesDoubleKilometres() = runBlocking {
        assertTrue("The matching maps-basic provider must actually be present", MapsAdvUtil.containsMapsBasic())
        val start = FreeFlightPoint(0.0, 0.0)
        val end = FreeFlightPoint(0.0, 1.0)
        val metres = DistanceCalculator.computeDistanceBetween(LatLng(0.0, 0.0), LatLng(0.0, 1.0))
        // An equatorial degree on the source SDK's 6378137m sphere is a known boundary fixture.
        assertEquals(111_319.49079327357, metres, 1.0e-6)
        val kilometres = distance.kilometres(start, end)
        assertEquals(metres / 1_000.0, kilometres, 0.0)
        assertTrue(kilometres > 111.0)
        println("ACTUAL_HMS_DISTANCE equatorial_m=$metres kilometre=$kilometres")
    }

    @Test fun realSamePointReturnsZeroWithoutInventingAnApproach() = runBlocking {
        val point = FreeFlightPoint(39.9042, 116.4074)
        assertEquals(0.0, distance.kilometres(point, point), 0.0)
    }

    @Test fun realDatelineRouteUsesTheShortDistance() = runBlocking {
        val kilometres = distance.kilometres(FreeFlightPoint(0.0, 179.0), FreeFlightPoint(0.0, -179.0))
        assertEquals(222.63898158654712, kilometres, 1.0e-8)
        println("ACTUAL_HMS_DISTANCE dateline_km=$kilometres")
    }

    @Test fun realPolarRouteIsFiniteAndNonzero() = runBlocking {
        val kilometres = distance.kilometres(FreeFlightPoint(85.0, 179.0), FreeFlightPoint(85.0, -179.0))
        assertTrue(kilometres.isFinite() && kilometres > 19.0 && kilometres < 20.0)
        println("ACTUAL_HMS_DISTANCE polar_km=$kilometres")
    }

    @Test fun silentZeroForDistinctEndpointsCannotBecomeASuccessfulLanding() {
        val a = FreeFlightPoint(0.0, 0.0)
        val b = FreeFlightPoint(0.0, 1.0)
        assertThrows(IllegalStateException::class.java) { distance.checkedKilometres(a, b, 0.0) }
        assertEquals(0.0, distance.checkedKilometres(FreeFlightPoint(0.0, 180.0), FreeFlightPoint(0.0, -180.0), 0.0), 0.0)
        assertEquals(0.0, distance.checkedKilometres(FreeFlightPoint(90.0, 15.0), FreeFlightPoint(90.0, -80.0), 0.0), 0.0)
    }

    @Test fun invalidProviderValuesFailInsteadOfBeingConvertedToDistance() {
        val a = FreeFlightPoint(0.0, 0.0)
        val b = FreeFlightPoint(1.0, 1.0)
        for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalStateException::class.java) { distance.checkedKilometres(a, b, invalid) }
        }
    }
}
