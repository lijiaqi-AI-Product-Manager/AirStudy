package com.projectfocus.app.booking

import android.content.Context
import android.content.SharedPreferences
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.common.plan.SourceFlightPlanDates
import com.projectfocus.core.common.plan.SourceFlightPlanStatus
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.CancelBatchDeleteFlightPlanRequest
import com.projectfocus.core.network.plan.FlightPlanGateway
import com.projectfocus.core.network.plan.FlightPlanIdentityChangedException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Only a user-confirmed whole route belongs here; unconfirmed edits live in SavedStateHandle. */
interface ConfirmedFlightPlanStore {
    suspend fun load(): FlightLegPlan?
    suspend fun save(plan: FlightLegPlan)

    /** Compatibility for older store implementations; production overrides this whole-day API. */
    suspend fun loadDay(date: String, timezone: String, today: String): DailyFlightPlan {
        val plan = load()
        // An undated legacy singleton belongs to device today, not every queried
        // day. In particular R's tomorrow prefetch must not duplicate it.
        val planDate = plan?.plannedDeparture?.date ?: today
        return if (plan == null || planDate != date) DailyFlightPlans.empty(date, timezone)
            else DailyFlightPlan(date, timezone, legs = listOf(DailyFlightPlans.fromRoute(plan, date, timezone, 1)))
    }

    suspend fun saveDay(day: DailyFlightPlan): DailyFlightPlan {
        day.legs.lastOrNull()?.route?.let { save(it) }
        return day
    }

    suspend fun deleteLastDay(remainder: DailyFlightPlan): DailyFlightPlan = saveDay(remainder)

    suspend fun deleteCanceledDay(day: DailyFlightPlan, localToday: String): DailyPlanDeletionResult =
        throw UnsupportedOperationException("Canceled-plan storage is unavailable")
}

data class DailyPlanDeletionResult(val day: DailyFlightPlan?, val deletedCount: Int)

internal object FlightPlanJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(plan: FlightLegPlan): String =
        json.encodeToString(FlightLegPlan.serializer(), validated(plan))

    fun decode(value: String): FlightLegPlan =
        validated(json.decodeFromString(FlightLegPlan.serializer(), value))

    // Run the domain constructors even if a future serializer changes its init-block handling.
    private fun validated(plan: FlightLegPlan): FlightLegPlan = plan.copy(
        departure = plan.departure.copy(), destination = plan.destination.copy(),
        scene = plan.scene?.copy(), plannedDeparture = plan.plannedDeparture?.copy(),
    )
}

@Singleton
class SharedPreferencesConfirmedFlightPlanStore internal constructor(
    private val preferences: SharedPreferences,
    private val io: CoroutineDispatcher,
    private val gateway: FlightPlanGateway? = null,
) : ConfirmedFlightPlanStore {
    @Inject
    constructor(@ApplicationContext context: Context, io: CoroutineDispatcher, gateway: FlightPlanGateway) : this(
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE), io, gateway,
    )

    private val mutex = Mutex()

    override suspend fun load(): FlightLegPlan? = withContext(io) {
        mutex.withLock { preferences.getString(PLAN_KEY, null)?.let(FlightPlanJson::decode) }
    }

    override suspend fun save(plan: FlightLegPlan): Unit = withContext(io) {
        mutex.withLock {
            val encoded = FlightPlanJson.encode(plan)
            val previous = preferences.getString(PLAN_KEY, null)
            if (!preferences.edit().putString(PLAN_KEY, encoded).commit()) {
                // SharedPreferences can change its memory cache even when its disk commit fails.
                // Restore the previous value so a later load cannot treat this failed save as confirmed.
                val restore = preferences.edit()
                if (previous == null) restore.remove(PLAN_KEY) else restore.putString(PLAN_KEY, previous)
                restore.commit()
                throw IOException("Could not persist the confirmed flight plan")
            }
        }
    }

    private val dayJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun loadDay(date: String, timezone: String, today: String): DailyFlightPlan = withContext(io) {
        val account = TokenStore.snapshot()
        val key = dayKey(date, timezone, account)
        val cached = mutex.withLock {
            val raw = preferences.getString(key, null)
            try {
                raw?.let(::decodeDay)?.also {
                    require(it.localDate == date && it.timezone == timezone && it.ownerScope == ownerScope(account))
                }
            } catch (error: Exception) {
                // A damaged local cache must not prevent an authenticated fresh read.
                // Local-only corruption still reports failure instead of fabricating an empty plan.
                if (account.token.isNullOrBlank()) throw error
                null
            }
        }
        val socket = gateway
        if (account.token.isNullOrBlank()) {
            if (cached != null) { checkIdentity(account); return@withContext cached }
            if (preferences.getString(LEGACY_MIGRATED_KEY, null) != null) {
                checkIdentity(account)
                return@withContext DailyFlightPlans.empty(date, timezone)
            }
            // The old unscoped key is a local-only migration; never import it into a signed-in account.
            val legacy = mutex.withLock { preferences.getString(PLAN_KEY, null)?.let(FlightPlanJson::decode) }
            val legacyDate = legacy?.plannedDeparture?.date ?: date
            if (legacy == null || legacyDate != date || legacy.plannedDeparture?.zoneId?.let { it != timezone } == true) {
                checkIdentity(account); return@withContext DailyFlightPlans.empty(date, timezone)
            }
            val migrated = DailyFlightPlan(date, timezone, legs = listOf(DailyFlightPlans.fromRoute(legacy, date, timezone, 1)))
            writeDayCache(key, migrated, account, mirrorLegacy = false)
            checkIdentity(account)
            return@withContext migrated
        }
        if (socket == null) throw IOException("Flight plan transport is unavailable")
        val localDate = LocalDate.parse(date)
        val zone = ZoneId.of(timezone)
        val range = SourceFlightPlanDates.history(localDate, LocalDate.parse(today), zone)
        val response = if (range == null) {
            val fields = SourceFlightPlanDates.get(localDate, zone)
            socket.get(GetFlightPlanRequest(fields.planDate, fields.localPlanDate, fields.timezone,
                fields.timezoneOffsetMinutes, SourceFlightPlanDates.getRequestId(UUID.randomUUID())))
        } else {
            socket.history(FlightPlanHistoryRequest(range.startDate, range.endDate, range.timezone,
                SourceFlightPlanDates.historyRequestId(UUID.randomUUID()), range.limit))
                .firstOrNull { it.localPlanDate == date && it.timezone == timezone }
        }
        checkIdentity(account)
        val result = (if (response == null) DailyFlightPlans.empty(date, timezone) else {
            require(response.localPlanDate == date && response.timezone == timezone) { "Flight plan reply belongs to another date" }
            DailyFlightPlans.fromSnapshot(response, cached)
        }).copy(ownerScope = ownerScope(account))
        writeDayCache(key, result, account, mirrorLegacy = false)
        checkIdentity(account)
        result
    }

    override suspend fun saveDay(day: DailyFlightPlan): DailyFlightPlan = saveDaySnapshot(day, deletingLast = false)

    override suspend fun deleteLastDay(remainder: DailyFlightPlan): DailyFlightPlan = saveDaySnapshot(remainder, deletingLast = true)

    override suspend fun deleteCanceledDay(day: DailyFlightPlan, localToday: String): DailyPlanDeletionResult = withContext(io) {
        val account = TokenStore.snapshot()
        if (day.ownerScope != ownerScope(account)) throw FlightPlanIdentityChangedException()
        val key = dayKey(day.localDate, day.timezone, account)
        val today = LocalDate.parse(localToday)
        val result = if (account.token.isNullOrBlank()) {
            // Explicit local-only adapter; a signed-in failure never falls back to it.
            val retained = day.legs.filter { SourceFlightPlanStatus.parse(it.snapshot.legStatus) != SourceFlightPlanStatus.CANCELED }.mapIndexed { index, leg ->
                leg.copy(snapshot = leg.snapshot.copy(legIndex = index + 1))
            }
            DailyPlanDeletionResult(day.copy(legs = retained), day.legs.size - retained.size)
        } else {
            val socket = gateway ?: throw IOException("Flight plan transport is unavailable")
            val fields = SourceFlightPlanDates.upsert(LocalDate.parse(day.localDate), ZoneId.of(day.timezone), day.planDate)
            val response = socket.deleteCanceled(CancelBatchDeleteFlightPlanRequest(fields.planDate, fields.localPlanDate,
                fields.timezone, fields.timezoneOffsetMinutes, today.toString(), today.plusDays(1).toString(),
                "batch_delete_" + UUID.randomUUID().toString().replace("-", "")))
            checkIdentity(account)
            require(response == null || (response.localPlanDate == day.localDate && response.timezone == day.timezone)) {
                "Batch deletion reply belongs to another date"
            }
            // Source accepts a success without legs, skips empty-plan cleanup,
            // and re-queries. Such an acknowledgement cannot replace the cache.
            DailyPlanDeletionResult(response?.let { DailyFlightPlans.fromSnapshot(it, day).copy(ownerScope = day.ownerScope) },
                response?.deletedCount ?: -1)
        }
        result.day?.let { writeDayCache(key, it, account, mirrorLegacy = account.token.isNullOrBlank()) }
        checkIdentity(account)
        result
    }

    private suspend fun saveDaySnapshot(day: DailyFlightPlan, deletingLast: Boolean): DailyFlightPlan = withContext(io) {
        val account = TokenStore.snapshot()
        if (day.ownerScope != ownerScope(account)) throw FlightPlanIdentityChangedException()
        val key = dayKey(day.localDate, day.timezone, account)
        if (!account.token.isNullOrBlank() && gateway == null) throw IOException("Flight plan transport is unavailable")
        val result = if (!account.token.isNullOrBlank() && gateway != null) {
            val fields = SourceFlightPlanDates.upsert(LocalDate.parse(day.localDate), ZoneId.of(day.timezone), day.planDate)
            val response = gateway.upsert(UpsertFlightPlanRequest(fields.planDate, fields.localPlanDate, fields.timezone,
                fields.timezoneOffsetMinutes, if (deletingLast) "req_del_${fields.planDate}_${day.legs.size}_${System.currentTimeMillis()}"
                    else SourceFlightPlanDates.upsertRequestId(fields.planDate, day.legs.size,
                        System.currentTimeMillis()), DailyFlightPlans.outgoing(day)))
                ?: throw IOException("Flight plan acknowledged without a confirmed snapshot")
            checkIdentity(account)
            require(response.localPlanDate == day.localDate && response.timezone == day.timezone) { "Flight plan reply belongs to another date" }
            DailyFlightPlans.fromSnapshot(response, day).copy(ownerScope = day.ownerScope)
        } else day
        writeDayCache(key, result, account, mirrorLegacy = account.token.isNullOrBlank())
        checkIdentity(account)
        result
    }

    private suspend fun writeDayCache(key: String, day: DailyFlightPlan, account: TokenStore.Snapshot, mirrorLegacy: Boolean) = mutex.withLock {
        checkIdentity(account)
        val encoded = dayJson.encodeToString(DailyFlightPlan.serializer(), day)
        val before = mutableMapOf(key to preferences.getString(key, null))
        val editor = preferences.edit().putString(key, encoded)
        if (account.token.isNullOrBlank()) {
            before[LEGACY_MIGRATED_KEY] = preferences.getString(LEGACY_MIGRATED_KEY, null)
            editor.putString(LEGACY_MIGRATED_KEY, "1")
        }
        if (mirrorLegacy) {
            before[PLAN_KEY] = preferences.getString(PLAN_KEY, null)
            val route = (day.legs.firstOrNull { it.snapshot.legStatus == "waiting_takeoff" }
                ?: day.legs.firstOrNull { it.snapshot.legStatus != "canceled" })?.route
            if (route == null) editor.remove(PLAN_KEY) else editor.putString(PLAN_KEY, FlightPlanJson.encode(route))
        }
        if (!editor.commit()) {
            val restore = preferences.edit()
            for ((name, value) in before) if (value == null) restore.remove(name) else restore.putString(name, value)
            restore.commit()
            throw IOException("Could not persist the daily flight plan")
        }
    }

    private fun checkIdentity(account: TokenStore.Snapshot) {
        if (TokenStore.snapshot() != account) throw FlightPlanIdentityChangedException()
    }

    private fun dayKey(date: String, timezone: String, account: TokenStore.Snapshot): String {
        LocalDate.parse(date)
        ZoneId.of(timezone)
        return "daily_v2:${ownerScope(account)}:$timezone:$date"
    }

    private fun ownerScope(account: TokenStore.Snapshot): String = FlightPlanOwnership.scope(account)

    private fun decodeDay(value: String): DailyFlightPlan = dayJson.decodeFromString(DailyFlightPlan.serializer(), value).let { day ->
        day.copy(legs = day.legs.map { it.copy(route = it.route?.let { route -> FlightPlanJson.decode(FlightPlanJson.encode(route)) }) })
    }

    companion object {
        const val FILE_NAME = "confirmed_flight_plan_v1"
        internal const val PLAN_KEY = "confirmed_plan_json"
        internal const val LEGACY_MIGRATED_KEY = "legacy_plan_migrated_to_day_v2"
    }
}
