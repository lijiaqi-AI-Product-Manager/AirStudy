package com.projectfocus.feature.booking.seat

import org.junit.Assert.assertEquals
import org.junit.Test


class SeatSelectionKeyTest {

    @Test
    fun `record id always wins`() {
        assertEquals("rec-1", seatDraftKey("rec-1", "wrong-key", "CA123"))
    }

    @Test
    fun `draft key is used without record`() {
        assertEquals("CA123", seatDraftKey("", "CA123", "MU456"))
    }

    @Test
    fun `flight number is last fallback`() {
        assertEquals("MU456", seatDraftKey("", "", "MU456"))
    }
}
