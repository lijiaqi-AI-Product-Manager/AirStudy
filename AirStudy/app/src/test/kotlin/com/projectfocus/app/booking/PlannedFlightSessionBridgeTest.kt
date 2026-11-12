package com.projectfocus.app.booking

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedDeparture
import com.projectfocus.app.home.HomeOriginSelection
import com.projectfocus.app.auth.AccountRestoreGate
import com.projectfocus.app.auth.AccountRestorePublication
import com.projectfocus.app.auth.EmailAccount
import com.projectfocus.app.auth.EmailAccountSessionStore
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.UpdateFlightPlanLegStatusRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import com.projectfocus.core.network.plan.FlightPlanGateway
import com.projectfocus.service.focus.session.MonotonicSource
import com.projectfocus.service.focus.session.NoOpWidgetPush
import com.projectfocus.service.focus.session.SessionController
import com.projectfocus.service.focus.session.SessionRepository
import com.projectfocus.service.focus.session.SessionState
import com.projectfocus.service.focus.session.TodayStatsNotifier
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real preparation action, Core/Room/DataStore, global/dispatch/handle/Home SPs. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PlannedFlightSessionBridgeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val harnesses = mutableListOf<Harness>()
    @Before fun setup() { TokenStore.set(TOKEN) }
    @After fun cleanup() {
        runBlocking { harnesses.forEach { it.close() } }
        TokenStore.clear()
    }

    @Test fun noOwnershipFromToggle() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        h.bridge.observe(); runCurrent()
        assertThat(h.gateway.requests).isEmpty()
        assertThat(h.handles.all).isEmpty()
        assertThat(h.sessions.startPlannedFlight(h.plan)).isTrue()
        runCurrent()
        assertThat(h.sessions.state.value).isInstanceOf(SessionState.Running::class.java)
        assertThat(h.gateway.requests).isEmpty()
        assertThat(h.handles.all).isEmpty()
    }

    @Test fun actualActionOwnsCore() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        val running = h.sessions.state.value as SessionState.Running
        assertThat(h.handle().sessionId).isEqualTo(running.sessionId)
        assertThat(h.database.flightHistoryDao().findById(running.sessionId)!!.status).isEqualTo("ONGOING")
        assertThat(h.gateway.requests.single().targetStatus).isEqualTo("in_progress")
        assertThat(h.gateway.requests.single().actualArrivalAt).isNull()
        assertThat(h.dispatch.snapshot()!!.sessionTimestampMillis).isEqualTo(START)
        assertThat(h.global.current().enabledDate).isEqualTo(DATE)
        assertThat(h.receipts.all).isEmpty()
        h.gateway.replies.single().complete(null); runCurrent()
        assertThat(h.dispatch.snapshot()!!.inProgressSent).isTrue()
        h.bridge.retryCurrent(); runCurrent()
        assertThat(h.gateway.requests).hasSize(1)
    }

    @Test fun assignedNumberRemainsPartOfTheOwnedRouteAfterColdRestoreAndCompletion() = runTest {
        val h = harness(backgroundScope)
        val assigned = h.plan.copy(flightNumber = "AS2701")
        h.enable(assigned)
        assertThat(h.actions().startFlight(assigned)).isTrue(); runCurrent()
        val id = (h.sessions.state.value as SessionState.Running).sessionId
        assertThat(h.handle().route.flightNumber).isEqualTo("AS2701")
        assertThat(h.sessions.currentRoute().flightNumber).isEqualTo("AS2701")
        assertThat(h.database.flightHistoryDao().findById(id)!!.flightNumber).isEqualTo("AS2701")
        h.recreate()
        assertThat(h.sessions.restore()).isInstanceOf(SessionState.Running::class.java)
        h.bridge.retryCurrent(); runCurrent()
        assertThat(h.handle().sessionId).isEqualTo(id)
        assertThat(h.sessions.currentRoute().flightNumber).isEqualTo("AS2701")
        h.ticks(60)
        h.bridge.retryCurrent(); runCurrent()
        val row = h.database.flightHistoryDao().findById(id)!!
        assertThat(row.status).isEqualTo("COMPLETED")
        assertThat(row.flightNumber).isEqualTo("AS2701")
        assertThat(h.handle().terminalHandedOff).isTrue()
    }

    @Test fun rejectActiveDoesNotClaim() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.sessions.startPlannedFlight(h.plan)).isTrue()
        val original = (h.sessions.state.value as SessionState.Running).sessionId
        assertThat(h.actions().startFlight(h.plan)).isFalse(); runCurrent()
        assertThat((h.sessions.state.value as SessionState.Running).sessionId).isEqualTo(original)
        assertThat(h.handles.all).isEmpty()
        assertThat(h.gateway.requests).isEmpty()
    }

    @Test fun unrelatedSelectionDoesNotNotify() = runTest {
        val h = harness(backgroundScope)
        h.enable(h.other)
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        assertThat(h.handle().selection).isNull()
        assertThat(h.gateway.requests).isEmpty()
        assertThat(h.receipts.all).isEmpty()
    }

    @Test fun activeSnapshotSurvivesMidnight() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        h.wall += 86_400_000L
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        assertThat(h.handle().selection?.localDate).isEqualTo(DATE)
        assertThat(h.gateway.requests.single().targetStatus).isEqualTo("in_progress")
        assertThat(h.gateway.requests.single().localPlanDate).isEqualTo(DATE)
        assertThat(h.dispatch.snapshot()!!.sessionTimestampMillis).isEqualTo(h.wall)
    }

    @Test fun naturalEndAdvancesWithoutAck() = runTest {
        // Real Room IO can suspend the test while runTest advances its own clock.
        // Drive the source arrival phase independently so its999/1000ms boundary
        // is measured from the actual completed notification, not after SQL returns.
        val phaseClock = TestCoroutineScheduler()
        // Unconfined cancellation drains this separate scheduler's collectors
        // immediately; test teardown must not wait for an unpumped dispatcher.
        val phaseScope = CoroutineScope(backgroundScope.coroutineContext + phaseClock + UnconfinedTestDispatcher(phaseClock))
        val h = harness(phaseScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); phaseClock.runCurrent()
        h.ticks(60); phaseClock.runCurrent()
        assertThat(phaseClock.currentTime).isEqualTo(0L)
        val arrived = h.sessions.state.value as SessionState.Arrived
        assertThat(arrived.abandoned).isFalse()
        assertThat(h.database.flightHistoryDao().findById(arrived.sessionId)!!.status).isEqualTo("COMPLETED")
        assertThat(h.gateway.requests.map { it.targetStatus }).containsExactly("in_progress", "completed").inOrder()
        assertThat(h.dispatch.exactReceiptMillis(DATE, 7)).isEqualTo(START + 60_000L)
        // The server's completion reply stays unresolved throughout phase6.
        phaseClock.advanceTimeBy(999); phaseClock.runCurrent()
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
        phaseClock.advanceTimeBy(1); phaseClock.runCurrent()
        assertThat(h.home.getString("origin_code", null)).isEqualTo("BBB")
        assertThat(h.home.getString("origin_city", null)).isEqualTo("显示目的城市")
        assertThat(h.actions().currentOriginCode()).isEqualTo("BBB")
        assertThat(h.dispatch.pendingStatus()?.targetStatus).isEqualTo("completed")
        assertThat(h.handle().originApplied).isTrue()
    }

    @Test fun cancelRestoresWaiting() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.gateway.replies.single().complete(null); runCurrent()
        h.ticks(10)
        assertThat(h.sessions.abandon()).isTrue(); runCurrent()
        val arrived = h.sessions.state.value as SessionState.Arrived
        assertThat(h.database.flightHistoryDao().findById(arrived.sessionId)!!.status).isEqualTo("CANCELLED")
        assertThat(h.gateway.requests.last().targetStatus).isEqualTo("waiting_takeoff")
        assertThat(h.gateway.requests.last().actualArrivalAt).isNull()
        assertThat(h.gateway.requests.last().arrivalDiffMinutes).isNull()
        advanceTimeBy(1_000); runCurrent()
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
        assertThat(h.receipts.all).isEmpty()
        assertThat(h.global.current().enabledDate).isEqualTo(DATE)
    }

    @Test fun restoreExactCoreId() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        val id = (h.sessions.state.value as SessionState.Running).sessionId
        h.gateway.replies.single().complete(null); runCurrent()
        h.ticks(60 / 2)
        h.recreate()
        assertThat((h.sessions.restore() as SessionState.Running).sessionId).isEqualTo(id)
        h.bridge.retryCurrent(); runCurrent()
        assertThat(h.handle().sessionId).isEqualTo(id)
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.sessions.abandon()).isTrue()
        h.bridge.retryCurrent(); runCurrent()
        assertThat(h.gateway.requests.last().targetStatus).isEqualTo("waiting_takeoff")
    }

    @Test fun terminalRecordRestoresOnce() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.ticks(60); runCurrent()
        val receipt = h.receipts.all.toMap()
        h.gateway.replies.last().complete(null); runCurrent()
        assertThat(h.sessions.settle()).isNotNull()
        h.recreate()
        assertThat(h.sessions.state.value).isEqualTo(SessionState.Idle)
        h.bridge.retryCurrent(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        h.awaitOriginCommit()
        assertThat(h.home.getString("origin_code", null)).isEqualTo("BBB")
        assertThat(h.gateway.requests).hasSize(2)
        assertThat(h.receipts.all).isEqualTo(receipt)
    }

    @Test fun accountGenerationGuardsArrival() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.ticks(60); runCurrent()
        TokenStore.set(TOKEN) // Same owner hash, new rejected callback generation.
        h.bridge.retryCurrent(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
    }

    @Test fun actualAccountRestoreBarrierAdoptsExactColdCoreAndCompletesArrival() = runTest {
        val h = persistedAccountFlight()
        val acceptedId = h.handle().sessionId
        val oldRaw = h.handles.all.toMap()
        restoreCold(h)
        assertThat((h.sessions.state.value as SessionState.Running).sessionId).isEqualTo(acceptedId)
        assertThat(h.handles.all).isEqualTo(oldRaw)
        assertThat(h.gateway.requests).hasSize(1) // No fabricated second started handoff.
        h.ticks(60); runCurrent()
        assertThat(h.gateway.requests.last().targetStatus).isEqualTo("completed")
        assertThat(h.gateway.requests).hasSize(2)
        advanceTimeBy(1_000); runCurrent(); h.awaitOriginCommit()
        assertThat(h.home.getString("origin_code", null)).isEqualTo("BBB")
        assertThat(h.handle().sessionId).isEqualTo(acceptedId)
    }

    @Test fun realRestoreWithoutBootstrapGateReproducesPriorOwnerRetirement() = runTest {
        val h = persistedAccountFlight()
        val acceptedId = h.handle().sessionId
        TokenStore.clear()
        h.recreate() // Existing direct-constructor compatibility path has no App restore gate.
        assertThat((h.sessions.restore() as SessionState.Running).sessionId).isEqualTo(acceptedId)
        h.accounts.restoreIfAnonymous() // Real SP read and real TokenStore publication, not set-as-refresh.
        h.bridge.retryCurrent(); h.bridge.observe(); runCurrent()
        h.ticks(60); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
        assertThat(h.handle().originApplied).isFalse()
    }

    @Test fun warmSameTokenSignInAfterBootstrapStillRetiresAcceptedHandle() = runTest {
        val h = persistedAccountFlight()
        restoreCold(h)
        val previous = TokenStore.snapshot()
        h.accounts.save(ACCOUNT, previous) // Actual sign-in publication; same String, newer generation.
        assertThat(TokenStore.snapshot().token).isEqualTo(previous.token)
        assertThat(TokenStore.snapshot().generation).isGreaterThan(previous.generation)
        h.bridge.retryCurrent(); runCurrent()
        h.ticks(60); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
    }

    @Test fun warmAccountSwitchAfterBootstrapCannotAdoptPriorAccountFlight() = runTest {
        val h = persistedAccountFlight()
        restoreCold(h)
        val previousOwner = FlightPlanOwnership.scope()
        h.accounts.save(EmailAccount("other-id", "other@example.test", "r169-other-synthetic-account"))
        assertThat(FlightPlanOwnership.scope()).isNotEqualTo(previousOwner)
        h.bridge.retryCurrent(); runCurrent()
        h.ticks(60); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
    }

    @Test fun logoutGenerationDuringBootstrapCannotRelabelLaterRestoreAsInitial() = runTest {
        val h = persistedAccountFlight()
        val acceptedId = h.handle().sessionId
        TokenStore.clear()
        val bootstrap = AccountRestoreGate()
        h.recreate(bootstrap::await)
        assertThat((h.sessions.restore() as SessionState.Running).sessionId).isEqualTo(acceptedId)
        TokenStore.clear() // A real null→null generation change after the App baseline.
        bootstrap.restore(h.accounts)
        h.bridge.retryCurrent(); h.bridge.observe(); runCurrent()
        h.ticks(60); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
    }

    @Test fun bootstrapRestorationStillRejectsAnotherCoreUuidOnSameRoute() = runTest {
        val h = persistedAccountFlight()
        val prior = h.handle().sessionId
        h.recreate() // Stop the old observers before replacing Core through its actual API.
        h.sessions.restore()
        assertThat(h.sessions.abandon()).isTrue()
        assertThat(h.sessions.settle()).isNotNull()
        assertThat(h.sessions.startPlannedFlight(h.plan)).isTrue()
        val replacement = (h.sessions.state.value as SessionState.Running).sessionId
        assertThat(replacement).isNotEqualTo(prior)
        restoreCold(h)
        h.ticks(60); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
    }

    @Test fun bootstrapRestorationStillRejectsDifferentFrozenRouteForSameCoreUuid() = runTest {
        val h = persistedAccountFlight()
        val prior = h.handle()
        val stale = prior.copy(route = prior.route.copy(departure = prior.route.departure.copy(name = "stale saved airport")))
        val key = prior.ownerScope + ":accepted-flight"
        check(h.handles.edit().putString(key, Json.encodeToString(AcceptedFlightHandle.serializer(), stale)).commit())
        restoreCold(h)
        assertThat((h.sessions.state.value as SessionState.Running).sessionId).isEqualTo(prior.sessionId)
        h.ticks(60); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
    }

    private suspend fun TestScope.persistedAccountFlight(): Harness {
        val h = harness(backgroundScope)
        h.accounts.save(ACCOUNT)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.gateway.replies.single().complete(null); runCurrent()
        assertThat(h.dispatch.snapshot()!!.inProgressSent).isTrue()
        return h
    }

    private suspend fun TestScope.restoreCold(h: Harness) {
        TokenStore.clear()
        val bootstrap = AccountRestoreGate()
        h.recreate(bootstrap::await)
        val row = h.database.flightHistoryDao().ongoing()!!
        assertThat((h.sessions.restore() as SessionState.Running).sessionId).isEqualTo(row.id)
        val recovering = async { h.bridge.retryCurrent() }
        runCurrent()
        assertThat(recovering.isCompleted).isFalse() // Real initial restore has not completed.
        bootstrap.restore(h.accounts)
        runCurrent(); recovering.await()
        h.bridge.observe(); runCurrent()
    }

    @Test fun statusDiskFailureKeepsFlight() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        h.statusStorage.failNext = true
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        assertThat(h.sessions.state.value).isInstanceOf(SessionState.Running::class.java)
        assertThat(h.database.flightHistoryDao().findById((h.sessions.state.value as SessionState.Running).sessionId)).isNotNull()
        h.bridge.retryCurrent(); runCurrent()
        assertThat(h.gateway.requests.map { it.targetStatus }).containsExactly("in_progress")
        assertThat(h.handle().startHandedOff).isTrue()
    }

    @Test fun laterFlightGuardsArrival() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.ticks(60); runCurrent()
        assertThat(h.sessions.settle()).isNotNull()
        assertThat(h.sessions.startPlannedFlight(h.other)).isTrue()
        advanceTimeBy(1_000); runCurrent()
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
    }

    @Test fun corruptOwnershipIsContained() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.recreate()
        h.handles.edit().putString(FlightPlanOwnership.scope() + ":accepted-flight", "{broken-json").commit()
        assertThat(h.sessions.restore()).isInstanceOf(SessionState.Running::class.java)
        h.bridge.retryCurrent(); runCurrent()
        assertThat(h.sessions.state.value).isInstanceOf(SessionState.Running::class.java)
        assertThat(h.gateway.requests).hasSize(1)
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
    }

    @Test fun historyReadFailureIsContained() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        assertThat(h.sessions.abandon()).isTrue(); runCurrent()
        assertThat(h.sessions.settle()).isNotNull()
        h.recreate()
        val before = h.gateway.requests.size
        // Actual Room query fails; missing data is not treated as a completed row.
        h.database.openHelper.writableDatabase.execSQL("DROP TABLE flight_history")
        h.bridge.retryCurrent(); runCurrent()
        assertThat(h.sessions.state.value).isEqualTo(SessionState.Idle)
        assertThat(h.gateway.requests).hasSize(before)
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
    }

    @Test fun abortRereadsRemovedActive() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.gateway.replies.single().complete(null); runCurrent()
        assertThat(h.dispatch.snapshot()!!.inProgressSent).isTrue()
        h.global.disable()
        assertThat(h.sessions.abandon()).isTrue(); runCurrent()
        assertThat(h.global.current().activeSelection).isNull()
        assertThat(h.gateway.requests.last().targetStatus).isEqualTo("waiting_takeoff")
        assertThat(h.gateway.requests.last().actualArrivalAt).isNull()
        assertThat(h.receipts.all).isEmpty()
    }

    @Test fun abortNoActiveUnsentKeepsPending() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        val pending = h.dispatch.snapshot()
        h.global.disable()
        assertThat(h.sessions.abandon()).isTrue(); runCurrent()
        assertThat(h.gateway.requests.map { it.targetStatus }).containsExactly("in_progress")
        assertThat(h.dispatch.snapshot()).isEqualTo(pending)
        assertThat(h.receipts.all).isEmpty()
    }

    @Test fun archiveReadThenCoreStart() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.ticks(60); runCurrent()
        assertThat(h.sessions.settle()).isNotNull()
        val entered = CompletableDeferred<OriginArchiveSnapshot>()
        val release = CompletableDeferred<Unit>()
        h.afterArchiveRead = { actual -> entered.complete(actual); release.await() }
        advanceTimeBy(1_000); runCurrent()
        val captured = entered.await()
        assertThat(captured.terminalRow!!.status).isEqualTo("COMPLETED")
        assertThat(captured.ongoingSessionId).isNull()
        // Actual Core changes after the actual Room results were captured.
        assertThat(h.sessions.startPlannedFlight(h.other)).isTrue()
        release.complete(Unit); runCurrent()
        assertThat(h.sessions.state.value).isInstanceOf(SessionState.Running::class.java)
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
        assertThat(h.handle().originApplied).isFalse()
    }

    @Test fun archiveReadThenCoreRestore() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.ticks(60); runCurrent()
        assertThat(h.sessions.settle()).isNotNull()
        h.recreate()
        h.bridge.retryCurrent(); runCurrent()
        val entered = CompletableDeferred<OriginArchiveSnapshot>()
        val release = CompletableDeferred<Unit>()
        h.afterArchiveRead = { actual -> entered.complete(actual); release.await() }
        advanceTimeBy(1_000); runCurrent()
        assertThat(entered.await().ongoingSessionId).isNull()
        // A production repository writes the future restored row, no fake DAO.
        val formerProcess = h.detachedRepository()
        assertThat(formerProcess.startPlannedFlight(h.other)).isTrue()
        val expectedId = (formerProcess.state.value as SessionState.Running).sessionId
        assertThat((h.sessions.restore() as SessionState.Running).sessionId).isEqualTo(expectedId)
        release.complete(Unit); runCurrent()
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
        assertThat(h.handle().originApplied).isFalse()
    }

    @Test fun originCommitHoldsCoreCommandLock() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.ticks(60); runCurrent()
        assertThat(h.sessions.settle()).isNotNull()
        var competingStart: Deferred<Boolean>? = null
        h.originStorage.afterNext = {
            // UNDISPATCHED drives the actual competing command until its first
            // suspension. With the Core mutex held it suspends before start;
            // without that guard Core publishes Running before its settings IO.
            competingStart = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                h.sessions.startPlannedFlight(h.other)
            }
            assertThat(h.sessions.state.value).isEqualTo(SessionState.Idle)
            assertThat(competingStart!!.isCompleted).isFalse()
        }
        advanceTimeBy(1_000); runCurrent()
        h.awaitOriginCommit()
        assertThat(checkNotNull(competingStart).await()).isTrue()
        assertThat(h.sessions.state.value).isInstanceOf(SessionState.Running::class.java)
        assertThat(h.home.getString("origin_code", null)).isEqualTo("BBB")
    }

    @Test fun originCommitGenerationCompensates() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.ticks(60); runCurrent()
        h.originStorage.afterNext = { TokenStore.set(TOKEN) }
        advanceTimeBy(1_000); runCurrent()
        assertThat(h.home.getString("origin_code", null)).isEqualTo("AAA")
        assertThat(h.home.getString("origin_city", null)).isNull()
        assertThat(h.handle().originApplied).isFalse()
    }

    @Test fun originRollbackKeepsNewManualSameCode() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.ticks(60); runCurrent()
        var newerMarker: String? = null
        h.originStorage.afterNext = {
            TokenStore.set(TOKEN)
            // This is the same writer now used by actual Home airport picking.
            HomeOriginSelection.selectManually(h.home, "BBB")
            newerMarker = h.home.getString(HomeOriginSelection.MARKER, null)
        }
        advanceTimeBy(1_000); runCurrent()
        assertThat(h.home.getString("origin_code", null)).isEqualTo("BBB")
        assertThat(h.home.getString(HomeOriginSelection.MARKER, null)).isEqualTo(newerMarker)
        assertThat(h.handle().originApplied).isFalse()
    }

    @Test fun originCommitOrdersOtherThreadIdentity() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.ticks(60); runCurrent()
        val before = TokenStore.snapshot()
        val attempted = CountDownLatch(1)
        val finished = CountDownLatch(1)
        h.originStorage.afterNext = {
            Thread {
                attempted.countDown()
                TokenStore.set(TOKEN)
                finished.countDown()
            }.apply { isDaemon = true; start() }
            check(attempted.await(5, TimeUnit.SECONDS))
            assertThat(finished.await(100, TimeUnit.MILLISECONDS)).isFalse()
            assertThat(TokenStore.snapshot()).isEqualTo(before)
        }
        advanceTimeBy(1_000); runCurrent()
        assertThat(withContext(Dispatchers.IO) { finished.await(5, TimeUnit.SECONDS) }).isTrue()
        assertThat(TokenStore.snapshot()).isNotEqualTo(before)
        assertThat(h.home.getString("origin_code", null)).isEqualTo("BBB")
    }

    @Test fun restoredTerminalDoesNotResurrectStart() = runTest {
        val h = harness(backgroundScope)
        h.enable()
        assertThat(h.actions().startFlight(h.plan)).isTrue(); runCurrent()
        h.recreate()
        assertThat(h.sessions.restore()).isInstanceOf(SessionState.Running::class.java)
        assertThat(h.sessions.abandon()).isTrue()
        assertThat(h.sessions.settle()).isNotNull()
        val old = h.handle()
        val raw = Json { encodeDefaults = true }.encodeToString(AcceptedFlightHandle.serializer(),
            old.copy(startHandedOff = false, terminalHandedOff = false, abandoned = null, endedAtMillis = null))
        h.handles.edit().putString(FlightPlanOwnership.scope() + ":accepted-flight", raw).commit()
        h.bridge.retryCurrent(); runCurrent()
        assertThat(h.gateway.requests.map { it.targetStatus }).containsExactly("in_progress", "waiting_takeoff").inOrder()
        assertThat(h.receipts.all).isEmpty()
    }

    @Test fun anOrdinaryStartNeverAdoptsAnOtherwiseMatchingDailyPlan() = runTest {
        val h = harness(backgroundScope)
        val route = h.plan.copy(flightNumber = "CA1234")
        h.enable(route)
        val selected = h.global.current().activeSelection
        assertThat(h.bridge.startOrdinary(route, TokenStore.snapshot())).isTrue(); runCurrent()
        assertThat(h.handle().selection).isNull()
        assertThat(h.sessions.currentRoute().flightNumber).isEqualTo("CA1234")
        assertThat(h.global.current().activeSelection).isEqualTo(selected)
        assertThat(h.gateway.requests).isEmpty()
        assertThat(h.sessions.abandon()).isTrue(); runCurrent()
        assertThat(h.gateway.requests).isEmpty()
    }

    @Test fun anOrdinaryStartRejectsAnIdentityChangedByTheInitialRestoreWait() = runTest {
        val h = harness(backgroundScope)
        val before = TokenStore.snapshot()
        h.recreate { TokenStore.set(TOKEN); null }
        assertThat(h.bridge.startOrdinary(h.plan.copy(flightNumber = "CA1234"), before)).isFalse(); runCurrent()
        assertThat(h.sessions.state.value).isEqualTo(SessionState.Idle)
        assertThat(h.handles.all).isEmpty()
        assertThat(h.database.flightHistoryDao().getAll()).isEmpty()
        assertThat(h.gateway.requests).isEmpty()
    }

    private fun harness(scope: CoroutineScope) = Harness(scope).also { harnesses += it }

    private inner class Harness(private val parent: CoroutineScope) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val preferencesFile = File(temporary.newFolder(), "accepted.preferences_pb")
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = preferenceScope,
            produceFile = { preferencesFile }))
        var wall = START
        private var mono = 0L
        private val clock = object : PreparationClock {
            override fun now(): ZonedDateTime = Instant.ofEpochMilli(wall).atZone(ZoneId.of(ZONE))
        }
        val plan = FlightLegPlan.between(PlannedAirport(1, "AAA", "A airport", "A city", "ZZ", 0.0, 0.0),
            PlannedAirport(2, "BBB", "B airport", "B city", "ZZ", 0.12, 0.0, displayCity = "显示目的城市"))
            .copy(seat = "01A", plannedDeparture = PlannedDeparture(DATE, 12, 0, ZONE))
        val other = FlightLegPlan.between(plan.departure,
            PlannedAirport(3, "CCC", "C airport", "C city", "ZZ", 0.24, 0.0))
            .copy(seat = "01A", plannedDeparture = plan.plannedDeparture)
        val global = SharedPreferencesGlobalFlightPlanStore(prefs(), clock)
        val handles = prefs()
        val accounts = EmailAccountSessionStore(prefs())
        private var accountRestore: suspend () -> AccountRestorePublication? = { null }
        val home = context.getSharedPreferences("projectfocus_home", Context.MODE_PRIVATE).also {
            it.edit().clear().putString("origin_code", "AAA").commit()
        }
        private val originCommits = Channel<Unit>(Channel.UNLIMITED)
        val originStorage = CommitBoundary(home).also {
            it.observed = { originCommits.trySend(Unit); Unit }
        }
        var afterArchiveRead: (suspend (OriginArchiveSnapshot) -> Unit)? = null
        private val statusReal = prefs()
        val statusStorage = CommitBoundary(statusReal)
        val receipts = prefs()
        val gateway = Gateway()
        private var networkScope = newScope()
        private var bridgeScope = newScope()
        var sessions = repository()
        var dispatch = dispatcher()
        var bridge = bridge()
        private fun newScope() = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))
        private fun repository() = SessionRepository(SessionController(MonotonicSource { mono }, { wall }),
            settings, database.flightHistoryDao(), database.airportDao(), NoOpWidgetPush, TodayStatsNotifier(), Dispatchers.IO)
        private fun dispatcher() = SharedPreferencesPlanCompletionDispatch(statusStorage, receipts, clock,
            gateway, networkScope, currentBacking = { listOf(DailyFlightPlans.fromRoute(plan, DATE, ZONE, 7)) },
            clearActiveIfMatches = global::clearActiveIfMatches,
            currentActiveForAbort = { global.current().activeSelection })
        private fun bridge() = PlannedFlightSessionBridge(sessions, database.flightHistoryDao(), global,
            dispatch, clock, handles, originStorage, bridgeScope,
            readOriginArchive = { id ->
                val actual = withContext(Dispatchers.IO) {
                    OriginArchiveSnapshot(database.flightHistoryDao().findById(id), database.flightHistoryDao().ongoing()?.id)
                }
                afterArchiveRead?.invoke(actual)
                actual
            }, awaitInitialAccountRestore = accountRestore)
        fun detachedRepository() = repository()
        suspend fun awaitOriginCommit() = withContext(Dispatchers.IO) {
            withTimeout(10_000L) { originCommits.receive() }
        }
        fun actions() = SessionFlightPreparationActions(sessions, context, database.flightHistoryDao(), bridge)
        fun enable(route: FlightLegPlan = plan) {
            val leg = DailyFlightPlans.fromRoute(route, DATE, ZONE, 7)
            val day = DailyFlightPlan(DATE, ZONE, legs = listOf(leg), ownerScope = FlightPlanOwnership.scope())
            val pending = global.beginPending(day, leg)
            check(global.acceptSeat(pending.requestId, route.seat, day, leg))
        }
        suspend fun ticks(count: Int) {
            repeat(count) { mono += 1_000L; wall += 1_000L; sessions.tick() }
        }
        suspend fun recreate(awaitRestore: suspend () -> AccountRestorePublication? = { null }) {
            bridgeScope.coroutineContext[Job]!!.cancelAndJoin()
            networkScope.coroutineContext[Job]!!.cancelAndJoin()
            bridgeScope = newScope(); networkScope = newScope()
            accountRestore = awaitRestore
            sessions = repository(); dispatch = dispatcher(); bridge = bridge()
        }
        fun handle(): AcceptedFlightHandle = Json { ignoreUnknownKeys = true }.decodeFromString(
            AcceptedFlightHandle.serializer(), handles.getString(FlightPlanOwnership.scope() + ":accepted-flight", null)!!)
        suspend fun close() {
            bridgeScope.coroutineContext[Job]!!.cancelAndJoin()
            networkScope.coroutineContext[Job]!!.cancelAndJoin()
            preferenceScope.coroutineContext[Job]!!.cancelAndJoin()
            database.close()
        }
        private fun prefs() = context.getSharedPreferences("r168_owned_flight_" + UUID.randomUUID(), Context.MODE_PRIVATE)
    }

    private class Gateway : FlightPlanGateway {
        val requests = mutableListOf<UpdateFlightPlanLegStatusRequest>()
        val replies = mutableListOf<CompletableDeferred<DailyFlightPlanSnapshot?>>()
        override suspend fun updateLegStatus(request: UpdateFlightPlanLegStatusRequest): DailyFlightPlanSnapshot? {
            requests += request
            return CompletableDeferred<DailyFlightPlanSnapshot?>().also { replies += it }.await()
        }
        override suspend fun get(request: GetFlightPlanRequest): DailyFlightPlanSnapshot? = error("No GET")
        override suspend fun upsert(request: UpsertFlightPlanRequest): DailyFlightPlanSnapshot? = error("No upsert")
        override suspend fun history(request: FlightPlanHistoryRequest): List<DailyFlightPlanSnapshot> = error("No history scan")
    }

    private class CommitBoundary(private val real: SharedPreferences) : SharedPreferences by real {
        var failNext = false
        var afterNext: (() -> Unit)? = null
        var observed: (() -> Unit)? = null
        override fun edit(): SharedPreferences.Editor {
            val editor = real.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
                override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }
                override fun commit(): Boolean {
                    val fail = failNext; val callback = afterNext
                    failNext = false; afterNext = null
                    val result = editor.commit()
                    try { callback?.invoke() } finally { observed?.invoke() }
                    return result && !fail
                }
            }
        }
    }

    private companion object {
        const val DATE = "2026-10-31"
        const val ZONE = "Asia/Shanghai"
        const val START = 1793419200000L
        const val TOKEN = "r168-synthetic-accepted-flight"
        val ACCOUNT = EmailAccount("r169-synthetic-account", "restored@example.test", TOKEN)
    }
}
