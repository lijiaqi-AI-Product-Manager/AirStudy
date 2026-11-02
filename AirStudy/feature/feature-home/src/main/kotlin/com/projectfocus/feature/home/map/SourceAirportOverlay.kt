package com.projectfocus.feature.home.map

import com.huawei.hms.maps.HuaweiMap
import com.huawei.hms.maps.model.LatLng
import com.huawei.hms.maps.model.Marker
import com.huawei.hms.maps.model.MarkerOptions

/** Real endpoint positions only. Original route/aircraft layers and camera policy remain unverified. */
internal class SourceAirportOverlay {
    private var origin: MapAirport? = null
    private var destination: MapAirport? = null
    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null

    fun render(map: HuaweiMap, input: MapRouteInput) {
        if (origin != input.origin) {
            originMarker?.remove()
            originMarker = input.origin?.let { add(map, it) }
            origin = input.origin
        }
        if (destination != input.destination) {
            destinationMarker?.remove()
            destinationMarker = input.destination?.let { add(map, it) }
            destination = input.destination
        }
    }

    private fun add(map: HuaweiMap, airport: MapAirport): Marker = requireNotNull(map.addMarker(
        MarkerOptions().position(LatLng(airport.latitude, airport.longitude)).title(airport.code).draggable(false),
    ))

    fun clear() {
        originMarker?.remove()
        destinationMarker?.remove()
        originMarker = null
        destinationMarker = null
        origin = null
        destination = null
    }
}
