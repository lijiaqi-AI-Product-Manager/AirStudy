package com.projectfocus.feature.home.map

data class MapAirport(val code: String, val latitude: Double, val longitude: Double) {
    init {
        require(code.isNotBlank())
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

/** Credentials are runtime inputs, never Parcelable/SavedState data or diagnostic text. */
data class MapConfiguration(val apiKey: String, val appId: String? = null) {
    override fun toString(): String = "MapConfiguration(apiKey=<redacted>, appId=<redacted>)"
}

enum class MapPhase { MISSING_CONFIGURATION, WAITING, CREATED, READY, LOADED, FAILED, DESTROYED }
enum class MapStyleStatus { PENDING, APPLIED, REJECTED }
data class MapHostState(
    val phase: MapPhase = MapPhase.MISSING_CONFIGURATION,
    val styleStatus: MapStyleStatus = MapStyleStatus.PENDING,
    val requestedStyleIndex: Int = 0,
)

internal data class MapRouteInput(val origin: MapAirport?, val destination: MapAirport?, val progress: Float)
