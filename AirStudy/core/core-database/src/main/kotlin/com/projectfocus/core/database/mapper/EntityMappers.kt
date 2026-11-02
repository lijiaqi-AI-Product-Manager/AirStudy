package com.projectfocus.core.database.mapper

import com.projectfocus.core.database.converter.Converters
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.database.entity.BlockedAppEntity
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.database.entity.FocusCategoryEntity
import com.projectfocus.core.database.entity.StampEntity
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.core.model.airport.AirportType
import com.projectfocus.core.model.flight.FlightHistory
import com.projectfocus.core.model.flight.FlightStatus
import com.projectfocus.core.model.flight.FocusType
import com.projectfocus.core.model.focus.BlockedApp
import com.projectfocus.core.model.focus.BlockedAppType
import com.projectfocus.core.model.focus.FocusCategory
import com.projectfocus.core.model.stamp.Stamp


object EntityMappers {

    // ═══════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════

    fun AirportEntity.toDomain(): Airport = Airport(
        id = id,
        iataCode = iataCode.takeIf { it.isNotBlank() && it != "\\N" },
        icaoCode = icaoCode.takeIf { it.isNotBlank() && it != "\\N" },
        name = name,
        city = city.takeIf { it.isNotBlank() },
        country = country.takeIf { it.isNotBlank() },
        latitude = latitude,
        longitude = longitude,
        elevation = elevation,
        continent = continent.takeIf { it.isNotBlank() },
        type = AirportType.fromRaw(type),
        popularity = popularity,
        isPremiumOnly = isPremiumOnly,
    )

    fun Airport.toEntity(): AirportEntity = AirportEntity(
        id = id,
        iataCode = iataCode.orEmpty(),
        icaoCode = icaoCode.orEmpty(),
        name = name,
        city = city.orEmpty(),
        country = country.orEmpty(),
        latitude = latitude,
        longitude = longitude,
        elevation = elevation ?: 0,
        continent = continent.orEmpty(),
        type = type.toRaw(),
        popularity = popularity,
        isPremiumOnly = isPremiumOnly,
    )


    private fun AirportType.toRaw(): String = when (this) {
        AirportType.LARGE_AIRPORT -> "large_airport"
        AirportType.MEDIUM_AIRPORT -> "medium_airport"
        AirportType.SMALL_AIRPORT -> "small_airport"
        AirportType.HELIPORT -> "heliport"
        AirportType.SEA_PLANE_BASE -> "seaplane_base"
        AirportType.CLOSED -> "closed"
        AirportType.UNKNOWN -> "unknown"
    }

    // ═══════════════════════════════════════════════════════
    // FlightHistory（31 列 + 2 修复列）
    // ═══════════════════════════════════════════════════════

    fun FlightHistoryEntity.toDomain(): FlightHistory = FlightHistory(
        id = id,
        roomId = roomId,
        focusType = FocusType.fromRaw(focusType),
        roomParticipantCount = roomParticipantCount,

        departureAirportCode = departureAirportCode,
        departureAirportName = departureAirportName,
        departureCity = departureCity,
        departureCountry = departureCountry,
        departureLatitude = departureLatitude,
        departureLongitude = departureLongitude,

        destinationAirportCode = destinationAirportCode,
        destinationAirportName = destinationAirportName,
        destinationCity = destinationCity,
        destinationCountry = destinationCountry,
        destinationLatitude = destinationLatitude,
        destinationLongitude = destinationLongitude,

        startTimeMillis = startTimeMillis,
        endTimeMillis = endTimeMillis,
        durationMinutes = durationMinutes,
        actualFocusMinutes = actualFocusMinutes,
        distanceKm = distanceKm,

        // 未知状态（脏数据）兜底为 CANCELLED —— 宁可在列表里显灰，也不让整页崩
        status = FlightStatus.fromRaw(status) ?: FlightStatus.CANCELLED,

        categoryId = categoryId,
        seat = seat,

        hasAnnouncedDeparture = hasAnnouncedDeparture,
        hasAnnouncedDescent = hasAnnouncedDescent,
        hasAnnouncedArrival = hasAnnouncedArrival,

        log = Converters().logFromJson(logJson),
        isSynced = isSynced,

        createdAt = createdAt,
        updatedAt = updatedAt,

        quote = quote,
        quoteAuthor = quoteAuthor,
    )

    fun FlightHistory.toEntity(): FlightHistoryEntity = FlightHistoryEntity(
        id = id,
        roomId = roomId,
        focusType = focusType.name,
        roomParticipantCount = roomParticipantCount,

        departureAirportCode = departureAirportCode,
        departureAirportName = departureAirportName,
        departureCity = departureCity,
        departureCountry = departureCountry,
        departureLatitude = departureLatitude,
        departureLongitude = departureLongitude,

        destinationAirportCode = destinationAirportCode,
        destinationAirportName = destinationAirportName,
        destinationCity = destinationCity,
        destinationCountry = destinationCountry,
        destinationLatitude = destinationLatitude,
        destinationLongitude = destinationLongitude,

        startTimeMillis = startTimeMillis,
        endTimeMillis = endTimeMillis,
        durationMinutes = durationMinutes,
        actualFocusMinutes = actualFocusMinutes,
        distanceKm = distanceKm,

        status = status.name,

        categoryId = categoryId,
        seat = seat,

        hasAnnouncedDeparture = hasAnnouncedDeparture,
        hasAnnouncedDescent = hasAnnouncedDescent,
        hasAnnouncedArrival = hasAnnouncedArrival,

        logJson = Converters().logToJson(log),
        isSynced = isSynced,

        createdAt = createdAt,
        updatedAt = updatedAt,

        quote = quote,
        quoteAuthor = quoteAuthor,
    )

    // ═══════════════════════════════════════════════════════
    // FocusCategory
    // ═══════════════════════════════════════════════════════

    fun FocusCategoryEntity.toDomain(): FocusCategory = FocusCategory(
        id = id,
        name = name,
        colorHex = colorHex,
        emoji = emoji,
        isDefault = isDefault,
        sortOrder = sortOrder,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun FocusCategory.toEntity(): FocusCategoryEntity = FocusCategoryEntity(
        id = id,
        name = name,
        colorHex = colorHex,
        emoji = emoji,
        isDefault = isDefault,
        sortOrder = sortOrder,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    // ═══════════════════════════════════════════════════════
    // BlockedApp
    // ═══════════════════════════════════════════════════════

    fun BlockedAppEntity.toDomain(): BlockedApp = BlockedApp(
        id = id,
        categoryId = categoryId,
        packageName = packageName,
        appName = appName,
        type = BlockedAppType.fromRaw(type),
        createdAt = createdAt,
    )

    fun BlockedApp.toEntity(): BlockedAppEntity = BlockedAppEntity(
        id = id,
        categoryId = categoryId,
        packageName = packageName,
        appName = appName,
        type = type.name,
        createdAt = createdAt,
    )

    // ═══════════════════════════════════════════════════════
    // Stamp
    // ═══════════════════════════════════════════════════════

    fun StampEntity.toDomain(): Stamp = Stamp(
        id = id,
        countryName = countryName,
        countryCode = countryCode,
        firstVisitDate = firstVisitDate,
        latestVisitDate = latestVisitDate,
        visitCount = visitCount,
        focusHours = focusHours,
        illustrationKey = illustrationResId.takeIf { it.isNotBlank() },
        airportCodes = Converters().parseJsonArray(airportCodes),
        unlockedLandmarkIds = Converters().parseJsonArray(unlockedLandmarkIds),
    )

    fun Stamp.toEntity(): StampEntity = StampEntity(
        id = id,
        countryName = countryName,
        countryCode = countryCode,
        firstVisitDate = firstVisitDate,
        latestVisitDate = latestVisitDate,
        visitCount = visitCount,
        focusHours = focusHours,
        illustrationResId = illustrationKey.orEmpty(),
        airportCodes = Converters.toJsonArray(airportCodes),
        unlockedLandmarkIds = Converters.toJsonArray(unlockedLandmarkIds),
    )
}
