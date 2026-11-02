package com.projectfocus.feature.booking.seat

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
class SeatSelectionStoreTest {

    private lateinit var store: SeatSelectionStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("seat_selection", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = SeatSelectionStore(context)
    }

    @Test
    fun `original unpadded identity becomes existing canonical identity without accepting invalid columns`() {
        assertTrue(store.save("rec-original", "1A"))
        assertEquals("01A", store.load("rec-original"))
        assertFalse(store.save("rec-original", "1B"))
        assertEquals("01A", store.load("rec-original"))
    }

    @Test
    fun `save then load returns same seat`() {
        assertTrue(store.save("PF123", "12C"))
        assertEquals("12C", store.load("PF123"))
    }

    @Test
    fun `different flights are isolated`() {
        store.save("PF123", "12C")
        store.save("PF456", "30F")
        assertEquals("12C", store.load("PF123"))
        assertEquals("30F", store.load("PF456"))
    }

    @Test
    fun `corrupt seat is removed after load`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("seat_selection", Context.MODE_PRIVATE)
        prefs.edit().putString("seat_bad", "99Z").commit()

        assertNull(store.load("bad"))
        assertFalse(prefs.contains("seat_bad"))
    }

    @Test
    fun `two records do not overwrite each other`() {
        store.save("rec-1", "12C")
        store.save("rec-2", "30F")

        assertEquals("12C", store.load("rec-1"))
        assertEquals("30F", store.load("rec-2"))
    }

    @Test
    fun `blank flight uses default key`() {
        store.save("", "01A")
        assertEquals("01A", store.load(""))
    }

    @Test
    fun `invalid seat is ignored on save and load`() {
        assertFalse(store.save("PF123", "99Z"))
        assertNull(store.load("PF123"))

        // 模拟旧版本/外部写入的损坏值。
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("seat_selection", Context.MODE_PRIVATE)
            .edit().putString("seat_PF123", "99Z").commit()
        assertNull(store.load("PF123"))
    }

    @Test
    fun `clear removes only that flight`() {
        store.save("PF123", "12C")
        store.save("PF456", "30F")
        store.clear("PF123")
        assertNull(store.load("PF123"))
        assertEquals("30F", store.load("PF456"))
    }
}
