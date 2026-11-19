package com.projectfocus.app.booking

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedDeparture
import com.projectfocus.core.network.di.TokenStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlobalFlightPlanStoreTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private lateinit var prefs: SharedPreferences
    private val clock = Clock()
    @Before fun setup() {
        TokenStore.clear()
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("global_plan_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }
    @After fun teardown() { TokenStore.clear() }

    @Test fun enabledDateAndPendingSurviveStorageButFreshHostClearsStalePendingOnlyOnce() {
        val first = SharedPreferencesGlobalFlightPlanStore(prefs, clock)
        first.bindHost("host-a")
        val request = first.beginPending(day(), a())
        first.bindHost("host-a")
        assertThat(first.current().enabledDate).isEqualTo(DATE)
        assertThat(first.current().pendingSelection).isEqualTo(request)
        val recreated = SharedPreferencesGlobalFlightPlanStore(prefs, clock)
        assertThat(recreated.current().selectionPending).isTrue()
        recreated.bindHost("host-b")
        assertThat(recreated.current().selectionPending).isFalse()
        assertThat(recreated.current().enabledDate).isNull()
        assertThat(recreated.current().pendingSelection).isNull()
    }

    @Test fun secondPendingWriteFailureDoesNotPretendBothSourceWritesWereAtomic() {
        var commits = 0
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val real = prefs.edit()
                return object : SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { real.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { real.remove(key); return this }
                    override fun commit(): Boolean { real.commit(); return ++commits != 2 }
                }
            }
        }
        val store = SharedPreferencesGlobalFlightPlanStore(failing, clock)
        var failed = false
        try { store.beginPending(day(), a()) } catch (_: IOException) { failed = true }
        assertThat(failed).isTrue()
        assertThat(store.current().enabledDate).isEqualTo(DATE)
        assertThat(store.current().selectionPending).isFalse()
        assertThat(SharedPreferencesGlobalFlightPlanStore(prefs, clock).current()).isEqualTo(store.current())
    }

    @Test fun returnUsesCurrentCandidateAndDeviceTodayRatherThanAnOpenedLegAcknowledgement() {
        val store = SharedPreferencesGlobalFlightPlanStore(prefs, clock)
        val request = store.beginPending(day(), a())
        val next = b()
        clock.current = clock.current.plusDays(1)
        assertThat(store.acceptSeat(request.requestId, " 1A ", day().copy(legs = listOf(next)), next)).isTrue()
        val result = store.current()
        assertThat(result.selectionPending).isFalse()
        assertThat(result.inheritedSeat).isEqualTo("1A")
        assertThat(result.activeSelection?.candidate).isEqualTo(next)
        assertThat(result.activeSelection?.localDate).isEqualTo("2026-11-01")
        assertThat(result.enabledDate).isEqualTo(DATE)
        assertThat(result.activeSelection?.candidate?.snapshot?.focusSceneText).isEqualTo(next.snapshot.focusSceneText)
        assertThat(store.acceptSeat(request.requestId, "2C", day(), a())).isFalse()
    }

    @Test fun anEmptyReturnConsumesPendingWithoutErasingAnExistingInheritedSeat() {
        val store = SharedPreferencesGlobalFlightPlanStore(prefs, clock)
        val first = store.beginPending(day(), a())
        store.acceptSeat(first.requestId, "1A", day(), a())
        val second = store.beginPending(day(), a())
        assertThat(store.acceptSeat(second.requestId, " \t", day(), a())).isTrue()
        assertThat(store.current().selectionPending).isFalse()
        assertThat(store.current().inheritedSeat).isEqualTo("1A")
        assertThat(store.current().lastSeatResult).isEmpty()
    }

    @Test fun confirmedSeatDoesNotRewriteTheOriginalDailyPlanOrStartASession() {
        val base = day()
        val store = SharedPreferencesGlobalFlightPlanStore(prefs, clock)
        val request = store.beginPending(base, a())
        store.acceptSeat(request.requestId, "30F", base, a())
        assertThat(base.legs.single().snapshot.seatNo).isEqualTo("original-seat")
        assertThat(base.legs.single().snapshot.legStatus).isEqualTo("waiting_takeoff")
        assertThat(store.current().activeSelection?.candidate).isEqualTo(base.legs.single())
        assertThat(store.current().inheritedSeat).isEqualTo("30F")
    }

    @Test fun confirmedDateClearingPortDoesNotInferPendingOrActualSessionReset() {
        val store = SharedPreferencesGlobalFlightPlanStore(prefs, clock)
        val pending = store.beginPending(day(), a())
        store.clearEnabledDate()
        assertThat(store.current().enabledDate).isNull()
        assertThat(store.current().pendingSelection).isEqualTo(pending)
        assertThat(store.current().selectionPending).isTrue()
    }

    @Test fun anotherAccountCannotConsumeThePreviousAccountsTypedPendingResult() {
        TokenStore.set("fixture-a")
        val store = SharedPreferencesGlobalFlightPlanStore(prefs, clock)
        val base = day().copy(ownerScope = FlightPlanOwnership.scope())
        val pending = store.beginPending(base, a())
        TokenStore.set("fixture-b")
        assertThat(store.acceptSeat(pending.requestId, "1A", base, a())).isFalse()
        assertThat(store.current().enabledDate).isNull()
        assertThat(store.current().inheritedSeat).isNull()
        TokenStore.set("fixture-a")
        assertThat(store.current().pendingSelection).isEqualTo(pending)
    }

    private fun a() = DailyFlightPlans.fromRoute(FlightLegPlan.between(PEK, PVG).copy(
        seat = "original-seat", plannedDeparture = PlannedDeparture(DATE, 13, 0, ZONE)), DATE, ZONE, 1)
    private fun b() = DailyFlightPlans.fromRoute(FlightLegPlan.between(PVG, PEK).copy(
        plannedDeparture = PlannedDeparture(DATE, 15, 0, ZONE)), DATE, ZONE, 2)
    private fun day() = DailyFlightPlan(DATE, ZONE, legs = listOf(a()))
    private class Clock : PreparationClock {
        var current = ZonedDateTime.parse("2026-10-31T12:34:00+08:00[Asia/Shanghai]")
        override fun now() = current
    }
    private companion object {
        const val DATE = "2026-10-31"; const val ZONE = "Asia/Shanghai"
        val PEK = PlannedAirport(1, "PEK", "首都", "北京", "CN", 40.08, 116.58)
        val PVG = PlannedAirport(2, "PVG", "浦东", "上海", "CN", 31.14, 121.8)
    }
}
