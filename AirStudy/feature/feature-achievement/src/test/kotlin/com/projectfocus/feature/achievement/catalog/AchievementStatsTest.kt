package com.projectfocus.feature.achievement.catalog

import com.projectfocus.core.database.entity.FlightHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementStatsTest {

    @Test
    fun empty_rows_yield_zero_stats() {
        val stats = AchievementStats.from(emptyList())

        assertEquals(0, stats.completedCount)
        assertEquals(0.0, stats.totalDistanceKm, 0.0)
        assertEquals(0, stats.visitedCityCount)
        assertEquals(0, stats.totalFocusMinutes)
        assertEquals(0, stats.averageFocusMinutes)
    }

    @Test
    fun only_completed_rows_are_counted() {
        val stats = AchievementStats.from(
            listOf(
                row(id = "a", status = "COMPLETED", distanceKm = 100.0, minutes = 60),
                row(id = "b", status = "ONGOING", distanceKm = 999.0, minutes = 999),
                row(id = "c", status = "CANCELLED", distanceKm = 999.0, minutes = 999),
                row(id = "d", status = "COMPLETED", distanceKm = 200.0, minutes = 120),
            ),
        )

        assertEquals(2, stats.completedCount)
        assertEquals(300.0, stats.totalDistanceKm, 0.0)
        assertEquals(180, stats.totalFocusMinutes)
        assertEquals(90, stats.averageFocusMinutes)
    }

    @Test
    fun cities_are_trimmed_deduplicated_and_blank_ignored() {
        val stats = AchievementStats.from(
            listOf(
                row(id = "a", city = " 北京 "),
                row(id = "b", city = "北京"),
                row(id = "c", city = ""),
                row(id = "d", city = "   "),
                row(id = "e", city = "上海"),
            ),
        )

        assertEquals(2, stats.visitedCityCount)
    }

    @Test
    fun average_focus_minutes_are_floored() {
        val stats = AchievementStats.from(
            listOf(
                row(id = "a", minutes = 100),
                row(id = "b", minutes = 101),
            ),
        )

        // (100 + 101) / 2 = 100.5 -> 100
        assertEquals(100, stats.averageFocusMinutes)
    }

    @Test
    fun average_is_zero_when_all_rows_are_not_completed() {
        val stats = AchievementStats.from(
            listOf(
                row(id = "a", status = "ONGOING", minutes = 500),
                row(id = "b", status = "CANCELLED", minutes = 500),
            ),
        )

        assertEquals(0, stats.completedCount)
        assertEquals(0, stats.totalFocusMinutes)
        assertEquals(0, stats.averageFocusMinutes)
    }

    private fun row(
        id: String,
        status: String = "COMPLETED",
        city: String = "北京",
        minutes: Int = 0,
        distanceKm: Double = 0.0,
    ): FlightHistoryEntity = FlightHistoryEntity(
        id = id,
        roomId = null,
        focusType = "SOLO",
        roomParticipantCount = null,
        departureAirportCode = "PEK",
        departureAirportName = "Beijing Capital",
        departureCity = "北京",
        departureCountry = "CN",
        departureLatitude = 40.0801,
        departureLongitude = 116.5846,
        destinationAirportCode = "SHA",
        destinationAirportName = "Shanghai Hongqiao",
        destinationCity = city,
        destinationCountry = "CN",
        destinationLatitude = 31.1979,
        destinationLongitude = 121.3363,
        startTimeMillis = 1_700_000_000_000L,
        endTimeMillis = 1_700_000_060_000L,
        durationMinutes = minutes,
        actualFocusMinutes = minutes,
        distanceKm = distanceKm,
        status = status,
        categoryId = null,
        seat = null,
        hasAnnouncedDeparture = false,
        hasAnnouncedDescent = false,
        hasAnnouncedArrival = false,
        logJson = null,
        isSynced = false,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
    )
}
