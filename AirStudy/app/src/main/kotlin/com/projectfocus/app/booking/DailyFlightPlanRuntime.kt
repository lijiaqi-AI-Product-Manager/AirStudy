package com.projectfocus.app.booking

import com.projectfocus.core.common.plan.SourceFlightPlanStatus
import com.projectfocus.core.network.di.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

data class DailyPlanRuntimeState(
    val ownerScope: String,
    val deviceDate: String,
    val deviceTimezone: String,
    val selectedDate: String? = null,
    val today: DailyFlightPlan? = null,
    val tomorrow: DailyFlightPlan? = null,
    /** Current g.o backing order, separate from sorted q/r/display projections. */
    val selectedDay: DailyFlightPlan? = null,
)

/**
 * Process-shared q/r provider for the source overlay/controller. It owns no timer
 * or request scope: callers own cancellation. Calendar/account/epoch guards and
 * StateFlow redraw are explicit project adapters around the observed R callback.
 */
@Singleton
class DailyFlightPlanRuntime @Inject constructor(private val clock: PreparationClock) {
    private val lock = Any()
    private var account = TokenStore.snapshot()
    private val initial = clock.now()
    private val mutableState = MutableStateFlow(DailyPlanRuntimeState(FlightPlanOwnership.scope(account),
        initial.toLocalDate().toString(), initial.zone.id))
    val state: StateFlow<DailyPlanRuntimeState> = mutableState.asStateFlow()
    private var lastTomorrowAttempt = 0L
    private var requestEpoch = 0L

    fun snapshot(): DailyPlanRuntimeState = synchronized(lock) { synchronizeContext(); mutableState.value }

    fun selectDate(date: String) = synchronized(lock) {
        synchronizeContext()
        mutableState.value = mutableState.value.copy(selectedDate = date,
            selectedDay = mutableState.value.selectedDay?.takeIf { it.localDate == date })
    }

    /** X: backing day remains wire-ordered; q and a directly bound r are sorted copies. */
    fun bind(day: DailyFlightPlan) = synchronized(lock) {
        val now = synchronizeContext()
        if (day.ownerScope != mutableState.value.ownerScope) return@synchronized
        val sorted = day.copy(legs = day.displayLegs())
        val current = mutableState.value.copy(selectedDate = day.localDate, selectedDay = day)
        mutableState.value = when (day.localDate) {
            now.toLocalDate().toString() -> current.copy(today = sorted)
            now.toLocalDate().plusDays(1).toString() -> current.copy(tomorrow = sorted)
            else -> current
        }
    }

    /** g.h after batch success clears only the selected date's matching cache. */
    fun clearSelectedDay(date: String) = synchronized(lock) {
        val now = synchronizeContext()
        val current = mutableState.value.let { if (it.selectedDay?.localDate == date) it.copy(selectedDay = null) else it }
        mutableState.value = when (date) {
            now.toLocalDate().toString() -> current.copy(today = null)
            now.toLocalDate().plusDays(1).toString() -> current.copy(tomorrow = null)
            else -> current
        }
    }

    fun todayBlocked(selectedDate: String): Boolean {
        val current = snapshot()
        return selectedDate == current.deviceDate && current.tomorrow?.legs.orEmpty().any {
            SourceFlightPlanStatus.parse(it.snapshot.legStatus) != SourceFlightPlanStatus.CANCELED
        }
    }

    fun tomorrowBlockedByCanceledToday(selectedDate: String): Boolean {
        val current = snapshot()
        return selectedDate == java.time.LocalDate.parse(current.deviceDate).plusDays(1).toString() &&
            current.today?.legs?.lastOrNull()?.let {
                SourceFlightPlanStatus.parse(it.snapshot.legStatus) == SourceFlightPlanStatus.CANCELED
            } == true
    }






    suspend fun prefetchTomorrow(store: ConfirmedFlightPlanStore) {
        val ticket = synchronized(lock) {
            val now = synchronizeContext()
            val elapsed = clock.elapsedRealtimeMillis()
            if (elapsed - lastTomorrowAttempt < 2_000L) return
            lastTomorrowAttempt = elapsed
            Ticket(++requestEpoch, account, now.toLocalDate().toString(),
                now.toLocalDate().plusDays(1).toString(), now.zone.id)
        }
        val day = try {
            store.loadDay(ticket.tomorrow, ticket.timezone, ticket.today)
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            synchronizeContext()
            val current = mutableState.value
            if (ticket.epoch != requestEpoch || ticket.account != account ||
                ticket.today != current.deviceDate || ticket.timezone != current.deviceTimezone ||
                (!current.selectedDate.isNullOrEmpty() && current.selectedDate != current.deviceDate)) return
            if (day.localDate != ticket.tomorrow || day.timezone != ticket.timezone || day.ownerScope != current.ownerScope) return
            // Unlike X binding, the original R callback does not sort this list.
            mutableState.value = current.copy(tomorrow = day)
        }
    }

    private fun synchronizeContext(): ZonedDateTime {
        val currentAccount = TokenStore.snapshot()
        val now = clock.now()
        val before = mutableState.value
        if (currentAccount != account || before.deviceDate != now.toLocalDate().toString() || before.deviceTimezone != now.zone.id) {
            account = currentAccount
            requestEpoch++
            lastTomorrowAttempt = 0L
            mutableState.value = DailyPlanRuntimeState(FlightPlanOwnership.scope(account), now.toLocalDate().toString(),
                now.zone.id, selectedDate = before.selectedDate)
        }
        return now
    }

    private data class Ticket(val epoch: Long, val account: TokenStore.Snapshot, val today: String,
                              val tomorrow: String, val timezone: String)
}
