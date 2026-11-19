package com.projectfocus.app.booking

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.FlightPlanAirportDto
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import com.projectfocus.core.network.plan.FlightPlanResponseParser
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SourcePendingFlightInputsTest {
    private lateinit var preferences: SharedPreferences

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        preferences = context.getSharedPreferences("r166_pending_input_test", Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        TokenStore.clear()
    }

    @After fun tearDown() {
        preferences.edit().clear().commit()
        TokenStore.clear()
    }

    @Test fun `city seeds and airport name labels stay distinct from the code and local display aliases`() {
        val input = requireNotNull(SourcePendingFlightInputs.from(leg()))
        assertEquals("Beijing", input.departureNumberInput)
        assertEquals("Shanghai", input.arrivalNumberInput)
        assertEquals("Capital Airport", input.departureDisplay)
        assertEquals("Pudong Airport", input.arrivalDisplay)
        assertEquals("SC8728", input.flightNumber())
        assertNotEquals(SourceFlightNumber.generate("PEK", "PVG", false), input.flightNumber())
    }

    @Test fun `source fallback uses Java trim and preserves a nonempty NBSP city`() {
        val from = departure.copy(code = " \tPEK\r", name = " \u001f", city = " \t")
        val to = arrival.copy(code = " ", name = "\u001f ", city = "\t")
        val input = requireNotNull(SourcePendingFlightInputs.from(leg(from, to)))
        assertEquals("PEK", input.departureNumberInput)
        assertEquals("ARR", input.arrivalNumberInput)
        assertEquals("PEK", input.departureDisplay)
        assertEquals("ARR", input.arrivalDisplay)
        assertEquals("ZH1285", input.flightNumber())
        val allBlank = requireNotNull(SourcePendingFlightInputs.from(leg(from.copy(code = " "), to)))
        assertEquals("DEP", allBlank.departureNumberInput)
        assertEquals("HU7287", allBlank.flightNumber())
        val nbsp = requireNotNull(SourcePendingFlightInputs.from(leg(from.copy(city = "\u00a0", name = "\u00a0"), to)))
        assertEquals("\u00a0", nbsp.departureNumberInput)
        assertEquals("\u00a0", nbsp.departureDisplay)
    }

    @Test fun `real parser and pending store reconstruction retain raw inputs even when typed route cannot be built`() {
        val from = JSONObject().put("id", 1).put("code", "PEK").put("name", " Capital Airport ")
            .put("city", " Beijing ").put("latitude", "raw-lat-not-a-number").put("longitude", "116.0000")
            .put("region", "CN").put("country_region_id", 156)
            .put("name_i18n", JSONObject().put("zh-CN", "首都机场").put("custom-locale", "retained"))
            .put("city_i18n", JSONObject().put("zh-CN", "北京"))
            .put("unknown_source_extra", "drop_me")
        val to = JSONObject().put("id", 2).put("code", "PVG").put("name", "Pudong Airport")
            .put("city", "Shanghai").put("latitude", "31.0000").put("longitude", "121.0000")
            .put("region", "CN").put("country_region_id", 156)
        val parsed = FlightPlanResponseParser.legs(JSONArray().put(JSONObject()
            .put("leg_index", 1).put("departure_airport", from).put("arrival_airport", to)
            .put("scheduled_departure_at", "2026-10-31T01:00:00Z").put("leg_status", "waiting_takeoff")
            .put("distance_km", 100.0).put("focus_icon_code", 1).put("focus_scene_text", "Study"))).single()
        val day = DailyFlightPlans.fromSnapshot(DailyFlightPlanSnapshot("2026-10-31", "Asia/Shanghai", listOf(parsed)))
        val candidate = day.legs.single()
        assertNull(candidate.route)
        assertEquals(" Beijing ", candidate.snapshot.departureAirport!!.city)
        val clock = PreparationClock { ZonedDateTime.parse("2026-10-31T09:00:00+08:00[Asia/Shanghai]") }
        SharedPreferencesGlobalFlightPlanStore(preferences, clock).beginPending(day, candidate)
        val restored = SharedPreferencesGlobalFlightPlanStore(preferences, clock).current()
            .pendingSelection!!.candidate
        assertEquals(parsed, restored.snapshot)
        assertNull(restored.route)
        val rawAirport = restored.snapshot.departureAirport!!
        assertEquals("raw-lat-not-a-number", rawAirport.latitude)
        assertEquals("116.0000", rawAirport.longitude)
        assertEquals(156, rawAirport.countryRegionId)
        assertNotNull(rawAirport.nameI18n)
        assertEquals("retained", rawAirport.nameI18n!!["custom-locale"]!!.toString().trim('"'))
        assertNotNull(rawAirport.cityI18n)
        assertFalse(preferences.all.values.joinToString().contains("drop_me"))
        val inputs = requireNotNull(SourcePendingFlightInputs.from(restored.snapshot))
        assertEquals("Capital Airport", inputs.departureDisplay)
        assertEquals("SC8728", inputs.flightNumber())
    }

    @Test fun `missing endpoint remains an admission failure rather than a fabricated DEP ARR route`() {
        assertNull(SourcePendingFlightInputs.from(leg(null, arrival)))
        assertNull(SourcePendingFlightInputs.from(leg(departure, null)))
    }

    @Test fun assignedNumberSurvivesPlanRefreshAndPendingReconstructionWithoutRegeneration() {
        val snapshot = DailyFlightPlanSnapshot("2026-10-31", "Asia/Shanghai", listOf(leg().copy(flightNo = " AS2701 ")))
        val original = DailyFlightPlans.fromSnapshot(snapshot)
        assertEquals("AS2701", original.legs.single().route!!.flightNumber)
        val refreshed = DailyFlightPlans.fromSnapshot(snapshot.copy(legs = listOf(leg().copy(flightNo = "AS2702"))), original)
        assertEquals("AS2702", refreshed.legs.single().route!!.flightNumber)
        val clock = PreparationClock { ZonedDateTime.parse("2026-10-31T09:00:00+08:00[Asia/Shanghai]") }
        SharedPreferencesGlobalFlightPlanStore(preferences, clock).beginPending(refreshed, refreshed.legs.single())
        val restored = SharedPreferencesGlobalFlightPlanStore(preferences, clock).current().pendingSelection!!.candidate
        assertEquals("AS2702", restored.route!!.flightNumber)
        assertEquals("AS2702", restored.snapshot.flightNo)
        val unassigned = DailyFlightPlans.fromSnapshot(snapshot.copy(legs = listOf(leg().copy(flightNo = "待分配"))), refreshed)
        assertNull(unassigned.legs.single().route!!.flightNumber)
        val malformed = DailyFlightPlans.fromSnapshot(snapshot.copy(legs = listOf(leg().copy(flightNo = "X".repeat(129)))), refreshed)
        assertNull(malformed.legs.single().route)
        assertEquals("X".repeat(129), malformed.legs.single().snapshot.flightNo)
    }

    private fun leg(from: FlightPlanAirportDto? = departure, to: FlightPlanAirportDto? = arrival) =
        FlightPlanLegSnapshot(1, from, to, "2026-10-31T01:00:00Z", "waiting_takeoff", 100.0,
            "", "", 1, "Study")

    private companion object {
        val departure = FlightPlanAirportDto(1, "PEK", "Capital Airport", "Beijing", "40", "116", "CN", countryRegionId = 156)
        val arrival = FlightPlanAirportDto(2, "PVG", "Pudong Airport", "Shanghai", "31", "121", "CN", countryRegionId = 156)
    }
}
