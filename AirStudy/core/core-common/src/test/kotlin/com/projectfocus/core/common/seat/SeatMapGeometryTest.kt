package com.projectfocus.core.common.seat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test

class SeatMapGeometryTest {

    @Test
    fun `thirty rows`() {
        assertEquals(30, SeatMapGeometry.ROWS)
    }

    @Test
    fun `four columns a c d f`() {
        assertEquals(listOf("A", "C", "D", "F"), SeatMapGeometry.COLUMNS)
    }

    @Test
    fun `first and last labels`() {
        assertEquals("01A", SeatMapGeometry.label(0, 0))
        assertEquals("30F", SeatMapGeometry.label(29, 3))
    }

    @Test
    fun `known seat codes only`() {
        assertTrue(SeatMapGeometry.isValid("01A"))
        assertTrue(SeatMapGeometry.isValid("30F"))
        assertFalse(SeatMapGeometry.isValid("31A"))
        assertFalse(SeatMapGeometry.isValid("1A"))
        assertFalse(SeatMapGeometry.isValid("01B"))
        assertFalse(SeatMapGeometry.isValid(""))
    }

    @Test
    fun `out of range throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            SeatMapGeometry.label(30, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SeatMapGeometry.label(0, 4)
        }
    }
}
