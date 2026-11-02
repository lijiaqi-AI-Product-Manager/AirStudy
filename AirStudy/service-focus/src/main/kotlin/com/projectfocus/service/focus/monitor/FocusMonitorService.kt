package com.projectfocus.service.focus.monitor

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.projectfocus.core.database.dao.BlockedAppDao
import com.projectfocus.core.database.dao.FocusCategoryDao
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.service.focus.session.SessionRepository
import com.projectfocus.service.focus.session.SessionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject


@AndroidEntryPoint
class FocusMonitorService : Service() {

    @Inject lateinit var blockedAppDao: BlockedAppDao

    @Inject lateinit var categoryDao: FocusCategoryDao

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var ruleAssembler: BlockRuleAssembler









    @Inject lateinit var sessionRepository: SessionRepository


    //   用 App 级 scope 会导致服务停止后协程还在跑（持有已销毁的 Context）。
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var enforcer: FocusEnforcer
    private lateinit var poller: UsageStatsPoller
    private lateinit var notification: FocusNotification

    private var loopJob: Job? = null
    private var rulesJob: Job? = null
    private var tickJob: Job? = null
    private var sessionObserverJob: Job? = null
    private var preferenceObserverJob: Job? = null

    /** 本 App 是否在前台。由 [onSelfForegroundChanged] 更新，见 UsageStatsPoller 的说明。 */
    private var selfForeground: Boolean = false

    /** 连续「查不到任何事件」的轮数，用于区分「权限丢了」和「用户没动手机」 */
    private var emptyRounds: Int = 0

    /** 上一次构建通知时用的屏蔽数量，避免每 500ms 重建通知（那是真耗电） */
    private var lastNotifiedCount: Int = -1
    private var lastNotifiedPaused: Boolean? = null
    private var lastNotifiedDegraded: Boolean? = null
    @Volatile private var shieldingDegraded = false

    override fun onCreate() {
        super.onCreate()
        Timber.d("FocusMonitorService 创建")

        notification = FocusNotification().also { it.ensureChannel(this) }
        poller = UsageStatsPoller(this)
        enforcer = FocusEnforcer()


        //   原因：系统给的时间窗是「startForegroundService() 调用后 5 秒」，
        //   而 onCreate → onStartCommand 之间如果做了一次数据库查询（几十毫秒到几百毫秒），
        //   在低端机上叠加起来就可能超时。先在 onCreate 里占住坑，
        //   后续用 notificationManager.notify 更新内容。
        startForegroundSafely(activeCount = 0, paused = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                serviceScope.launch { settings.write(PrefKey.SessionPaused, true) }
                return START_STICKY
            }

            ACTION_RESUME -> {
                serviceScope.launch { settings.write(PrefKey.SessionPaused, false) }
                return START_STICKY
            }

            ACTION_STOP -> {
                serviceScope.launch {
                    settings.write(PrefKey.SessionPaused, false)
                    settings.write(PrefKey.ActiveSessionCategoryId, NO_SESSION)
                    stopSelfSafely()
                }
                return START_NOT_STICKY
            }
        }

        startEngine()
        return START_STICKY
    }

    // ==================================================================
    // 引擎启动
    // ==================================================================

    private fun startEngine() {

        //   即使没有 UsageStats 权限（屏蔽不可用），会话也必须继续计时到站，
        //   否则用户会卡在“正在飞”永远不结算。所以 tick 放在权限检查之前。
        startSessionTicker()
        observeSessionCompletion()

        if (loopJob?.isActive == true) {

            //   用户被遮一下又自由 2 秒。这是「多次点开始」的常见路径。
            Timber.d("引擎已在运行，忽略重复启动")
            return
        }

        loopJob = serviceScope.launch {

            if (!FocusPermissions.hasUsageStats(this@FocusMonitorService)) {
                Timber.e("缺少使用情况访问权限，屏蔽引擎无法工作")
                enforcer.enabled = false
                notifyState(activeCount = 0, paused = enforcer.paused, degraded = true)
                // The ticker and completion observer remain owned by the live service.
                // Missing application monitoring access disables shielding, not the flight.
                return@launch
            }

            shieldingDegraded = false
            observeRules()
            observeSessionState()
            refreshNotification()
            runPollingLoop()
        }
    }










    private fun startSessionTicker() {
        if (tickJob?.isActive == true) return
        tickJob = serviceScope.launch {
            while (serviceScope.isActive) {
                when (sessionRepository.state.value) {
                    is SessionState.Running, is SessionState.Paused -> {
                        runCatching { sessionRepository.tick() }
                            .onFailure { Timber.e(it, "会话 tick 失败") }
                    }
                    // Idle / Arrived 不推进；Arrived 的结算由落地页 settle() 负责。
                    SessionState.Idle, is SessionState.Arrived -> Unit
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }








    private fun observeSessionCompletion() {
        if (sessionObserverJob?.isActive == true) return
        sessionObserverJob = serviceScope.launch {
            sessionRepository.state.collect { state ->
                when (state) {
                    is SessionState.Arrived -> {
                        Timber.i("会话到站，停止计时与屏蔽服务")
                        tickJob?.cancel()
                        stopSelfSafely()
                    }
                    SessionState.Idle -> {
                        tickJob?.cancel()
                        stopSelfSafely()
                    }
                    is SessionState.Running, is SessionState.Paused -> Unit
                }
            }
        }
    }


    private fun observeRules() {
        if (rulesJob?.isActive == true) return
        rulesJob = serviceScope.launch {
            runCatching {
                blockedAppDao.observeAll().collect { entities ->

                    //   这确实是一条 N+1，但 N = 分类数（≤ 20），且只在变更时跑一次。
                    //   更优解是把 JOIN 写进 DAO 的 @Query 返回一个投影 data class，
                    //   但那要改 core-database 的 DAO 签名 —— 留到 M3 做规则页时一并改。
                    val categories = runCatching { categoryDao.getAll() }.getOrElse { emptyList() }
                    val labelById = categories.associate { it.id to it.name }

                    val rows = entities.map { e ->
                        BlockedRow(
                            type = e.type,
                            packageName = e.packageName,
                            categoryLabel = labelById[e.categoryId],
                        )
                    }

                    val rules = ruleAssembler.assemble(rows)
                    enforcer.configure(
                        selfPackage = packageName,
                        rules = effectiveRules(rules),
                    )
                    Timber.d("规则已更新：%d 条原始行 → %d 条规则", rows.size, rules.size)
                    refreshNotification()
                }
            }.onFailure { ex ->

                //   不应该导致 500ms 的检测循环也停掉。
                Timber.e(ex, "观察屏蔽规则失败")
            }
        }
    }








    private fun observeSessionState() {
        if (preferenceObserverJob?.isActive == true) return
        preferenceObserverJob = serviceScope.launch {
            try {
                combine(
                    settings.observe(PrefKey.ActiveSessionCategoryId),
                    settings.observe(PrefKey.SessionPaused),
                ) { categoryId, paused -> categoryId to paused }.collect { (categoryId, paused) ->
                    val inSession = categoryId != NO_SESSION

                    //   这是对的：会话结束后用户随便用手机不该被拦。
                    enforcer.enabled = inSession
                    enforcer.paused = paused
                    Timber.d("会话状态：inSession=%s (categoryId=%d), paused=%s", inSession, categoryId, paused)
                    refreshNotification()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "观察会话及暂停状态失败")
            }
        }
    }









    private suspend fun runPollingLoop() {
        var lastBlockAt: Long = 0L
        while (serviceScope.isActive) {
            val loopStart = android.os.SystemClock.elapsedRealtime()
            try {
                poller.selfInForeground = selfForeground
                val events = poller.poll(packageName)

                if (events.isEmpty()) {
                    emptyRounds++

                    //   10 秒的依据：正常情况下，即使用户一动不动，
                    //   ACTIVITY_PAUSED/RESUMED 也不会 10 秒一条都没有（屏幕熄灭有 ACTION_SCREEN_OFF，
                    //   但那不是前台事件 —— 所以这里不能太激进，10 秒是折中）。
                    if (emptyRounds >= PERMISSION_LOST_ROUNDS &&
                        !FocusPermissions.hasUsageStats(this)
                    ) {
                        Timber.e("使用情况访问权限已被撤销，停止引擎")
                        enforcer.enabled = false
                        notifyState(lastNotifiedCount, lastNotifiedPaused ?: false, degraded = true)
                        break
                    }
                } else {
                    emptyRounds = 0
                }

                val now = android.os.SystemClock.elapsedRealtime()
                when (val decision = enforcer.evaluate(events, now)) {
                    is EnforcementDecision.Block -> {

                        //   500ms 轮询 + 2s 阈值下，理论上每 2 秒触发一次；
                        //   但如果 Activity 启动慢（低端机 800ms），可能连触发两次，
                        //   用户会看到遮挡页闪一下又重建。
                        if (now - lastBlockAt >= MIN_BLOCK_INTERVAL_MS) {
                            lastBlockAt = now
                            launchBlockedScreen(decision)
                        }
                    }

                    EnforcementDecision.None -> Unit
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {

                //   可能原因：系统服务临时不可用、Binder 死亡、ROM 侧限流。
                //   记录后继续 —— 下一轮很可能就正常了。
                Timber.w(t, "轮询异常，本轮跳过")
            }


            //   而不是「每轮固定多等 500ms」。后者会让实际周期变成 620ms，
            //   拦截延迟从 2.5 秒劣化到 3.1 秒。
            val elapsed = android.os.SystemClock.elapsedRealtime() - loopStart
            delay((FocusMonitorConfig.POLL_INTERVAL_MS - elapsed).coerceAtLeast(0L))
        }
    }

    // ==================================================================
    // 遮挡页
    // ==================================================================

















    private fun launchBlockedScreen(decision: EnforcementDecision.Block) {
        val label = decision.appLabel ?: ruleAssembler.labelOf(decision.packageName)
        val intent = Intent(this, BlockedAppActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

            //   不加的话用户连点三次会叠三层，每次都要按三下返回。
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(BlockedAppActivity.EXTRA_PACKAGE, decision.packageName)
            putExtra(BlockedAppActivity.EXTRA_LABEL, label)
            putExtra(BlockedAppActivity.EXTRA_CATEGORY, decision.categoryLabel)
            putExtra(BlockedAppActivity.EXTRA_DURATION_MS, decision.foregroundDurationMs)
        }

        val started = runCatching { startActivity(intent) }.isSuccess
        if (!started) {
            Timber.w("后台启动遮挡页失败（ROM 限制），降级为全屏通知")
            // 降级：用全屏 intent 通知
            runCatching {
                ShowBlockedNotification.show(this, label, decision.categoryLabel)
            }.onFailure { Timber.e(it, "全屏通知也失败") }
        }
    }

    // ==================================================================
    // 通知
    // ==================================================================

    private fun refreshNotification() {
        val count = enforcer.blockedPackageCount()
        val paused = enforcer.paused

        //   每 500ms 一次的话，光通知就够把电量吃掉 3%。
        val degraded = shieldingDegraded
        if (count == lastNotifiedCount && paused == lastNotifiedPaused && degraded == lastNotifiedDegraded) return
        notifyState(count, paused, degraded)
    }

    private fun notifyState(activeCount: Int, paused: Boolean, degraded: Boolean = false) {
        lastNotifiedCount = activeCount
        lastNotifiedPaused = paused
        lastNotifiedDegraded = degraded
        shieldingDegraded = degraded


        //   contentIntent 必须指向 **Activity**（getActivity），不能指向 Service（getService）。
        //   指向 Service 的 PendingIntent 在通知点击时只会调一次 onStartCommand，
        //   用户看到的是「点了通知没反应」。而且这种错误不会抛异常，只会在真机上表现成
        //   「偶尔点通知打不开」—— 是最难查的一类 bug。
        val realContent = runCatching {
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                android.app.PendingIntent.getActivity(
                    this,
                    REQ_OPEN_APP,
                    Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }.getOrNull()

        // 动作按钮指向 Service（getService），这是对的 —— 它们要触发的是命令，不是界面。
        val pauseIntent = FocusNotification.pendingIntent(
            this,
            Intent(this, FocusMonitorService::class.java).setAction(ACTION_PAUSE),
            REQ_PAUSE,
        )
        val stopIntent = FocusNotification.pendingIntent(
            this,
            Intent(this, FocusMonitorService::class.java).setAction(ACTION_STOP),
            REQ_STOP,
        )

        val builderNotification = notification.build(
            context = this,
            activeAppCount = activeCount,
            paused = paused,
            contentIntent = realContent,
            pauseIntent = pauseIntent,
            stopIntent = stopIntent,
        )

        if (degraded) {

            //   不说的话是最坏的情况：用户以为在专注，其实什么都没拦。
            builderNotification.extras.putString(
                android.app.Notification.EXTRA_TEXT,
                getString(com.projectfocus.service.focus.R.string.focus_perm_core_missing),
            )
        }

        startForegroundSafely(activeCount, paused, builderNotification)
    }

    private fun startForegroundSafely(
        activeCount: Int,
        paused: Boolean,
        existing: android.app.Notification? = null,
    ) {
        val n = existing ?: notification.build(
            context = this,
            activeAppCount = activeCount,
            paused = paused,
            contentIntent = null,
            pauseIntent = null,
            stopIntent = null,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {

                //   否则抛 `MissingForegroundServiceTypeException`。
                startForeground(
                    FocusMonitorConfig.NOTIFICATION_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(FocusMonitorConfig.NOTIFICATION_ID, n)
            }
        } catch (t: Throwable) {
            // Report the actual foreground-start failure separately from notification permission.
            // Permission denial alone is not proof that startForeground failed or a timed grace period exists.
            Timber.e(t, "startForeground 失败，服务将退化为普通后台服务")
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================









    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.d("任务被移除，服务保持运行")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Timber.d("FocusMonitorService 销毁")
        loopJob?.cancel()
        rulesJob?.cancel()
        tickJob?.cancel()
        sessionObserverJob?.cancel()
        preferenceObserverJob?.cancel()
        serviceScope.cancel()
        poller.reset()
        notification.cancel(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null


    fun onSelfForegroundChanged(foreground: Boolean) {
        selfForeground = foreground
    }

    private fun stopSelfSafely() {
        runCatching {

            //   minSdk = 26，`STOP_FOREGROUND_REMOVE` 与两参重载都存在，
            //   那个 else 是死代码（lint ObsoleteSdkInt）。
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }


    private fun effectiveRules(all: List<BlockRule>): List<BlockRule> = all

    companion object {
        const val ACTION_START = "com.projectfocus.action.FOCUS_START"
        const val ACTION_STOP = "com.projectfocus.action.FOCUS_STOP"
        const val ACTION_PAUSE = "com.projectfocus.action.FOCUS_PAUSE"
        const val ACTION_RESUME = "com.projectfocus.action.FOCUS_RESUME"

        /** 没有进行中的会话时，ActiveSessionCategoryId 的值 */
        const val NO_SESSION: Long = -1L

        private const val REQ_OPEN_APP = 100
        private const val REQ_PAUSE = 101
        private const val REQ_STOP = 102

        /** 连续多少轮无事件才怀疑权限被撤销。20 轮 × 500ms = 10 秒 */
        private const val PERMISSION_LOST_ROUNDS = 20

        /** 单人会话每次请求一个采样秒；阻塞期间漏掉的秒不补计。 */
        private const val TICK_INTERVAL_MS = 1_000L










        private const val MIN_BLOCK_INTERVAL_MS = 1_500L

        /** 启动服务的标准 Intent */
        fun startIntent(context: android.content.Context): Intent =
            Intent(context, FocusMonitorService::class.java).setAction(ACTION_START)

        /** 停止 */
        fun stopIntent(context: android.content.Context): Intent =
            Intent(context, FocusMonitorService::class.java).setAction(ACTION_STOP)
    }
}
