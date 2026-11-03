package com.projectfocus.core.common.plan

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SourceFlightPlanDatesTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `Shanghai GET stays local while a new UPSERT uses previous UTC date`() {
        val date = LocalDate.of(2026, 10, 31)
        assertEquals(
            SourceFlightPlanDates.DailyFields("2026-10-31", "2026-10-31", "Asia/Shanghai", 480),
            SourceFlightPlanDates.get(date, shanghai),
        )
        assertEquals(
            SourceFlightPlanDates.DailyFields("2026-10-30", "2026-10-31", "Asia/Shanghai", 480),
            SourceFlightPlanDates.upsert(date, shanghai),
        )
    }

    @Test
    fun `UTC GET and UPSERT keep equal dates and zero signed offset`() {
        val date = LocalDate.of(2026, 10, 31)
        val expected = SourceFlightPlanDates.DailyFields("2026-10-31", "2026-10-31", "UTC", 0)
        assertEquals(expected, SourceFlightPlanDates.get(date, utc))
        assertEquals(expected, SourceFlightPlanDates.upsert(date, utc))
    }

    @Test
    fun `midnight conversion crosses both year boundary and leap February`() {
        assertEquals("2026-12-31", SourceFlightPlanDates.upsert(LocalDate.of(2027, 1, 1), shanghai).planDate)
        val leap = SourceFlightPlanDates.upsert(LocalDate.of(2024, 3, 1), shanghai)
        assertEquals("2024-02-29", leap.planDate)
        assertEquals("2024-03-01", leap.localPlanDate)
        assertEquals("2025-02-28", SourceFlightPlanDates.upsert(LocalDate.of(2025, 3, 1), shanghai).planDate)
    }

    @Test
    fun `remote cached plan date is retained without substituting selected local date`() {
        val selected = LocalDate.of(2026, 10, 31)
        val cached = SourceFlightPlanDates.upsert(selected, shanghai, " \t2026-10-29\r\n")
        assertEquals("2026-10-29", cached.planDate)
        assertEquals("2026-10-31", cached.localPlanDate)
        assertEquals(480, cached.timezoneOffsetMinutes)
        assertEquals("2026-10-30", SourceFlightPlanDates.upsert(selected, shanghai, " \t\n").planDate)
        // Java trim does not remove NBSP. Keep the opaque nonblank source cache;
        // it is not this helper's job to pretend that an invalid remote date is valid.
        assertEquals("\u00A0", SourceFlightPlanDates.upsert(selected, shanghai, "\u00A0").planDate)
    }

    @Test
    fun `spring DST offset comes from local noon after the overnight change`() {
        val date = LocalDate.of(2026, 3, 8)
        val newYork = ZoneId.of("America/New_York")
        // Midnight is still EST, but the source R() reference is noon, already EDT.
        assertEquals(-300, date.atStartOfDay(newYork).offset.totalSeconds / 60)
        assertEquals(-240, SourceFlightPlanDates.offsetMinutes(date, newYork))
        assertEquals(
            SourceFlightPlanDates.DailyFields("2026-03-08", "2026-03-08", "America/New_York", -240),
            SourceFlightPlanDates.upsert(date, newYork),
        )
    }

    @Test
    fun `fall DST offset comes from local noon after summer time ends`() {
        val date = LocalDate.of(2026, 11, 1)
        val newYork = ZoneId.of("America/New_York")
        assertEquals(-240, date.atStartOfDay(newYork).offset.totalSeconds / 60)
        assertEquals(-300, SourceFlightPlanDates.offsetMinutes(date, newYork))
        assertEquals(-300, SourceFlightPlanDates.get(date, newYork).timezoneOffsetMinutes)
    }

    @Test
    fun `non hour timezone offset is kept in minutes with its caller supplied zone id`() {
        val date = LocalDate.of(2026, 10, 31)
        assertEquals(
            SourceFlightPlanDates.DailyFields("2026-10-30", "2026-10-31", "Asia/Kathmandu", 345),
            SourceFlightPlanDates.upsert(date, ZoneId.of("Asia/Kathmandu")),
        )
    }

    @Test
    fun `current month past history includes device tomorrow across a month boundary`() {
        assertEquals(
            SourceFlightPlanDates.HistoryRange("2026-01-01", "2026-02-01", "Asia/Shanghai", 200),
            SourceFlightPlanDates.history(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 31), shanghai),
        )
    }

    @Test
    fun `older month history uses its own final day including leap day and previous year`() {
        assertEquals(
            SourceFlightPlanDates.HistoryRange("2024-02-01", "2024-02-29", "UTC", 200),
            SourceFlightPlanDates.history(LocalDate.of(2024, 2, 14), LocalDate.of(2024, 3, 1), utc),
        )
        assertEquals(
            SourceFlightPlanDates.HistoryRange("2026-12-01", "2026-12-31", "UTC", 200),
            SourceFlightPlanDates.history(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1), utc),
        )
    }

    @Test
    fun `history partition uses injected device today and not the plan timezone today`() {
        val date = LocalDate.of(2026, 3, 1)
        assertNull(SourceFlightPlanDates.history(date, date, shanghai))
        assertNull(SourceFlightPlanDates.history(date.plusDays(1), date, shanghai))
        assertEquals(
            SourceFlightPlanDates.HistoryRange("2026-03-01", "2026-03-03", "Pacific/Honolulu", 200),
            SourceFlightPlanDates.history(date, date.plusDays(1), ZoneId.of("Pacific/Honolulu")),
        )
    }

    @Test
    fun `explicit plan zone and US wire digits do not change with process defaults`() {
        val oldZone = TimeZone.getDefault()
        val oldLocale = Locale.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            assertEquals(
                SourceFlightPlanDates.DailyFields("2026-10-30", "2026-10-31", "Asia/Shanghai", 480),
                SourceFlightPlanDates.upsert(LocalDate.of(2026, 10, 31), shanghai),
            )
        } finally {
            TimeZone.setDefault(oldZone)
            Locale.setDefault(oldLocale)
        }
    }

    @Test
    fun `GET and HISTORY ids use distinct prefixes and dashless UUID values`() {
        val uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        assertEquals("get_00112233445566778899aabbccddeeff", SourceFlightPlanDates.getRequestId(uuid))
        assertEquals("history_00112233445566778899aabbccddeeff", SourceFlightPlanDates.historyRequestId(uuid))
    }

    @Test
    fun `UPSERT ids bind effective plan date list length and injected epoch milliseconds`() {
        assertEquals("req_add_2026-10-30_2_1793404800000",
            SourceFlightPlanDates.upsertRequestId(" \t2026-10-30\n", 2, 1_793_404_800_000L))
        assertEquals("req_add__0_0", SourceFlightPlanDates.upsertRequestId("", 0, 0L))
        assertEquals("req_1793404800000", SourceFlightPlanDates.fallbackUpsertRequestId(1_793_404_800_000L))
        assertThrows(IllegalArgumentException::class.java) {
            SourceFlightPlanDates.upsertRequestId("2026-10-30", -1, 0L)
        }
    }
}
