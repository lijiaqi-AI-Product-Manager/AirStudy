package com.projectfocus.app.booking

import android.content.Context
import android.content.SharedPreferences
import com.projectfocus.core.network.di.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PendingPlanSeatSelection(val requestId: String, val localDate: String, val timezone: String,
                                    val candidate: DailyFlightLeg)

@Serializable
data class ActivePlanSelection(val localDate: String, val timezone: String, val candidate: DailyFlightLeg,
                               val seatNo: String = "")

@Serializable
data class GlobalFlightPlanState(
    val ownerScope: String,
    val enabledDate: String? = null,
    val selectionPending: Boolean = false,
    val inheritedSeat: String? = null,
    val pendingSelection: PendingPlanSeatSelection? = null,
    val activeSelection: ActivePlanSelection? = null,
    /** null before a result; empty is a real empty return, not a cached old seat. */
    val lastSeatResult: String? = null,
    val storageError: Boolean = false,
)

interface GlobalFlightPlanStore {
    val state: StateFlow<GlobalFlightPlanState>
    fun current(): GlobalFlightPlanState
    fun bindHost(hostToken: String)
    fun beginPending(day: DailyFlightPlan, candidate: DailyFlightLeg): PendingPlanSeatSelection
    fun acceptSeat(requestId: String, seat: String?, currentDay: DailyFlightPlan,
                   currentCandidate: DailyFlightLeg?): Boolean
    fun clearEnabledDate()
    fun clearActiveIfMatches(selection: ActivePlanSelection)
    fun disable()
}

/**
 * Source date/pending/inherited-seat separation. JSON, owner isolation, commit
 * checks and typed navigation identity are project adapters; not source fsync.
 */
@Singleton
class SharedPreferencesGlobalFlightPlanStore internal constructor(
    private val preferences: SharedPreferences,
    private val clock: PreparationClock,
) : GlobalFlightPlanStore {
    @Inject constructor(@ApplicationContext context: Context, clock: PreparationClock) :
        this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE), clock)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private var account = TokenStore.snapshot()
    private var host: String? = null
    private val mutableState = MutableStateFlow(read(account))
    override val state = mutableState.asStateFlow()

    override fun current() = synchronized(lock) { syncAccount(); mutableState.value }

    override fun bindHost(hostToken: String) = synchronized(lock) {
        syncAccount()
        if (host == hostToken) return@synchronized
        host = hostToken
        val old = mutableState.value
        // E's fresh-binding stale pending cleanup is not every return from the
        // embedded picker. The same actual Activity host keeps its binding token.
        if (old.selectionPending) write(old.copy(enabledDate = null, selectionPending = false, pendingSelection = null))
    }

    override fun beginPending(day: DailyFlightPlan, candidate: DailyFlightLeg): PendingPlanSeatSelection = synchronized(lock) {
        syncAccount()
        val old = mutableState.value
        require(!old.storageError && day.ownerScope == old.ownerScope)
        require(day.localDate == clock.now().toLocalDate().toString())
        require(candidate.snapshot.departureAirport != null && candidate.snapshot.arrivalAirport != null)
        // Source writes enabled date first, pending separately. A second-write
        // failure must not be reported as an atomic rollback of both operations.
        write(old.copy(enabledDate = day.localDate, lastSeatResult = null))
        val pending = PendingPlanSeatSelection("plan-toggle:"+UUID.randomUUID(), day.localDate, day.timezone, candidate)
        write(mutableState.value.copy(selectionPending = true, pendingSelection = pending))
        pending
    }

    override fun acceptSeat(requestId: String, seat: String?, currentDay: DailyFlightPlan,
                            currentCandidate: DailyFlightLeg?): Boolean = synchronized(lock) {
        syncAccount()
        val old = mutableState.value
        if (!old.selectionPending || old.pendingSelection?.requestId != requestId || currentDay.ownerScope != old.ownerScope) return@synchronized false
        val normalized = seat?.trim { it <= '\u0020' }.orEmpty()
        // c3 pending callback recomputes d() and device today at return. It does
        // not rewrite the opened leg's scene or persist a chosen picker category.
        write(old.copy(selectionPending = false, pendingSelection = null,
            inheritedSeat = normalized.takeIf(String::isNotEmpty) ?: old.inheritedSeat,
            lastSeatResult = normalized,
            activeSelection = currentCandidate?.let { ActivePlanSelection(clock.now().toLocalDate().toString(), currentDay.timezone, it, normalized) }))
        true
    }

    override fun clearEnabledDate() = synchronized(lock) {
        syncAccount()
        write(mutableState.value.copy(enabledDate = null))
    }

    override fun clearActiveIfMatches(selection: ActivePlanSelection) = synchronized(lock) {
        syncAccount()
        if (mutableState.value.activeSelection == selection) write(mutableState.value.copy(activeSelection = null))
    }

    override fun disable() = synchronized(lock) {
        syncAccount()
        // Directly established Off effects. Pending is not casually equated with
        // a close/abort callback; its deeper reset delegates remain separate.
        write(mutableState.value.copy(enabledDate = null, inheritedSeat = null, activeSelection = null, lastSeatResult = null))
    }

    private fun syncAccount() {
        val next = TokenStore.snapshot()
        if (next != account) { account = next; host = null; mutableState.value = read(next) }
    }

    private fun read(identity: TokenStore.Snapshot): GlobalFlightPlanState {
        val owner = FlightPlanOwnership.scope(identity)
        val raw = preferences.getString("$owner:state", null) ?: return GlobalFlightPlanState(owner)
        return try {
            json.decodeFromString(GlobalFlightPlanState.serializer(), raw).also {
                require(it.ownerScope == owner)
                it.enabledDate?.let { date -> require(LocalDate.parse(date).toString() == date) }
            }.copy(storageError = false)
        } catch (_: Exception) { GlobalFlightPlanState(owner, storageError = true) }
    }

    private fun write(value: GlobalFlightPlanState) {
        check(TokenStore.snapshot() == account) { "Plan account changed" }
        val key = "${value.ownerScope}:state"
        val before = preferences.getString(key, null)
        val next = value.copy(storageError = false)
        if (!preferences.edit().putString(key, json.encodeToString(GlobalFlightPlanState.serializer(), next)).commit()) {
            val rollback = preferences.edit()
            if (before == null) rollback.remove(key) else rollback.putString(key, before)
            rollback.commit()
            throw IOException("Could not persist global flight plan state")
        }
        mutableState.value = next
    }

    companion object { const val FILE_NAME = "projectfocus_global_flight_plan_v1" }
}

/** Compatibility host for existing direct VM callers; production uses the shared store. */
internal class MemoryGlobalFlightPlanStore(private val clock: PreparationClock) : GlobalFlightPlanStore {
    private val mutableState = MutableStateFlow(GlobalFlightPlanState(FlightPlanOwnership.scope()))
    override val state = mutableState.asStateFlow()
    override fun current(): GlobalFlightPlanState {
        if (mutableState.value.ownerScope != FlightPlanOwnership.scope()) mutableState.value = GlobalFlightPlanState(FlightPlanOwnership.scope())
        return mutableState.value
    }
    override fun bindHost(hostToken: String) = Unit
    override fun beginPending(day: DailyFlightPlan, candidate: DailyFlightLeg): PendingPlanSeatSelection {
        val request = PendingPlanSeatSelection("plan-toggle:"+UUID.randomUUID(), day.localDate, day.timezone, candidate)
        mutableState.value = current().copy(enabledDate = day.localDate, selectionPending = true, pendingSelection = request)
        return request
    }
    override fun acceptSeat(requestId: String, seat: String?, currentDay: DailyFlightPlan, currentCandidate: DailyFlightLeg?): Boolean {
        val old = current()
        if (!old.selectionPending || old.pendingSelection?.requestId != requestId) return false
        val selected = seat?.trim { it <= '\u0020' }.orEmpty()
        mutableState.value = old.copy(selectionPending = false, pendingSelection = null,
            inheritedSeat = selected.takeIf(String::isNotEmpty) ?: old.inheritedSeat, lastSeatResult = selected,
            activeSelection = currentCandidate?.let { ActivePlanSelection(clock.now().toLocalDate().toString(), currentDay.timezone, it, selected) })
        return true
    }
    override fun clearEnabledDate() { mutableState.value = current().copy(enabledDate = null) }
    override fun clearActiveIfMatches(selection: ActivePlanSelection) {
        if (current().activeSelection == selection) mutableState.value = current().copy(activeSelection = null)
    }
    override fun disable() { mutableState.value = current().copy(enabledDate = null, inheritedSeat = null, activeSelection = null, lastSeatResult = null) }
}

internal object AnonymousPlanEntitlementAccess : PlanEntitlementAccess {
    override val state = MutableStateFlow(PlanEntitlementState("local", false)).asStateFlow()
    override fun admit() = false
    override fun refresh() = Unit
}
