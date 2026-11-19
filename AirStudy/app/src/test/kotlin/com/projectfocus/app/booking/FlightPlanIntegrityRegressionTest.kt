package com.projectfocus.app.booking

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedDeparture
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import com.projectfocus.core.network.plan.FlightPlanGateway
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/** Uses production VM, converter and SharedPreferences store; only the remote/action ports are fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlightPlanIntegrityRegressionTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<FlightPreparationViewModel>()
    private lateinit var preferences: SharedPreferences

    @Before fun setup() {
        TokenStore.clear()
        Dispatchers.setMain(dispatcher)
        preferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("r162_plan_integrity_regression", Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
    }

    @After fun teardown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        TokenStore.clear()
        preferences.edit().clear().commit()
    }

    @Test fun sameViewModelCannotStartAnOldAccountsConfirmedSnapshotAndReloadsTheNewAccount() = runTest(dispatcher) {
        TokenStore.set(ACCOUNT_A)
        val gateway = AccountGateway()
        val actions = RecordingActions()
        val vm = model(store(gateway), actions)
        advanceUntilIdle()
        assertThat(vm.state.value.confirmed).isTrue()
        assertThat(vm.state.value.departureDue).isTrue()

        TokenStore.set(ACCOUNT_B)
        vm.startFlight()
        advanceUntilIdle()

        assertThat(actions.started).isEmpty()
        assertThat(gateway.getAccounts).contains(ACCOUNT_B)
        assertThat(vm.state.value.confirmed).isFalse()
        assertThat(vm.state.value.dailyPlan!!.legs).isEmpty()
        assertThat(vm.state.value.started).isFalse()
    }

    @Test fun restoredSavedStateCannotStartThePreviousAccountsConfirmedSnapshot() = runTest(dispatcher) {
        TokenStore.set(ACCOUNT_A)
        val gateway = AccountGateway()
        val realStore = store(gateway)
        val saved = SavedStateHandle()
        val original = model(realStore, RecordingActions(), saved)
        advanceUntilIdle()
        assertThat(original.state.value.confirmed).isTrue()
        val copied = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        original.viewModelScope.cancel()

        TokenStore.set(ACCOUNT_B)
        val actions = RecordingActions()
        val restored = model(realStore, actions, copied)
        advanceUntilIdle()
        restored.startFlight()
        advanceUntilIdle()

        assertThat(actions.started).isEmpty()
        assertThat(gateway.getAccounts).contains(ACCOUNT_B)
        assertThat(restored.state.value.confirmed).isFalse()
        assertThat(restored.state.value.dailyPlan!!.legs).isEmpty()
        assertThat(restored.state.value.started).isFalse()
    }

    @Test fun legacyTomorrowSavedStateMigratesAndEditsTomorrowInsteadOfAppendingToToday() = runTest(dispatcher) {
        val tomorrowRoute = route(TOMORROW)
        val realStore = store()
        realStore.save(tomorrowRoute)
        val saved = SavedStateHandle(mapOf(
            "flight_preparation_draft_present_v1" to true,
            "flight_preparation_origin_v1" to tomorrowRoute.departure.code,
            "flight_preparation_destination_v1" to tomorrowRoute.destination.code,
            "flight_preparation_snapshot_v1" to FlightPlanJson.encode(tomorrowRoute),
            "flight_preparation_confirmed_v1" to true,
            "flight_preparation_started_v1" to false,
            "flight_preparation_stage_v2" to PreparationStage.EXPANDED.name,
            "flight_preparation_editing_v2" to false,
        ))
        val vm = model(realStore, RecordingActions(), saved)
        advanceUntilIdle()

        assertThat(vm.state.value.selectedDate).isEqualTo(TOMORROW)
        assertThat(vm.state.value.timezone).isEqualTo(ZONE)
        assertThat(vm.state.value.dailyPlan!!.legs.map { it.route }).containsExactly(tomorrowRoute)
        assertThat(vm.state.value.confirmed).isTrue()

        vm.selectSeat("02C")
        assertThat(vm.state.value.stage).isEqualTo(PreparationStage.PREVIEW)
        vm.confirmPlan()
        advanceUntilIdle()

        assertThat(realStore.loadDay(TODAY, ZONE, TODAY).legs).isEmpty()
        val tomorrow = realStore.loadDay(TOMORROW, ZONE, TODAY)
        assertThat(tomorrow.legs).hasSize(1)
        assertThat(tomorrow.legs.single().route?.plannedDeparture?.date).isEqualTo(TOMORROW)
        assertThat(tomorrow.legs.single().route?.seat).isEqualTo("02C")
        assertThat(vm.state.value.selectedDate).isEqualTo(TOMORROW)
        assertThat(vm.state.value.confirmed).isTrue()
    }

    @Test fun remoteAirportAndSceneChangesInvalidateOldRouteButAnUnchangedSceneKeepsItsIdentity() {
        val originalRoute = route()
        val previous = DailyFlightPlan(TODAY, ZONE, legs = listOf(DailyFlightPlans.fromRoute(originalRoute, TODAY, ZONE, 1)))
        val oldWire = previous.legs.single().snapshot
        val originalReply = DailyFlightPlanSnapshot(TODAY, ZONE, listOf(oldWire), "2026-10-30")
        val unchanged = DailyFlightPlans.fromSnapshot(originalReply, previous).legs.single()
        // Do not fix the stale-route defect by throwing away the independent custom-scene identity every time.
        assertThat(unchanged.route?.scene).isEqualTo(originalRoute.scene)

        val movedOrigin = originalRoute.departure.copy(name = "远端更新的起点", displayName = "远端更新的起点",
            latitude = 39.75, longitude = 116.20)
        val movedRoute = FlightLegPlan.between(movedOrigin, originalRoute.destination)
        val movedWire = oldWire.copy(departureAirport = requireNotNull(oldWire.departureAirport).copy(
            name = movedOrigin.name, latitude = movedOrigin.latitude.toString(), longitude = movedOrigin.longitude.toString()),
            distanceKm = movedRoute.distanceKm)
        val moved = DailyFlightPlans.fromSnapshot(originalReply.copy(legs = listOf(movedWire)), previous).legs.single()
        assertThat(moved.snapshot).isEqualTo(movedWire)
        assertThat(moved.route?.departure).isEqualTo(movedOrigin)
        assertThat(moved.route?.distanceKm).isEqualTo(movedRoute.distanceKm)
        assertThat(moved.route?.durationMinutes).isEqualTo(movedRoute.durationMinutes)

        val scene = SourceSceneCatalog.builtins.first { it.type != oldWire.focusIconCode }
        val changedSceneWire = oldWire.copy(focusIconCode = scene.type, focusSceneText = "远端场景更新")
        val changed = DailyFlightPlans.fromSnapshot(originalReply.copy(legs = listOf(changedSceneWire)), previous).legs.single()
        assertThat(changed.snapshot).isEqualTo(changedSceneWire)
        assertThat(changed.route?.scene?.iconKey).isEqualTo(scene.iconKey)
        assertThat(changed.route?.scene?.displayName ?: changed.route?.scene?.name).isEqualTo("远端场景更新")
        assertThat(changed.route?.scene?.optionId).isNotEqualTo(originalRoute.scene!!.optionId)
    }

    @Test fun damagedSignedInCacheStillFetchesAndRepairsTheActualRemoteDay() = runTest(dispatcher) {
        TokenStore.set(ACCOUNT_A)
        val gateway = AccountGateway()
        val realStore = store(gateway)
        val first = realStore.loadDay(TODAY, ZONE, TODAY)
        assertThat(first.legs.single().snapshot.flightNo).isEqualTo("RF-REVIEW-001")
        // Discover the key written by the production store rather than guessing the token digest.
        val cacheKey = preferences.all.keys.single { it.startsWith("daily_v2:") }
        check(preferences.edit().putString(cacheKey, "{").commit())
        gateway.getAccounts.clear()

        val recovered = realStore.loadDay(TODAY, ZONE, TODAY)

        assertThat(gateway.getAccounts).containsExactly(ACCOUNT_A)
        assertThat(recovered.legs.single().snapshot).isEqualTo(first.legs.single().snapshot)
        assertThat(recovered.legs.single().route).isNotNull()
        assertThat(preferences.getString(cacheKey, null)).isNotEqualTo("{")
        assertThat(store(gateway).loadDay(TODAY, ZONE, TODAY).legs).isEqualTo(recovered.legs)
    }

    private fun store(gateway: FlightPlanGateway? = null) =
        SharedPreferencesConfirmedFlightPlanStore(preferences, dispatcher, gateway)

    private fun model(store: ConfirmedFlightPlanStore, actions: RecordingActions,
        saved: SavedStateHandle = SavedStateHandle()) = FlightPreparationViewModel(saved, actions, store,
        PreparationSceneSource { emptyList() }, PreparationClock { NOW }).also { models += it }

    private class RecordingActions : FlightPreparationActions {
        val started = mutableListOf<FlightLegPlan>()
        override suspend fun findAirport(code: String): PlannedAirport? = listOf(PEK, PVG).firstOrNull { it.code == code }
        override suspend fun startFlight(plan: FlightLegPlan): Boolean { started += plan; return true }
        override fun hasActiveSession() = false
    }

    private class AccountGateway : FlightPlanGateway {
        val getAccounts = mutableListOf<String?>()
        override suspend fun get(request: GetFlightPlanRequest): DailyFlightPlanSnapshot {
            val account = TokenStore.peek()
            getAccounts += account
            val legs = if (account == ACCOUNT_A) listOf(DailyFlightPlans.fromRoute(route(), TODAY, ZONE, 1).snapshot
                .copy(flightNo = "RF-REVIEW-001")) else emptyList()
            return DailyFlightPlanSnapshot(request.localPlanDate, request.timezone, legs, "2026-10-30")
        }
        override suspend fun history(request: FlightPlanHistoryRequest): List<DailyFlightPlanSnapshot> =
            error("These today/tomorrow fixtures must not request history")
        override suspend fun upsert(request: UpsertFlightPlanRequest): DailyFlightPlanSnapshot? =
            error("Account validation and cache repair must not mutate the remote list")
    }

    private companion object {
        const val TODAY = "2026-10-31"
        const val TOMORROW = "2026-11-01"
        const val ZONE = "Asia/Shanghai"
        const val ACCOUNT_A = "r162-test-account-a"
        const val ACCOUNT_B = "r162-test-account-b"
        val NOW: ZonedDateTime = ZonedDateTime.parse("2026-10-31T12:34:00+08:00[Asia/Shanghai]")
        val PEK = PlannedAirport(1, "PEK", "首都", "北京", "CN", 40.08, 116.58)
        val PVG = PlannedAirport(2, "PVG", "浦东", "上海", "CN", 31.14, 121.8)
        fun route(date: String = TODAY) = FlightLegPlan.between(PEK, PVG).copy(seat = "01A",
            plannedDeparture = PlannedDeparture(date, 12, 0, ZONE),
            scene = PlannedScene("custom-review-id", "本地研究", "work", 0xFF345678.toInt(), true,
                42L, "review-client-uuid", "本地研究"))
    }
}
