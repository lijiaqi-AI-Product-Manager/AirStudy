package com.projectfocus.app.countdown

import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.booking.seat.SeatSceneChoice
import java.time.ZonedDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrdinaryFlightPreparationTest {
    @Test fun onlyExplicitDepartureStartsTheFrozenNumberSeatSceneAndRouteOnce() = runTest {
        val actions = Actions().apply { startGate = CompletableDeferred() }
        val flow = OrdinaryFlightPreparationCoordinator(actions, backgroundScope)
        flow.begin(); runCurrent()
        assertEquals(OrdinaryPreparationStep.SEAT, flow.state.value.step)
        assertEquals("SC8728", flow.state.value.route!!.flightNumber)
        val id = flow.state.value.requestId!!
        flow.inspectionCompleted(id); flow.depart(id)
        assertTrue(actions.starts.isEmpty())
        flow.chooseSeat(id, actions.choice)
        flow.confirmSeat(id, actions.choice)
        assertEquals(OrdinaryPreparationStep.BOARDING, flow.state.value.step)
        flow.inspectionCompleted(id)
        assertEquals(OrdinaryPreparationStep.BOARDING, flow.state.value.step)
        flow.checkIn(id)
        assertEquals(OrdinaryPreparationStep.INSPECTION, flow.state.value.step)
        flow.depart(id)
        assertTrue(actions.starts.isEmpty())
        flow.inspectionCompleted(id)
        assertEquals(OrdinaryPreparationStep.PREPARE, flow.state.value.step)
        flow.depart(id); runCurrent()
        assertTrue(actions.starts.isEmpty())
        flow.prepareBoarding(id)
        assertEquals(OrdinaryPreparationStep.READY, flow.state.value.step)
        actions.route = actions.route.copy(departure = actions.route.departure.copy(name = "Changed later"))
        flow.depart(id); flow.depart(id); runCurrent()
        assertEquals(1, actions.starts.size)
        val submitted = actions.starts.single()
        assertEquals("SC8728", submitted.flightNumber)
        assertEquals("Beijing airport", submitted.departure.name)
        assertEquals("1A", submitted.seat)
        assertEquals(actions.scene, submitted.scene)
        assertFalse(flow.cancel())
        actions.startGate!!.complete(true); runCurrent()
        assertEquals(OrdinaryPreparationStep.STARTED, flow.state.value.step)
    }

    @Test fun cancellationCannotResurrectANonCancellableAirportLookup() = runTest {
        val actions = Actions().apply { routeGate = CompletableDeferred(); nonCancellableLookup = true }
        val flow = OrdinaryFlightPreparationCoordinator(actions, backgroundScope)
        flow.begin(); runCurrent()
        assertTrue(flow.cancel())
        actions.routeGate!!.complete(actions.route); runCurrent()
        assertEquals(OrdinaryPreparationStep.CLOSED, flow.state.value.step)
        assertNull(flow.state.value.route)
        assertTrue(actions.starts.isEmpty())
    }

    @Test fun anAccountGenerationChangeWhileLookingUpAirportsRejectsTheResult() = runTest {
        val actions = Actions().apply { routeGate = CompletableDeferred() }
        val flow = OrdinaryFlightPreparationCoordinator(actions, backgroundScope)
        flow.begin(); runCurrent()
        actions.selected = actions.selected.copy(account = actions.selected.account.copy(generation = 8))
        actions.routeGate!!.complete(actions.route); runCurrent()
        assertEquals(OrdinaryPreparationStep.UNAVAILABLE, flow.state.value.step)
        assertTrue(actions.starts.isEmpty())
    }

    @Test fun accountRouteAndOriginMarkerChangesInvalidateAnUnsubmittedReadyFlight() = runTest {
        val changes: List<(OrdinaryFlightContext) -> OrdinaryFlightContext> = listOf(
            { it.copy(account = it.account.copy(generation = it.account.generation + 1)) },
            { it.copy(account = it.account.copy(token = "other-account")) },
            { it.copy(destinationCode = "TSN") },
            { it.copy(originMarker = "new-origin-selection") },
        )
        for (change in changes) {
            val actions = Actions()
            val flow = OrdinaryFlightPreparationCoordinator(actions, backgroundScope)
            flow.begin(); runCurrent(); ready(flow, actions)
            val id = flow.state.value.requestId!!
            actions.selected = change(actions.selected)
            flow.depart(id); runCurrent()
            assertEquals(OrdinaryPreparationStep.UNAVAILABLE, flow.state.value.step)
            assertTrue(actions.starts.isEmpty())
        }
    }

    @Test fun theCatalogMustStillContainTheConfirmedSceneAtSeatReturn() = runTest {
        val actions = Actions()
        val flow = OrdinaryFlightPreparationCoordinator(actions, backgroundScope)
        flow.begin(); runCurrent()
        val id = flow.state.value.requestId!!
        flow.chooseSeat(id, actions.choice)
        actions.sceneAvailable = false
        flow.confirmSeat(id, actions.choice)
        assertEquals(OrdinaryPreparationStep.SEAT, flow.state.value.step)
        assertNull(flow.state.value.selection)
        assertEquals(OrdinaryPreparationError.SELECTION, flow.state.value.error)
        assertTrue(actions.starts.isEmpty())
    }

    @Test fun catalogEditsAfterBoardingDoNotReplaceTheAlreadyConfirmedScene() = runTest {
        val actions = Actions()
        val flow = OrdinaryFlightPreparationCoordinator(actions, backgroundScope)
        flow.begin(); runCurrent(); ready(flow, actions)
        actions.sceneAvailable = false
        flow.depart(flow.state.value.requestId!!); runCurrent()
        assertEquals(actions.scene, actions.starts.single().scene)
        assertEquals(OrdinaryPreparationStep.STARTED, flow.state.value.step)
    }

    @Test fun aRejectedStartCanRetryTheSameFrozenFlightWithoutReopeningSeatSelection() = runTest {
        val actions = Actions().apply { accepted = false }
        val flow = OrdinaryFlightPreparationCoordinator(actions, backgroundScope)
        flow.begin(); runCurrent(); ready(flow, actions)
        val id = flow.state.value.requestId!!
        flow.depart(id); runCurrent()
        assertEquals(OrdinaryPreparationStep.READY, flow.state.value.step)
        assertEquals(OrdinaryPreparationError.START, flow.state.value.error)
        actions.accepted = true
        flow.depart(id); runCurrent()
        assertEquals(2, actions.starts.size)
        assertEquals(actions.starts[0], actions.starts[1])
        assertEquals(OrdinaryPreparationStep.STARTED, flow.state.value.step)
    }

    @Test fun callbacksFromACancelledSeatSurfaceCannotChangeTheNextRequest() = runTest {
        val actions = Actions()
        val flow = OrdinaryFlightPreparationCoordinator(actions, backgroundScope)
        flow.begin(); runCurrent()
        val old = flow.state.value.requestId!!
        assertTrue(flow.cancel())
        flow.begin(); runCurrent()
        val next = flow.state.value.requestId!!
        assertNotEquals(old, next)
        flow.chooseSeat(old, actions.choice); flow.confirmSeat(old, actions.choice)
        flow.checkIn(old); flow.inspectionCompleted(old); flow.prepareBoarding(old); flow.depart(old)
        assertEquals(OrdinaryPreparationStep.SEAT, flow.state.value.step)
        assertNull(flow.state.value.selection)
        assertTrue(actions.starts.isEmpty())
    }

    @Test fun normalCityInputsKeepLocalizedCityRawCityAndRawNameFallbacksSeparateFromCodes() {
        val airport = Actions().route.departure.copy(displayCity = " 北京 ")
        assertEquals("北京", OrdinaryFlightNumber.city(airport))
        assertEquals("Beijing", OrdinaryFlightNumber.city(airport.copy(displayCity = " ")))
        assertEquals("Beijing airport", OrdinaryFlightNumber.city(airport.copy(displayCity = " ", city = "\t")))
        assertEquals("--", OrdinaryFlightNumber.city(airport.copy(displayCity = "", city = "", name = "")))
    }

    @Test fun missingAirportsAndInvalidSeatLabelsCannotProduceAFlight() = runTest {
        val actions = Actions().apply { routeMissing = true }
        val flow = OrdinaryFlightPreparationCoordinator(actions, backgroundScope)
        flow.begin(); runCurrent()
        assertEquals(OrdinaryPreparationStep.UNAVAILABLE, flow.state.value.step)
        actions.routeMissing = false
        flow.begin(); runCurrent()
        val id = flow.state.value.requestId!!
        for (seat in listOf("31A", "1B", "A1", "", "001A")) flow.chooseSeat(id, actions.choice.copy(seat = seat))
        assertNull(flow.state.value.selection)
        flow.confirmSeat(id, actions.choice)
        assertEquals(OrdinaryPreparationStep.SEAT, flow.state.value.step)
        assertTrue(actions.starts.isEmpty())
    }

    @Test fun paddedSeatLabelsUseTheOrdinarySeatCaptionWithoutChangingTheSelectedChair() = runTest {
        val actions = Actions()
        val flow = OrdinaryFlightPreparationCoordinator(actions, backgroundScope)
        flow.begin(); runCurrent()
        val id = flow.state.value.requestId!!
        flow.chooseSeat(id, actions.choice.copy(seat = "01a"))
        flow.confirmSeat(id, actions.choice.copy(seat = "01a"))
        assertEquals("1A", flow.state.value.route!!.seat)
        assertEquals(OrdinaryPreparationStep.BOARDING, flow.state.value.step)
    }

    private fun ready(flow: OrdinaryFlightPreparationCoordinator, actions: Actions) {
        val id = flow.state.value.requestId!!
        flow.chooseSeat(id, actions.choice); flow.confirmSeat(id, actions.choice)
        flow.checkIn(id); flow.inspectionCompleted(id); flow.prepareBoarding(id)
    }

    private class Actions : OrdinaryFlightPreparationActions {
        var selected = OrdinaryFlightContext(TokenStore.Snapshot("ordinary-account", 7), "PEK", "SHA", "selection-1")
        var route = FlightLegPlan.between(
            PlannedAirport(1, "PEK", "Beijing airport", "Beijing", "CN", 40.0799, 116.6031),
            PlannedAirport(2, "SHA", "Shanghai airport", "Shanghai", "CN", 31.1979, 121.3363))
        val scene = PlannedScene("reading", "Reading", "reading", 0xFF4488AA.toInt(), false)
        val choice = OrdinarySeatChoice("1A", SeatSceneChoice("reading", "reading", "Reading", 3, 0, scene.color))
        val starts = mutableListOf<FlightLegPlan>()
        var routeGate: CompletableDeferred<FlightLegPlan?>? = null
        var startGate: CompletableDeferred<Boolean>? = null
        var nonCancellableLookup = false
        var routeMissing = false
        var accepted = true
        var sceneAvailable = true
        override fun context() = selected
        override fun available(context: OrdinaryFlightContext) = context == selected
        override suspend fun loadRoute(context: OrdinaryFlightContext): FlightLegPlan? =
            if (routeMissing) null else if (nonCancellableLookup) withContext(NonCancellable) { routeGate?.await() ?: route }
            else routeGate?.await() ?: route
        override fun resolveScene(choice: SeatSceneChoice): PlannedScene? = scene.takeIf { sceneAvailable && choice.optionId == it.optionId }
        override fun now(): ZonedDateTime = ZonedDateTime.parse("2026-11-10T18:30:00+08:00[Asia/Shanghai]")
        override suspend fun start(context: OrdinaryFlightContext, route: FlightLegPlan): Boolean {
            starts += route
            return startGate?.await() ?: accepted
        }
    }
}
