package com.projectfocus.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import com.projectfocus.app.route.EntryAction
import com.projectfocus.app.route.EntryActionParser
import com.projectfocus.app.scene.AppSceneCatalog
import com.projectfocus.app.scene.AppSceneEditorSession
import com.projectfocus.app.widget.DayChangeDetector
import com.projectfocus.app.widget.TodayWidgetBridge
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.Orientation
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.datastore.ThemeMode
import com.projectfocus.core.network.content.SceneAccessResolver
import com.projectfocus.core.network.content.SceneContentChecker
import com.projectfocus.feature.booking.seat.SeatSceneChoices
import com.projectfocus.feature.booking.seat.SeatSceneChoicesHost
import com.projectfocus.feature.booking.seat.SeatSceneEditorSession
import com.projectfocus.service.focus.monitor.FocusMonitorService
import com.projectfocus.service.focus.session.SessionRepository
import com.projectfocus.service.focus.session.SessionState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/** Hosts navigation, system insets, theme changes and session side effects. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity(), SeatSceneChoicesHost, com.projectfocus.core.ui.nav.FreeFlightPreparationHost,
    com.projectfocus.core.ui.nav.OrdinaryFlightPreparationHost {

    private var freeFlightPreparationController: com.projectfocus.app.freeflight.FreeFlightPreparationController? = null
    private var ordinaryPreparationController: com.projectfocus.app.countdown.OrdinaryFlightPreparationController? = null
    private val ordinaryPreparation by lazy {
        androidx.lifecycle.ViewModelProvider(this)[com.projectfocus.app.countdown.OrdinaryFlightPreparationViewModel::class.java]
    }

    override fun openOrdinaryFlightPreparation(): Boolean {
        if (freeFlightPreparationController?.isActive == true || !returnHomeForFreeFlight()) return false
        val coordinator = ordinaryPreparation.coordinator
        if (coordinator.state.value.active) { ensureOrdinaryPreparationSurface(); return true }
        val selected = coordinator.selectedContext()
        if (selected.originCode.isEmpty() || selected.destinationCode.isEmpty()) {
            val host = supportFragmentManager.findFragmentById(R.id.nav_host_container) as? NavHostFragment ?: return false
            val home = host.childFragmentManager.primaryNavigationFragment as? com.projectfocus.app.home.HomeFragment
            host.navController.navigate(R.id.action_home_to_airport, Bundle().apply {
                putString(com.projectfocus.app.route.RouteArgs.ARG_MODE,
                    if (selected.originCode.isEmpty()) com.projectfocus.app.route.RouteArgs.MODE_PICK_ORIGIN
                    else com.projectfocus.app.route.RouteArgs.MODE_PICK_DESTINATION)
                if (selected.originCode.isNotEmpty()) com.projectfocus.feature.airport.ui.AirportSearchReferenceArgs
                    .writeTo(this, home?.ordinaryOriginReference())
            })
            return true
        }
        ordinaryPreparationController?.detach()
        ordinaryPreparationController = com.projectfocus.app.countdown.OrdinaryFlightPreparationController(this, coordinator)
        coordinator.begin()
        return true
    }

    private fun ensureOrdinaryPreparationSurface() {
        if (ordinaryPreparation.coordinator.state.value.active && ordinaryPreparationController == null) {
            ordinaryPreparationController = com.projectfocus.app.countdown.OrdinaryFlightPreparationController(this, ordinaryPreparation.coordinator)
        }
    }

    internal fun beginFreeFlightHomePresentation(): java.io.Closeable? {
        val host = supportFragmentManager.findFragmentById(R.id.nav_host_container) as? NavHostFragment ?: return null
        val home = host.childFragmentManager.primaryNavigationFragment as? com.projectfocus.app.home.HomeFragment ?: return null
        return home.beginFreeFlightPreparationPresentation()
    }

    internal fun reflectFreeFlightStart(sessionId: String) {
        if (isFinishing || isDestroyed || !returnHomeForFreeFlight()) return
        val host = supportFragmentManager.findFragmentById(R.id.nav_host_container) as? NavHostFragment ?: return
        (host.childFragmentManager.primaryNavigationFragment as? com.projectfocus.app.home.HomeFragment)?.reflectFreeFlightStart(sessionId)
    }

    override fun openFreeFlightPreparation(): Boolean {
        if (ordinaryPreparation.coordinator.state.value.active) return false
        if (freeFlightPreparationController?.isActive == true) return returnHomeForFreeFlight()
        val actions = (application as? com.projectfocus.app.freeflight.FreeFlightPreparationActionsProvider)
            ?.freeFlightPreparationActions(this, null) ?: return false
        if (actions.unavailableReason != null || !returnHomeForFreeFlight()) {
            actions.dismissed(null, false, com.projectfocus.app.freeflight.FreeFlightPreparationDismissal.HEADING_CANCEL)
            (actions as? java.io.Closeable)?.close()
            return false
        }
        freeFlightPreparationController?.detach()
        freeFlightPreparationController = com.projectfocus.app.freeflight.FreeFlightPreparationController(this, actions).also { it.begin() }
        return true
    }

    private fun returnHomeForFreeFlight(): Boolean {
        val host = supportFragmentManager.findFragmentById(R.id.nav_host_container) as? NavHostFragment ?: return false
        val nav = host.navController
        if (nav.currentDestination?.id != R.id.homeFragment && !nav.popBackStack(R.id.homeFragment, false)) return false
        host.childFragmentManager.executePendingTransactions()
        return true
    }

    private fun restoreFreeFlightPreparation(saved: Bundle?) {
        val id = saved?.getString(com.projectfocus.app.freeflight.FreeFlightPreparationController.KEY_PREPARATION) ?: return
        val actions = (application as? com.projectfocus.app.freeflight.FreeFlightPreparationActionsProvider)
            ?.freeFlightPreparationActions(this, id) ?: return
        freeFlightPreparationController = com.projectfocus.app.freeflight.FreeFlightPreparationController(this, actions).also { it.restore(saved) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        freeFlightPreparationController?.saved()?.let { outState.putBundle("freeFlightPreparationUi", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // View teardown is not explicit preparation cancellation or formal-admission cancellation.
        freeFlightPreparationController?.detach()
        freeFlightPreparationController = null
        ordinaryPreparationController?.detach()
        ordinaryPreparationController = null
        super.onDestroy()
    }

    override fun onPostResume() {
        super.onPostResume()
        ensureOrdinaryPreparationSurface()
    }

    /** Actual Activity binding lifetime; returning from a picker retains this token. */
    internal val planBindingToken: String = java.util.UUID.randomUUID().toString()

    override fun seatSceneChoices(arguments: Bundle): SeatSceneChoices =
        AppSceneCatalog.enabledSeatChoices(this)

    override fun createSeatSceneEditor(arguments: Bundle): SeatSceneEditorSession =
        AppSceneEditorSession(this, sceneAccessResolver, sceneContentChecker)

    override fun presetSeatScene(arguments: Bundle): com.projectfocus.feature.booking.seat.SeatSceneChoice? {
        val title = arguments.getString("planSceneTitle")?.takeIf(String::isNotEmpty) ?: return null
        val type = arguments.getInt("planSceneType", 1)
        val builtin = com.projectfocus.feature.scene.source.SourceSceneCatalog.builtins.firstOrNull { it.type == type }
        return com.projectfocus.feature.booking.seat.SeatSceneChoice("plan-preset:$type:$title",
            arguments.getString("planSceneKey") ?: builtin?.iconKey, title, type,
            builtin?.iconRes ?: 0, builtin?.color ?: 0)
    }

    @Inject
    lateinit var sceneAccessResolver: SceneAccessResolver

    @Inject
    lateinit var sceneContentChecker: SceneContentChecker








    @Inject
    lateinit var todayWidgetBridge: TodayWidgetBridge











    @Inject
    lateinit var sessionRepository: SessionRepository

    @Inject
    lateinit var plannedFlightSessions: com.projectfocus.app.booking.PlannedFlightSessionBridge

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var blockedApps: com.projectfocus.core.database.dao.BlockedAppDao

    /** 避免 Activity 重建后重复 restore；真正的“是否恢复过”由 SessionRepository 的状态判断。 */
    private var restoreAttempted = false

    /** 同一个 sessionId 只自动导航一次，防止返回后又被观察者弹回去。 */
    private var lastNavigatedArrivalId: String? = null











    private val dayChangeDetector = DayChangeDetector()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val day = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = day
            isAppearanceLightNavigationBars = day
        }


        //
        //   非空 savedInstanceState 时，FragmentManager 会**自动恢复**
        //   上一次的 NavHostFragment 实例与它的返回栈。
        //   如果这里不加判断地再 replace 一次，后果是：
        //     旋转屏幕 → 用户本来在「飞行页」→ 被重置回「引导页」
        //   而且旧实例会被 detach，它内部的 ViewModel 也随之销毁 ——
        //   正在计时的会话状态看起来"丢了"（其实 SessionRepository 还在，
        //   但 UI 回到了起点，用户会以为出了问题）。
        if (savedInstanceState == null) {
            val navHost = NavHostFragment.create(R.navigation.nav_main)
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_container, navHost)

                //   没有它，按返回键时 FragmentManager 不会把事件交给
                //   NavHost 处理，而是直接 finish Activity ——
                //   表现是「在二级页面按返回直接退出 App」。
                .setPrimaryNavigationFragment(navHost)
                .commit()
        }

        installSystemBarInsets()


        //   否则 navHost 的 currentBackStackEntry 还不存在，写进去也没人读。
        handleIntent(intent)

        // R8：恢复会话 + 观察状态。必须在 handleIntent 之后，
        // 否则调试启动会先写 Running、再被 restore 读成旧状态。
        plannedFlightSessions.observe()
        restoreSessionIfNeeded()
        observeSession()
        observeThemePreference()
        observeScreenOrientationPreference()
        restoreFreeFlightPreparation(savedInstanceState?.getBundle("freeFlightPreparationUi"))
    }


    private fun observeScreenOrientationPreference() {
        lifecycleScope.launch {
            settingsRepository.observe(PrefKey.ScreenOrientation).collect { preference ->
                val target = when (preference) {
                    Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    Orientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    else -> return@collect
                }
                // Auto releases the app request; it does not force sensor rotation.
                // On a fresh Activity already unspecified, no setter is needed.
                if (requestedOrientation != target) requestedOrientation = target
            }
        }
    }

    private fun observeThemePreference() {
        lifecycleScope.launch {
            settingsRepository.observe(PrefKey.ThemeMode).collect { preference ->
                val mode = when (preference) {
                    ThemeMode.DAY -> AppCompatDelegate.MODE_NIGHT_NO
                    ThemeMode.NIGHT -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                // The unspecified delegate already follows the system on first launch.
                if (mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM &&
                    delegate.localNightMode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) return@collect
                if (delegate.localNightMode != mode) delegate.localNightMode = mode
            }
        }
    }

    private fun installSystemBarInsets() {
        supportFragmentManager.executePendingTransactions()
        val container = findViewById<android.view.View>(R.id.nav_host_container)
        val host = supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            // Home and the floating preparation editor use source full-window coordinates.

            if (host.navController.currentDestination?.id in setOf(R.id.homeFragment, R.id.flightPreparationFragment)) {
                view.setPadding(0, 0, 0, 0)
            } else {
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
        host.navController.addOnDestinationChangedListener { _, _, _ ->
            ViewCompat.requestApplyInsets(container)
        }
        ViewCompat.requestApplyInsets(container)
    }








    private fun restoreSessionIfNeeded() {
        if (restoreAttempted) return
        restoreAttempted = true
        lifecycleScope.launch {
            if (sessionRepository.state.value is SessionState.Idle) {
                runCatching { sessionRepository.restore() }
                    .onFailure { Timber.e(it, "恢复会话失败") }
            }
        }
    }








    private fun observeSession() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var monitoringSessionId: String? = null
                sessionRepository.state.collect { state ->
                    when (state) {
                        is SessionState.Running, is SessionState.Paused -> {
                            val id = if (state is SessionState.Running) state.sessionId
                                else (state as SessionState.Paused).sessionId
                            if (monitoringSessionId != id && startMonitorService()) monitoringSessionId = id
                        }
                        is SessionState.Arrived -> {
                            monitoringSessionId = null
                            // 到站后不再需要 500ms 轮询；结算由落地页的“完成飞行”触发。
                            stopMonitorService()
                            navigateToCompletion(state.sessionId)
                        }
                        SessionState.Idle -> {
                            monitoringSessionId = null
                            stopMonitorService()
                        }
                    }
                }
            }
        }
    }

    private fun startMonitorService(): Boolean =
        runCatching {
            ContextCompat.startForegroundService(this, FocusMonitorService.startIntent(this))
        }.onFailure { Timber.w(it, "启动 FocusMonitorService 失败") }.isSuccess

    private fun stopMonitorService() {
        runCatching { stopService(Intent(this, FocusMonitorService::class.java)) }
            .onFailure { Timber.w(it, "停止 FocusMonitorService 失败") }
    }

    /**
     * 到站自动进入落地页。
     *
     * 只对“当前这次会话”导航一次；用户按返回离开落地页后，
     * 状态仍是 Arrived，但不会因为 StateFlow 重放而再次弹回去。
     */
    private fun navigateToCompletion(sessionId: String) {
        if (lastNavigatedArrivalId == sessionId) return
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_container) as? NavHostFragment
            ?: return
        val nav = navHost.navController
        if (nav.currentDestination?.id == R.id.flightCompletionFragment) return

        val uri = Uri.parse("projectfocus://completion/${Uri.encode(sessionId)}")
        runCatching { nav.navigate(uri) }
            .onSuccess {
                lastNavigatedArrivalId = sessionId
                Timber.i("到站自动进入落地页：%s", sessionId)
            }
            .onFailure { Timber.e(it, "到站导航失败：%s", sessionId) }
    }

    /**
     * 先尝试 Navigation 深链（R7 落地页 projectfocus://completion/{recordId}）；
     * 不是深链才落到小组件入口动作。
     */
    private fun handleIntent(intent: android.content.Intent?) {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_container) as? NavHostFragment
        if (intent != null && navHost?.navController?.handleDeepLink(intent) == true) {
            return
        }
        dispatchEntryAction(intent)
    }






















    override fun onResume() {
        super.onResume()
        plannedFlightSessions.resume()
        freeFlightPreparationController?.refreshNight()
        ordinaryPreparationController?.refreshNight()
        if (dayChangeDetector.shouldRefresh()) {
            todayWidgetBridge.requestRefresh()
        }
    }





















    private fun dispatchEntryAction(intent: android.content.Intent?) {
        val action = EntryActionParser.parse(intent)
        if (action is EntryAction.Unknown) {

            //   必须留日志：否则"用户说点小组件没反应"时无法区分
            //   "没收到" 和 "收到了但不懂"。
            Timber.w("收到未知的入口动作：%s（可能是小组件版本与 App 不匹配）", action.raw)
        }
        if (action is EntryAction.None) return

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_container) as? NavHostFragment
        val entry = navHost?.navController?.currentBackStackEntry ?: run {
            Timber.w("NavHost 尚未就绪，入口动作无处落盘：%s", action)
            return
        }
        entry.savedStateHandle.set(KEY_ENTRY_ACTION, action)
        Timber.i("入口动作已交给首页：%s（来自小组件=%s）", action, EntryActionParser.isFromWidget(intent))
    }


    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)

        //   都应该看到最新的那个，否则会拿到冷启动时的旧值。
        setIntent(intent)
        handleIntent(intent)
    }

    companion object {







        const val KEY_ENTRY_ACTION = "key_entry_action"
    }
}
