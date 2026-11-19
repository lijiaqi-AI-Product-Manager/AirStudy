package com.projectfocus.app.booking

import android.content.Context
import android.content.SharedPreferences
import com.projectfocus.core.common.plan.SourceFlightPlanDates
import com.projectfocus.core.network.di.NetworkModule.NetworkScope
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.UpdateFlightPlanLegStatusRequest
import com.projectfocus.core.network.plan.FlightPlanGateway
import com.projectfocus.core.network.plan.FlightPlanRejectedException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface PlanCompletionActions {
    val updates: SharedFlow<DailyFlightPlan>
    fun complete(selection: ActivePlanSelection)
    fun receiptMillis(leg: DailyFlightLeg): Long?
    fun recoverySelection(): ActivePlanSelection?
    /** Null means this caller has no real completion transport, not a successful sync. */
    val originReconciliation: PlanOriginReconciliationAccess? get() = null
    /** Null for older hosts without a real planned-start/abort status bridge. */
    val flightStatuses: PlanFlightStatusActions? get() = null
    val sessionStatuses: PlanSessionStatusActions? get() = null
}

@Serializable
enum class PlanCompletionFailure { FAILED, TIMEOUT, STORAGE, INVALID_ACK, TERMINAL_CONFLICT }

@Serializable
data class PlanCompletionDiagnostic(val ownerScope: String, val entryId: String?, val kind: PlanCompletionFailure)

@Serializable
internal data class PendingPlanCompletion(
    val ownerScope: String,
    val entryId: String,
    val selection: ActivePlanSelection,
    val targetStatus: String = "completed",
    // ActivePlanSelection currently has no session timestamp. Zero is meaningful
    // for source Off before real start; it is not replaced by completion now.
    val sessionTimestampMillis: Long = 0,
    // Source flags are separate prefs. Co-locating them with the exact pending
    // entry is an Own JSON/atomic-compensation adapter; legacy JSON defaults false.
    val inProgressSent: Boolean = false,
    val actuallyStarted: Boolean = false,
    /** Own stable receipt for a durably accepted Core session. */
    val fixedCompletionMillis: Long? = null,
)

/**
 * Source planned in_progress / undo-start waiting / completed status dispatch.
 * JSON/commit checks/token-hash scope/entry and generation guards are Own adapters.
 * This owns no scheduler, boarding UI, whole-list queue or optimistic UI status.
 */
@Singleton
class SharedPreferencesPlanCompletionDispatch internal constructor(
    private val preferences: SharedPreferences,
    private val receipts: SharedPreferences,
    private val clock: PreparationClock,
    private val gateway: FlightPlanGateway,
    private val scope: CoroutineScope,
    private val currentBacking: () -> List<DailyFlightLeg> = { emptyList() },
    private val identity: () -> TokenStore.Snapshot = TokenStore::snapshot,
    private val deviceTimezone: () -> TimeZone = TimeZone::getDefault,
    private val clearActiveIfMatches: (ActivePlanSelection) -> Unit = {},
    private val currentActiveForAbort: () -> ActivePlanSelection? = { null },
) : PlanCompletionActions, PlanOriginReconciliationAccess, PlanFlightStatusActions, PlanSessionStatusActions {
    @Inject constructor(@ApplicationContext context: Context, clock: PreparationClock,
                        gateway: FlightPlanGateway, @NetworkScope scope: CoroutineScope,
                        runtime: DailyFlightPlanRuntime, global: GlobalFlightPlanStore) : this(
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
        context.getSharedPreferences(RECEIPT_FILE_NAME, Context.MODE_PRIVATE), clock, gateway, scope,
        currentBacking = { runtime.snapshot().selectedDay?.legs.orEmpty() },
        clearActiveIfMatches = global::clearActiveIfMatches,
        currentActiveForAbort = {
            global.current().also { check(!it.storageError) { "Global plan metadata unavailable" } }.activeSelection
        },
    )

    private val lock = Any()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }
    private val mutableUpdates = MutableSharedFlow<DailyFlightPlan>(extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val updates = mutableUpdates.asSharedFlow()
    private val mutableDiagnostic = MutableStateFlow<PlanCompletionDiagnostic?>(null)
    val diagnostic = mutableDiagnostic.asStateFlow()
    private val attempts = mutableMapOf<String, Attempt>()
    private var sequence = 0L
    override val originReconciliation: PlanOriginReconciliationAccess get() = this
    override val flightStatuses: PlanFlightStatusActions get() = this
    override val sessionStatuses: PlanSessionStatusActions get() = this

    /** Own durable session/status journal; source B still reads current active. */
    override fun handoff(command: AcceptedPlanStatusCommand, expectedIdentity: TokenStore.Snapshot): Unit = synchronized(lock) {
        checkAccount(expectedIdentity)
        validate(command.selection)
        require(command.sessionId.isNotBlank() && command.sessionId.length <= 128)
        require(command.sessionTimestampMillis >= 0)
        require(if (command.status == AcceptedPlanStatus.COMPLETED)
            command.completedAtMillis != null && command.completedAtMillis > 0
            else command.completedAtMillis == null)
        val owner = FlightPlanOwnership.scope(expectedIdentity)
        val journalKey = "$owner:accepted-command:" + command.sessionId + ":" + command.status.name
        val journal = preferences.getString(journalKey, null)
        if (journal != null) {
            val receipt = json.decodeFromString(AcceptedPlanCommandReceipt.serializer(), journal)
            check(receipt.command == command) { "Accepted session status snapshot changed" }
            val pending = readPending(owner)
            // An ACK may already have removed pending. A journal never recreates
            // old pending or overwrites a newer entry.
            if (receipt.entryId != null && pending != null && pending.entryId == receipt.entryId) {
                if (pending.targetStatus == COMPLETED) ensureReceipt(pending, expectedIdentity)
                retryPending(force = false, account = expectedIdentity)
            }
            return@synchronized
        }
        val active = if (command.status == AcceptedPlanStatus.ABORTED) currentActiveForAbort()?.let {
            val date = it.localDate.trim { char -> char <= '\u0020' }
            it.takeIf { date.isNotEmpty() && it.candidate.snapshot.legIndex > 0 }?.copy(localDate = date)
        } else null
        active?.let(::validate)
        checkAccount(expectedIdentity)
        val previous = readPending(owner)
        if (command.status == AcceptedPlanStatus.ABORTED && active == null &&
            (previous == null || !previous.inProgressSent)) {
            // Source B's no-active branch requires sent=true. Recording the Own
            // once-only invocation changes no pending, receipt or active field.
            val receipt = AcceptedPlanCommandReceipt(command, AcceptedPlanCommandAction.NO_PENDING)
            writeSessionEntry(null, journalKey, json.encodeToString(AcceptedPlanCommandReceipt.serializer(), receipt), expectedIdentity)
            return@synchronized
        }
        val selection = if (command.status == AcceptedPlanStatus.ABORTED)
            active ?: checkNotNull(previous).selection else command.selection
        val related = previous?.takeIf { sameLeg(it.selection, selection) }
        // Do not turn the no-active fallback into universal completed protection.
        val protectCompleted = command.status == AcceptedPlanStatus.ABORTED && active != null &&
            previous?.targetStatus == COMPLETED && sameLeg(previous.selection, active)
        val entry = if (protectCompleted) checkNotNull(previous) else when (command.status) {
            AcceptedPlanStatus.STARTED -> PendingPlanCompletion(owner, UUID.randomUUID().toString(), selection,
                IN_PROGRESS, command.sessionTimestampMillis, actuallyStarted = true)
            AcceptedPlanStatus.ABORTED -> PendingPlanCompletion(owner, UUID.randomUUID().toString(), selection,
                WAITING, related?.sessionTimestampMillis ?: 0,
                actuallyStarted = related?.actuallyStarted ?: false)
            AcceptedPlanStatus.COMPLETED -> PendingPlanCompletion(owner, UUID.randomUUID().toString(), selection,
                COMPLETED, command.sessionTimestampMillis,
                inProgressSent = related?.inProgressSent ?: false,
                actuallyStarted = related?.actuallyStarted ?: true,
                fixedCompletionMillis = command.completedAtMillis)
        }
        val receipt = AcceptedPlanCommandReceipt(command,
            if (protectCompleted) AcceptedPlanCommandAction.PROTECTED_COMPLETION else AcceptedPlanCommandAction.QUEUED,
            entry.entryId)
        writeSessionEntry(entry, journalKey, json.encodeToString(AcceptedPlanCommandReceipt.serializer(), receipt), expectedIdentity)
        checkAccount(expectedIdentity)
        if (protectCompleted) {
            clearActiveIfMatches(checkNotNull(active))
            checkAccount(expectedIdentity)
            retryPending(force = false, account = expectedIdentity)
        } else {
            if (entry.targetStatus == COMPLETED) ensureReceipt(entry, expectedIdentity)
            if (entry.targetStatus == WAITING) clearActiveIfMatches(selection)
            checkAccount(expectedIdentity)
            launch(entry, expectedIdentity)
        }
        Unit
    }

    private fun writeSessionEntry(entry: PendingPlanCompletion?, journalKey: String,
                                  rawReceipt: String, account: TokenStore.Snapshot) {
        val pendingKey = entry?.let { pendingKey(it.ownerScope) }
        val previousPending = pendingKey?.let { preferences.getString(it, null) }
        val previousJournal = preferences.getString(journalKey, null)
        val rawPending = entry?.let { json.encodeToString(PendingPlanCompletion.serializer(), it) }
        checkAccount(account)
        try {
            val editor = preferences.edit().putString(journalKey, rawReceipt)
            if (pendingKey != null) editor.putString(pendingKey, rawPending)
            if (!editor.commit()) throw IOException(STORAGE_ERROR)
            checkAccount(account)
            check((pendingKey == null || preferences.getString(pendingKey, null) == rawPending) &&
                preferences.getString(journalKey, null) == rawReceipt) { "Accepted command entry changed" }
        } catch (error: Exception) {
            // Compensate only values still owned by this exact journal/pending.
            val rollback = preferences.edit()
            var changed = false
            if (pendingKey != null && preferences.getString(pendingKey, null) == rawPending) {
                if (previousPending == null) rollback.remove(pendingKey) else rollback.putString(pendingKey, previousPending)
                changed = true
            }
            if (preferences.getString(journalKey, null) == rawReceipt) {
                if (previousJournal == null) rollback.remove(journalKey) else rollback.putString(journalKey, previousJournal)
                changed = true
            }
            if (changed && !rollback.commit()) error.addSuppressed(IOException(STORAGE_ERROR))
            throw error
        }
    }

    override fun complete(selection: ActivePlanSelection): Unit = synchronized(lock) {
        val account = identity()
        val owner = FlightPlanOwnership.scope(account)
        validate(selection)
        try {
            val previous = readPending(owner)
            val related = previous?.takeIf { sameLeg(it.selection, selection) }
            val entry = previous?.takeIf { it.targetStatus == COMPLETED && it.selection == selection }
                ?: PendingPlanCompletion(owner, UUID.randomUUID().toString(), selection,
                    sessionTimestampMillis = related?.sessionTimestampMillis ?: 0,
                    inProgressSent = related?.inProgressSent ?: false,
                    actuallyStarted = related?.actuallyStarted ?: false).also { writeReconciliationPending(it, account) }
            checkAccount(account)
            // A reconstructed retry of the exact pending entry keeps its first
            // receipt; a new complete captures now only after pending is stored.
            ensureReceipt(entry)
            launch(entry, account)
            Unit
        } catch (error: Exception) {
            recordDiagnostic(owner, null, PlanCompletionFailure.STORAGE)
            throw error
        }
    }

    override fun pendingStatus(): PlanPendingReconciliationState? = synchronized(lock) {
        val account = identity()
        val owner = FlightPlanOwnership.scope(account)
        val entry = readPending(owner)
        checkAccount(account)
        entry?.let {
            PlanPendingReconciliationState(it.ownerScope, it.entryId, it.selection.localDate,
                it.selection.candidate.snapshot.legIndex, it.targetStatus, mappedStatus(it.targetStatus), it.sessionTimestampMillis)
        }
    }

    override fun exactReceiptMillis(localDate: String, legIndex: Int): Long? = synchronized(lock) {
        if (legIndex <= 0) return@synchronized null
        val account = identity()
        val value = readReceipt(FlightPlanOwnership.scope(account), localDate, legIndex)
        checkAccount(account)
        value
    }

    override fun resendPending(): PlanReconciliationLease = synchronized(lock) { retryPending(force = true) }

    override fun retryPendingStatus(): PlanReconciliationLease = synchronized(lock) { retryPending(force = false) }

    private fun retryPending(force: Boolean, account: TokenStore.Snapshot = identity()): PlanReconciliationLease {
        checkAccount(account)
        val owner = FlightPlanOwnership.scope(account)
        val entry = readPending(owner) ?: return BorrowedReconciliationLease
        checkAccount(account)
        if (!force && attempts[owner]?.let { it.entryId == entry.entryId && it.job.isActive } == true)
            return BorrowedReconciliationLease
        if (entry.sessionTimestampMillis > 0 && clock.now().toInstant().toEpochMilli() - entry.sessionTimestampMillis >
            SourceOriginReconciliationPolicy.MAX_AGE_MILLIS) {
            // L clears expired pending, without deleting its separate receipt.
            removePending(entry)
            return BorrowedReconciliationLease
        }
        if (entry.targetStatus == IN_PROGRESS && entry.inProgressSent) {
            if (entry.actuallyStarted) return BorrowedReconciliationLease
            // L hydrates active metadata then B restores waiting. No receipt or
            // current time is invented for this undo-start branch.
            return abort(entry.selection, account).second
        }
        // Source x clears q (the status-request in-flight latch) before L.
        // Force a real retry even for this same active entry. Cancelling the old
        // exact attempt and rejecting its late ACK are Own lifecycle adapters.
        return launch(entry, account, force = force)
    }

    override fun markInProgress(selection: ActivePlanSelection, sessionTimestampMillis: Long,
                                expectedIdentity: TokenStore.Snapshot): Unit = synchronized(lock) {
        checkAccount(expectedIdentity)
        validate(selection)
        require(sessionTimestampMillis >= 0)
        val owner = FlightPlanOwnership.scope(expectedIdentity)
        val previous = readPending(owner)
        val entry = previous?.takeIf { it.targetStatus == IN_PROGRESS && it.selection == selection &&
            it.sessionTimestampMillis == sessionTimestampMillis }
            ?: PendingPlanCompletion(owner, UUID.randomUUID().toString(), selection, IN_PROGRESS,
                sessionTimestampMillis, actuallyStarted = true)
        val started = if (entry.actuallyStarted) entry else entry.copy(actuallyStarted = true)
        if (previous != started) writeReconciliationPending(started, expectedIdentity)
        checkAccount(expectedIdentity)
        launch(started, expectedIdentity)
        Unit
    }

    override fun notifyAborted(activeSelection: ActivePlanSelection?,
                               expectedIdentity: TokenStore.Snapshot): PlanAbortStatusAction = synchronized(lock) {
        checkAccount(expectedIdentity)
        abort(activeSelection, expectedIdentity).first
    }

    private fun abort(activeSelection: ActivePlanSelection?, account: TokenStore.Snapshot):
        Pair<PlanAbortStatusAction, PlanReconciliationLease> {
        val owner = FlightPlanOwnership.scope(account)
        val pending = readPending(owner)
        checkAccount(account)
        if (activeSelection != null) {
            validate(activeSelection)
            if (pending != null && pending.targetStatus == COMPLETED && sameLeg(pending.selection, activeSelection)) {
                // B clears active g, but protects same date/index completed data.
                clearActiveIfMatches(activeSelection)
                checkAccount(account)
                return PlanAbortStatusAction.PRESERVED_COMPLETION to retryPending(force = false, account = account)
            }
        } else if (pending == null || !pending.inProgressSent) {
            return PlanAbortStatusAction.NO_PENDING to BorrowedReconciliationLease
        }
        val selection = activeSelection ?: checkNotNull(pending).selection
        val related = pending?.takeIf { sameLeg(it.selection, selection) }
        val entry = PendingPlanCompletion(owner, UUID.randomUUID().toString(), selection, WAITING,
            related?.sessionTimestampMillis ?: 0, inProgressSent = false,
            actuallyStarted = related?.actuallyStarted ?: false)
        // Source B’s fallback sends waiting without e. This Own exact-entry
        // adapter persists that outbound target too, so retries cannot resurrect
        // in_progress and an old ACK cannot mutate the replacement waiting entry.
        writeReconciliationPending(entry, account)
        clearActiveIfMatches(selection)
        checkAccount(account)
        return PlanAbortStatusAction.QUEUED_WAITING to launch(entry, account)
    }

    override fun snapshot(): PlanFlightStatusSnapshot? = synchronized(lock) {
        val account = identity()
        val entry = readPending(FlightPlanOwnership.scope(account))
        checkAccount(account)
        entry?.let { PlanFlightStatusSnapshot(it.ownerScope, it.entryId, it.selection, it.targetStatus,
            it.sessionTimestampMillis, it.inProgressSent, it.actuallyStarted) }
    }

    override fun requeueFromReceipt(selection: ActivePlanSelection, receiptMillis: Long): Unit = synchronized(lock) {
        val account = identity()
        val owner = FlightPlanOwnership.scope(account)
        validate(selection)
        require(receiptMillis > 0)
        val index = selection.candidate.snapshot.legIndex
        check(readReceipt(owner, selection.localDate, index) == receiptMillis) { "Completion receipt changed" }
        val previous = readPending(owner)
        if (previous != null && previous.targetStatus == COMPLETED && previous.selection == selection && previous.sessionTimestampMillis == receiptMillis) {
            checkAccount(account)
        } else {
            writeReconciliationPending(PendingPlanCompletion(owner, UUID.randomUUID().toString(), selection,
                sessionTimestampMillis = receiptMillis), account)
        }
        // H writes only pending metadata with m=the positive receipt. Calling
        // complete/ensureReceipt here would incorrectly replace it with now.
        Unit
    }

    override fun recoverySelection(): ActivePlanSelection? = synchronized(lock) {
        val owner = FlightPlanOwnership.scope(identity())
        try {
            mutableDiagnostic.value = readDiagnostic(owner)
            readPending(owner)?.takeIf { it.targetStatus == COMPLETED }?.selection
        } catch (_: Exception) {
            recordDiagnostic(owner, null, PlanCompletionFailure.STORAGE)
            null
        }
    }

    override fun receiptMillis(leg: DailyFlightLeg): Long? = synchronized(lock) {
        val owner = FlightPlanOwnership.scope(identity())
        val index = leg.snapshot.legIndex
        if (index <= 0) return@synchronized null
        try {
            PlanCompletionParameters.receiptDays(leg.snapshot, deviceTimezone()).firstNotNullOfOrNull { date ->
                readReceipt(owner, date, index)
            }
        } catch (_: Exception) {
            recordDiagnostic(owner, null, PlanCompletionFailure.STORAGE)
            null
        }
    }

    private fun launch(entry: PendingPlanCompletion, account: TokenStore.Snapshot, force: Boolean = false): PlanReconciliationLease {
        val old = attempts[entry.ownerScope]
        if (!force && old?.entryId == entry.entryId && old.job.isActive) return BorrowedReconciliationLease
        old?.job?.cancel()
        val ticket = ++sequence
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val index = entry.selection.candidate.snapshot.legIndex
                val actual = if (entry.targetStatus == COMPLETED)
                    synchronized(lock) { readReceipt(entry.ownerScope, entry.selection.localDate, index) }
                        ?: clock.now().toInstant().toEpochMilli() else null
                // Source v(index): first matching index in current BACKING order,
                // deliberately not a date-filtered/sorted/active-candidate lookup.
                val expectedLeg = if (actual != null) currentBacking().firstOrNull { it.snapshot.legIndex == index }?.snapshot else null
                val difference = if (actual != null) expectedLeg?.let { PlanCompletionParameters.differenceMinutes(it, actual) }
                    ?.takeUnless { it == Int.MIN_VALUE } else null
                val request = UpdateFlightPlanLegStatusRequest(entry.selection.localDate, entry.selection.timezone,
                    SourceFlightPlanDates.get(LocalDate.parse(entry.selection.localDate), ZoneId.of(entry.selection.timezone))
                        .timezoneOffsetMinutes,
                    index, entry.targetStatus, requestId(entry),
                    actualArrivalAt = actual?.let(PlanCompletionParameters::actualArrival)?.takeIf(String::isNotEmpty),
                    arrivalDiffMinutes = difference,
                    flightNo = DailyFlightPlans.normalizeAssignment(entry.selection.candidate.snapshot.flightNo).takeIf(String::isNotEmpty),
                    seatNo = DailyFlightPlans.normalizeAssignment(entry.selection.seatNo).takeIf(String::isNotEmpty))
                checkAccount(account)
                val response = gateway.updateLegStatus(request)
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (!isExact(entry, account)) return@synchronized
                    if (response != null && (response.localPlanDate != entry.selection.localDate || response.timezone != entry.selection.timezone)) {
                        recordDiagnostic(entry.ownerScope, entry.entryId, PlanCompletionFailure.INVALID_ACK)
                        return@synchronized
                    }
                    // Construct before deleting pending, so malformed ack fields
                    // remain recoverable instead of becoming a fabricated day.
                    val updated = response?.let {
                        DailyFlightPlans.fromSnapshot(it, DailyFlightPlan(entry.selection.localDate, entry.selection.timezone,
                            legs = listOf(entry.selection.candidate), ownerScope = entry.ownerScope))
                            .copy(ownerScope = entry.ownerScope)
                    }
                    if (entry.targetStatus == IN_PROGRESS) {
                        // Mode2 success records only the sent flag; it is not
                        // completion and does not clear active/pending or receipt.
                        writeReconciliationPending(entry.copy(inProgressSent = true), account)
                        if (!isExact(entry, account)) return@synchronized
                    } else removePending(entry)
                    try { clearDiagnostic(entry.ownerScope, entry.entryId) }
                    catch (_: Exception) { recordDiagnostic(entry.ownerScope, entry.entryId, PlanCompletionFailure.STORAGE) }
                    if (entry.targetStatus != IN_PROGRESS) {
                        try { clearActiveIfMatches(entry.selection) }
                        catch (_: Exception) { recordDiagnostic(entry.ownerScope, entry.entryId, PlanCompletionFailure.STORAGE) }
                    }
                    if (updated != null && identity() == account) mutableUpdates.tryEmit(updated)
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                fail(entry, account, PlanCompletionFailure.TIMEOUT)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error is FlightPlanRejectedException && error.isTerminalConflict) {
                    synchronized(lock) {
                        if (isExact(entry, account)) {
                            try {
                                removePending(entry)
                                clearActiveIfMatches(entry.selection)
                                recordDiagnostic(entry.ownerScope, entry.entryId, PlanCompletionFailure.TERMINAL_CONFLICT)
                            } catch (_: Exception) { recordDiagnostic(entry.ownerScope, entry.entryId, PlanCompletionFailure.STORAGE) }
                        }
                    }
                } else fail(entry, account, if (error is IOException && error.message == STORAGE_ERROR)
                    PlanCompletionFailure.STORAGE else PlanCompletionFailure.FAILED)
            } finally {
                synchronized(lock) { if (attempts[entry.ownerScope]?.ticket == ticket) attempts.remove(entry.ownerScope) }
            }
        }
        attempts[entry.ownerScope] = Attempt(entry.entryId, ticket, job)
        job.start()
        return object : PlanReconciliationLease {
            override fun close() = synchronized(lock) {
                val current = attempts[entry.ownerScope]
                if (current?.ticket == ticket && current.entryId == entry.entryId) current.job.cancel()
            }
        }
    }

    private fun fail(entry: PendingPlanCompletion, account: TokenStore.Snapshot, kind: PlanCompletionFailure) = synchronized(lock) {
        if (isExact(entry, account)) recordDiagnostic(entry.ownerScope, entry.entryId, kind)
    }

    private fun isExact(entry: PendingPlanCompletion, account: TokenStore.Snapshot): Boolean =
        identity() == account && try { readPending(entry.ownerScope)?.entryId == entry.entryId } catch (_: Exception) { false }

    private fun removePending(entry: PendingPlanCompletion) {
        if (readPending(entry.ownerScope)?.entryId == entry.entryId) writeString(preferences, pendingKey(entry.ownerScope), null)
    }

    private fun ensureReceipt(entry: PendingPlanCompletion, account: TokenStore.Snapshot? = null) {
        account?.let(::checkAccount)
        check(entry.targetStatus == COMPLETED)
        val index = entry.selection.candidate.snapshot.legIndex
        val key = receiptKey(entry.ownerScope, entry.selection.localDate, index)
        val marker = "$key:entry"
        if (receipts.getString(marker, null) == entry.entryId && readReceipt(entry.ownerScope, entry.selection.localDate, index) != null) return
        val now = entry.fixedCompletionMillis ?: clock.now().toInstant().toEpochMilli()
        if (now <= 0) return // Source C guards positive now; N independently falls back to now.
        val before = receipts.getLong(key, 0)
        val had = receipts.contains(key)
        val previousMarker = receipts.getString(marker, null)
        try {
            if (!receipts.edit().putLong(key, now).putString(marker, entry.entryId).commit())
                throw IOException(STORAGE_ERROR)
            account?.let(::checkAccount)
        } catch (error: Exception) {
            if (receipts.getString(marker, null) == entry.entryId && receipts.getLong(key, 0) == now) {
                val rollback = receipts.edit()
                if (had) rollback.putLong(key, before) else rollback.remove(key)
                if (previousMarker == null) rollback.remove(marker) else rollback.putString(marker, previousMarker)
                if (!rollback.commit()) error.addSuppressed(IOException(STORAGE_ERROR))
            }
            throw error
        }
    }

    private fun readReceipt(owner: String, date: String, index: Int): Long? =
        receipts.getLong(receiptKey(owner, date, index), -1).takeIf { it > 0 }

    private fun readPending(owner: String): PendingPlanCompletion? {
        val raw = preferences.getString(pendingKey(owner), null) ?: return null
        return json.decodeFromString(PendingPlanCompletion.serializer(), raw).also {
            require(it.ownerScope == owner && it.entryId.isNotBlank() && it.targetStatus in setOf(COMPLETED, IN_PROGRESS, WAITING) && it.sessionTimestampMillis >= 0)
            require(it.fixedCompletionMillis == null || (it.targetStatus == COMPLETED && it.fixedCompletionMillis > 0))
            validate(it.selection)
        }
    }

    private fun validate(selection: ActivePlanSelection) {
        require(LocalDate.parse(selection.localDate).toString() == selection.localDate)
        ZoneId.of(selection.timezone)
        require(selection.candidate.snapshot.legIndex > 0)
    }

    private fun checkAccount(account: TokenStore.Snapshot) { check(identity() == account) { "Completion account changed" } }

    private fun sameLeg(first: ActivePlanSelection, second: ActivePlanSelection): Boolean =
        first.localDate == second.localDate && first.candidate.snapshot.legIndex == second.candidate.snapshot.legIndex

    private fun mappedStatus(value: String) = when (value) { IN_PROGRESS -> 2; WAITING -> 1; else -> 3 }

    private fun writeReconciliationPending(entry: PendingPlanCompletion, account: TokenStore.Snapshot) {
        val key = pendingKey(entry.ownerScope)
        val previous = preferences.getString(key, null)
        val next = json.encodeToString(PendingPlanCompletion.serializer(), entry)
        checkAccount(account)
        try {
            if (!preferences.edit().putString(key, next).commit()) throw IOException(STORAGE_ERROR)
            checkAccount(account)
            check(preferences.getString(key, null) == next) { "Completion pending entry changed" }
        } catch (error: Exception) {
            try {
                // Same-token generations share a scope. A generation change in
                // commit must not leave this rejected H write behind. Reentrant
                // or replacement entry writes are independently owned: never
                // roll them back to the value captured before this H attempt.
                if (preferences.getString(key, null) == next) {
                    val rollback = preferences.edit()
                    if (previous == null) rollback.remove(key) else rollback.putString(key, previous)
                    if (!rollback.commit()) error.addSuppressed(IOException(STORAGE_ERROR))
                }
            } catch (rollbackError: Exception) {
                if (rollbackError !== error) error.addSuppressed(rollbackError)
            }
            throw error
        }
    }

    private fun writeString(storage: SharedPreferences, key: String, next: String?) {
        val previous = storage.getString(key, null)
        val editor = storage.edit()
        if (next == null) editor.remove(key) else editor.putString(key, next)
        if (!editor.commit()) {
            val rollback = storage.edit()
            if (previous == null) rollback.remove(key) else rollback.putString(key, previous)
            rollback.commit()
            throw IOException(STORAGE_ERROR)
        }
    }

    private fun readDiagnostic(owner: String): PlanCompletionDiagnostic? = preferences.getString("$owner:diagnostic", null)?.let {
        json.decodeFromString(PlanCompletionDiagnostic.serializer(), it).takeIf { value -> value.ownerScope == owner }
    }
    private fun recordDiagnostic(owner: String, entry: String?, kind: PlanCompletionFailure) {
        val value = PlanCompletionDiagnostic(owner, entry, kind)
        try { writeString(preferences, "$owner:diagnostic", json.encodeToString(PlanCompletionDiagnostic.serializer(), value)) }
        catch (_: Exception) { /* The original storage failure still must not turn into success. */ }
        if (FlightPlanOwnership.scope(identity()) == owner) mutableDiagnostic.value = value
    }
    private fun clearDiagnostic(owner: String, entry: String) {
        val old = readDiagnostic(owner)
        if (old != null && (old.entryId == null || old.entryId == entry)) writeString(preferences, "$owner:diagnostic", null)
        mutableDiagnostic.value = null
    }

    private fun requestId(entry: PendingPlanCompletion): String =
        "req_leg_${entry.selection.localDate}_${entry.selection.candidate.snapshot.legIndex}_${entry.targetStatus}" +
            if (entry.sessionTimestampMillis > 0) "_r${entry.sessionTimestampMillis}" else ""
    private fun pendingKey(owner: String) = "$owner:pending"
    private fun receiptKey(owner: String, date: String, index: Int) = "$owner:receipt:${date}_$index"
    private data class Attempt(val entryId: String, val ticket: Long, val job: Job)
    private object BorrowedReconciliationLease : PlanReconciliationLease { override fun close() = Unit }

    companion object {
        const val FILE_NAME = "projectfocus_plan_completion_dispatch_v1"
        const val RECEIPT_FILE_NAME = "projectfocus_plan_completion_receipts_v1"
        private const val STORAGE_ERROR = "Could not persist planned completion"
        private const val COMPLETED = "completed"
        private const val IN_PROGRESS = "in_progress"
        private const val WAITING = "waiting_takeoff"
    }
}
