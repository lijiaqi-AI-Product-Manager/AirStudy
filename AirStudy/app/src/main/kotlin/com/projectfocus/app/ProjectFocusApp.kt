package com.projectfocus.app

import android.app.Application
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.app.widget.TodayWidgetBridge
import com.projectfocus.core.database.bootstrap.DatabaseBootstrap
import com.projectfocus.core.database.seed.AirportSeeder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import timber.log.Timber
import javax.inject.Inject


@HiltAndroidApp
class ProjectFocusApp : Application(), com.projectfocus.app.freeflight.FreeFlightPreparationActionsProvider,
    com.projectfocus.service.focus.history.RecordCloudWorkerOwner {

    @Inject override lateinit var recordCloudMutations: com.projectfocus.service.focus.history.RecordCloudMutationCoordinator
    @Inject lateinit var recordMutationRetries: com.projectfocus.service.focus.history.RecordMutationRetryScheduler

    override suspend fun awaitRecordCloudReady() {
        awaitInitialAccountRestore()
        awaitInitialAirportSeed()
    }

    @Inject lateinit var freeFlightPreparationFactory: com.projectfocus.app.freeflight.AndroidFreeFlightPreparationActionsFactory

    override fun freeFlightPreparationActions(activity: MainActivity, restoringPreparationId: String?) =
        freeFlightPreparationFactory.create(activity, restoringPreparationId)

    @Inject
    lateinit var database: AppDatabase










    @Inject
    lateinit var todayWidgetBridge: TodayWidgetBridge

    @Inject lateinit var emailAccounts: com.projectfocus.app.auth.EmailAccountSessionStore
    @Inject lateinit var authPromoImages: com.projectfocus.app.auth.AuthPromoImageLoader
    @Inject lateinit var registrationGateway: com.projectfocus.app.auth.RegistrationGateway
    @Inject lateinit var passwordResetGateway: com.projectfocus.app.auth.PasswordResetGateway

    /**
     * 应用级协程作用域。
     *
     * 只在 Application 里创建一次；不要往这个 scope 里塞
     * 需要随页面销毁取消的任务（那是 ViewModel 的职责）。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ownedDebugTree: Timber.Tree? = null

    /** Release only this Application's logger; never uproot other owners' trees. */
    internal fun releaseOwnedDebugLogging() {
        val tree = synchronized(this) { ownedDebugTree.also { ownedDebugTree = null } } ?: return
        if (Timber.forest().any { it === tree }) Timber.uproot(tree)
    }

    override fun onTerminate() {
        releaseOwnedDebugLogging()
        super.onTerminate()
    }
    /** Admission callbacks use this exact real Application owner; tests cancel/join the same Job. */
    internal val admissionApplicationScope: CoroutineScope get() = appScope
    private val initialAirportSeed = CompletableDeferred<AirportSeeder.Result?>()
    // This baseline is captured during Application construction, before restore/login work.
    private val initialAccountRestore = com.projectfocus.app.auth.AccountRestoreGate()

    internal suspend fun awaitInitialAccountRestore(): com.projectfocus.app.auth.AccountRestorePublication? =
        initialAccountRestore.await()

    /** Waits for the existing startup task, including a real failure; never launches a second import. */
    internal suspend fun awaitInitialAirportSeed(): AirportSeeder.Result? = initialAirportSeed.await()






    @Volatile
    var databaseBootstrapResult: DatabaseBootstrap.Result? = null
        private set

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.DebugTree().also { ownedDebugTree = it; Timber.plant(it) }
            enableStrictMode()
        }


        //   为什么放在预热之前：用户从桌面点小组件进来时，冷启动路径极短，
        //   如果桥在数据库预热之后才接上，那一次启动期间的信号可能落空。
        //   `start()` 自身只建两个 collector + 入队一次刷新，耗时 < 1ms。
        todayWidgetBridge.start()

        appScope.launch {
            awaitRecordCloudReady()
            com.projectfocus.core.network.di.TokenStore.identities.collect { identity ->
                if (!identity.token.isNullOrBlank() && identity == com.projectfocus.core.network.di.TokenStore.snapshot()) {
                    try { recordMutationRetries.enqueueIfPending(com.projectfocus.core.network.di.TokenStore.storageScope(identity)) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { Timber.w("记录同步重试暂不可用") }
                }
            }
        }

        appScope.launch {
            try { initialAccountRestore.restore(emailAccounts) }
            catch (_: Exception) { Timber.w("本地账号恢复失败") }
        }.invokeOnCompletion { initialAccountRestore.complete(null) }

        // 预热数据库：不阻塞 onCreate
        appScope.launch {
            val warmUp = DatabaseBootstrap.warmUp(database) { elapsedMs ->
                // TODO(M2): 接入埋点上报 —— 目前只有日志
                Timber.w("数据库冷启动预热 %d ms（超阈值）", elapsedMs)
            }
            databaseBootstrapResult = warmUp

            // 机场库导入必须排在预热之后：预热失败时连表都不可信，
            // 再去写 9 千行只会把一个可诊断的错误变成一堆级联错误。
            if (warmUp is DatabaseBootstrap.Result.Success) {
                val seeded = AirportSeeder.ensureSeeded(this@ProjectFocusApp, database)
                initialAirportSeed.complete(seeded)
                when (seeded) {
                    is AirportSeeder.Result.Seeded ->
                        Timber.i("机场库就绪：%d 行（本次导入 %d ms）", seeded.rows, seeded.elapsedMs)
                    is AirportSeeder.Result.UpToDate ->
                        Timber.d("机场库已是最新：%d 行", seeded.rows)
                    is AirportSeeder.Result.Failed ->
                        Timber.e(seeded.cause, "机场库导入失败，搜索页将显示重试入口")
                }
            } else initialAirportSeed.complete(null)
        }.invokeOnCompletion { cause ->
            if (cause != null) initialAirportSeed.completeExceptionally(cause)
        }

        Timber.i(
            "ProjectFocus 启动 v%s(%d) debug=%s",
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.DEBUG,
        )
    }












    private fun enableStrictMode() {
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        android.os.StrictMode.setVmPolicy(
            android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .detectCleartextNetwork()
                .detectUntaggedSockets()
                .penaltyLog()
                .build(),
        )
    }
}
