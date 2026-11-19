package com.projectfocus.app.booking

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test


class SourceFlightNumberTest {
    @Test fun `source modes and directed routes retain their own fixed number`() {
        assertEquals("BK2495", SourceFlightNumber.generate("PEK", "PVG", false))
        assertEquals("FF7203", SourceFlightNumber.generate("PEK", "PVG", true))
        assertEquals("BK4575", SourceFlightNumber.generate("PVG", "PEK", false))
        assertEquals("FJ4638", SourceFlightNumber.generate("LAX", "JFK", true))
    }

    @Test fun `null and Java blank endpoints use the source XX fallback`() {
        assertEquals("CA8707", SourceFlightNumber.generate(null, null, false))
        assertEquals("FV9179", SourceFlightNumber.generate(null, null, true))
        assertEquals("CA8707", SourceFlightNumber.generate("\u0000 \t\r\n\u001f", " ", false))
        assertEquals("CA8707", SourceFlightNumber.generate("XX", "XX", false))
    }

    @Test fun `Java trim and embedded ASCII whitespace normalize before hashing`() {
        assertEquals("BK2495", SourceFlightNumber.generate(" \tp e k\n", "\rpvg\u000c", false))
        assertEquals("CA1918", SourceFlightNumber.generate("\u0000 i st\u001f", "lhr", false))
    }

    @Test fun `English case mapping does not follow the device Turkish locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("CA1918", SourceFlightNumber.generate("ist", "lhr", false))
            assertEquals("SC3306", SourceFlightNumber.generate("ß", "PVG", false))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun `supplementary UTF16 input preserves signed hash widening semantics`() {
        // Both this seed and PEK>PVG have a negative signed Java hash.
        assertEquals("MF8019", SourceFlightNumber.generate("🛫", "PVG", false))
        repeat(5) { assertEquals("MF8019", SourceFlightNumber.generate("🛫", "PVG", false)) }
    }
}
