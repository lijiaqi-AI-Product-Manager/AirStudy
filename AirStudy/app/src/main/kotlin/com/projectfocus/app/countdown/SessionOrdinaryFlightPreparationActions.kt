package com.projectfocus.app.countdown

import android.content.Context
import com.projectfocus.app.booking.LocalPreparationSceneSource
import com.projectfocus.app.booking.PlannedFlightSessionBridge
import com.projectfocus.app.booking.PreparationClock
import com.projectfocus.app.freeflight.FreeFlightCompletionCoordinator
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.app.home.HomeOriginSelection
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.booking.seat.SeatSceneChoice
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeRegistry
import com.projectfocus.service.focus.session.SessionRepository
import com.projectfocus.service.focus.session.SessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

class SessionOrdinaryFlightPreparationActions @Inject constructor(
    @ApplicationContext private val application: Context,
    private val sessions: SessionRepository,
    private val starts: PlannedFlightSessionBridge,
    private val scenes: LocalPreparationSceneSource,
    private val clock: PreparationClock,
    private val freeFlightRuntime: FreeFlightRuntimeRegistry,
    private val completion: FreeFlightCompletionCoordinator,
) : OrdinaryFlightPreparationActions {
    private val preferences get() = application.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE)

    override fun context(): OrdinaryFlightContext = preferences.let { saved ->
        OrdinaryFlightContext(TokenStore.snapshot(), code(saved.getString("origin_code", null)),
            code(saved.getString("destination_code", null)), saved.getString(HomeOriginSelection.MARKER, null))
    }

    override fun available(context: OrdinaryFlightContext): Boolean = this.context() == context &&
        sessions.state.value is SessionState.Idle && freeFlightRuntime.target.value == null && !completion.blocksPreparation()

    override suspend fun loadRoute(context: OrdinaryFlightContext): FlightLegPlan? {
        if (!available(context) || context.originCode.isEmpty() || context.destinationCode.isEmpty()) return null
        (application.applicationContext as? com.projectfocus.app.ProjectFocusApp)?.awaitInitialAirportSeed()
        if (!available(context)) return null
        val origin = HomeOriginSelection.selectedSnapshot(preferences, context.originCode)
            ?: sessions.findAirportForPlan(context.originCode) ?: return null
        val destination = sessions.findAirportForPlan(context.destinationCode) ?: return null
        return if (available(context)) FlightLegPlan.between(origin, destination) else null
    }

    override fun resolveScene(choice: SeatSceneChoice): PlannedScene? = runCatching {
        scenes.enabledScenes().firstOrNull {
            it.optionId == choice.optionId && (it.displayName ?: it.name) == choice.title && it.iconKey == choice.iconKey
        }
    }.getOrNull()

    override fun now() = clock.now()

    override suspend fun start(context: OrdinaryFlightContext, route: FlightLegPlan): Boolean =
        available(context) && starts.startOrdinary(route, context.account)

    private fun code(value: String?): String = value?.trim { it <= '\u0020' }?.uppercase(Locale.ROOT).orEmpty()
}
