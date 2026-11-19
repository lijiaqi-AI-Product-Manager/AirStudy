package com.projectfocus.feature.airport.ui

import android.os.Bundle
import com.projectfocus.core.common.flight.FlightMath
import com.projectfocus.core.model.airport.Airport


data class AirportReferencePoint private constructor(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }

    companion object {
        fun from(latitude: Double, longitude: Double): AirportReferencePoint? =
            if (latitude.isFinite() && latitude in -90.0..90.0 &&
                longitude.isFinite() && longitude in -180.0..180.0
            ) AirportReferencePoint(latitude, longitude) else null
    }
}

/** Shared feature/host Bundle contract; incomplete or invalid doubles mean no reference. */
object AirportSearchReferenceArgs {
    const val LATITUDE = "arg_airport_reference_latitude"
    const val LONGITUDE = "arg_airport_reference_longitude"

    fun readFrom(arguments: Bundle?): AirportReferencePoint? = arguments?.let {
        AirportReferencePoint.from(it.getDouble(LATITUDE, Double.NaN), it.getDouble(LONGITUDE, Double.NaN))
    }

    fun writeTo(arguments: Bundle, reference: AirportReferencePoint?) {
        if (reference == null) {
            arguments.remove(LATITUDE)
            arguments.remove(LONGITUDE)
        } else {
            arguments.putDouble(LATITUDE, reference.latitude)
            arguments.putDouble(LONGITUDE, reference.longitude)
        }
    }
}


internal object AirportCardFlightTime {
    fun minutes(reference: AirportReferencePoint?, airport: Airport): Int? {
        reference ?: return null
        if (!airport.latitude.isFinite() || airport.latitude !in -90.0..90.0 ||
            !airport.longitude.isFinite() || airport.longitude !in -180.0..180.0
        ) return null
        val km = FlightMath.airportDistanceKm(
            reference.latitude, reference.longitude, airport.latitude, airport.longitude,
        ).toDouble()
        return km.takeIf { it.isFinite() && it >= 0.0 }?.let(FlightMath::minutesForDistanceKm)
    }


    fun format(minutes: Int?): String = when {
        minutes == null || minutes < 0 -> "--"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h${minutes % 60}m"
    }
}
