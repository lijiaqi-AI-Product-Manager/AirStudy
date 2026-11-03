package com.projectfocus.app.booking

import android.content.Context
import android.content.SharedPreferences
import com.projectfocus.core.network.di.NetworkModule.NetworkScope
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.subscription.SubscriptionStatusGateway
import com.projectfocus.core.network.subscription.SubscriptionStatusIdentityChangedException
import com.projectfocus.core.network.subscription.SubscriptionStatusParseException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class EntitlementRefreshFailure { NETWORK, PARSE, STORAGE }

data class PlanEntitlementState(
    val ownerScope: String,
    val authenticated: Boolean,
    val qualified: Boolean = false,
    val checkedAtMillis: Long = 0L,
    val expiresAt: String? = null,
    val refreshFailure: EntitlementRefreshFailure? = null,
)

interface PlanEntitlementAccess {
    val state: StateFlow<PlanEntitlementState>
    /** Source b: returns cached eligibility immediately, including stale true. */
    fun admit(): Boolean
    fun refresh()
}

/**
 * The source eligibility/cache rules, using project-owned scoped preferences and
 * HTTP envelope. Token generations and one in-flight refresh are own adapters.
 * No endpoint here grants eligibility or substitutes a demo premium flag.
 */
@Singleton
class SharedPreferencesPlanEntitlementAccess internal constructor(
    private val preferences: SharedPreferences,
    private val clock: PreparationClock,
    private val gateway: SubscriptionStatusGateway,
    private val scope: CoroutineScope,
) : PlanEntitlementAccess {
    @Inject constructor(@ApplicationContext context: Context, clock: PreparationClock,
                        gateway: SubscriptionStatusGateway, @NetworkScope scope: CoroutineScope) :
        this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE), clock, gateway, scope)

    private val lock = Any()
    private var account = TokenStore.snapshot()
    private var inFlight: Job? = null
    private var epoch = 0L
    private val mutableState = MutableStateFlow(read(account))
    override val state = mutableState.asStateFlow()

    override fun admit(): Boolean = synchronized(lock) {
        synchronizeAccount()
        if (account.token.isNullOrEmpty()) {
            mutableState.value = PlanEntitlementState(FlightPlanOwnership.scope(account), false)
            return@synchronized false
        }
        val current = mutableState.value
        if (clock.now().toInstant().toEpochMilli() - current.checkedAtMillis >= 60_000L) refreshLocked()
        current.qualified
    }

    override fun refresh() = synchronized(lock) {
        synchronizeAccount()
        if (!account.token.isNullOrEmpty()) refreshLocked()
    }

    private fun refreshLocked() {
        if (inFlight?.isActive == true) return
        val identity = account
        val ticket = ++epoch
        inFlight = scope.launch {
            try {
                val reply = gateway.fetch()
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    synchronizeAccount()
                    if (identity != account || ticket != epoch) return@synchronized
                    val next = PlanEntitlementState(FlightPlanOwnership.scope(account), true,
                        reply != null && reply.isActive && reply.isPremium && reply.remainingDays > 0,
                        clock.now().toInstant().toEpochMilli(), reply?.expiresAt)
                    try {
                        write(next)
                        mutableState.value = next
                    } catch (_: IOException) {
                        mutableState.value = mutableState.value.copy(refreshFailure = EntitlementRefreshFailure.STORAGE)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                failed(identity, ticket, EntitlementRefreshFailure.NETWORK)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: SubscriptionStatusIdentityChangedException) {
                synchronized(lock) { synchronizeAccount() }
            } catch (_: SubscriptionStatusParseException) {
                failed(identity, ticket, EntitlementRefreshFailure.PARSE)
            } catch (_: Exception) {
                failed(identity, ticket, EntitlementRefreshFailure.NETWORK)
            } finally {
                synchronized(lock) { if (ticket == epoch) inFlight = null }
            }
        }
    }

    private fun failed(identity: TokenStore.Snapshot, ticket: Long, failure: EntitlementRefreshFailure) = synchronized(lock) {
        synchronizeAccount()
        if (identity == account && ticket == epoch) {
            // Failure/parse exception does not call source u: keep eligibility,
            // expiry and last-check time; never turn it into a false response.
            mutableState.value = mutableState.value.copy(refreshFailure = failure)
        }
    }

    private fun synchronizeAccount() {
        val now = TokenStore.snapshot()
        if (now != account) {
            epoch++
            inFlight?.cancel()
            inFlight = null
            account = now
            mutableState.value = read(now)
        }
    }

    private fun read(identity: TokenStore.Snapshot): PlanEntitlementState {
        val owner = FlightPlanOwnership.scope(identity)
        if (identity.token.isNullOrEmpty()) return PlanEntitlementState(owner, false)
        return PlanEntitlementState(owner, true, preferences.getBoolean("$owner:qualified", false),
            preferences.getLong("$owner:checked_at", 0L), preferences.getString("$owner:expires_at", null))
    }

    private fun write(value: PlanEntitlementState) {
        val keys = listOf("${value.ownerScope}:qualified", "${value.ownerScope}:checked_at", "${value.ownerScope}:expires_at")
        val before = preferences.all.filterKeys { it in keys }
        val edit = preferences.edit().putBoolean(keys[0], value.qualified).putLong(keys[1], value.checkedAtMillis)
        if (value.expiresAt == null) edit.remove(keys[2]) else edit.putString(keys[2], value.expiresAt)
        if (!edit.commit()) {
            val rollback = preferences.edit()
            keys.forEach { key ->
                when (val old = before[key]) {
                    is Boolean -> rollback.putBoolean(key, old)
                    is Long -> rollback.putLong(key, old)
                    is String -> rollback.putString(key, old)
                    else -> rollback.remove(key)
                }
            }
            rollback.commit()
            throw IOException("Could not persist subscription cache")
        }
    }

    companion object { const val FILE_NAME = "projectfocus_plan_entitlement_v1" }
}
