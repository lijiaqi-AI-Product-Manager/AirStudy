package com.projectfocus.app.booking

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedDeparture
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.CancelBatchDeleteFlightPlanRequest
import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import com.projectfocus.core.network.plan.FlightPlanGateway
import com.projectfocus.core.network.plan.FlightPlanIdentityChangedException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyFlightPlanStoreTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private lateinit var preferences: SharedPreferences
    @Before fun setup() {
        TokenStore.clear()
        preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("daily_test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }
    @After fun teardown() { TokenStore.clear() }

    @Test fun completeDaysSurviveStoreRecreationAndZoneDateSeparation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val day = day()
        val other = day.copy(localDate = "2026-11-01", legs = emptyList())
        val tokyo = day.copy(timezone = "Asia/Tokyo", legs = day.legs.take(1))
        val first = SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher)
        first.saveDay(day); first.saveDay(other); first.saveDay(tokyo)
        val second = SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher)
        assertThat(second.loadDay(DATE, ZONE, DATE)).isEqualTo(day)
        assertThat(second.loadDay("2026-11-01", ZONE, DATE)).isEqualTo(other)
        assertThat(second.loadDay(DATE, "Asia/Tokyo", DATE)).isEqualTo(tokyo)
        assertThat(day.legs.last().snapshot.legStatus).isEqualTo("canceled")
        assertThat(day.legs.last().snapshot.arrivalDiffMinutes).isEqualTo(-3)
        assertThat(day.nextOrigin()?.code).isEqualTo("PVG")
    }

    @Test fun legacySnapshotMigratesOnceAndAnEmptyDayDoesNotResurrectIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher)
        store.save(route())
        val migrated = store.loadDay(DATE, ZONE, DATE)
        assertThat(migrated.legs.single().route).isEqualTo(route())
        store.saveDay(migrated.copy(legs = emptyList()))
        assertThat(SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher).loadDay(DATE, ZONE, DATE).legs).isEmpty()
        assertThat(store.load()).isNull()
        assertThat(store.loadDay("2026-11-01", ZONE, DATE).legs).isEmpty()
    }

    @Test fun failedMemoryFirstCommitRestoresWholeDayAndLegacyMirror() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val original = day()
        val real = SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher)
        real.saveDay(original)
        val before = preferences.all.toMap()
        val failing = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val delegate = preferences.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { delegate.remove(key); return this }
                    override fun commit(): Boolean { delegate.commit(); return false }
                }
            }
        }
        val error = failure { SharedPreferencesConfirmedFlightPlanStore(failing, dispatcher).saveDay(original.copy(legs = emptyList())) }
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(preferences.all).isEqualTo(before)
        assertThat(real.loadDay(DATE, ZONE, DATE)).isEqualTo(original)
    }

    @Test fun signedInSaveUsesWholeListAndIndependentDatesWithoutImportingLocalPlans() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway()
        val store = SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher, gateway)
        store.saveDay(day())
        val localValues = preferences.all.toMap()
        TokenStore.set("account-a")
        val emptyRemoteDay = store.loadDay(DATE, ZONE, DATE)
        assertThat(emptyRemoteDay.legs).isEmpty()
        assertThat(gateway.gets.single().planDate).isEqualTo(DATE)
        val saved = store.saveDay(emptyRemoteDay.copy(legs = day().legs))
        val request = gateway.upserts.single()
        assertThat(request.planDate).isEqualTo("2026-10-30")
        assertThat(request.localPlanDate).isEqualTo(DATE)
        assertThat(request.timezoneOffsetMinutes).isEqualTo(480)
        assertThat(request.legs.map { it.legStatus }).containsExactly("waiting_takeoff", "canceled").inOrder()
        assertThat(saved.legs.first().route?.scene).isEqualTo(route().scene)
        TokenStore.clear()
        assertThat(store.loadDay(DATE, ZONE, DATE)).isEqualTo(day())
        for ((key, value) in localValues) assertThat(preferences.all[key]).isEqualTo(value)
    }

    @Test fun missingOrWrongDateAcknowledgementNeverEntersTheCache() = runTest {
        val gateway = FakeGateway()
        val store = SharedPreferencesConfirmedFlightPlanStore(preferences, StandardTestDispatcher(testScheduler), gateway)
        TokenStore.set("account-a")
        val base = store.loadDay(DATE, ZONE, DATE)
        val before = preferences.all.toMap()
        gateway.returnNull = true
        assertThat(failure { store.saveDay(base.copy(legs = day().legs)) }).isInstanceOf(IOException::class.java)
        assertThat(preferences.all).isEqualTo(before)
        gateway.returnNull = false
        gateway.wrongDate = true
        assertThat(failure { store.saveDay(base.copy(legs = day().legs)) }).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(preferences.all).isEqualTo(before)
    }

    @Test fun accountChangeAndTokenRotationRequireFreshDayLoad() = runTest {
        val gateway = FakeGateway()
        val store = SharedPreferencesConfirmedFlightPlanStore(preferences, StandardTestDispatcher(testScheduler), gateway)
        TokenStore.set("account-a")
        val accountA = store.loadDay(DATE, ZONE, DATE)
        TokenStore.set("account-b")
        assertThat(failure { store.saveDay(accountA.copy(legs = day().legs)) }).isInstanceOf(FlightPlanIdentityChangedException::class.java)
        assertThat(gateway.upserts).isEmpty()
        val accountB = store.loadDay(DATE, ZONE, DATE)
        gateway.changeAccountOnSave = true
        val before = preferences.all.toMap()
        assertThat(failure { store.saveDay(accountB.copy(legs = day().legs)) }).isInstanceOf(FlightPlanIdentityChangedException::class.java)
        assertThat(preferences.all).isEqualTo(before)
    }

    @Test fun pastDateLoadsTheMonthHistoryWithoutIssuingDailyGet() = runTest {
        TokenStore.set("account-a")
        val gateway = FakeGateway()
        val store = SharedPreferencesConfirmedFlightPlanStore(preferences, StandardTestDispatcher(testScheduler), gateway)
        assertThat(store.loadDay("2026-09-15", ZONE, DATE).legs).isEmpty()
        assertThat(gateway.gets).isEmpty()
        val history = gateway.histories.single()
        assertThat(history.startDate).isEqualTo("2026-09-01")
        assertThat(history.endDate).isEqualTo("2026-09-30")
        assertThat(history.limit).isEqualTo(200)
    }

    @Test fun deletingLastLegUsesTheObservedDeleteRequestPrefixAndFullRemainder() = runTest {
        TokenStore.set("account-a")
        val gateway = FakeGateway()
        val store = SharedPreferencesConfirmedFlightPlanStore(preferences, StandardTestDispatcher(testScheduler), gateway)
        val loaded = store.loadDay(DATE, ZONE, DATE)
        val saved = store.saveDay(loaded.copy(legs = day().legs))
        store.deleteLastDay(saved.copy(legs = saved.legs.dropLast(1)))
        val request = gateway.upserts.last()
        assertThat(request.requestId).startsWith("req_del_2026-10-30_1_")
        assertThat(request.legs.size).isEqualTo(1)
        assertThat(request.legs.single().legIndex).isEqualTo(1)
    }

    @Test fun localCanceledBatchKeepsUnknownAndCompletedRowsAndSurvivesRecreation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher)
        val first = day().legs.first()
        val original = day().copy(legs = listOf(first.copy(snapshot = first.snapshot.copy(legStatus = "canceled")),
            first.copy(snapshot = first.snapshot.copy(legIndex = 2, legStatus = "future_status")),
            first.copy(snapshot = first.snapshot.copy(legIndex = 3, legStatus = "completed", actualArrivalAt = "receipt"))))
        store.saveDay(original)
        val result = store.deleteCanceledDay(original, DATE)
        assertThat(result.deletedCount).isEqualTo(1)
        assertThat(result.day!!.legs.map { it.snapshot.legStatus }).containsExactly("future_status", "completed").inOrder()
        assertThat(result.day!!.legs.map { it.snapshot.legIndex }).containsExactly(1, 2).inOrder()
        assertThat(result.day!!.legs.last().snapshot.actualArrivalAt).isEqualTo("receipt")
        assertThat(SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher).loadDay(DATE, ZONE, DATE)).isEqualTo(result.day)
        assertThat(result.day!!.legs.first().route?.scene?.optionId).isEqualTo("custom-id")
    }

    @Test fun remoteCanceledBatchUsesIndependentDatesAndKeepsSceneIdentityAfterReindexing() = runTest {
        TokenStore.set("account-a")
        val gateway = FakeGateway()
        val store = SharedPreferencesConfirmedFlightPlanStore(preferences, StandardTestDispatcher(testScheduler), gateway)
        val base = store.loadDay(DATE, ZONE, DATE)
        val retained = day().legs.first()
        val canceled = day().legs.last().copy(snapshot = day().legs.last().snapshot.copy(legIndex = 1))
        val ordered = base.copy(legs = listOf(canceled, retained.copy(snapshot = retained.snapshot.copy(legIndex = 2))))
        val saved = store.saveDay(ordered)
        gateway.deleteReply = DailyFlightPlanSnapshot(DATE, ZONE, listOf(retained.snapshot.copy(legIndex = 1)),
            "2026-10-30", deletedCount = 1)
        val result = store.deleteCanceledDay(saved, DATE)
        val request = gateway.deletes.single()
        assertThat(request.planDate).isEqualTo("2026-10-30")
        assertThat(request.localPlanDate).isEqualTo(DATE)
        assertThat(request.localToday).isEqualTo(DATE)
        assertThat(request.localTomorrow).isEqualTo("2026-11-01")
        assertThat(request.requestId).startsWith("batch_delete_")
        assertThat(request.userScopeId).isNull()
        assertThat(result.deletedCount).isEqualTo(1)
        assertThat(result.day!!.legs.single().route?.scene).isEqualTo(route().scene)
        assertThat(result.day!!.legs.single().route?.seat).isEqualTo("01A")
        assertThat(gateway.upserts).hasSize(1) // Batch deletion did not masquerade as full upsert.
    }

    @Test fun emptySuccessWrongScopeAndAccountChangedBatchAcknowledgementsCannotReplaceTheCache() = runTest {
        TokenStore.set("account-a")
        val gateway = FakeGateway()
        val store = SharedPreferencesConfirmedFlightPlanStore(preferences, StandardTestDispatcher(testScheduler), gateway)
        val base = store.loadDay(DATE, ZONE, DATE)
        val saved = store.saveDay(base.copy(legs = day().legs))
        val before = preferences.all.toMap()
        val emptySuccess = store.deleteCanceledDay(saved, DATE)
        assertThat(emptySuccess.day).isNull()
        assertThat(emptySuccess.deletedCount).isEqualTo(-1)
        assertThat(preferences.all).isEqualTo(before)
        gateway.deleteReply = DailyFlightPlanSnapshot("2026-11-01", ZONE, emptyList())
        assertThat(failure { store.deleteCanceledDay(saved, DATE) }).isInstanceOf(IllegalArgumentException::class.java)
        gateway.deleteReply = DailyFlightPlanSnapshot(DATE, "UTC", emptyList())
        assertThat(failure { store.deleteCanceledDay(saved, DATE) }).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(preferences.all).isEqualTo(before)
        gateway.deleteReply = DailyFlightPlanSnapshot(DATE, ZONE, emptyList())
        gateway.changeAccountOnDelete = true
        assertThat(failure { store.deleteCanceledDay(saved, DATE) }).isInstanceOf(FlightPlanIdentityChangedException::class.java)
        assertThat(preferences.all).isEqualTo(before)
    }

    private fun route() = FlightLegPlan.between(
        PlannedAirport(1, "PEK", "首都", "北京", "CN", 40.08, 116.58),
        PlannedAirport(2, "PVG", "浦东", "上海", "CN", 31.14, 121.8),
    ).copy(plannedDeparture = PlannedDeparture(DATE, 12, 34, ZONE), seat = "01A",
        scene = PlannedScene("custom-id", "研究", "work", 0xFF123456.toInt(), true, 100, "uuid", "研究"))
    private fun day(): DailyFlightPlan {
        val first = DailyFlightPlans.fromRoute(route(), DATE, ZONE, 1)
        val second = first.copy(snapshot = first.snapshot.copy(legIndex = 2, legStatus = "canceled", arrivalDiffMinutes = -3,
            actualArrivalAt = "2026-10-31T05:00:00Z", focusIconCode = 20))
        return DailyFlightPlan(DATE, ZONE, legs = listOf(first, second))
    }
    private suspend fun failure(block: suspend () -> Any?): Throwable {
        try { block() } catch (error: Throwable) { return error }
        throw AssertionError("Expected store failure")
    }
    private class FakeGateway : FlightPlanGateway {
        val gets = mutableListOf<GetFlightPlanRequest>()
        val histories = mutableListOf<FlightPlanHistoryRequest>()
        val upserts = mutableListOf<UpsertFlightPlanRequest>()
        val deletes = mutableListOf<CancelBatchDeleteFlightPlanRequest>()
        var deleteReply: DailyFlightPlanSnapshot? = null
        var changeAccountOnDelete = false
        var returnNull = false
        var wrongDate = false
        var changeAccountOnSave = false
        override suspend fun get(request: GetFlightPlanRequest): DailyFlightPlanSnapshot? { gets += request; return null }
        override suspend fun history(request: FlightPlanHistoryRequest): List<DailyFlightPlanSnapshot> { histories += request; return emptyList() }
        override suspend fun deleteCanceled(request: CancelBatchDeleteFlightPlanRequest): DailyFlightPlanSnapshot? {
            deletes += request
            if (changeAccountOnDelete) TokenStore.set("changed-account")
            return deleteReply
        }
        override suspend fun upsert(request: UpsertFlightPlanRequest): DailyFlightPlanSnapshot? {
            upserts += request
            if (changeAccountOnSave) TokenStore.set("changed-account")
            if (returnNull) return null
            return DailyFlightPlanSnapshot(if (wrongDate) "2026-11-01" else request.localPlanDate, request.timezone,
                request.legs.map { leg -> com.projectfocus.core.network.dto.FlightPlanLegSnapshot(leg.legIndex,
                    leg.departureAirport, leg.arrivalAirport, leg.scheduledDepartureAt, leg.legStatus, leg.distanceKm,
                    leg.flightNo, leg.seatNo, leg.focusIconCode, leg.focusSceneText) }, request.planDate)
        }
    }
    private companion object { const val DATE = "2026-10-31"; const val ZONE = "Asia/Shanghai" }
}
