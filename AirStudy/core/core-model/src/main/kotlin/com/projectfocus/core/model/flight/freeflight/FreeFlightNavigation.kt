package com.projectfocus.core.model.flight.freeflight

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan


object FreeFlightNavigation {
    const val SPEED_KM_PER_HOUR = 800.0
    const val EARTH_RADIUS_KM = 6371.0088
    const val LATITUDE_BOUND = 85.05112878
    private const val DISTANCE_EPSILON = 1.0e-9
    private const val ANGLE_EPSILON = 1.0e-12

    data class Projection(
        val point: FreeFlightPoint,
        val bearingDegrees: Double,
        val boundaryCrossings: Long,
    )

    fun bearing(degrees: Double): Double {
        require(degrees.isFinite()) // Own input rejection; no source invalid-input (0,0) fallback.
        val value = degrees % 360.0
        return if (value < 0.0) value + 360.0 else value
    }

    fun longitude(degrees: Double): Double {
        require(degrees.isFinite())
        var value = degrees % 360.0
        if (value > 180.0) value -= 360.0
        return if (value < -180.0) value + 360.0 else value
    }

    fun normalize(point: FreeFlightPoint, heading: Double): Projection {
        var latitude = point.latitude.coerceIn(-90.0, 90.0)
        var currentLongitude = longitude(point.longitude)
        var direction = bearing(heading)
        var crossings = 0L
        while (latitude > LATITUDE_BOUND || latitude < -LATITUDE_BOUND) {
            latitude = (if (latitude > LATITUDE_BOUND) 2.0 * LATITUDE_BOUND else -2.0 * LATITUDE_BOUND) - latitude
            currentLongitude = longitude(currentLongitude + 180.0)
            direction = bearing(180.0 - direction)
            crossings++
        }
        return Projection(FreeFlightPoint(latitude, currentLongitude), direction, crossings)
    }

    fun advance(point: FreeFlightPoint, heading: Double, distanceKm: Double): Projection {
        require(distanceKm.isFinite() && distanceKm >= 0.0)
        val start = normalize(point, heading)
        var current = start.point
        var direction = start.bearingDegrees
        var crossings = start.boundaryCrossings
        var remaining = distanceKm
        while (remaining > DISTANCE_EPSILON) {
            val north = cos(Math.toRadians(direction))
            if (abs(north) <= ANGLE_EPSILON) {
                current = step(current, direction, remaining)
                break
            }
            val edge = if (north > 0.0) LATITUDE_BOUND else -LATITUDE_BOUND
            val toEdgeKm = max(0.0, Math.toRadians(edge - current.latitude) / north) * EARTH_RADIUS_KM
            if (remaining + DISTANCE_EPSILON < toEdgeKm) {
                current = step(current, direction, remaining)
                break
            }
            if (toEdgeKm > DISTANCE_EPSILON) current = step(current, direction, toEdgeKm)
            current = FreeFlightPoint(edge, longitude(current.longitude + 180.0))
            direction = bearing(180.0 - direction)
            crossings = Math.addExact(crossings, 1L)
            remaining = max(0.0, remaining - toEdgeKm)
        }
        return Projection(current, direction, crossings)
    }

    private fun step(point: FreeFlightPoint, heading: Double, distanceKm: Double): FreeFlightPoint {
        if (distanceKm <= 0.0) return point.copy(longitude = longitude(point.longitude))
        val angular = distanceKm / EARTH_RADIUS_KM
        val theta = Math.toRadians(bearing(heading))
        val latitude = Math.toRadians(point.latitude)
        val limit = Math.toRadians(LATITUDE_BOUND)
        val nextLatitude = min(limit, max(-limit, latitude + cos(theta) * angular))
        val deltaPsi = ln(tan(nextLatitude / 2.0 + Math.PI / 4.0) / tan(latitude / 2.0 + Math.PI / 4.0))
        val q = if (abs(deltaPsi) > ANGLE_EPSILON) (nextLatitude - latitude) / deltaPsi else cos(latitude)
        val deltaLongitude = if (abs(q) > ANGLE_EPSILON) sin(theta) * angular / q else 0.0
        return FreeFlightPoint(
            Math.toDegrees(nextLatitude),
            longitude(point.longitude + Math.toDegrees(deltaLongitude)),
        )
    }

    /** Source utils.l.P uses a rhumb bearing, including the wrapped longitude delta. */
    fun routeBearing(start: FreeFlightPoint, end: FreeFlightPoint): Double {
        if (start == end) return 0.0
        return bearing(Math.toDegrees(atan2(
            Math.toRadians(longitude(end.longitude - start.longitude)),
            mercator(end.latitude) - mercator(start.latitude),
        )))
    }

    /** Source utils.l.h; used for seam segment weighting, not as a fabricated SDK result. */
    private fun seamDistanceKm(start: FreeFlightPoint, end: FreeFlightPoint): Double {
        val a = Math.toRadians(start.latitude)
        val b = Math.toRadians(end.latitude)
        val halfLat = (b - a) / 2.0
        val halfLng = Math.toRadians(longitude(end.longitude - start.longitude)) / 2.0
        val h = sin(halfLng) * sin(halfLng) * cos(b) * cos(a) + sin(halfLat) * sin(halfLat)
        return atan2(sqrt(h), sqrt(max(0.0, 1.0 - h))) * (2.0 * EARTH_RADIUS_KM)
    }

    private fun mercator(latitude: Double): Double =
        ln(tan(Math.toRadians(latitude.coerceIn(-LATITUDE_BOUND, LATITUDE_BOUND)) / 2.0 + Math.PI / 4.0))

    /** Source utils.l.x, not latitude/longitude linear interpolation. */
    private fun interpolate(start: FreeFlightPoint, end: FreeFlightPoint, progress: Double): FreeFlightPoint {
        val p = progress.coerceIn(0.0, 1.0)
        val y = mercator(start.latitude)
        return FreeFlightPoint(
            Math.toDegrees(atan(exp(y + (mercator(end.latitude) - y) * p)) * 2.0 - Math.PI / 2.0),
            longitude(start.longitude + longitude(end.longitude - start.longitude) * p),
        )
    }

    /**
     * Source y4.a.v/m0 + l4.b.a: split the wrapped map seam at +/-179.9999,
     * weight the two pieces by the source haversine helper, then interpolate Mercator.
     */
    fun approachSegments(start: FreeFlightPoint, end: FreeFlightPoint): List<List<FreeFlightPoint>> {
        val delta = longitude(end.longitude - start.longitude)
        val unwrappedEnd = start.longitude + delta
        if (unwrappedEnd <= 180.000000000001 && unwrappedEnd >= -180.000000000001) return listOf(listOf(start, end))
        val edge = if (unwrappedEnd > 180.0) 179.9999 else -179.9999
        val split = if (abs(delta) <= ANGLE_EPSILON) 0.5 else ((edge - start.longitude) / delta).coerceIn(0.0, 1.0)
        val latitude = interpolate(start, end, split).latitude
        return listOf(listOf(start, FreeFlightPoint(latitude, edge)), listOf(FreeFlightPoint(latitude, -edge), end))
    }

    fun approach(start: FreeFlightPoint, end: FreeFlightPoint, progress: Double): Projection {
        require(progress.isFinite())
        val p = progress.coerceIn(0.0, 1.0)
        val delta = longitude(end.longitude - start.longitude)
        val unwrappedEnd = start.longitude + delta
        val crossing = unwrappedEnd > 180.000000000001 || unwrappedEnd < -180.000000000001
        if (!crossing) {
            val position = when {
                p <= 0.0 -> start
                p >= 1.0 -> end
                else -> interpolate(start, end, p)
            }
            return Projection(position, routeBearing(start, end), 0L)
        }
        val edge = if (unwrappedEnd > 180.0) 179.9999 else -179.9999
        val split = if (abs(delta) <= ANGLE_EPSILON) 0.5 else ((edge - start.longitude) / delta).coerceIn(0.0, 1.0)
        val latitude = interpolate(start, end, split).latitude
        val beforeSeam = FreeFlightPoint(latitude, edge)
        val afterSeam = FreeFlightPoint(latitude, -edge)
        val firstDistance = seamDistanceKm(start, beforeSeam)
        val total = firstDistance + seamDistanceKm(afterSeam, end)
        val firstWeight = (if (total > ANGLE_EPSILON) firstDistance / total else 0.5)
            .coerceIn(ANGLE_EPSILON, 0.999999999999)
        val firstPiece = p <= firstWeight
        val position = when {
            p <= 0.0 -> start
            p >= 1.0 -> end
            firstPiece -> interpolate(start, beforeSeam, p / firstWeight)
            else -> interpolate(afterSeam, end, (p - firstWeight) / (1.0 - firstWeight))
        }
        return Projection(position, if (firstPiece) routeBearing(start, beforeSeam) else routeBearing(afterSeam, end), if (firstPiece) 0L else 1L)
    }
}
