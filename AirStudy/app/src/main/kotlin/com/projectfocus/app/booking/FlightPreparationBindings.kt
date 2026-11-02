package com.projectfocus.app.booking

import android.content.Context
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.app.home.HomeOriginSelection
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.service.focus.session.SessionRepository
import com.projectfocus.service.focus.session.SessionState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface FlightPreparationActions {
    suspend fun findAirport(code: String): PlannedAirport?
    suspend fun startFlight(plan: FlightLegPlan): Boolean
    fun hasActiveSession(): Boolean
    suspend fun defaultOrigin(): PreparationOrigin? = null
    /** Original LastArrival API callback is still a separate integration boundary. */
    suspend fun lastArrivalOrigin(): PreparationOrigin? = null
    fun currentOriginCode(): String? = null
    fun hasActualFlight(): Boolean = hasActiveSession()
    suspend fun hasFlightArchive(): Boolean = false
}

data class PreparationOrigin(val code: String, val airport: PlannedAirport?)

class SessionFlightPreparationActions @Inject constructor(
    private val sessions: SessionRepository,
    @ApplicationContext private val context: Context,
    private val history: FlightHistoryDao,
    private val plannedSessions: PlannedFlightSessionBridge,
) : FlightPreparationActions {
    override suspend fun findAirport(code: String): PlannedAirport? =
        HomeOriginSelection.selectedSnapshot(context.getSharedPreferences(HomeMapStyleSelection.FILE_NAME,Context.MODE_PRIVATE),code)
            ?: sessions.findAirportForPlan(code)
    override suspend fun startFlight(plan: FlightLegPlan): Boolean = plannedSessions.start(plan)
    override fun hasActiveSession(): Boolean = sessions.state.value !is SessionState.Idle
    override fun hasActualFlight() = sessions.state.value is SessionState.Running || sessions.state.value is SessionState.Paused
    override suspend fun hasFlightArchive(): Boolean = withContext(Dispatchers.IO) { history.ongoing() != null }
    override fun currentOriginCode(): String? = context.getSharedPreferences("projectfocus_home", Context.MODE_PRIVATE)
        .getString("origin_code", null)?.trim { it <= '\u0020' }?.takeIf(String::isNotEmpty)
    override suspend fun defaultOrigin(): PreparationOrigin? {
        // Project-owned persisted Home origin plus its own DAO cache. This does
        // not read the reference application's preference namespace or GPS.
        val code = currentOriginCode() ?: return null
        return PreparationOrigin(code, findAirport(code))
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FlightPreparationBindings {
    @Binds
    abstract fun bindStore(store: SharedPreferencesConfirmedFlightPlanStore): ConfirmedFlightPlanStore

    @Binds
    abstract fun bindActions(actions: SessionFlightPreparationActions): FlightPreparationActions

    @Binds
    abstract fun bindScenes(source: LocalPreparationSceneSource): PreparationSceneSource

    @Binds
    abstract fun bindClock(clock: DevicePreparationClock): PreparationClock

    @Binds
    abstract fun bindEntitlement(access: SharedPreferencesPlanEntitlementAccess): PlanEntitlementAccess

    @Binds
    abstract fun bindGlobalPlan(store: SharedPreferencesGlobalFlightPlanStore): GlobalFlightPlanStore

    @Binds
    abstract fun bindPlanCompletion(dispatch: SharedPreferencesPlanCompletionDispatch): PlanCompletionActions
}
