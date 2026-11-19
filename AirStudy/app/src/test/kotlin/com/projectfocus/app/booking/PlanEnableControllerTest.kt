package com.projectfocus.app.booking

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightPlanAirportDto
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import java.io.IOException
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real production controller and SP store; only external entitlement/load/archive ports are fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanEnableControllerTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private val clock = Clock()
    @Before fun setup() { TokenStore.set(TOKEN) }
    @After fun teardown() { TokenStore.clear() }

    @Test fun cachedFalseShowsGateAndWatcherOnlyDismissesUntilAnotherUserOn() = runTest {
        val h = Harness(backgroundScope, qualified = false)
        runCurrent()
        h.controller.toggle(true)
        assertThat(h.controller.state.value.gateVisible).isTrue()
        assertThat(h.controller.state.value.gateAuthenticated).isTrue()
        assertThat(h.controller.state.value.switchOverride).isFalse()
        assertThat(h.controller.state.value.busy).isFalse()
        assertThat(h.entitlement.admissions).isEqualTo(1)
        assertThat(h.reopen().enabledDate).isNull()
        assertThat(h.loads).isEqualTo(0)
        h.controller.refreshGate()
        assertThat(h.entitlement.refreshes).isEqualTo(1)
        h.entitlement.publish(true)
        runCurrent()
        assertThat(h.controller.state.value.gateVisible).isFalse()
        assertThat(h.entitlement.admissions).isEqualTo(1)
        assertThat(h.reopen().pendingSelection).isNull()
        assertThat(h.reopen().activeSelection).isNull()
        assertThat(h.completed).isEmpty()
        // A separate actual user action is required after the watcher closes.
        h.controller.toggle(true); runCurrent()
        assertThat(h.entitlement.admissions).isEqualTo(2)
        assertThat(h.reopen().selectionPending).isTrue()
    }

    @Test fun denialGateUsesLocalAuthenticationPresenceWithoutTreatingItAsPaidAdmission() = runTest {
        TokenStore.clear()
        val h = Harness(backgroundScope, qualified = false)
        runCurrent()
        h.controller.toggle(true)
        assertThat(h.controller.state.value.gateAuthenticated).isFalse()
        TokenStore.set(TOKEN)
        h.entitlement.publish(false)
        runCurrent()
        assertThat(h.controller.state.value.gateVisible).isTrue()
        assertThat(h.controller.state.value.gateAuthenticated).isTrue()
        assertThat(h.reopen().enabledDate).isNull()
        assertThat(h.reopen().pendingSelection).isNull()
    }

    @Test fun admittedOnForAnotherDateRejectsBeforeReloadOrAnyPersistentEnable() = runTest {
        val h = Harness(backgroundScope, initialDay = day(date = "2026-11-01"))
        runCurrent()
        h.controller.toggle(true); runCurrent()
        assertThat(h.notices).containsExactly(PlanEnableMessage(PlanEnableNotice.ONLY_TODAY))
        assertThat(h.controller.state.value.switchOverride).isFalse()
        assertThat(h.controller.state.value.busy).isFalse()
        assertThat(h.loads).isEqualTo(0)
        assertThat(h.reopen().enabledDate).isNull()
        assertThat(h.reopen().pendingSelection).isNull()
        assertThat(h.completed).isEmpty()
    }

    @Test fun candidateUsesSortedWaitingWithBothEndpointsBeforeTheTemporaryFallback() = runTest {
        val invalidFirst = leg(1).copy(snapshot = leg(1).snapshot.copy(arrivalAirport = null))
        val selected = leg(2)
        val input = listOf(leg(9), leg(3, "in_progress"), selected, invalidFirst, leg(4, "completed"))
        val h = Harness(backgroundScope, initialDay = day(input))
        h.temporary = leg(99)
        runCurrent()
        h.controller.toggle(true); runCurrent()
        assertThat(h.controller.state.value.pendingNavigation?.candidate).isEqualTo(selected)
        assertThat(h.reopen().pendingSelection?.candidate).isEqualTo(selected)
        assertThat(h.reopen().enabledDate).isEqualTo(DATE)
        assertThat(h.currentDay.legs).containsExactlyElementsIn(input).inOrder()
        assertThat(h.loads).isEqualTo(0)
        assertThat(h.completed).isEmpty()
    }

    @Test fun validTemporaryCandidateIsUsedOnlyWhenNoEligibleWaitingHasEndpoints() = runTest {
        val h = Harness(backgroundScope, initialDay = day(listOf(leg(1).copy(snapshot = leg(1).snapshot.copy(departureAirport = null)),
            leg(2, "in_progress"))))
        val temporary = leg(7, origin = "CAN")
        h.temporary = temporary
        runCurrent()
        h.controller.toggle(true); runCurrent()
        assertThat(h.reopen().pendingSelection?.candidate).isEqualTo(temporary)
        assertThat(h.loads).isEqualTo(0)
        assertThat(h.notices).isEmpty()
    }

    @Test fun blankOriginAndCaseInsensitiveTrimmedOriginAreAcceptedWithoutReload() = runTest {
        for (origin in listOf<String?>(null, "", " \t ", "  pEk  ")) {
            val h = Harness(backgroundScope)
            h.origin = origin
            runCurrent()
            h.controller.toggle(true); runCurrent()
            assertThat(h.reopen().selectionPending).isTrue()
            assertThat(h.loads).isEqualTo(0)
            assertThat(h.notices).isEmpty()
        }
    }

    @Test fun refreshSuccessAndFailureBothRetryCurrentAdmissionWithoutASecondRead() = runTest {
        val loaded = day(listOf(leg(1, origin = "CAN")))
        val success = Harness(backgroundScope)
        success.origin = "CAN"
        success.load = { loaded }
        runCurrent()
        success.controller.toggle(true); runCurrent()
        assertThat(success.loads).isEqualTo(1)
        assertThat(success.installs).isEqualTo(1)
        assertThat(success.reopen().pendingSelection?.candidate).isEqualTo(loaded.legs.single())

        val failure = Harness(backgroundScope)
        failure.origin = "CAN"
        failure.load = {
            // A concurrent backing update is already visible when the failed
            // refresh callback re-enters a(false); it must not immediately return.
            failure.currentDay = loaded
            throw IOException("fixture refresh failed after another update")
        }
        runCurrent()
        failure.controller.toggle(true); runCurrent()
        assertThat(failure.loads).isEqualTo(1)
        assertThat(failure.installs).isEqualTo(0)
        assertThat(failure.reopen().pendingSelection?.candidate).isEqualTo(loaded.legs.single())
        assertThat(failure.notices).isEmpty()
    }

    @Test fun aSecondOriginMismatchReportsItsRequiredAirportInsteadOfReloadingAgain() = runTest {
        for (throws in listOf(false, true)) {
            val h = Harness(backgroundScope)
            h.origin = "CAN"
            h.load = { if (throws) throw IOException("still mismatched") else h.currentDay }
            runCurrent()
            h.controller.toggle(true); runCurrent()
            assertThat(h.loads).isEqualTo(1)
            assertThat(h.notices).containsExactly(PlanEnableMessage(PlanEnableNotice.FLY_BACK_FIRST, "PEK"))
            assertThat(h.controller.state.value.switchOverride).isFalse()
            assertThat(h.reopen().enabledDate).isNull()
        }
    }

    @Test fun absentCandidateAfterOneRefreshDistinguishesInProgressFromAnEmptyDay() = runTest {
        val inProgress = Harness(backgroundScope, initialDay = day(listOf(leg(1, "in_progress"))))
        runCurrent(); inProgress.controller.toggle(true); runCurrent()
        assertThat(inProgress.loads).isEqualTo(1)
        assertThat(inProgress.notices).containsExactly(PlanEnableMessage(PlanEnableNotice.IN_PROGRESS))
        assertThat(inProgress.reopen().selectionPending).isFalse()
        val empty = Harness(backgroundScope, initialDay = day(emptyList()))
        runCurrent(); empty.controller.toggle(true); runCurrent()
        assertThat(empty.loads).isEqualTo(1)
        assertThat(empty.notices).containsExactly(PlanEnableMessage(PlanEnableNotice.NO_WAITING))
        assertThat(empty.reopen().selectionPending).isFalse()
    }

    @Test fun pendingNavigationAndSeatReturnOnlyPersistMetadataWithoutCompletingOrMutatingTheDay() = runTest {
        val h = Harness(backgroundScope)
        val before = h.currentDay
        runCurrent(); h.controller.toggle(true); runCurrent()
        val pending = h.controller.state.value.pendingNavigation!!
        assertThat(h.reopen().activeSelection).isNull()
        assertThat(h.reopen().selectionPending).isTrue()
        assertThat(h.controller.acceptSeat("wrong-scope", "02C")).isFalse()
        assertThat(h.reopen().pendingSelection?.requestId).isEqualTo(pending.requestId)
        h.controller.navigationOpened(pending.requestId)
        assertThat(h.controller.state.value.pendingNavigation).isNull()
        assertThat(h.controller.acceptSeat(pending.requestId, " 02C ")).isTrue()
        assertThat(h.reopen().selectionPending).isFalse()
        assertThat(h.reopen().activeSelection?.seatNo).isEqualTo("02C")
        assertThat(h.reopen().inheritedSeat).isEqualTo("02C")
        assertThat(h.currentDay).isEqualTo(before)
        assertThat(h.currentDay.legs.single().snapshot.legStatus).isEqualTo("waiting_takeoff")
        assertThat(h.completed).isEmpty()
        assertThat(h.loads).isEqualTo(0)
    }

    @Test fun entitlementDowngradeOnlyClearsEnabledDateAndKeepsPendingSeatAndActiveMetadata() = runTest {
        val h = Harness(backgroundScope)
        h.seedActive()
        h.global.beginPending(h.currentDay, h.currentDay.legs.single())
        val before = h.reopen()
        runCurrent()
        h.entitlement.publish(false); runCurrent()
        assertThat(h.reopen()).isEqualTo(before.copy(enabledDate = null))
        assertThat(h.completed).isEmpty()
        assertThat(h.flyingReads).isEqualTo(0)
        assertThat(h.archiveReads).isEqualTo(0)
        assertThat(h.controller.state.value.pendingNavigation).isNull()
    }

    @Test fun offUsesTheDisplayedConjunctionAndReadsBothActualFlightAndArchivePorts() = runTest {
        data class Case(val displayed: Boolean, val flying: Boolean, val archive: Boolean,
                        val reads: Int, val rejected: Boolean, val completions: Int)
        val cases = listOf(
            Case(false, true, true, 0, false, 0),
            Case(true, true, false, 1, true, 0),
            Case(true, false, true, 1, true, 0),
            Case(true, true, true, 1, true, 0),
            Case(true, false, false, 1, false, 1))
        for (case in cases) {
            val h = Harness(backgroundScope)
            val active = h.seedActive()
            h.currentDay = day(listOf(leg(1, if (case.displayed) "in_progress" else "waiting_takeoff")))
            h.flying = case.flying; h.archive = { case.archive }
            val before = h.reopen()
            runCurrent(); h.controller.toggle(false); runCurrent()
            assertThat(h.flyingReads).isEqualTo(case.reads)
            assertThat(h.archiveReads).isEqualTo(case.reads)
            assertThat(h.completed).hasSize(case.completions)
            if (case.rejected) {
                assertThat(h.notices).containsExactly(PlanEnableMessage(PlanEnableNotice.CANNOT_CLOSE))
                assertThat(h.controller.state.value.switchOverride).isTrue()
                assertThat(h.reopen()).isEqualTo(before)
            } else {
                assertThat(h.notices).isEmpty()
                assertThat(h.reopen().enabledDate).isNull()
                assertThat(h.reopen().inheritedSeat).isNull()
                assertThat(h.reopen().activeSelection).isNull()
                if (case.completions == 1) assertThat(h.completed).containsExactly(active)
            }
        }
    }

    @Test fun offUsesOnlyAnExistingRecoverySelectionAndNeverSelectsAnArbitraryDisplayedLeg() = runTest {
        for (restore in listOf(false, true)) {
            val h = Harness(backgroundScope)
            h.global.beginPending(h.currentDay, h.currentDay.legs.single())
            h.currentDay = day(listOf(leg(1, "in_progress")))
            val recovery = ActivePlanSelection(DATE, ZONE, leg(9), "03F")
            h.recovery = recovery.takeIf { restore }
            runCurrent(); h.controller.toggle(false); runCurrent()
            assertThat(h.recoveryReads).isEqualTo(1)
            if (restore) assertThat(h.completed).containsExactly(recovery)
            else assertThat(h.completed).isEmpty()
            assertThat(h.reopen().enabledDate).isNull()
            assertThat(h.reopen().activeSelection).isNull()
        }
    }

    @Test fun offAfterSuspensionCannotCompleteOrDisableAfterSameTokenGenerationOrDateRevisionChanges() = runTest {
        for (changeAccount in listOf(true, false)) {
            val h = Harness(backgroundScope)
            h.seedActive(); h.currentDay = day(listOf(leg(1, "in_progress")))
            val before = h.reopen()
            val result = CompletableDeferred<Boolean>()
            h.archive = { result.await() }
            runCurrent(); h.controller.toggle(false); runCurrent()
            assertThat(h.controller.state.value.busy).isTrue()
            assertThat(h.flyingReads).isEqualTo(1)
            assertThat(h.archiveReads).isEqualTo(1)
            if (changeAccount) TokenStore.set(TOKEN) else {
                h.userRevision++
                h.currentDay = h.currentDay.copy(localDate = "2026-11-01")
            }
            result.complete(false); runCurrent()
            assertThat(h.completed).isEmpty()
            assertThat(h.reopen()).isEqualTo(before)
            assertThat(h.notices).isEmpty()
            assertThat(h.controller.state.value.busy).isFalse()
        }
    }

    @Test fun onRefreshAlsoDiscardsAReplyAfterSameTokenGenerationOrDateRevisionChanges() = runTest {
        for (changeAccount in listOf(true, false)) {
            val h = Harness(backgroundScope)
            h.origin = "CAN"
            val deferred = CompletableDeferred<DailyFlightPlan>()
            h.load = { deferred.await() }
            runCurrent(); h.controller.toggle(true); runCurrent()
            assertThat(h.loads).isEqualTo(1)
            if (changeAccount) TokenStore.set(TOKEN) else {
                h.userRevision++
                h.currentDay = h.currentDay.copy(localDate = "2026-11-01")
            }
            deferred.complete(day(listOf(leg(1, origin = "CAN")))); runCurrent()
            assertThat(h.installs).isEqualTo(0)
            assertThat(h.reopen().enabledDate).isNull()
            assertThat(h.reopen().pendingSelection).isNull()
            assertThat(h.notices).isEmpty()
            assertThat(h.controller.state.value.busy).isFalse()
        }
    }

    private inner class Harness(scope: CoroutineScope, qualified: Boolean = true, initialDay: DailyFlightPlan = day()) {
        val prefs: SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("plan_enable_test_" + UUID.randomUUID(), Context.MODE_PRIVATE)
        val global = SharedPreferencesGlobalFlightPlanStore(prefs, clock)
        val entitlement = Entitlement(qualified)
        var currentDay = initialDay
        var temporary: DailyFlightLeg? = null
        var userRevision = 0L
        var origin: String? = null
        var loads = 0; var installs = 0
        var load: suspend () -> DailyFlightPlan = { currentDay }
        var flying = false; var flyingReads = 0; var archiveReads = 0; var recoveryReads = 0
        var archive: suspend () -> Boolean = { false }
        var recovery: ActivePlanSelection? = null
        val completed = mutableListOf<ActivePlanSelection>()
        val notices = mutableListOf<PlanEnableMessage>()
        val controller = PlanEnableController(scope, entitlement, global, clock, { currentDay }, { temporary },
            { userRevision }, { origin }, { loads++; load() }, { installs++; currentDay = it },
            { flyingReads++; flying }, { archiveReads++; archive() }, { completed.add(it) }, { recoveryReads++; recovery })
        init {
            controller.bindHost("controller-fixture")
            scope.launch { controller.messages.collect { notices.add(it) } }
        }
        fun reopen() = SharedPreferencesGlobalFlightPlanStore(prefs, clock).current()
        fun seedActive(): ActivePlanSelection {
            val candidate = currentDay.legs.first()
            val pending = global.beginPending(currentDay, candidate)
            check(global.acceptSeat(pending.requestId, "02C", currentDay, candidate))
            return checkNotNull(global.current().activeSelection)
        }
    }

    private class Entitlement(initial: Boolean) : PlanEntitlementAccess {
        private val mutable = MutableStateFlow(PlanEntitlementState(FlightPlanOwnership.scope(), !TokenStore.peek().isNullOrEmpty(), initial))
        override val state = mutable.asStateFlow()
        var admissions = 0; var refreshes = 0
        override fun admit(): Boolean { admissions++; return state.value.qualified }
        override fun refresh() { refreshes++ }
        fun publish(value: Boolean) { mutable.value = PlanEntitlementState(FlightPlanOwnership.scope(), !TokenStore.peek().isNullOrEmpty(), value) }
    }
    private class Clock : PreparationClock {
        override fun now() = ZonedDateTime.parse("2026-10-31T12:34:00+08:00[Asia/Shanghai]")
    }
    private fun day(legs: List<DailyFlightLeg> = listOf(leg(1)), date: String = DATE) =
        DailyFlightPlan(date, ZONE, legs = legs, ownerScope = FlightPlanOwnership.scope())
    private fun leg(index: Int, status: String = "waiting_takeoff", origin: String = "PEK") = DailyFlightLeg(
        FlightPlanLegSnapshot(index, airport(origin), airport("PVG"), "2026-10-31T05:00:00Z", status,
            100.0, "PF8", "original-seat", 1, "学习"), null)
    private fun airport(code: String) = FlightPlanAirportDto(1, code, code, "Fixture City", "31.1", "121.1", "CN", countryRegionId = 0)
    private companion object { const val DATE = "2026-10-31"; const val ZONE = "Asia/Shanghai"; const val TOKEN = "plan-enable-fixture-token" }
}
