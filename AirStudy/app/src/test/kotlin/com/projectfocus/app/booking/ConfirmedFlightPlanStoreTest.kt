package com.projectfocus.app.booking

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedDeparture
import com.projectfocus.core.model.flight.PlannedScene
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConfirmedFlightPlanStoreTest {
    private lateinit var preferences: SharedPreferences

    @Before
    fun resetPreferences() {
        preferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(SharedPreferencesConfirmedFlightPlanStore.FILE_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @Test
    fun `whole confirmed plan survives a new store instance with float distance and display snapshots`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher)
        val plan = sample().copy(seat = "03C", categoryId = 9L)
        assertThat(store.load()).isNull()
        store.save(plan)
        val recreated = SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher)
        assertThat(recreated.load()).isEqualTo(plan)
        assertThat(recreated.load()?.departure?.displayName).isEqualTo("Beijing Capital")
        assertThat(recreated.load()?.distanceKm).isEqualTo(plan.distanceKm)
    }

    @Test
    fun `independent custom scene and departure clock survive a new store without changing category`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scene = PlannedScene("opaque-custom-second", "晨读", "reading", 0xFFFDAA0F.toInt(), true,
            81L, "local-scene-client", "晨读")
        val plan = sample().copy(categoryId = 7L, scene = scene,
            plannedDeparture = PlannedDeparture("2026-10-31", 23, 59, "Asia/Shanghai"))
        SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher).save(plan)
        val actual = SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher).load()!!
        assertThat(actual).isEqualTo(plan)
        assertThat(actual.scene?.optionId).isEqualTo("opaque-custom-second")
        assertThat(actual.categoryId).isEqualTo(7L)
        assertThat(actual.plannedDeparture?.minuteOfDay).isEqualTo(1439)
    }

    @Test
    fun `legacy confirmed JSON without scene or departure fields remains readable`() = runTest {
        val plan = sample().copy(seat = "01A", categoryId = 9L)
        val legacy = JsonObject(Json.parseToJsonElement(FlightPlanJson.encode(plan)).jsonObject
            .filterKeys { it != "scene" && it != "plannedDeparture" }).toString()
        preferences.edit().putString(SharedPreferencesConfirmedFlightPlanStore.PLAN_KEY, legacy).commit()
        val actual = SharedPreferencesConfirmedFlightPlanStore(preferences, StandardTestDispatcher(testScheduler)).load()!!
        assertThat(actual).isEqualTo(plan)
        assertThat(actual.scene).isNull()
        assertThat(actual.plannedDeparture).isNull()
    }

    @Test
    fun `malformed or forged route JSON is rejected rather than restored as confirmed`() = runTest {
        val store = SharedPreferencesConfirmedFlightPlanStore(preferences, StandardTestDispatcher(testScheduler))
        val plan = sample()
        val encoded = FlightPlanJson.encode(plan)
        val forged = encoded.replace("\"distanceKm\":${plan.distanceKm}", "\"distanceKm\":1.0")
        assertThat(forged).isNotEqualTo(encoded)
        for (invalid in listOf("{broken", forged)) {
            preferences.edit().putString(SharedPreferencesConfirmedFlightPlanStore.PLAN_KEY, invalid).commit()
            val error = try { store.load(); null } catch (failure: Exception) { failure }
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `failed commit cannot leave an unconfirmed snapshot visible through the preferences cache`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val original = sample().copy(seat = "01A")
        SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher).save(original)
        // Model commit's memory-first behavior while reporting a disk failure.
        val failingPreferences = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val delegate = preferences.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        delegate.putString(key, value)
                        return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor {
                        delegate.remove(key)
                        return this
                    }
                    override fun commit(): Boolean { delegate.commit(); return false }
                }
            }
        }
        val store = SharedPreferencesConfirmedFlightPlanStore(failingPreferences, dispatcher)
        val error = try { store.save(original.copy(seat = "02C")); null } catch (failure: Exception) { failure }
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(store.load()).isEqualTo(original)
    }

    private fun sample() = FlightLegPlan.between(
        PlannedAirport(1, "PEK", "首都机场", "北京", "中国", 40.08, 116.58,
            displayName = "Beijing Capital", displayCity = "Beijing"),
        PlannedAirport(2, "PVG", "浦东机场", "上海", "中国", 31.14, 121.8),
    )
}
