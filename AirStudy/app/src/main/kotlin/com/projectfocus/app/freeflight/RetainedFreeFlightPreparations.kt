package com.projectfocus.app.freeflight

import android.content.Context
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.booking.FreeFlightHostOwnerFactory
import com.projectfocus.app.booking.SourceFlightNumber
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.app.home.HomeOriginSelection
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.database.seed.AirportSeeder
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.FreeFlightStartRequest
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.freeflight.FreeFlightArchiveStore
import com.projectfocus.service.focus.freeflight.FreeFlightCommandResult
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.FreeFlightPreparationAdmission
import com.projectfocus.service.focus.freeflight.FreeFlightPreparationResult
import com.projectfocus.service.focus.freeflight.FreeFlightSessionRepository
import com.projectfocus.service.focus.freeflight.FreeFlightStartAcknowledgement
import com.projectfocus.service.focus.freeflight.FreeFlightStartIdentity
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightPreparationDispatch
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightPreparationServiceOperations
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An in-process lookup handle, not an account/host grant and never reconstructed from saved fields. */
class FreeFlightPreparationHandle internal constructor(val preparationId: String)

/**
 * The real Application retains preparation and the one formal request. Activity waits can detach
 * without cancelling either operation. No Activity, View, Bundle, credential or accepting port is
 * retained here. A process restart cannot recreate this authority from a saved preparation id.
 */
@Singleton
class RetainedFreeFlightPreparations @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hosts: FreeFlightHostOwnerFactory,
    private val airports: AirportDao,
    private val settings: SettingsRepository,
    private val archives: FreeFlightArchiveStore,
    private val admission: FreeFlightPreparationAdmission,
    private val repository: FreeFlightSessionRepository,
    @FreeFlightApplicationScope private val scope: CoroutineScope,
    private val keepAlive: FreeFlightPreparationServiceOperations,
    private val runtime: FreeFlightRuntimeRegistry,
) {
    private class Entry(val handle: FreeFlightPreparationHandle) {
        lateinit var preparation: Deferred<FreeFlightPreparationDecision>
        var owner: FreeFlightOwnerToken? = null
        var prepared: FreeFlightPrepared? = null
        var closed = false
        var rejected = false
        var submitted: FreeFlightStartRequest? = null
        var start: Deferred<FreeFlightCommandResult>? = null
    }
    private val lock = Any()
    private val hostOpening = Mutex()
    private var retained: Entry? = null
    val startState get() = repository.startState
    val ownerInvalidations get() = hosts.invalidations
    val keepAliveState get() = keepAlive.observation

    init {
        scope.launch {
            hosts.currentHosts.collect { observed ->
                val entry = synchronized(lock) { retained?.takeIf { it.owner != null && it.owner != observed } }
                val owner = entry?.owner
                if (entry != null && owner != null && !hosts.isCurrent(owner)) closeUnsubmitted(entry)
            }
        }
    }

    fun begin(): FreeFlightPreparationHandle = synchronized(lock) {
        retained?.takeIf(::finishedSubmission)?.let { it.closed = true }
        retained?.takeUnless { it.closed || it.rejected }?.let { return it.handle }
        retained?.takeIf { it.rejected && !it.closed }?.let { old ->
            old.closed = true
            old.owner?.let { owner -> retirePreparation(old, owner) }
        }
        val entry = Entry(FreeFlightPreparationHandle(UUID.randomUUID().toString()))
        entry.preparation = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { prepareOwned(entry) }
        retained = entry
        entry.handle
    }

    private fun finishedSubmission(entry: Entry): Boolean {
        val submitted = entry.submitted ?: return false
        val prepared = entry.prepared ?: return false
        if (entry.closed || entry.start?.isCompleted != true) return false
        val archive = repository.state.value ?: return false
        if (archive.sessionId != submitted.sessionId || archive.ownerScope != submitted.ownerScope ||
            archive.status == FreeFlightStatus.ONGOING) return false
        val key = runtime.observation.value.key ?: return false
        if (key.sessionId != submitted.sessionId || key.owner != prepared.owner || !runtime.isStopped(key)) return false
        if (hosts.retainsRuntime(submitted.sessionId, prepared.owner)) return false
        val identity = FreeFlightStartIdentity(prepared.owner, submitted.sessionId)
        return repository.startAcknowledgements.value[identity] is FreeFlightStartAcknowledgement.Recorded
    }

    /** Lookup only: never open another host, retry eligibility or mint a session on restoration. */
    fun restore(preparationId: String, sessionId: String?): FreeFlightPreparationHandle? = synchronized(lock) {
        retained?.takeIf { !it.closed && it.handle.preparationId == preparationId &&
            (sessionId == null || it.prepared?.sessionId == sessionId) }?.handle
    }

    suspend fun prepare(handle: FreeFlightPreparationHandle): FreeFlightPreparationDecision {
        val task = synchronized(lock) { current(handle)?.preparation }
            ?: return unavailable()
        task.start()
        return task.await()
    }

    /** The retained operation is authoritative, rather than the screen's submitted hint. */
    fun isSubmitted(handle: FreeFlightPreparationHandle): Boolean = synchronized(lock) { current(handle)?.submitted != null }

    suspend fun isCurrent(handle: FreeFlightPreparationHandle, prepared: FreeFlightPrepared): Boolean {
        val entry = synchronized(lock) { current(handle)?.takeIf { it.prepared === prepared } } ?: return false
        if (!hosts.isCurrent(prepared.owner)) { closeUnsubmitted(entry);return false }
        return synchronized(lock) { current(handle) === entry && entry.prepared === prepared }
    }

    suspend fun startKeepAlive(handle: FreeFlightPreparationHandle, prepared: FreeFlightPrepared): FreeFlightPreparationDispatch {
        if (!isCurrent(handle, prepared)) return FreeFlightPreparationDispatch.Rejected("当前飞行准备已失效")
        return keepAlive.start(prepared.preparationId, prepared.sessionId, prepared.owner)
    }

    suspend fun start(handle: FreeFlightPreparationHandle, prepared: FreeFlightPrepared,
                      request: FreeFlightStartRequest): FreeFlightCommandResult {
        if (!isCurrent(handle, prepared)) return FreeFlightCommandResult.Rejected("当前飞行准备已失效，请重新准备")
        if (request.sessionId != prepared.sessionId || request.ownerScope != prepared.owner.ownerScope ||
            request.departure != prepared.departure || request.flightNumber != prepared.flightNumber ||
            request.shieldingCategoryId != prepared.shieldingCategoryId)
            return FreeFlightCommandResult.Rejected("起飞信息与本次准备不一致")
        val task = synchronized(lock) {
            val entry = current(handle)?.takeIf { it.prepared === prepared }
                ?: return FreeFlightCommandResult.Rejected("当前飞行准备已失效，请重新准备")
            if (entry.submitted != null && entry.submitted != request)
                return FreeFlightCommandResult.Rejected("本次起飞已提交，请等待原申请结果")
            entry.start ?: scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                repository.start(prepared.owner, request).also { result ->
                    // Keep its real context current while the UI receives the definitive rejection.
                    // A subsequent explicit begin replaces it; a page never re-submits this request.
                    if (result is FreeFlightCommandResult.Rejected) {
                        synchronized(lock) { entry.rejected = true }
                        keepAlive.stop(prepared.preparationId, prepared.sessionId, prepared.owner)
                    }
                }
            }.also { entry.submitted = request;entry.start = it }
        }
        // Cancelling this await never cancels the Application-owned deferred/formal operation.
        // Start synchronously BEFORE the first caller cancellation point after submitted is stored.
        task.start()
        return task.await()
    }

    suspend fun retryRuntime(handle: FreeFlightPreparationHandle, prepared: FreeFlightPrepared): FreeFlightCommandResult {
        val submitted = synchronized(lock) { current(handle)?.takeIf { it.prepared === prepared && !it.rejected }?.submitted }
        if (submitted == null || !isCurrent(handle, prepared))
            return FreeFlightCommandResult.Rejected("当前飞行已变化，无法重试后台运行")
        return repository.synchronizeRuntime(prepared.owner, prepared.sessionId)
    }

    /** Explicit cancellation only. The retained submitted bit, rather than a UI hint, is authoritative. */
    fun cancel(handle: FreeFlightPreparationHandle): Boolean {
        val entry = synchronized(lock) {
            val entry = current(handle) ?: return false
            if (entry.submitted != null) return false
            entry.closed = true
            entry
        }
        entry.owner?.let { retirePreparation(entry, it) }
        // A still-running preparation checks closed before publication and retires its late owner.
        return true
    }

    private fun current(handle: FreeFlightPreparationHandle): Entry? =
        retained?.takeIf { !it.closed && it.handle === handle }

    private suspend fun prepareOwned(entry: Entry): FreeFlightPreparationDecision {
        var published = false
        try {
            val app = context.applicationContext as? ProjectFocusApp ?: return unavailable()
            app.awaitInitialAccountRestore()
            val seed = app.awaitInitialAirportSeed()
            if (seed == null || seed is AirportSeeder.Result.Failed)
                return FreeFlightPreparationDecision.Unavailable("机场数据暂未就绪，请先重试加载")
            if (archives.active() != null)
                return FreeFlightPreparationDecision.Unavailable("还有一趟飞行需要恢复，请先处理原飞行")
            val preferences = context.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE)
            val code = preferences.getString(HomeOriginSelection.CODE, null)?.takeIf(String::isNotBlank)
                ?: return FreeFlightPreparationDecision.Rejected("请先选择出发机场")
            val account = TokenStore.snapshot()
            val marker = preferences.getString(HomeOriginSelection.MARKER,null)
            val departure = HomeOriginSelection.selectedSnapshot(preferences,code,account)
                ?: airports.findByCode(code)?.snapshot()
                ?: return FreeFlightPreparationDecision.Unavailable("找不到所选出发机场，请重新选择")
            val owner = hostOpening.withLock {
                // A cancelled preparation must not open late and replace a newer real host.
                if (!entryCurrent(entry)) return unavailable()
                hosts.open().also { opened -> synchronized(lock) { entry.owner = opened } }
            }
            if (!entryCurrent(entry) || TokenStore.snapshot() != account || owner.ownerScope != TokenStore.storageScope(account))
                return unavailable()
            when (val decision = admission.check(owner)) {
                is FreeFlightPreparationResult.Rejected -> return FreeFlightPreparationDecision.Rejected(decision.reason)
                FreeFlightPreparationResult.Allowed -> Unit
            }
            if (!hosts.isCurrent(owner) || !entryCurrent(entry) || TokenStore.snapshot() != account ||
                preferences.getString(HomeOriginSelection.CODE, null) != code ||
                preferences.getString(HomeOriginSelection.MARKER,null) != marker)
                return unavailable()
            val selectedCategory = settings.read(PrefKey.SelectedCategoryId).takeIf { it >= 0L }
            val prepared = FreeFlightPrepared(entry.handle.preparationId, owner, UUID.randomUUID().toString(), departure,
                SourceFlightNumber.generate(departure.code, "FREE", true), selectedCategory)
            if (!hosts.isCurrent(owner)) return unavailable()
            synchronized(lock) {
                if (current(entry.handle) !== entry) return unavailable()
                entry.prepared = prepared
                published = true
            }
            return FreeFlightPreparationDecision.Prepared(prepared)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return FreeFlightPreparationDecision.Unavailable("暂时无法准备飞行，请稍后重试") }
        finally {
            if (!published) {
                val owner = synchronized(lock) { entry.closed = true;entry.owner }
                if (owner != null) hosts.invalidate(owner)
            }
        }
    }

    private fun entryCurrent(entry: Entry) = synchronized(lock) { current(entry.handle) === entry }
    private fun closeUnsubmitted(entry: Entry) {
        val owner = synchronized(lock) {
            if (current(entry.handle) !== entry || entry.submitted != null) return
            entry.closed = true
            entry.owner
        }
        if (owner != null) retirePreparation(entry, owner)
    }
    private fun retirePreparation(entry: Entry, owner: FreeFlightOwnerToken) {
        synchronized(lock) { entry.prepared }?.let { keepAlive.stop(it.preparationId, it.sessionId, owner) }
        scope.launch { hosts.invalidate(owner) }
    }
    private fun unavailable() = FreeFlightPreparationDecision.Unavailable("原飞行准备已失效，请重新准备")
    private fun AirportEntity.snapshot() = PlannedAirport(id, iataCode.ifBlank { icaoCode }, name, city, country,
        latitude, longitude, nameZh?.takeIf(String::isNotBlank) ?: name, cityZh?.takeIf(String::isNotBlank) ?: city)
}
