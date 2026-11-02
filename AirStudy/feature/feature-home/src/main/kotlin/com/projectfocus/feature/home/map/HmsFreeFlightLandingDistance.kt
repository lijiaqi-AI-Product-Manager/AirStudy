package com.projectfocus.feature.home.map

import com.huawei.hms.maps.common.util.DistanceCalculator
import com.huawei.hms.maps.model.LatLng
import com.projectfocus.core.model.flight.freeflight.FreeFlightPoint
import com.projectfocus.service.focus.freeflight.FreeFlightLandingDistance
import javax.inject.Inject
import kotlin.math.abs

/** Uses the actual matching HMS provider; it never substitutes planned-route math. */
class HmsFreeFlightLandingDistance @Inject constructor() : FreeFlightLandingDistance {
    override suspend fun kilometres(start: FreeFlightPoint, destination: FreeFlightPoint): Double {
        val metres = DistanceCalculator.computeDistanceBetween(
            LatLng(start.latitude, start.longitude),
            LatLng(destination.latitude, destination.longitude),
        )
        return checkedKilometres(start, destination, metres)
    }

    internal fun checkedKilometres(
        start: FreeFlightPoint,
        destination: FreeFlightPoint,
        metres: Double,
    ): Double {
        check(metres.isFinite() && metres >= 0.0) { "HMS distance is unavailable" }
        // The public facade can silently return zero when a provider cannot be loaded.
        check(metres != 0.0 || samePhysicalPoint(start, destination)) {
            "HMS returned zero for distinct endpoints"
        }
        return metres / 1_000.0
    }

    private fun samePhysicalPoint(a: FreeFlightPoint, b: FreeFlightPoint): Boolean {
        if (a.latitude != b.latitude) return false
        if (abs(a.latitude) == 90.0) return true
        val difference = abs(a.longitude - b.longitude)
        return difference == 0.0 || difference == 360.0
    }
}
