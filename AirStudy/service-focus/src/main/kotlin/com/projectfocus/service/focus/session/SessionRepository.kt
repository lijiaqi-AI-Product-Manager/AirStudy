package com.projectfocus.service.focus.session

import com.projectfocus.core.common.flight.FlightMath
import com.projectfocus.core.common.coroutines.runCatchingCancellable
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.FlightLegPlan
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.PlannedDeparture
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.FlightRuntimeLeaseContext
import com.projectfocus.core.network.di.TokenStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单人专注会话的协调入口。
 *
 * Controller负责已接受秒的状态计算；本类串行执行命令，并在副作用结束后对外发布状态。
 * 开始时冻结航线并写入ONGOING，正整分钟保存专注检查点，完成或取消先更新记录再清屏蔽命令。
 * 原包静态路径依据见R157_progress_findings与R158_session_findings；未执行原包动态验收。
 * 写库失败保留内存快照和到站重试，是本品故障处理策略，不作为原包已证实行为。
 */
@Singleton
class SessionRepository @Inject constructor(
    private val controller: SessionController,
    private val settings: SettingsRepository,
    private val historyDao: FlightHistoryDao,
    private val airportDao: AirportDao,
    private val widgetPush: WidgetPush,














    private val todayNotifier: TodayStatsNotifier,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    // Controller计算状态；界面只观察完成持久化与命令副作用后的快照。
    private val publishedState = MutableStateFlow<SessionState>(controller.state.value)
    val state: StateFlow<SessionState> = publishedState.asStateFlow()









    private var pendingHistory: FlightHistoryEntity? = null
    private var savedHistory: FlightHistoryEntity? = null
    private var checkpointAttemptMinute = 0
    private var notifiedTerminalId: String? = null

    /** 会话元信息（航线、座位），start 时确定，Restore 时从库里读回 */
    private var route: RouteInfo = RouteInfo.EMPTY
    private val commandMutex = Mutex()
    // Free-flight admission reserves the SAME command domain as Core start/restore.
    // No second independent runtime mutex can exclude the real countdown commands.
    private data class FreeFlightReservation(val sessionId: String, val owner: FreeFlightOwnerToken)
    private val freeFlightHostState = MutableStateFlow<FreeFlightOwnerToken?>(null)
    val freeFlightHostChanges: StateFlow<FreeFlightOwnerToken?> = freeFlightHostState.asStateFlow()
    private var freeFlightHost: FreeFlightOwnerToken?
        get() = freeFlightHostState.value
        set(value) { freeFlightHostState.value = value }
    private var freeFlightHostGeneration = 0L
    private var freeFlightReservation: FreeFlightReservation? = null

    private suspend fun <T> runtimeOwnerCommand(action: suspend () -> T): T {
        FlightRuntimeLeaseContext.assertNoReentry()
        return commandMutex.withLock { action() }
    }

    private suspend fun <T> command(action: suspend () -> T): T = runtimeOwnerCommand {
        try {
            action()
        } finally {
            // 特别是Arrived：成功路径必须先保存终态，再让界面停止ticker或导航。
            // 写库失败时仍保留Arrived供settle重试，这是本品的故障处理策略。
            publishedState.value = controller.state.value
        }
    }

    private val _lastSettled = MutableStateFlow<FlightHistoryEntity?>(null)

    /** 最近一次结算写入的历史记录，供到站页读（UI 不需要再查库） */
    val lastSettled: StateFlow<FlightHistoryEntity?> = _lastSettled.asStateFlow()

    // ==================================================================
    // 开始
    // ==================================================================

    /**
     * 开始一场飞行。
     *
     * 执行顺序（不可调换）：
     *   ① Controller.start —— 内存权威状态先立起来，后续任何一步失败都能回滚到 Idle
     *   ② DataStore 写 categoryId + paused=false —— 命令屏蔽引擎开始工作
     *   ③ 写 flight_history ONGOING 行 —— 存档
     *   ④ 推小组件
     *
     * @param categoryId 专注分类（[NO_CATEGORY] 表示无分类）
     * @param plannedDurationMs 计划时长，必须 > 0
     * @param departureCode 出发机场三字码（null = 不指定航线）
     * @param destinationCode 目的机场三字码
     */
    suspend fun start(
        categoryId: Long,
        plannedDurationMs: Long,
        departureCode: String? = null,
        destinationCode: String? = null,
    ): Boolean = command {
        if (state.value !is SessionState.Idle) return@command false
        require(plannedDurationMs > 0L)
        val resolved = resolveRoute(departureCode, destinationCode)
        val distance = if (resolved.departure != null && resolved.arrival != null) {
            FlightMath.airportDistanceKm(
                resolved.departure.latitude, resolved.departure.longitude,
                resolved.arrival.latitude, resolved.arrival.longitude,
            ).toDouble()
        } else {
            // 无路线计时器沿用既有恒速口径；有真实机场的生产入口使用startPlannedFlight。
            FlightMath.distanceKmForMinutes((plannedDurationMs / 60_000L).toInt())
        }
        require(distance.isFinite() && distance >= 0.0)
        startResolved(categoryId, plannedDurationMs, resolved.copy(distanceKm = distance))
    }

    /** 准备阶段只读机场，不开始计时，也不修改已有飞行。 */
    suspend fun findAirportForPlan(code: String): PlannedAirport? = withContext(io) {
        code.trim().uppercase(java.util.Locale.ROOT).takeIf { it.isNotEmpty() }
            ?.let { airportDao.findByCode(it) }?.plannedSnapshot()
    }

    suspend fun preparePlan(departureCode: String, destinationCode: String): FlightLegPlan {
        val departure = requireNotNull(findAirportForPlan(departureCode)) { "Departure airport is missing" }
        val destination = requireNotNull(findAirportForPlan(destinationCode)) { "Destination airport is missing" }
        return FlightLegPlan.between(departure, destination)
    }

    /** 确认后的机场、Float精度里程、分钟、座位作为一个快照提交。 */
    suspend fun startPlannedFlight(plan: FlightLegPlan, requestedSessionId: String? = null): Boolean = command {
        startResolved(
            plan.categoryId ?: NO_CATEGORY, plan.durationMinutes * 60_000L,
            RouteInfo(plan.departure.code, plan.destination.code, plan.departure, plan.destination,
                plan.distanceKm, plan.seat, plan.scene, plan.plannedDeparture),
            requestedSessionId,
        )
    }

    private suspend fun startResolved(
        categoryId: Long,
        plannedDurationMs: Long,
        resolved: RouteInfo,
        requestedSessionId: String? = null,
    ): Boolean {
        if (freeFlightReservation != null) return false
        if (state.value !is SessionState.Idle) return false
        pendingHistory = null
        savedHistory = null
        checkpointAttemptMinute = 0
        notifiedTerminalId = null
        // 先冻结完整路线，再发布Running；观察者不会读到上一趟的机场/里程。
        route = resolved

        //   它拒绝时后面的步骤全部不执行，不会出现「多写一条 ONGOING」。
        if (!controller.start(categoryId, plannedDurationMs, requestedSessionId)) {
            Timber.w("已有进行中的会话，拒绝 start")
            return false
        }
        // Taking the real countdown runtime permanently supersedes an unsubmitted preparation.
        // Returning to Idle must not revive its old airport/context after the other flight.
        freeFlightHost = null
        val running = controller.state.value as SessionState.Running


        //   它失败（几乎不会，DataStore 写失败通常意味着磁盘坏了）也必须继续，
        //   因为不写库的话至少还能靠内存计时完成这次专注。
        withContext(NonCancellable) {
            runCatchingCancellable {
                settings.writeAll(
                    listOf(
                        PrefKey.ActiveSessionCategoryId to categoryId,
                        PrefKey.SessionPaused to false,
                    ),
                )
            }.onFailure { Timber.e(it, "写会话命令失败，屏蔽引擎可能不生效") }


            writeOngoing(running)


            pushWidget(running)
        }

        return true
    }

    // ==================================================================
    // 暂停 / 继续
    // ==================================================================

    suspend fun pause(): Boolean = command {

        //   若先写 DataStore 再 pause，中间这几十毫秒里屏蔽引擎已经停了，
        //   但 Controller 仍按 Running 累计时间 —— 用户「暂停了但时间还在走」。
        if (!controller.pause()) return@command false
        withContext(NonCancellable) {
            runCatchingCancellable { settings.write(PrefKey.SessionPaused, true) }
                .onFailure { Timber.e(it, "写暂停标记失败") }
            pushWidget(controller.state.value)
        }
        true
    }

    suspend fun resume(): Boolean = command {
        if (!controller.resume()) return@command false
        withContext(NonCancellable) {
            runCatchingCancellable { settings.write(PrefKey.SessionPaused, false) }
                .onFailure { Timber.e(it, "写恢复标记失败") }
            pushWidget(controller.state.value)
        }
        true
    }

    // ==================================================================
    // 推进
    // ==================================================================

    /** 接受计时采样并保存整分钟；到达先提交终态，再等待界面下机。 */
    suspend fun tick(): Boolean = command {
        val migrated = controller.tick()
        if (migrated) {
            val arrived = controller.state.value as SessionState.Arrived
            // Arrived观察者会停掉ticker，不能让它取消正在执行的终态提交。
            withContext(NonCancellable) {
                persistTerminal(arrived)
                clearShieldingCommand()
                pushWidget(arrived)
            }
        } else if (controller.state.value is SessionState.Running) {
            persistCheckpoint(controller.state.value as SessionState.Running)
            pushWidgetIfMinuteChanged()
        }
        migrated
    }

    private suspend fun persistCheckpoint(running: SessionState.Running) {
        val minute = (running.elapsedMs(controller.nowMono()) / 60_000L).toInt()
        if (minute <= checkpointAttemptMinute) return
        checkpointAttemptMinute = minute
        val fallback = readHistory(running.sessionId) ?: buildOngoing(running)
        val saved = runCatchingCancellable {
            withContext(io) {
                historyDao.saveSessionCheckpoint(fallback, minute, controller.nowWall())
            }
        }.onFailure { Timber.e(it, "专注检查点保存失败：%s", running.sessionId) }.getOrNull()
        if (saved != null) {
            savedHistory = saved
            pendingHistory = null
        }
    }

    private var lastPushedMinute: Long = -1L

    private suspend fun pushWidgetIfMinuteChanged() {
        val running = controller.state.value as? SessionState.Running ?: return
        val minute = (running.remainingMs(controller.nowMono()) + 59_999L) / 60_000L
        if (minute == lastPushedMinute) return
        lastPushedMinute = minute
        pushWidget(running)
    }

    /** 取消保留最后已保存的专注检查点与全程距离。 */
    suspend fun abandon(): Boolean = command {
        if (!controller.abandon()) return@command false
        val arrived = controller.state.value as SessionState.Arrived
        withContext(NonCancellable) {
            persistTerminal(arrived)
            clearShieldingCommand()
            pushWidget(arrived)
        }
        true
    }

    /** 终态提交失败可重试；已提交时只确认下机，不重复写库或广播。 */
    suspend fun settle(): FlightHistoryEntity? = command {
        val arrived = controller.state.value as? SessionState.Arrived ?: return@command null
        withContext(NonCancellable) {
            val entity = persistTerminal(arrived) ?: return@withContext null
            controller.settle()
            clearShieldingCommand()
            _lastSettled.value = entity
            pushWidget(SessionState.Idle)
            entity
        }
    }

    private suspend fun persistTerminal(arrived: SessionState.Arrived): FlightHistoryEntity? {
        val fallback = buildCompleted(arrived)
        val entity = runCatchingCancellable {
            withContext(io) { historyDao.saveSessionTerminal(fallback) }
        }.onFailure {
            pendingHistory = fallback
            Timber.e(it, "终态保存失败，保留Arrived供下机重试：%s", arrived.sessionId)
        }.getOrNull() ?: return null
        savedHistory = entity
        pendingHistory = null
        if (notifiedTerminalId != entity.id) {
            notifiedTerminalId = entity.id
            todayNotifier.notifyChanged()
            runCatchingCancellable { widgetPush.onTodayChanged() }
                .onFailure { Timber.w(it, "推送今日汇总失败") }
        }
        return entity
    }

    private suspend fun readHistory(id: String): FlightHistoryEntity? {
        val stored = runCatchingCancellable { withContext(io) { historyDao.findById(id) } }
            .onFailure { Timber.w(it, "读取会话快照失败：%s", id) }.getOrNull()
        if (stored != null) savedHistory = stored
        return stored ?: savedHistory?.takeIf { it.id == id }
            ?: pendingHistory?.takeIf { it.id == id }
    }

    /**
     * 单人恢复只采用已保存的实际专注分钟。
     * 原包构造路径发布默认isPaused=false并写回偏好；冷重建默认继续，非Idle保持现态。
     * 联飞独立时间戳协议尚未实现；其旧估算路径不作为单人恢复依据。
     */
    suspend fun restore(): SessionState = command {
        // In particular, do not clear free-flight shielding merely because Core has no row.
        if (freeFlightReservation != null) return@command controller.state.value
        if (controller.state.value !is SessionState.Idle) return@command controller.state.value
        // 查询失败不能当作“没有记录”，也不能据此清除仍可能有效的命令。
        val ongoing = withContext(io) { historyDao.ongoing() }
        if (ongoing == null) {
            val category = withContext(io) { settings.read(PrefKey.ActiveSessionCategoryId) }
            if (category != NO_SESSION) clearShieldingCommand()
            return@command controller.state.value
        }
        val plannedMs = ongoing.durationMinutes.toLong() * 60_000L
        require(plannedMs > 0L) { "Persisted flight has invalid duration" }
        val checkpointMs = if (ongoing.focusType == FOCUS_TYPE_SOLO) {
            ongoing.actualFocusMinutes.toLong().coerceIn(0L, ongoing.durationMinutes.toLong()) * 60_000L
        } else {
            (controller.nowWall() - ongoing.startTimeMillis).coerceIn(0L, plannedMs)
        }
        savedHistory = ongoing
        pendingHistory = null
        checkpointAttemptMinute = (checkpointMs / 60_000L).toInt()
        notifiedTerminalId = null
        route = RouteInfo(
            ongoing.departureAirportCode, ongoing.destinationAirportCode,
            ongoing.airportSnapshot(departure = true), ongoing.airportSnapshot(departure = false),
            ongoing.distanceKm, ongoing.seat,
            PlannedFlightMetadataCodec.decodeSceneOrNull(ongoing.sceneSnapshotJson),
            PlannedFlightMetadataCodec.decodeDepartureOrNull(ongoing.plannedDepartureJson),
        )
        freeFlightHost = null
        withContext(NonCancellable) {
            when {
                checkpointMs >= plannedMs -> {
                    controller.restoreArrived(
                        ongoing.id, ongoing.categoryId ?: NO_CATEGORY, plannedMs,
                        ongoing.startTimeMillis, plannedMs, controller.nowWall(), false,
                    )
                    persistTerminal(controller.state.value as SessionState.Arrived)
                    clearShieldingCommand()
                }
                else -> {
                    controller.restoreRunning(ongoing.id, ongoing.categoryId ?: NO_CATEGORY,
                        plannedMs, ongoing.startTimeMillis, checkpointMs)
                    runCatchingCancellable {
                        settings.writeAll(listOf(
                            PrefKey.ActiveSessionCategoryId to (ongoing.categoryId ?: NO_CATEGORY),
                            PrefKey.SessionPaused to false,
                        ))
                    }.onFailure { Timber.e(it, "恢复会话时写命令失败") }
                }
            }
            pushWidget(controller.state.value)
        }
        if (notifiedTerminalId != ongoing.id) todayNotifier.notifyChanged()
        controller.state.value
    }

    // ==================================================================
    // 查询辅助
    // ==================================================================

    /** 当前航线的三字码（出发 / 到达），供飞行页显示 */
    fun currentRoute(): RouteInfo = route

    /**
     * Own app side-effect guard. The non-suspending callback runs under the same
     * mutex as start/restore/terminal commands, with their latest published state
     * and frozen route. The callback must not call back into a session command.
     */
    suspend fun <T> withStableSessionSnapshot(action: (SessionState, RouteInfo) -> T): T =
        runtimeOwnerCommand { action(publishedState.value, route) }

    /** Mint an application preparation host; no countdown/restore or pending owner is displaced. */
    suspend fun openFreeFlightHost(ownerScope: String): FreeFlightOwnerToken = runtimeOwnerCommand {
        require(ownerScope.isNotBlank())
        check(freeFlightReservation == null) { "A free flight is pending or owns the runtime" }
        check(controller.state.value is SessionState.Idle) { "Core already owns the runtime" }
        // A cold process may still be Idle before MainActivity.restore has read the database.
        check(withContext(io) { historyDao.ongoing() } == null) { "A Core archive requires restoration" }
        freeFlightHostGeneration = Math.addExact(freeFlightHostGeneration, 1L)
        FreeFlightOwnerToken(ownerScope, freeFlightHostGeneration).also { freeFlightHost = it }
    }

    /** Explicit preparation/host invalidation; never a screen onStop/rotation signal. */
    suspend fun invalidateFreeFlightHost(owner: FreeFlightOwnerToken) = runtimeOwnerCommand {
        if (freeFlightHost == owner) freeFlightHost = null
        // Keep a pending/active reservation until actual reconciliation/runtime cleanup.
    }

    suspend fun isFreeFlightHostCurrent(owner: FreeFlightOwnerToken): Boolean = runtimeOwnerCommand {
        freeFlightHost == owner && controller.state.value is SessionState.Idle
    }

    /** Preparation can keep this real host alive without reserving/starting a flight. A formal
     * request for the same session may already hold the slot; another reservation never qualifies. */
    suspend fun <T : Any> withFreeFlightPreparationLease(
        sessionId: String, owner: FreeFlightOwnerToken, action: suspend () -> T,
    ): T? = runtimeOwnerCommand {
        TokenStore.requireSnapshotLease()
        require(sessionId.isNotBlank())
        if (freeFlightHost != owner || controller.state.value !is SessionState.Idle ||
            freeFlightReservation?.let { it != FreeFlightReservation(sessionId, owner) } == true) return@runtimeOwnerCommand null
        FlightRuntimeLeaseContext.run(action)
    }

    suspend fun reserveFreeFlightRuntime(sessionId: String, owner: FreeFlightOwnerToken): Boolean = runtimeOwnerCommand {
        require(sessionId.isNotBlank())
        if (freeFlightHost != owner || controller.state.value !is SessionState.Idle) return@runtimeOwnerCommand false
        val reservation = FreeFlightReservation(sessionId, owner)
        freeFlightReservation?.let { return@runtimeOwnerCommand it == reservation }
        if (withContext(io) { historyDao.ongoing() } != null) return@runtimeOwnerCommand false
        freeFlightReservation = reservation
        true
    }

    /** Exact-owner cleanup is allowed after account invalidation; it cannot unlock another flight. */
    suspend fun releaseFreeFlightRuntime(sessionId: String, owner: FreeFlightOwnerToken) = runtimeOwnerCommand {
        val held = freeFlightReservation ?: return@runtimeOwnerCommand
        check(held == FreeFlightReservation(sessionId, owner)) { "A different flight owns the runtime" }
        freeFlightReservation = null
    }

    /** Called only after the real driver joined, under the CURRENT account publication lease. */
    internal suspend fun retireObsoleteFreeFlightRuntime(
        sessionId: String, owner: FreeFlightOwnerToken, reservationAccount: TokenStore.Snapshot,
        proof: com.projectfocus.service.focus.freeflight.runtime.FreeFlightStoppedDriver,
    ): Boolean = runtimeOwnerCommand {
        TokenStore.requireSnapshotLease()
        proof.requireStopped() // Recheck the exact driver epoch INSIDE the Core command lock.
        require(proof.key.sessionId == sessionId && proof.key.owner == owner)
        if (freeFlightReservation != FreeFlightReservation(sessionId, owner)) return@runtimeOwnerCommand false
        if (TokenStore.snapshot() == reservationAccount && freeFlightHost == owner) return@runtimeOwnerCommand false
        check(controller.state.value is SessionState.Idle) { "A different Core session is active" }
        // Hold the exact shared slot and the current publication lease through durable cleanup.
        // A new flight cannot start between checking ownership and clearing these global commands.
        withContext(io) {
            settings.writeAll(listOf(PrefKey.ActiveSessionCategoryId to -1L, PrefKey.SessionPaused to false))
        }
        if (freeFlightHost == owner) freeFlightHost = null
        freeFlightReservation = null
        true
    }

    /**
     * The caller must first hold TokenStore's real publication lease.
     * Hold this existing commandMutex for the ENTIRE action, including durable effects.
     * action must not call Core commands/ownership release or publish a new account.
     */
    suspend fun <T : Any> withFreeFlightRuntimeLease(
        sessionId: String, owner: FreeFlightOwnerToken, action: suspend () -> T,
    ): T? = runtimeOwnerCommand {
        TokenStore.requireSnapshotLease()
        if (freeFlightHost != owner || freeFlightReservation != FreeFlightReservation(sessionId, owner) ||
            controller.state.value !is SessionState.Idle) return@runtimeOwnerCommand null
        FlightRuntimeLeaseContext.run(action)
    }


    /** Non-suspending map effects use the SAME Core lock, acquired without waiting on Main.
     * The account publication lease must already be held. A busy lock runs no UI effect. */
    internal fun <T : Any> tryFreeFlightPaintLease(sessionId: String, owner: FreeFlightOwnerToken,
                                                  preparation: Boolean, action: () -> T): com.projectfocus.service.focus.freeflight.FreeFlightPaintAttempt<T> {
        FlightRuntimeLeaseContext.assertNoReentry()
        TokenStore.requireSnapshotLease()
        if (!commandMutex.tryLock()) return com.projectfocus.service.focus.freeflight.FreeFlightPaintAttempt.Busy
        try {
            require(sessionId.isNotBlank())
            val reservation = FreeFlightReservation(sessionId, owner)
            if (freeFlightHost != owner || controller.state.value !is SessionState.Idle ||
                (if (preparation) freeFlightReservation != null && freeFlightReservation != reservation else freeFlightReservation != reservation))
                return com.projectfocus.service.focus.freeflight.FreeFlightPaintAttempt.Obsolete
            return com.projectfocus.service.focus.freeflight.FreeFlightPaintAttempt.Applied(FlightRuntimeLeaseContext.runImmediate(action))
        } finally { commandMutex.unlock() }
    }

    /** 单调时钟现值 —— 供 UI 计算进度，避免各处自己调 SystemClock 造成漂移 */
    fun nowMono(): Long = controller.nowMono()

    // ==================================================================
    // 内部
    // ==================================================================

    private suspend fun resolveRoute(
        departureCode: String?,
        destinationCode: String?,
    ): RouteInfo = withContext(io) {
        val dep = departureCode?.let { runCatchingCancellable { airportDao.findByCode(it) }.getOrNull() }
        val arr = destinationCode?.let { runCatchingCancellable { airportDao.findByCode(it) }.getOrNull() }
        RouteInfo(
            departureCode = dep?.let { it.iataCode.ifBlank { it.icaoCode } } ?: departureCode.orEmpty(),
            arrivalCode = arr?.let { it.iataCode.ifBlank { it.icaoCode } } ?: destinationCode.orEmpty(),
            departure = dep?.plannedSnapshot(),
            arrival = arr?.plannedSnapshot(),
        )
    }

    private suspend fun writeOngoing(running: SessionState.Running) {
        val entity = buildOngoing(running)
        withContext(io) {
            runCatchingCancellable { historyDao.upsert(entity) }
                .onSuccess { savedHistory = entity; pendingHistory = null }
                .onFailure {

                    pendingHistory = entity
                    Timber.e(it, "写 ONGOING 记录失败，继续用内存状态")
                }
        }
    }

    private fun buildOngoing(s: SessionState.Running): FlightHistoryEntity {
        val plannedMin = (s.plannedDurationMs / 60_000L).toInt()
        val now = System.currentTimeMillis()
        return FlightHistoryEntity(
            id = s.sessionId,
            roomId = null,
            focusType = FOCUS_TYPE_SOLO,
            roomParticipantCount = null,
            departureAirportCode = route.departureCode.ifBlank { UNKNOWN_AIRPORT },
            departureAirportName = route.departure?.name ?: UNKNOWN_AIRPORT,
            departureCity = route.departure?.city ?: UNKNOWN_AIRPORT,
            departureCountry = route.departure?.country ?: UNKNOWN_AIRPORT,
            departureLatitude = route.departure?.latitude ?: 0.0,
            departureLongitude = route.departure?.longitude ?: 0.0,
            destinationAirportCode = route.arrivalCode.ifBlank { UNKNOWN_AIRPORT },
            destinationAirportName = route.arrival?.name ?: UNKNOWN_AIRPORT,
            destinationCity = route.arrival?.city ?: UNKNOWN_AIRPORT,
            destinationCountry = route.arrival?.country ?: UNKNOWN_AIRPORT,
            destinationLatitude = route.arrival?.latitude ?: 0.0,
            destinationLongitude = route.arrival?.longitude ?: 0.0,
            startTimeMillis = s.startedAtWallMs,

            //   虽然只按 status 过滤，但统计页按 endTimeMillis 排序，
            //   写个 0 会让「进行中」排到 1970 年去。
            endTimeMillis = null,
            durationMinutes = plannedMin,
            actualFocusMinutes = 0,
            distanceKm = route.distanceKm,
            status = STATUS_ONGOING,
            categoryId = s.categoryId.takeIf { it != NO_CATEGORY },
            seat = route.seat,
            sceneSnapshotJson = PlannedFlightMetadataCodec.encodeScene(route.scene),
            plannedDepartureJson = PlannedFlightMetadataCodec.encodeDeparture(route.plannedDeparture),
            hasAnnouncedDeparture = false,
            hasAnnouncedDescent = false,
            hasAnnouncedArrival = false,
            logJson = null,
            isSynced = false,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun buildCompleted(a: SessionState.Arrived): FlightHistoryEntity {

        //   不能再 resolve 一次机场（机场可能已经改名）。
        //

        //   settle() 本身就在 IO 调度器上，runBlocking 会额外起一个事件循环，
        //   在「已经有协程上下文」的地方用 runBlocking 是经典的死锁诱因
        //   （虽然这里只查一次库不会死锁，但这种写法一旦被复制到别处就会）。
        val base = readHistory(a.sessionId)
        val plannedMin = (a.plannedDurationMs / 60_000L).toInt()
        val actualMin = if (a.abandoned) base?.actualFocusMinutes ?: 0 else plannedMin
        val now = System.currentTimeMillis()

        return FlightHistoryEntity(
            id = a.sessionId,
            roomId = base?.roomId,
            focusType = base?.focusType ?: FOCUS_TYPE_SOLO,
            roomParticipantCount = base?.roomParticipantCount,
            departureAirportCode = base?.departureAirportCode
                ?: route.departureCode.ifBlank { UNKNOWN_AIRPORT },
            departureAirportName = base?.departureAirportName ?: route.departure?.name ?: UNKNOWN_AIRPORT,
            departureCity = base?.departureCity ?: route.departure?.city ?: UNKNOWN_AIRPORT,
            departureCountry = base?.departureCountry ?: route.departure?.country ?: UNKNOWN_AIRPORT,
            departureLatitude = base?.departureLatitude ?: route.departure?.latitude ?: 0.0,
            departureLongitude = base?.departureLongitude ?: route.departure?.longitude ?: 0.0,
            destinationAirportCode = base?.destinationAirportCode
                ?: route.arrivalCode.ifBlank { UNKNOWN_AIRPORT },
            destinationAirportName = base?.destinationAirportName ?: route.arrival?.name ?: UNKNOWN_AIRPORT,
            destinationCity = base?.destinationCity ?: route.arrival?.city ?: UNKNOWN_AIRPORT,
            destinationCountry = base?.destinationCountry ?: route.arrival?.country ?: UNKNOWN_AIRPORT,
            destinationLatitude = base?.destinationLatitude ?: route.arrival?.latitude ?: 0.0,
            destinationLongitude = base?.destinationLongitude ?: route.arrival?.longitude ?: 0.0,
            startTimeMillis = a.startedAtWallMs,
            endTimeMillis = if (a.abandoned) base?.endTimeMillis else a.endedAtWallMs,
            durationMinutes = plannedMin,
            actualFocusMinutes = actualMin,
            distanceKm = base?.distanceKm ?: route.distanceKm,
            // R157原包终态复制保留所选路线全距；取消修改状态，不把全距换算成partial距离。
            status = if (a.abandoned) STATUS_CANCELLED else STATUS_COMPLETED,
            categoryId = a.categoryId.takeIf { it != NO_CATEGORY } ?: base?.categoryId,
            seat = if (base != null) base.seat else route.seat,
            hasAnnouncedDeparture = base?.hasAnnouncedDeparture ?: false,
            hasAnnouncedDescent = base?.hasAnnouncedDescent ?: false,
            hasAnnouncedArrival = base?.hasAnnouncedArrival ?: false,
            logJson = base?.logJson,
            isSynced = false,
            createdAt = base?.createdAt ?: a.startedAtWallMs,
            updatedAt = now,
            quote = base?.quote,
            quoteAuthor = base?.quoteAuthor,
            // Existing raw JSON wins even when null; never resurrect stale route metadata.
            sceneSnapshotJson = if (base != null) base.sceneSnapshotJson
                else PlannedFlightMetadataCodec.encodeScene(route.scene),
            plannedDepartureJson = if (base != null) base.plannedDepartureJson
                else PlannedFlightMetadataCodec.encodeDeparture(route.plannedDeparture),
        )
    }

    private suspend fun clearShieldingCommand() {
        runCatchingCancellable {
            settings.writeAll(
                listOf(
                    PrefKey.ActiveSessionCategoryId to NO_SESSION,
                    PrefKey.SessionPaused to false,
                ),
            )
        }.onFailure { Timber.e(it, "清理屏蔽命令失败，restore 时会纠正") }
    }

    private suspend fun pushWidget(state: SessionState) {
        val total = when (state) {
            is SessionState.Running -> state.plannedDurationMs
            is SessionState.Paused -> state.plannedDurationMs
            else -> 0L
        }
        val scenario = route.scenarioLabel
        val mirror = when (state) {
            SessionState.Idle, is SessionState.Arrived -> WidgetMirror.Idle
            is SessionState.Running -> WidgetMirror.Flying(
                remainingMs = state.remainingMs(controller.nowMono()),
                totalMs = total,
                destinationCode = route.arrivalCode.takeIf { it.isNotBlank() },
                scenarioLabel = scenario,
            )
            is SessionState.Paused -> WidgetMirror.Paused(
                elapsedMs = state.elapsedMs(),
                totalMs = total,
                scenarioLabel = scenario,
            )
        }
        widgetPush.push(mirror)


        //   pushWidget 每次结束时把水位清掉，下一轮 tick 一看「不一样」就再推一次，
        //   == 每 200ms 推一次小组件（5 次/秒跨进程 IPC）。
        //   测试 `tick 只在剩余分钟数变化时才推小组件` 一次就抓到了（期望 1，实得 11）。
        //   节流水位只能由 tick 自己按「当前剩余分钟」推进，推送函数不该动它。
    }

    // ==================================================================
    // 常量与值对象
    // ==================================================================

    companion object {
        /** 与 FocusMonitorService.NO_SESSION 必须一致（-1 表示没有会话） */
        const val NO_SESSION: Long = -1L

        /** 分类 id 缺省值。用 -2 而不是 0：SQLite 的 AUTOINCREMENT 可能真的产生 0 */
        const val NO_CATEGORY: Long = -2L

        const val STATUS_ONGOING = "ONGOING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val FOCUS_TYPE_SOLO = "SOLO"

        /** 没有航线信息时的占位三字码。不用空串：NOT NULL 列，且空串在列表页显示为空白 */
        const val UNKNOWN_AIRPORT = "---"

    }
}

/** 航线信息（快照，start 时确定后不再变） */
data class RouteInfo(
    val departureCode: String = "",
    val arrivalCode: String = "",
    val departure: PlannedAirport? = null,
    val arrival: PlannedAirport? = null,
    val distanceKm: Double = 0.0,
    val seat: String? = null,
    val scene: PlannedScene? = null,
    val plannedDeparture: PlannedDeparture? = null,
) {
    /** 本品冻结的独立场景展示快照；不反查或改写专注类别。 */
    val scenarioLabel: String
        get() = scene?.displayName?.takeIf { it.isNotEmpty() }
            ?: scene?.name?.takeIf { it.isNotEmpty() }
            ?: arrival?.city ?: arrival?.name ?: ""

    companion object {
        val EMPTY = RouteInfo()
    }
}

private fun AirportEntity.plannedSnapshot(): PlannedAirport = PlannedAirport(
    id, iataCode.ifBlank { icaoCode }, name, city, country, latitude, longitude,
    displayName = nameZh?.takeIf { it.isNotBlank() } ?: name,
    displayCity = cityZh?.takeIf { it.isNotBlank() } ?: city,
)

private fun FlightHistoryEntity.airportSnapshot(departure: Boolean): PlannedAirport? =
    runCatchingCancellable {
        if (departure) {
            PlannedAirport(-1, departureAirportCode, departureAirportName, departureCity,
                departureCountry, departureLatitude, departureLongitude)
        } else {
            PlannedAirport(-2, destinationAirportCode, destinationAirportName, destinationCity,
                destinationCountry, destinationLatitude, destinationLongitude)
        }
    }.getOrNull()























sealed interface WidgetMirror {
    data object Idle : WidgetMirror
    data class Flying(
        val remainingMs: Long,
        val totalMs: Long,
        val destinationCode: String?,
        val scenarioLabel: String,
    ) : WidgetMirror
    data class Paused(
        val elapsedMs: Long,
        val totalMs: Long,
        val scenarioLabel: String,
    ) : WidgetMirror
}







interface WidgetPush {
    suspend fun push(mirror: WidgetMirror)





















    suspend fun onTodayChanged() = Unit
}

/** 空实现，用于单测与「用户没放小组件」的场景 */
object NoOpWidgetPush : WidgetPush {
    override suspend fun push(mirror: WidgetMirror) = Unit
}

/** 一次性读取当前是否在飞（供通知栏、快捷设置磁贴等非 Flow 场景使用） */
suspend fun SessionRepository.isFlyingNow(): Boolean = state.first() !is SessionState.Idle
