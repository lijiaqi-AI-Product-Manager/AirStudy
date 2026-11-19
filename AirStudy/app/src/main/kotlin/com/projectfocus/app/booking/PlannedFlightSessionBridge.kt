package com.projectfocus.app.booking

import android.content.Context
import android.content.SharedPreferences
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.network.di.NetworkModule.NetworkScope
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.app.home.HomeOriginSelection
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.auth.AccountRestorePublication
import com.projectfocus.service.focus.session.RouteInfo
import com.projectfocus.service.focus.session.SessionRepository
import com.projectfocus.service.focus.session.SessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
internal data class AcceptedFlightHandle(
    val ownerScope: String,
    val sessionId: String,
    val admissionMillis: Long,
    val route: FlightLegPlan,
    val selection: ActivePlanSelection?,
    val accepted: Boolean = false,
    val startHandedOff: Boolean = false,
    val abandoned: Boolean? = null,
    val endedAtMillis: Long? = null,
    val terminalHandedOff: Boolean = false,
    val originApplied: Boolean = false,
)

internal data class OriginArchiveSnapshot(
    val terminalRow: FlightHistoryEntity?,
    val ongoingSessionId: String?,
)

/**
 * Own accepted-Core adapter for source boarding/abort/K0 notifications.
 * A persisted intent chooses a new Core session UUID before start, so a cold
 * restore can match that exact ID and route. An enabled switch, seat return or
 * unrelated Running snapshot never establishes ownership.
 */
@Singleton
class PlannedFlightSessionBridge internal constructor(
    private val sessions: SessionRepository,
    private val history: FlightHistoryDao,
    private val global: GlobalFlightPlanStore,
    private val completion: PlanCompletionActions,
    private val clock: PreparationClock,
    private val preferences: SharedPreferences,
    private val home: SharedPreferences,
    private val scope: CoroutineScope,
    private val identity: () -> TokenStore.Snapshot = TokenStore::snapshot,
    private val readOriginArchive: suspend (String) -> OriginArchiveSnapshot = { id ->
        withContext(Dispatchers.IO) { OriginArchiveSnapshot(history.findById(id), history.ongoing()?.id) }
    },
    private val awaitInitialAccountRestore: suspend () -> AccountRestorePublication? = { null },
) {
    @Inject constructor(
        sessions: SessionRepository,
        history: FlightHistoryDao,
        global: GlobalFlightPlanStore,
        completion: PlanCompletionActions,
        clock: PreparationClock,
        @ApplicationContext context: Context,
        @NetworkScope scope: CoroutineScope,
    ) : this(sessions, history, global, completion, clock,
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
        context.getSharedPreferences("projectfocus_home", Context.MODE_PRIVATE), scope,
        awaitInitialAccountRestore = {
            (context.applicationContext as? ProjectFocusApp)?.awaitInitialAccountRestore()
        })

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val commands = Mutex()
    private val observerLock = Any()
    private var observer: Job? = null
    private var originJob: Job? = null
    private var originSession: String? = null
    private var account = identity()
    private var initialAccountRestoreChecked = false
    private var memory: AcceptedFlightHandle? = null
    // A same-token logout/login generation cannot re-adopt an old in-process
    // handle. A fresh process instead verifies exact Core UUID and frozen route.
    private val retiredOwners = mutableSetOf<String>()

    fun observe() = synchronized(observerLock) {
        if (observer?.isActive == true) return@synchronized
        observer = scope.launch {
            sessions.state.collect { retryCurrent() }
        }
    }

    fun resume() {
        observe()
        scope.launch { retryCurrent(retryTransport = true) }
    }

    suspend fun retryCurrent(retryTransport: Boolean = false) = commands.withLock {
        val current = syncIdentity()
        try {
            val handle = read(current) ?: return@withLock
            reconcile(handle, current)
            if (retryTransport && identity() == current && handle.selection != null) {
                val pending = completion.flightStatuses?.snapshot()
                if (pending?.selection == handle.selection &&
                    pending.sessionTimestampMillis == handle.admissionMillis) {
                    completion.flightStatuses?.retryPendingStatus()
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { Timber.w(error, "Planned session handoff remains pending") }
    }

    /** Actual production preparation action. A status failure cannot reject Core. */
    suspend fun start(plan: FlightLegPlan): Boolean = startSession(plan, associatePlan = true)

    /** A normal flight cannot adopt a matching daily-plan selection. */
    suspend fun startOrdinary(plan: FlightLegPlan, expectedIdentity: TokenStore.Snapshot): Boolean =
        startSession(plan, associatePlan = false, expectedIdentity = expectedIdentity)

    private suspend fun startSession(plan: FlightLegPlan, associatePlan: Boolean,
                                    expectedIdentity: TokenStore.Snapshot? = null): Boolean = commands.withLock {
        observe()
        val current = syncIdentity()
        if (expectedIdentity != null && current != expectedIdentity) return@withLock false
        if (sessions.state.value !is SessionState.Idle) return@withLock false
        val owner = FlightPlanOwnership.scope(current)
        val state = global.current()
        val selection = state.activeSelection?.takeIf {
            associatePlan && state.ownerScope == owner && it.candidate.route?.copy(seat = plan.seat) == plan
        }?.copy(seatNo = plan.seat.orEmpty())
        val handle = AcceptedFlightHandle(owner, UUID.randomUUID().toString(),
            clock.now().toInstant().toEpochMilli(), plan, selection)
        // This is Own recovery metadata, not a fabricated started notification.
        // Disk failure retains a memory copy and does not prevent Core starting.
        persistBestEffort(handle, current)
        withContext(NonCancellable) {
            if (identity() != current) return@withContext false
            val accepted = sessions.startPlannedFlight(plan, handle.sessionId, current)
            if (!accepted) {
                discard(handle, current)
                return@withContext false
            }
            persistBestEffort(handle.copy(accepted = true), current)
            // Published Core state is now authoritative. It may already be a
            // same-session terminal state; matching is performed before effects.
            try { reconcile(checkNotNull(memory), current) }
            catch (error: Exception) { Timber.w(error, "Accepted flight has a pending plan handoff") }
            true
        }
    }

    private suspend fun reconcile(original: AcceptedFlightHandle, current: TokenStore.Snapshot) {
        checkCurrent(current)
        var state = sessions.state.value
        if (state is SessionState.Idle) {
            // Core restores ONGOING rows. A crash after its terminal SQL commit
            // instead needs this exact row, never the newest record by date.
            val row = withContext(Dispatchers.IO) { history.findById(original.sessionId) } ?: return
            checkCurrent(current)
            if (!matchesRow(row, original.route)) return
            if (row.status !in setOf(SessionRepository.STATUS_COMPLETED, SessionRepository.STATUS_CANCELLED)) return
            val abandoned = row.status == SessionRepository.STATUS_CANCELLED
            val ended = original.endedAtMillis ?: row.endTimeMillis ?: row.updatedAt.takeIf { abandoned } ?: return
            state = SessionState.Arrived(row.id, row.categoryId ?: SessionRepository.NO_CATEGORY,
                row.durationMinutes * 60_000L, row.startTimeMillis, row.actualFocusMinutes * 60_000L,
                ended, abandoned)
        } else if (sessionId(state) != original.sessionId ||
            !matches(sessions.currentRoute(), original.route, state)) return
        var handle = original
        if (!handle.accepted) {
            // Only a real matching Core session can adopt a pre-start disk intent.
            handle = handle.copy(accepted = true)
            persistBestEffort(handle, current)
        }
        if (state is SessionState.Arrived) {
            if (handle.abandoned == null) {
                handle = handle.copy(abandoned = state.abandoned, endedAtMillis = state.endedAtWallMs)
                persistBestEffort(handle, current)
            }
            if (handle.abandoned != state.abandoned || handle.endedAtMillis != state.endedAtWallMs) return
            // Local arrival is independent of whether plan handoff/storage works.
            if (!state.abandoned && !handle.originApplied) scheduleOrigin(handle, current)
        }
        // A restored terminal must never recreate obsolete in_progress before B
        // inspects its actual existing sent/pending state.
        if (!handle.startHandedOff && state !is SessionState.Arrived) {
            if (handle.selection != null) {
                val handoff = completion.sessionStatuses ?: return
                handoff.handoff(AcceptedPlanStatusCommand(handle.sessionId, handle.selection,
                    handle.admissionMillis, AcceptedPlanStatus.STARTED), current)
            }
            handle = handle.copy(startHandedOff = true)
            persistBestEffort(handle, current)
        }
        if (state !is SessionState.Arrived) return
        if (!handle.terminalHandedOff) {
            if (handle.selection != null) {
                val handoff = completion.sessionStatuses ?: return
                handoff.handoff(AcceptedPlanStatusCommand(handle.sessionId, handle.selection,
                    handle.admissionMillis,
                    if (state.abandoned) AcceptedPlanStatus.ABORTED else AcceptedPlanStatus.COMPLETED,
                    completedAtMillis = state.endedAtWallMs.takeUnless { state.abandoned }), current)
            }
            handle = handle.copy(terminalHandedOff = true)
            persistBestEffort(handle, current)
        }
    }

    private fun scheduleOrigin(handle: AcceptedFlightHandle, current: TokenStore.Snapshot) {
        if (originSession == handle.sessionId && originJob?.isActive == true) return
        originJob?.cancel()
        originSession = handle.sessionId
        originJob = scope.launch {
            try {
                delay(1_000L)
                commands.withLock {
                if (syncIdentity() != current) return@withLock
                val latest = read(current)?.takeIf {
                    it.sessionId == handle.sessionId && it.accepted &&
                        it.abandoned == false && !it.originApplied
                } ?: return@withLock
                try {
                    var archiveRead = false
                    var archive: OriginArchiveSnapshot? = null
                    while (true) {
                        val result = sessions.withStableSessionSnapshot { state, route ->
                            checkCurrent(current)
                            if (state is SessionState.Idle && !archiveRead)
                                return@withStableSessionSnapshot OriginGuardResult.NEED_ARCHIVE
                            val valid = when (state) {
                                is SessionState.Arrived -> state.sessionId == latest.sessionId &&
                                    !state.abandoned && state.endedAtWallMs == latest.endedAtMillis &&
                                    matches(route, latest.route, state)
                                SessionState.Idle -> archive?.let { snapshot ->
                                    val row = snapshot.terminalRow
                                    row != null && row.id == latest.sessionId &&
                                        row.status == SessionRepository.STATUS_COMPLETED &&
                                        row.endTimeMillis == latest.endedAtMillis && matchesRow(row, latest.route) &&
                                        (snapshot.ongoingSessionId == null || snapshot.ongoingSessionId == latest.sessionId)
                                } == true
                                is SessionState.Running, is SessionState.Paused -> false
                            }
                            if (!valid) OriginGuardResult.REJECTED else {
                                HomeOriginSelection.advance(home, latest.route.destination, current, identity)
                                OriginGuardResult.APPLIED
                            }
                        }
                        if (result == OriginGuardResult.APPLIED) {
                            persistBestEffort(latest.copy(originApplied = true), current)
                            break
                        }
                        if (result == OriginGuardResult.REJECTED) break
                        // No IO is held under the Core lock. After real Room
                        // lookup, the loop acquires that lock and checks again.
                        archiveRead = true
                        archive = readOriginArchive(latest.sessionId)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { Timber.w(error, "Arrival origin update remains pending") }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { Timber.w(error, "Arrival ownership could not be verified") }
        }
    }

    private suspend fun syncIdentity(): TokenStore.Snapshot {
        val restored = if (!initialAccountRestoreChecked) {
            awaitInitialAccountRestore().also { initialAccountRestoreChecked = true }
        } else null
        val next = identity()
        if (next != account) {
            // This exception is one real Application restore, not any anonymous→account
            // change. Existing anonymous ownership or any intervening login/logout
            // still takes the ordinary retirement path. read/reconcile retain the
            // exact persisted owner, Core UUID and complete frozen-route checks.
            val bootstrap = restored != null && account == restored.before && next == restored.after &&
                memory == null && retiredOwners.isEmpty() && originJob == null && originSession == null
            if (!bootstrap) {
                retiredOwners += FlightPlanOwnership.scope(account)
                retiredOwners += FlightPlanOwnership.scope(next)
                memory = null
                originJob?.cancel()
                originSession = null
            }
            account = next
        }
        return next
    }

    private fun read(current: TokenStore.Snapshot): AcceptedFlightHandle? {
        checkCurrent(current)
        val owner = FlightPlanOwnership.scope(current)
        memory?.takeIf { it.ownerScope == owner }?.let { return it }
        if (owner in retiredOwners) return null
        val raw = preferences.getString(key(owner), null) ?: return null
        return json.decodeFromString(AcceptedFlightHandle.serializer(), raw).also {
            require(it.ownerScope == owner && it.sessionId.isNotBlank())
            require(it.admissionMillis >= 0)
            checkCurrent(current)
            memory = it
        }
    }

    private fun persistBestEffort(value: AcceptedFlightHandle, current: TokenStore.Snapshot) {
        try {
            checkCurrent(current)
            memory = value
            val key = key(value.ownerScope)
            val previous = preferences.getString(key, null)
            val raw = json.encodeToString(AcceptedFlightHandle.serializer(), value)
            try {
                if (!preferences.edit().putString(key, raw).commit()) throw IOException(STORAGE)
                checkCurrent(current)
                check(preferences.getString(key, null) == raw)
            } catch (error: Exception) {
                if (preferences.getString(key, null) == raw) {
                    val rollback = preferences.edit()
                    if (previous == null) rollback.remove(key) else rollback.putString(key, previous)
                    if (!rollback.commit()) error.addSuppressed(IOException(STORAGE))
                }
                throw error
            }
        } catch (error: Exception) { Timber.w(error, "Accepted flight recovery metadata could not be saved") }
    }

    private fun discard(handle: AcceptedFlightHandle, current: TokenStore.Snapshot) {
        if (identity() != current || memory?.sessionId != handle.sessionId) return
        memory = null
        val key = key(handle.ownerScope)
        val raw = preferences.getString(key, null) ?: return
        if (runCatching { json.decodeFromString(AcceptedFlightHandle.serializer(), raw).sessionId }.getOrNull() == handle.sessionId) {
            if (!preferences.edit().remove(key).commit()) Timber.w("Rejected flight recovery intent could not be removed")
        }
    }

    private fun checkCurrent(expected: TokenStore.Snapshot) {
        check(identity() == expected) { "Planned session account changed" }
    }

    private fun matches(route: RouteInfo, plan: FlightLegPlan, state: SessionState): Boolean =
        matchesRoute(route, plan) && when (state) {
            is SessionState.Running -> state.plannedDurationMs == plan.durationMinutes * 60_000L &&
                state.categoryId == (plan.categoryId ?: SessionRepository.NO_CATEGORY)
            is SessionState.Paused -> state.plannedDurationMs == plan.durationMinutes * 60_000L &&
                state.categoryId == (plan.categoryId ?: SessionRepository.NO_CATEGORY)
            is SessionState.Arrived -> state.plannedDurationMs == plan.durationMinutes * 60_000L &&
                state.categoryId == (plan.categoryId ?: SessionRepository.NO_CATEGORY)
            SessionState.Idle -> false
        }

    private fun matchesRoute(route: RouteInfo, plan: FlightLegPlan): Boolean =
        sameAirport(route.departure, plan.departure) && sameAirport(route.arrival, plan.destination) &&
            route.departureCode == plan.departure.code && route.arrivalCode == plan.destination.code &&
            route.distanceKm == plan.distanceKm && route.seat == plan.seat &&
            route.scene == plan.scene && route.plannedDeparture == plan.plannedDeparture &&
            route.flightNumber == plan.flightNumber

    private fun matchesRow(row: FlightHistoryEntity, plan: FlightLegPlan): Boolean =
        row.departureAirportCode == plan.departure.code && row.departureAirportName == plan.departure.name &&
            row.departureCity == plan.departure.city && row.departureCountry == plan.departure.country &&
            row.departureLatitude == plan.departure.latitude && row.departureLongitude == plan.departure.longitude &&
            row.destinationAirportCode == plan.destination.code && row.destinationAirportName == plan.destination.name &&
            row.destinationCity == plan.destination.city && row.destinationCountry == plan.destination.country &&
            row.destinationLatitude == plan.destination.latitude && row.destinationLongitude == plan.destination.longitude &&
            row.distanceKm == plan.distanceKm && row.durationMinutes == plan.durationMinutes &&
            row.categoryId == plan.categoryId && row.seat == plan.seat && row.flightNumber == plan.flightNumber &&
            (row.sceneSnapshotJson == null || PlannedFlightMetadataCodec.decodeSceneOrNull(row.sceneSnapshotJson) != null) &&
            (row.plannedDepartureJson == null || PlannedFlightMetadataCodec.decodeDepartureOrNull(row.plannedDepartureJson) != null) &&
            PlannedFlightMetadataCodec.decodeSceneOrNull(row.sceneSnapshotJson) == plan.scene &&
            PlannedFlightMetadataCodec.decodeDepartureOrNull(row.plannedDepartureJson) == plan.plannedDeparture

    // Room history recreates airport IDs as-1/-2 and localized display values as
    // raw values. Every persisted raw airport field and coordinate must match.
    private fun sameAirport(actual: PlannedAirport?, expected: PlannedAirport): Boolean =
        actual != null && actual.code == expected.code && actual.name == expected.name &&
            actual.city == expected.city && actual.country == expected.country &&
            actual.latitude == expected.latitude && actual.longitude == expected.longitude

    private fun sessionId(state: SessionState): String? = when (state) {
        is SessionState.Running -> state.sessionId
        is SessionState.Paused -> state.sessionId
        is SessionState.Arrived -> state.sessionId
        SessionState.Idle -> null
    }

    private fun key(owner: String) = "$owner:accepted-flight"

    private enum class OriginGuardResult { NEED_ARCHIVE, APPLIED, REJECTED }

    companion object {
        const val FILE_NAME = "projectfocus_planned_session_bridge_v1"
        private const val STORAGE = "Could not persist planned session ownership"
    }
}
