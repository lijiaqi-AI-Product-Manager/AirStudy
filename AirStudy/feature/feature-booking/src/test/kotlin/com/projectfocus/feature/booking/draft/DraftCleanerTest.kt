package com.projectfocus.feature.booking.draft

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.feature.booking.seat.SeatSelectionStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DraftCleanerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("booking_draft", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("seat_selection", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `clear removes booking draft and seat`() {
        SeatSelectionStore(context).save("rec-1", "12A")
        val saved = BookingDraftStore(context).save(
            BookingDraft(
                flightNo = "",
                fromCode = "PEK",
                toCode = "SHA",
                seat = "12A",
                savedAtMillis = System.currentTimeMillis(),
            ),
            draftKey = "rec-1",
        )
        assertNotNull(BookingDraftStore(context).load("rec-1"))
        assertNotNull(SeatSelectionStore(context).load("rec-1"))
        assert(saved)

        DraftCleaner.clear(context, "rec-1")

        assertNull(BookingDraftStore(context).load("rec-1"))
        assertNull(SeatSelectionStore(context).load("rec-1"))
    }

    @Test
    fun `clearAll removes every draft`() {
        SeatSelectionStore(context).save("rec-1", "12A")
        SeatSelectionStore(context).save("rec-2", "30F")
        assertNotNull(SeatSelectionStore(context).load("rec-1"))
        assertNotNull(SeatSelectionStore(context).load("rec-2"))

        DraftCleaner.clearAll(context)

        assertNull(SeatSelectionStore(context).load("rec-1"))
        assertNull(SeatSelectionStore(context).load("rec-2"))
        assertNull(BookingDraftStore(context).load("rec-1"))
    }

    @Test
    fun `clear does not touch other key`() {
        SeatSelectionStore(context).save("rec-1", "12A")
        SeatSelectionStore(context).save("rec-2", "30F")

        DraftCleaner.clear(context, "rec-1")

        assertNull(SeatSelectionStore(context).load("rec-1"))
        assertNotNull(SeatSelectionStore(context).load("rec-2"))
    }
}
