package com.projectfocus.feature.airport.detail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AirportReportStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("airport_reports", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun saveThenListFiltersByAirportIgnoringCase() {
        assertTrue(AirportReportStore.save(context, "PEK", "北京首都", "介绍过期"))

        val reports = AirportReportStore.list(context, "pek")

        assertEquals(1, reports.size)
        assertEquals("PEK", reports[0].code)
        assertEquals("北京首都", reports[0].name)
        assertEquals("介绍过期", reports[0].reason)
    }

    @Test
    fun listDoesNotLeakOtherAirports() {
        AirportReportStore.save(context, "PEK", "北京首都", "A")
        AirportReportStore.save(context, "SHA", "上海虹桥", "B")

        assertEquals(1, AirportReportStore.list(context, "PEK").size)
        assertEquals(1, AirportReportStore.list(context, "SHA").size)
        assertEquals(2, AirportReportStore.list(context, null).size)
    }

    @Test
    fun deleteRemovesOnlyRequestedReport() {
        AirportReportStore.save(context, "PEK", "北京首都", "A")
        AirportReportStore.save(context, "PEK", "北京首都", "B")
        val first = AirportReportStore.list(context, "PEK").first()

        assertTrue(AirportReportStore.delete(context, first.id))
        assertEquals(1, AirportReportStore.list(context, "PEK").size)
        assertNull(AirportReportStore.list(context, "PEK").firstOrNull { it.id == first.id })
    }

    @Test
    fun malformedJsonIsIgnoredInsteadOfCrashing() {
        context.getSharedPreferences("airport_reports", Context.MODE_PRIVATE)
            .edit()
            .putString("report_bad", "not-a-json")
            .commit()

        assertEquals(0, AirportReportStore.list(context, null).size)
    }
}
