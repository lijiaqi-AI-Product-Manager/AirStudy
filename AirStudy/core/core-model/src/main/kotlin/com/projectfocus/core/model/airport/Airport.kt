package com.projectfocus.core.model.airport


data class Airport(
    val id: Long,
    val iataCode: String?,
    val icaoCode: String?,
    val name: String,
    val city: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
    val elevation: Int?,
    val continent: String?,
    val type: AirportType,
    val popularity: Int,
    val isPremiumOnly: Boolean,


    val nameZh: String? = null,

    /** 中文城市名（如「北京」）。见 [nameZh] 的说明 */
    val cityZh: String? = null,
) {
    /** 展示用主键：优先 IATA（乘客认这个），退化为 ICAO，再退化为 name */
    val displayCode: String
        get() = iataCode?.takeIf { it.isNotBlank() }
            ?: icaoCode?.takeIf { it.isNotBlank() }
            ?: name

    /** "PEK · Beijing Capital International" */
    fun label(): String = if (city.isNullOrBlank()) "$displayCode · $name" else "$displayCode · $city"
}

enum class AirportType {
    LARGE_AIRPORT,
    MEDIUM_AIRPORT,
    SMALL_AIRPORT,
    HELIPORT,
    SEA_PLANE_BASE,
    CLOSED,
    UNKNOWN,
    ;

    companion object {
        fun fromRaw(raw: String?): AirportType = when (raw?.lowercase()) {
            "large_airport" -> LARGE_AIRPORT
            "medium_airport" -> MEDIUM_AIRPORT
            "small_airport" -> SMALL_AIRPORT
            "heliport" -> HELIPORT
            "seaplane_base" -> SEA_PLANE_BASE
            "closed" -> CLOSED
            else -> UNKNOWN
        }
    }
}


data class AirportWithFlightTime(
    val airport: Airport,
    val distanceKm: Double,
    val flightTimeMinutes: Int,
)
