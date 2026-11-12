package com.projectfocus.core.common.plan

import java.time.LocalDate
import java.util.Locale
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SourceTakeoffClockTest {
    private lateinit var oldLocale: Locale

    @BeforeEach
    fun useStableLocale() {
        oldLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(oldLocale)
    }

    @Test
    fun `parse boundaries are actual minute of day values`() {
        assertEquals(0, SourceTakeoffClock.parseMinutes("00:00"))
        assertEquals(62, SourceTakeoffClock.parseMinutes("01:02"))
        assertEquals(1439, SourceTakeoffClock.parseMinutes("23:59"))
    }

    @Test
    fun `source parser accepts non padded signed zero unicode decimal and trailing empty components`() {
        for (value in listOf("1:2", "+1:+2", "01:02:", "01:02::", "٠١:٠٢", "０１:０２")) {
            assertEquals(62, SourceTakeoffClock.parseMinutes(value), value)
        }
        assertEquals(0, SourceTakeoffClock.parseMinutes("-0:-0"))
        assertEquals(1439, SourceTakeoffClock.parseMinutes("23:+59"))
    }

    @Test
    fun `invalid clock inputs are not trimmed into valid values or normalized into bounds`() {
        val invalid = listOf(null, "", " \t\r\n", ":", "01:", "01", "a:b", "24:00", "23:60",
            "-1:00", "00:-1", " 01:02", "01:02 ", "01::02", "01:02: ", "2147483648:00", "\u200301:02")
        for (value in invalid) assertNull(SourceTakeoffClock.parseMinutes(value), value ?: "null")
    }

    @Test
    fun `Q shifts both ways across midnight and retains source Int overflow`() {
        assertEquals("00:00", SourceTakeoffClock.shift(2, "23:58"))
        assertEquals("00:03", SourceTakeoffClock.shift(5, "23:58"))
        assertEquals("23:59", SourceTakeoffClock.shift(-1, "00:00"))
        assertEquals("23:59", SourceTakeoffClock.shift(-1441, "00:00"))
        assertEquals("00:03", SourceTakeoffClock.shift(2885, "23:58"))
        assertEquals("21:52", SourceTakeoffClock.shift(Int.MIN_VALUE, "00:00"))
        assertEquals("21:52", SourceTakeoffClock.shift(Int.MAX_VALUE, "00:01"))
        assertNull(SourceTakeoffClock.shift(5, "24:00"))
    }

    @Test
    fun `format normalizes minutes and uses the current default locale rather than fixed ASCII`() {
        assertEquals("23:59", SourceTakeoffClock.format(-1))
        assertEquals("00:00", SourceTakeoffClock.format(1440))
        assertEquals("02:07", SourceTakeoffClock.format(Int.MAX_VALUE))
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
        assertEquals("٠١:٠٢", SourceTakeoffClock.format(62))
    }

    @Test
    fun `J earliest blank branch stays distinct from full clock validation and Kotlin Unicode trim`() {
        assertFalse(SourceTakeoffClock.canSelect(null, null))
        assertFalse(SourceTakeoffClock.canSelect(" \t\r\n", null))
        assertTrue(SourceTakeoffClock.canSelect("not-a-clock", null))
        assertTrue(SourceTakeoffClock.canSelect("not-a-clock", " \t\r\n"))
        assertTrue(SourceTakeoffClock.canSelect("\u2003", null))
        assertFalse(SourceTakeoffClock.canSelect("not-a-clock", "\u2003"))
    }

    @Test
    fun `J compares numeric minutes and does not invent absolute cross day ordering`() {
        assertTrue(SourceTakeoffClock.canSelect("2:5", "02:05"))
        assertFalse(SourceTakeoffClock.canSelect("2:4", "02:05"))
        assertTrue(SourceTakeoffClock.canSelect("10:00", "9:59"))
        assertFalse(SourceTakeoffClock.canSelect("bad", "09:00"))
        assertFalse(SourceTakeoffClock.canSelect("09:00", "bad"))
        assertTrue(SourceTakeoffClock.canSelect("23:00", "00:00"))
    }

    @Test
    fun `new dates compare only caller supplied device today and tomorrow text across year boundary`() {
        val today = LocalDate.of(2026, 12, 31)
        assertTrue(SourceTakeoffClock.newDateSupported("2026-12-31", today))
        assertTrue(SourceTakeoffClock.newDateSupported(" \t2027-01-01\r\n", today))
        for (value in listOf(null, "", "2026-12-30", "2027-01-02", "2027-1-1", "\u00A02027-01-01\u00A0")) {
            assertFalse(SourceTakeoffClock.newDateSupported(value, today), value ?: "null")
        }
    }

    @Test
    fun `tomorrow is a calendar date including leap day and month boundary`() {
        assertTrue(SourceTakeoffClock.newDateSupported("2028-02-29", LocalDate.of(2028, 2, 28)))
        assertFalse(SourceTakeoffClock.newDateSupported("2028-03-01", LocalDate.of(2028, 2, 28)))
        assertTrue(SourceTakeoffClock.newDateSupported("2026-03-01", LocalDate.of(2026, 2, 28)))
    }

    @Test
    fun `ETA consumes supplied duration without a distance calculation or date carry`() {
        assertEquals("01:12", SourceTakeoffClock.eta("23:50", 82))
        assertEquals("23:50", SourceTakeoffClock.eta("23:50", 0))
        assertEquals("00:10", SourceTakeoffClock.eta("00:00", 1450))
        assertNull(SourceTakeoffClock.eta(null, 8))
        assertNull(SourceTakeoffClock.eta("24:00", 8))
        assertNull(SourceTakeoffClock.eta("08:00", -1))
    }
}
