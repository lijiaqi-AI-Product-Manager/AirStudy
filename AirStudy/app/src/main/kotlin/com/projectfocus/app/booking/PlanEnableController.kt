package com.projectfocus.app.booking

import com.projectfocus.core.common.plan.SourceFlightPlanStatus
import com.projectfocus.core.network.di.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PlanEnableNotice { ONLY_TODAY, IN_PROGRESS, NO_WAITING, FLY_BACK_FIRST, CANNOT_CLOSE, STORAGE_FAILED, SYNCING_STATUS }
data class PlanEnableMessage(val kind: PlanEnableNotice, val argument: String? = null)
data class PlanEnablePresentation(val busy: Boolean = false, val gateVisible: Boolean = false,
                                  val gateAuthenticated: Boolean = false, val switchOverride: Boolean? = null,
                                  val pendingNavigation: PendingPlanSeatSelection? = null)

/** Source admission/pending protocol. It never starts a session on a seat result. */
internal class PlanEnableController(
    private val scope: CoroutineScope,
    private val entitlement: PlanEntitlementAccess,
    private val global: GlobalFlightPlanStore,
    private val clock: PreparationClock,
    private val day: () -> DailyFlightPlan,
    private val temporaryCandidate: () -> DailyFlightLeg?,
    private val revision: () -> Long,
    private val currentOriginCode: () -> String?,
    private val loadToday: suspend () -> DailyFlightPlan,
    private val installDay: (DailyFlightPlan) -> Unit,
    private val isFlying: () -> Boolean,
    private val hasArchive: suspend () -> Boolean,
    private val forceComplete: (ActivePlanSelection) -> Unit,
    private val recoverySelection: () -> ActivePlanSelection? = { null },
    private val reconciliation: PlanOriginReconciliationAccess? = null,
) {
    private val mutableState = MutableStateFlow(PlanEnablePresentation())
    val state = mutableState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<PlanEnableMessage>(extraBufferCapacity = 8)
    val messages = mutableMessages.asSharedFlow()
    private var epoch = 0L
    private var job: Job? = null
    private var reconciling = false

    init {
        scope.launch {
            var previous = entitlement.state.value
            entitlement.state.collect { next ->
                if (previous.ownerScope == next.ownerScope && previous.qualified && !next.qualified &&
                    global.current().ownerScope == next.ownerScope) {
                    try { global.clearEnabledDate() } catch (_: Exception) { message(PlanEnableNotice.STORAGE_FAILED) }
                }
                previous = next
                // The selected watcher callback closes the overlay only. It
                // does not call On again or mark a selected flight accepted.
                if (next.qualified && state.value.gateVisible) mutableState.value = state.value.copy(gateVisible = false)
                else if (state.value.gateVisible) mutableState.value = state.value.copy(gateAuthenticated = !TokenStore.peek().isNullOrEmpty())
            }
        }
    }

    fun bindHost(token: String) {
        try { global.bindHost(token) } catch (_: Exception) { message(PlanEnableNotice.STORAGE_FAILED) }
    }

    fun toggle(enabled: Boolean) {
        if (state.value.busy) return
        if (enabled && !entitlement.admit()) {
            mutableState.value = state.value.copy(gateVisible = true,
                gateAuthenticated = !TokenStore.peek().isNullOrEmpty(), switchOverride = false)
            return
        }
        mutableState.value = state.value.copy(gateVisible = false)
        val identity = TokenStore.snapshot()
        val capturedRevision = revision()
        val ticket = ++epoch
        mutableState.value = state.value.copy(busy = true)
        job = scope.launch {
            try {
                if (enabled) activate(true, identity, capturedRevision, ticket) else disable(identity, capturedRevision, ticket)
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (valid(identity, capturedRevision, ticket)) message(PlanEnableNotice.STORAGE_FAILED)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (valid(identity, capturedRevision, ticket)) message(PlanEnableNotice.STORAGE_FAILED) }
            finally { if (ticket == epoch) mutableState.value = state.value.copy(busy = false) }
        }
    }

    private suspend fun activate(retryAllowed: Boolean, identity: TokenStore.Snapshot, capturedRevision: Long, ticket: Long) {
        if (!valid(identity, capturedRevision, ticket)) return
        if (day().localDate != clock.now().toLocalDate().toString()) { reject(PlanEnableNotice.ONLY_TODAY); return }
        val candidate = candidate()
        val required = candidate?.snapshot?.departureAirport?.code?.trim { it <= '\u0020' }.orEmpty()
        val origin = currentOriginCode()?.trim { it <= '\u0020' }.orEmpty()
        val endpoints = candidate?.snapshot?.let { it.departureAirport != null && it.arrivalAirport != null } == true
        val originMatches = origin.isEmpty() || required.isEmpty() || origin.equals(required, ignoreCase = true)
        if ((!endpoints || !originMatches) && retryAllowed) {
            // Both original refresh callbacks retry a(false), not an unbounded
            // read loop. A failed refresh leaves the existing candidate intact.
            try {
                val loaded = loadToday()
                currentCoroutineContext().ensureActive()
                if (!valid(identity, capturedRevision, ticket)) return
                require(loaded.localDate == day().localDate && loaded.timezone == day().timezone)
                installDay(loaded)
            } catch (timeout: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Re-enter admission with the current list on failure as well. */ }
            activate(false, identity, capturedRevision, ticket)
            return
        }
        if (!endpoints || candidate == null) {
            reject(if (hasDisplayedInProgress()) PlanEnableNotice.IN_PROGRESS else PlanEnableNotice.NO_WAITING)
            return
        }
        if (!originMatches) {
            reconcileMismatch(candidate, required, identity, capturedRevision, ticket)
            return
        }
        val request = global.beginPending(day(), candidate)
        mutableState.value = state.value.copy(switchOverride = null, pendingNavigation = request)
    }

    private suspend fun reconcileMismatch(candidate: DailyFlightLeg, required: String,
                                         identity: TokenStore.Snapshot, capturedRevision: Long, ticket: Long) {
        val access = reconciliation ?: run { reject(PlanEnableNotice.FLY_BACK_FIRST, required); return }
        if (reconciling) return
        val now = clock.now()
        val context = RepairContext(now.toLocalDate().toString(), now.zone.id, day().timezone)
        if (!validRepair(identity, capturedRevision, ticket, context)) return
        val status = access.pendingStatus()
        if (!validRepair(identity, capturedRevision, ticket, context)) return
        val index = candidate.snapshot.legIndex // Wire leg_index; never a UI list position.
        val millis = now.toInstant().toEpochMilli()
        val matches = status != null && status.ownerScope == FlightPlanOwnership.scope(identity) &&
            (SourceOriginReconciliationPolicy.recentPendingMatches(index, status.localDate, status.legIndex,
                status.mappedStatus, status.timestampMillis, millis) ||
                SourceOriginReconciliationPolicy.recentPendingMatches(index - 1, status.localDate, status.legIndex,
                    status.mappedStatus, status.timestampMillis, millis))
        if (!matches) {
            // H uses device today and THIS candidate's wire index, with no
            // receiptDays/departure-date fallback and no navigation-pending data.
            val receipt = access.exactReceiptMillis(context.date, index)
            if (!validRepair(identity, capturedRevision, ticket, context)) return
            if (!SourceOriginReconciliationPolicy.receiptAllowsRequeue(context.date, index, receipt ?: 0, millis)) {
                reject(PlanEnableNotice.FLY_BACK_FIRST, required)
                return
            }
            access.requeueFromReceipt(ActivePlanSelection(context.date, context.planTimezone, candidate,
                candidate.snapshot.seatNo), checkNotNull(receipt))
        }
        if (!validRepair(identity, capturedRevision, ticket, context)) return
        reconciling = true
        var lease: PlanReconciliationLease? = null
        try {
            message(PlanEnableNotice.SYNCING_STATUS)
            lease = access.resendPending() // Real existing status dispatcher, after H's persisted write and notice.
            var attempt = 0
            while (SourceOriginReconciliationPolicy.canSchedulePoll(attempt)) {
                delay(SourceOriginReconciliationPolicy.POLL_DELAY_MILLIS)
                currentCoroutineContext().ensureActive()
                if (!validRepair(identity, capturedRevision, ticket, context)) return
                val pending = access.pendingStatus()
                if (!SourceOriginReconciliationPolicy.queueStillPending(pending?.localDate, pending?.legIndex ?: 0)) {
                    reconciling = false
                    try {
                        val loaded = loadToday()
                        currentCoroutineContext().ensureActive()
                        if (!validRepair(identity, capturedRevision, ticket, context)) return
                        require(loaded.localDate == context.date && loaded.timezone == context.planTimezone)
                        installDay(loaded)
                    } catch (timeout: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Source post-sync failure also re-enters a(false). */ }
                    if (validRepair(identity, capturedRevision, ticket, context)) {
                        activate(false, identity, capturedRevision, ticket)
                    }
                    return
                }
                attempt++
            }
            if (!validRepair(identity, capturedRevision, ticket, context)) return
            reconciling = false
            val pending = access.pendingStatus()
            mutableState.value = state.value.copy(switchOverride = false)
            if (!pending?.localDate?.trim { it <= '\u0020' }.isNullOrEmpty()) message(PlanEnableNotice.SYNCING_STATUS)
            else message(PlanEnableNotice.FLY_BACK_FIRST, required)
            // False-mode timeout is a UI override only. Pending and enabledDate
            // belong to their actual stores and are not cleared here.
        } finally {
            reconciling = false
            // Own lifecycle cancellation, exact-attempt scoped; durable pending
            // and receipt survive cancellation/timeout. Borrowed attempts remain owned by their caller.
            lease?.close()
        }
    }

    private fun validRepair(identity: TokenStore.Snapshot, capturedRevision: Long, ticket: Long,
                            context: RepairContext): Boolean {
        val now = clock.now()
        return valid(identity, capturedRevision, ticket) && now.toLocalDate().toString() == context.date &&
            now.zone.id == context.deviceTimezone && day().localDate == context.date && day().timezone == context.planTimezone
    }

    private data class RepairContext(val date: String, val deviceTimezone: String, val planTimezone: String)

    private suspend fun disable(identity: TokenStore.Snapshot, capturedRevision: Long, ticket: Long) {
        if (hasDisplayedInProgress()) {
            val flying = isFlying()
            val archived = hasArchive()
            val blocked = flying || archived
            if (!valid(identity, capturedRevision, ticket)) return
            if (blocked) {
                mutableState.value = state.value.copy(switchOverride = true)
                message(PlanEnableNotice.CANNOT_CLOSE)
                return
            }
            // C has active metadata; D only restores an existing valid completion
            // receipt. An absent receipt must not select another visible row.
            (global.current().activeSelection ?: recoverySelection())?.let(forceComplete)
        }
        if (!valid(identity, capturedRevision, ticket)) return
        global.disable()
        mutableState.value = state.value.copy(switchOverride = null)
    }

    fun navigationOpened(requestId: String) {
        if (state.value.pendingNavigation?.requestId == requestId) mutableState.value = state.value.copy(pendingNavigation = null)
    }

    fun acceptSeat(requestId: String, seat: String?): Boolean = try {
        global.acceptSeat(requestId, seat, day(), candidate())
    } catch (_: Exception) { message(PlanEnableNotice.STORAGE_FAILED); false }

    fun refreshGate() { if (state.value.gateVisible) entitlement.refresh() }

    private fun candidate(): DailyFlightLeg? = day().displayLegs().firstOrNull {
        SourceFlightPlanStatus.parse(it.snapshot.legStatus) == SourceFlightPlanStatus.WAITING &&
            it.snapshot.departureAirport != null && it.snapshot.arrivalAirport != null
    } ?: temporaryCandidate()?.takeIf { it.snapshot.departureAirport != null && it.snapshot.arrivalAirport != null }

    private fun hasDisplayedInProgress() = day().displayLegs().any {
        SourceFlightPlanStatus.parse(it.snapshot.legStatus) == SourceFlightPlanStatus.IN_PROGRESS
    }

    private fun valid(identity: TokenStore.Snapshot, capturedRevision: Long, ticket: Long) =
        identity == TokenStore.snapshot() && revision() == capturedRevision && epoch == ticket

    private fun reject(kind: PlanEnableNotice, argument: String? = null) {
        mutableState.value = state.value.copy(switchOverride = false)
        message(kind, argument)
    }
    private fun message(kind: PlanEnableNotice, argument: String? = null) { mutableMessages.tryEmit(PlanEnableMessage(kind, argument)) }
}
