package com.projectfocus.app.booking

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.ZonedDateTime

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DailyFlightPlanRuntimeTest {
    @Before fun setup() { TokenStore.clear() }
    @After fun teardown() { TokenStore.clear() }

    @Test fun directBindingSortsCachesStablyWithoutChangingTheAuthoritativeWireList() {
        val clock = Clock()
        val runtime = DailyFlightPlanRuntime(clock)
        val second = leg(2, "completed", "second")
        val first = leg(1, "waiting_takeoff", "first")
        val tied = leg(1, "canceled", "tie")
        val raw = day(TODAY, listOf(second, first, tied))
        runtime.bind(raw)
        assertThat(raw.legs).containsExactly(second, first, tied).inOrder()
        assertThat(raw.displayLegs()).containsExactly(first, tied, second).inOrder()
        assertThat(runtime.snapshot().today!!.legs).containsExactly(first, tied, second).inOrder()
        runtime.bind(raw.copy(localDate = TOMORROW))
        assertThat(runtime.snapshot().tomorrow!!.legs).containsExactly(first, tied, second).inOrder()
    }

    @Test fun tomorrowPrefetchUsesDeviceDateAndZoneAndKeepsItsResponseOrder() = runTest {
        val clock = Clock()
        val runtime = DailyFlightPlanRuntime(clock)
        runtime.bind(day(TODAY, emptyList()).copy(timezone = "UTC"))
        val returned = day(TOMORROW, listOf(leg(2), leg(1)))
        var calls = 0
        runtime.prefetchTomorrow(Store { date, zone, today ->
            calls++
            assertThat(date).isEqualTo(TOMORROW)
            assertThat(zone).isEqualTo(ZONE)
            assertThat(today).isEqualTo(TODAY)
            returned
        })
        assertThat(calls).isEqualTo(1)
        assertThat(runtime.snapshot().tomorrow).isEqualTo(returned)
    }

    @Test fun twoSecondThrottleIsConsumedEvenWhenTheAttemptFails() = runTest {
        val clock = Clock().apply { elapsed = 1_999L }
        val runtime = DailyFlightPlanRuntime(clock)
        runtime.selectDate(TODAY)
        var calls = 0
        val failed = Store { _, _, _ -> calls++; throw IOException("disconnected") }
        runtime.prefetchTomorrow(failed)
        assertThat(calls).isEqualTo(0)
        clock.elapsed = 2_000L
        runtime.prefetchTomorrow(failed)
        clock.elapsed = 3_999L
        runtime.prefetchTomorrow(failed)
        assertThat(calls).isEqualTo(1)
        clock.elapsed = 4_000L
        runtime.prefetchTomorrow(failed)
        assertThat(calls).isEqualTo(2)
        assertThat(runtime.snapshot().tomorrow).isNull()
    }

    @Test fun noncanceledTomorrowBlocksTodayEvenWhenCompletedUnknownOrLocked() {
        val runtime = DailyFlightPlanRuntime(Clock())
        for (status in listOf("completed", "future_status", "waiting_takeoff")) {
            val candidate = leg(1, status).let { it.copy(snapshot = it.snapshot.copy(archived = true, readOnly = true)) }
            runtime.bind(day(TOMORROW, listOf(candidate)))
            assertThat(runtime.todayBlocked(TODAY)).isTrue()
        }
        runtime.bind(day(TOMORROW, listOf(leg(1, "canceled"), leg(2, "已取消"), leg(3, "cancelled"))))
        assertThat(runtime.todayBlocked(TODAY)).isFalse()
        assertThat(runtime.todayBlocked(TOMORROW)).isFalse()
        runtime.bind(day(TOMORROW, emptyList()))
        assertThat(runtime.todayBlocked(TODAY)).isFalse()
    }

    @Test fun tomorrowAddGuardUsesTheLastSortedTodayItemRatherThanTheWireTail() {
        val runtime = DailyFlightPlanRuntime(Clock())
        runtime.bind(day(TODAY, listOf(leg(2, "canceled"), leg(1))))
        assertThat(runtime.tomorrowBlockedByCanceledToday(TOMORROW)).isTrue()
        assertThat(runtime.tomorrowBlockedByCanceledToday(TODAY)).isFalse()
        runtime.bind(day(TODAY, listOf(leg(2, "completed"), leg(1, "canceled"))))
        assertThat(runtime.tomorrowBlockedByCanceledToday(TOMORROW)).isFalse()
    }

    @Test fun successfulPrefetchIsIgnoredWhileBrowsingTomorrowOrPastButEmptyTodayDeliveryIsReal() = runTest {
        val clock = Clock()
        val runtime = DailyFlightPlanRuntime(clock)
        for (selection in listOf(TOMORROW, "2026-10-30")) {
            runtime.selectDate(TODAY)
            val reply = CompletableDeferred<DailyFlightPlan>()
            val work = async { runtime.prefetchTomorrow(Store { _, _, _ -> reply.await() }) }
            runCurrent()
            runtime.selectDate(selection)
            reply.complete(day(TOMORROW, listOf(leg(1))))
            work.await()
            assertThat(runtime.snapshot().tomorrow).isNull()
            clock.elapsed += 2_000L
        }
        runtime.selectDate(TODAY)
        runtime.prefetchTomorrow(Store { _, _, _ -> day(TOMORROW, emptyList()) })
        assertThat(runtime.snapshot().tomorrow).isEqualTo(day(TOMORROW, emptyList()))
    }

    @Test fun failuresTimeoutsAndCallerCancellationPreserveTheOldTomorrowCache() = runTest {
        val clock = Clock()
        val runtime = DailyFlightPlanRuntime(clock)
        val previous = day(TOMORROW, listOf(leg(1, "completed")))
        runtime.bind(previous)
        runtime.selectDate(TODAY)
        runtime.prefetchTomorrow(Store { _, _, _ -> throw IOException("failed") })
        assertThat(runtime.snapshot().tomorrow).isEqualTo(previous)
        clock.elapsed += 2_000L
        runtime.prefetchTomorrow(Store { _, _, _ -> withTimeout(1L) { awaitCancellation() } })
        assertThat(runtime.snapshot().tomorrow).isEqualTo(previous)
        clock.elapsed += 2_000L
        val work = async { runtime.prefetchTomorrow(Store { _, _, _ -> awaitCancellation() }) }
        runCurrent()
        work.cancelAndJoin()
        assertThat(work.isCancelled).isTrue()
        assertThat(runtime.snapshot().tomorrow).isEqualTo(previous)
    }

    @Test fun ownerGenerationCalendarAndZoneChangesRejectLateDataInsteadOfLeakingCachedPlans() = runTest {
        for (change in listOf("account", "same-token-generation", "date", "zone")) {
            TokenStore.set("account-a")
            val clock = Clock()
            val runtime = DailyFlightPlanRuntime(clock)
            runtime.bind(day(TODAY, listOf(leg(1))))
            val reply = CompletableDeferred<DailyFlightPlan>()
            val old = day(TOMORROW, listOf(leg(1)))
            val work = async { runtime.prefetchTomorrow(Store { _, _, _ -> reply.await() }) }
            runCurrent()
            when (change) {
                "account" -> TokenStore.set("account-b")
                "same-token-generation" -> TokenStore.set("account-a")
                "date" -> clock.current = clock.current.plusDays(1)
                "zone" -> clock.current = clock.current.withZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo"))
            }
            reply.complete(old)
            work.await()
            assertThat(runtime.snapshot().today).isNull()
            assertThat(runtime.snapshot().tomorrow).isNull()
        }
    }

    @Test fun aLaterAttemptWinsWhenResponsesArriveInReverseOrder() = runTest {
        val clock = Clock()
        val runtime = DailyFlightPlanRuntime(clock)
        runtime.selectDate(TODAY)
        val older = CompletableDeferred<DailyFlightPlan>()
        val newer = CompletableDeferred<DailyFlightPlan>()
        val first = async { runtime.prefetchTomorrow(Store { _, _, _ -> older.await() }) }
        runCurrent()
        clock.elapsed += 2_000L
        val second = async { runtime.prefetchTomorrow(Store { _, _, _ -> newer.await() }) }
        runCurrent()
        val fresh = day(TOMORROW, listOf(leg(2, "completed")))
        newer.complete(fresh); second.await()
        older.complete(day(TOMORROW, listOf(leg(1)))); first.await()
        assertThat(runtime.snapshot().tomorrow).isEqualTo(fresh)
    }

    private class Clock : PreparationClock {
        var current = ZonedDateTime.parse("2026-10-31T12:00:00+08:00[Asia/Shanghai]")
        var elapsed = 2_000L
        override fun now() = current
        override fun elapsedRealtimeMillis() = elapsed
    }
    private class Store(val loader: suspend (String, String, String) -> DailyFlightPlan) : ConfirmedFlightPlanStore {
        override suspend fun load(): FlightLegPlan? = error("not legacy")
        override suspend fun save(plan: FlightLegPlan) = error("not a write")
        override suspend fun loadDay(date: String, timezone: String, today: String) = loader(date, timezone, today)
    }
    private fun leg(index: Int, status: String = "waiting_takeoff", label: String = "") = DailyFlightLeg(
        FlightPlanLegSnapshot(index, null, null, "2026-10-31T04:00:00Z", status, 100.0, label, "", 1, ""), null)
    private fun day(date: String, legs: List<DailyFlightLeg>) =
        DailyFlightPlan(date, ZONE, legs = legs, ownerScope = FlightPlanOwnership.scope())
    private companion object { const val TODAY = "2026-10-31"; const val TOMORROW = "2026-11-01"; const val ZONE = "Asia/Shanghai" }
}
