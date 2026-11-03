package com.projectfocus.feature.booking.draft

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner


@RunWith(RobolectricTestRunner::class)
class BookingDraftStoreTest {

    private lateinit var store: BookingDraftStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("booking_drafts", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = BookingDraftStore(context) { FIXED_NOW }
    }

    @Test
    fun `roundtrip keeps all fields`() {
        val draft = BookingDraft(
            flightNo = "CA123",
            fromCode = "PEK",
            toCode = "SHA",
            seat = "12C",
            savedAtMillis = 1_700_000_000_000,
            recordId = "rec-1",
        )


        //       原先按航班号存取会被当作损坏数据丢弃。
        assertTrue(store.save(draft, draftKey = "rec-1"))
        assertEquals(draft, store.load("rec-1"))
    }

    @Test
    fun `record key can store blank flight`() {
        val draft = BookingDraft(
            flightNo = "",
            fromCode = "PEK",
            toCode = "SHA",
            seat = "12C",
            savedAtMillis = 1_700_000_000_000,
        )

        assertTrue(store.save(draft, draftKey = "rec-1"))
        assertEquals(draft, store.load("rec-1"))
    }

    @Test
    fun `two records do not overwrite each other`() {

        store.save(BookingDraft("", "PEK", "SHA", "12C", OLDER), draftKey = "rec-1")
        store.save(BookingDraft("", "PEK", "CAN", "30F", NEWER), draftKey = "rec-2")

        assertEquals("12C", store.load("rec-1")?.seat)
        assertEquals("30F", store.load("rec-2")?.seat)
    }

    @Test
    fun `record id must match draft key`() {
        val draft = BookingDraft(
            flightNo = "",
            fromCode = "PEK",
            toCode = "SHA",
            seat = "12C",
            savedAtMillis = 1_700_000_000_000,
            recordId = "rec-1",
        )
        store.save(draft, draftKey = "wrong-key")

        assertNull(store.load("wrong-key"))
    }

    @Test
    fun `corrupt draft is removed after load`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("booking_drafts", Context.MODE_PRIVATE)
        prefs.edit().putString("draft_corrupt", "{not-json").commit()

        assertNull(store.load("corrupt"))
        assertFalse(prefs.contains("draft_corrupt"))
    }

    @Test
    fun `all returns drafts newest first`() {
        store.save(BookingDraft("CA123", "PEK", "SHA", "12C", OLDER), draftKey = "rec-1")
        store.save(BookingDraft("MU456", "SHA", "CAN", "30F", NEWER), draftKey = "rec-2")

        assertEquals(listOf("30F", "12C"), store.all().map { it.seat })
    }

    @Test
    fun `different flights are isolated`() {
        store.save(BookingDraft("CA123", "PEK", "SHA", "12C", OLDER))
        store.save(BookingDraft("MU456", "SHA", "CAN", "30F", NEWER))

        assertEquals("CA123", store.load("CA123")?.flightNo)
        assertEquals("MU456", store.load("MU456")?.flightNo)
        assertNull(store.load("CZ789"))
    }

    @Test
    fun `clear removes only that flight`() {
        store.save(BookingDraft("CA123", "PEK", "SHA", "12C", OLDER))
        store.save(BookingDraft("MU456", "SHA", "CAN", "30F", NEWER))

        store.clear("CA123")

        assertNull(store.load("CA123"))
        assertEquals("30F", store.load("MU456")?.seat)
    }

    @Test
    fun `blank flight or seat is rejected`() {
        assertFalse(store.save(BookingDraft("", "PEK", "SHA", "12C", 1)))
        assertFalse(store.save(BookingDraft("CA123", "PEK", "SHA", "", 1)))
        assertNull(store.load(""))
    }

    @Test
    fun `malformed json is ignored`() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("booking_drafts", Context.MODE_PRIVATE)
            .edit().putString("draft_CA123", "{not-json").commit()

        assertNull(store.load("CA123"))
    }

    @Test
    fun `invalid seat in stored json is ignored`() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("booking_drafts", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "draft_CA123",
                """{"flightNo":"CA123","fromCode":"PEK","toCode":"SHA","seat":"99Z"}""",
            )
            .commit()

        assertNull(store.load("CA123"))
    }

    @Test
    fun `draft older than seven days is ignored`() {
        assertTrue(
            store.save(
                BookingDraft("CA123", "PEK", "SHA", "12C", FIXED_NOW - MAX_AGE - 1),
            ),
        )
        assertNull(store.load("CA123"))

        // 正好 7 天仍有效：只有“大于”才过期。
        assertTrue(
            store.save(
                BookingDraft("CA456", "PEK", "SHA", "12C", FIXED_NOW - MAX_AGE),
            ),
        )
        assertEquals("12C", store.load("CA456")?.seat)
    }

    private companion object {
        const val FIXED_NOW: Long = 1_700_000_000_000L
        const val MAX_AGE: Long = 7L * 24 * 60 * 60 * 1000
        // 都在 7 天有效期内，且 NEWER 比 OLDER 晚，用于验证排序与互不覆盖
        const val OLDER: Long = FIXED_NOW - 2_000L
        const val NEWER: Long = FIXED_NOW - 1_000L
    }
}
