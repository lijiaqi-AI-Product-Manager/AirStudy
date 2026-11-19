package com.projectfocus.service.focus.session

import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.common.flight.FlightMath
import com.projectfocus.core.database.dao.CountryTripCount
import com.projectfocus.core.database.dao.FlightHistoryDao
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
import com.projectfocus.service.focus.freeflight.FlightRuntimeLeaseGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
































// 保持 DataStore 文件替换路径与 minSdk 26+ 的实际 Android 环境一致。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var mono: FakeMono
    private lateinit var controller: SessionController
    private lateinit var historyDao: FakeHistoryDao
    private lateinit var airportDao: FakeAirportDao
    private lateinit var settings: SettingsRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var push: RecordingWidgetPush
    private lateinit var notifier: RecordingNotifier
    private var wallNow = 1_700_000_000_000L
    private var nextId = 1

    private val min45 = 45L * 60 * 1_000

    private class FakeMono(var now: Long = 0L) : MonotonicSource {
        override fun elapsedRealtimeMs(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    /** 真实 upsert 语义：同 id 覆盖 */
    private class FakeHistoryDao : FlightHistoryDao() {
        val store = LinkedHashMap<String, FlightHistoryEntity>()
        var failOnWrite = false
        val writeCount get() = writeCalls
        private var writeCalls = 0

        override fun observeAll(): Flow<List<FlightHistoryEntity>> =
            MutableStateFlow(store.values.toList())

        override suspend fun getAll(): List<FlightHistoryEntity> = store.values.toList()

        override suspend fun findById(id: String): FlightHistoryEntity? = store[id]

        override fun observeById(id: String): Flow<FlightHistoryEntity?> =
            MutableStateFlow(store[id])

        override suspend fun pendingSync(): List<FlightHistoryEntity> =
            store.values.filter { !it.isSynced && it.status == "COMPLETED" }


        //   这也顺便说明了一件事：Fake 里的查询逻辑和 DAO 的 SQL 必须**两处都改**，
        //   所以 DAO 的 SQL 语义应由 core-database 的 Room 测试守，不在这里重复守。
        override fun observePendingSyncCount(): Flow<Int> = MutableStateFlow(
            store.values.count { !it.isSynced && it.status == "COMPLETED" },
        )

        override suspend fun latestCompleted(): FlightHistoryEntity? =
            store.values.filter { it.status == "COMPLETED" }
                .maxByOrNull { it.endTimeMillis ?: Long.MIN_VALUE }

        override fun observeLatestCompleted(): Flow<FlightHistoryEntity?> =
            MutableStateFlow(store.values.firstOrNull())

        override suspend fun ongoing(): FlightHistoryEntity? =
            store.values.firstOrNull { it.status == "ONGOING" }

        override fun observeOngoing(): Flow<FlightHistoryEntity?> =
            MutableStateFlow(store.values.firstOrNull { it.status == "ONGOING" })

        override suspend fun visitedCountries(): List<String> =
            store.values.map { it.destinationCountry }.distinct()


        override suspend fun visitedAirportCodesForCountry(country: String): List<String> =
            store.values
                .filter { it.status == "COMPLETED" && it.destinationCountry == country }
                .map { it.destinationAirportCode }
                .filter { it.isNotEmpty() }
                .distinct()

        override suspend fun markSynced(id: String, synced: Boolean) {
            store[id]?.let { store[id] = it.copy(isSynced = synced) }
        }

        override suspend fun deleteById(id: String) { store.remove(id) }

        override fun observeCompletedCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeTotalDistanceKm(): Flow<Double> = MutableStateFlow(0.0)
        override fun observeTotalFocusMinutes(): Flow<Int> = MutableStateFlow(0)
        override fun observeVisitedCountryCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeVisitedAirportCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeCountryBreakdown(): Flow<List<CountryTripCount>> =
            MutableStateFlow(emptyList())

        override suspend fun dailyFocusMinutes(fromMillis: Long, tzOffsetMillis: Long) =
            emptyList<com.projectfocus.core.database.dao.DailyFocusRow>()


        //   在 Fake 里返回"空"是**刻意的**，不是偷懒：
        //   本文件测的是会话状态机（开始/暂停/完成/放弃），
        //   今日汇总与连续天数与它无关。真正守这两条 SQL 的是
        //   core-database 的 FlightHistoryDaoTest（跑在真 SQLite 上）。

        //     它会与真实 DAO 静默分歧，而分歧只在集成时才暴露。
        override suspend fun todaySummary(
            fromMillis: Long,
            toMillis: Long,
        ): com.projectfocus.core.database.dao.TodaySummaryRow? = null

        override suspend fun completedStartTimesSince(fromMillis: Long): List<Long> = emptyList()

        private fun beforeWrite() {
            writeCalls++
            if (failOnWrite) error("模拟磁盘写失败")
        }

        override suspend fun insert(entity: FlightHistoryEntity) {
            beforeWrite()
            // 与 @Insert(ABORT) 一致：同 id 报错
            check(!store.containsKey(entity.id)) { "UNIQUE constraint failed: id" }
            store[entity.id] = entity
        }

        override suspend fun upsert(entity: FlightHistoryEntity) {
            beforeWrite()
            store[entity.id] = entity
        }

        override suspend fun update(entity: FlightHistoryEntity) {
            beforeWrite()
            if (store.containsKey(entity.id)) store[entity.id] = entity
        }

        override suspend fun deleteAll() { store.clear() }
    }

    /** 只实现 findByCode，其余抛异常 —— 一旦被调用就能立刻发现「不该走这里」 */
    private class FakeAirportDao(
        private val byCode: Map<String, AirportEntity>,
    ) : AirportDao() {
        override fun observeAll(hasPremium: Boolean): Flow<List<AirportEntity>> =
            error("不应调用")

        override suspend fun getAll(hasPremium: Boolean): List<AirportEntity> = error("不应调用")

        override suspend fun findByCode(code: String): AirportEntity? = byCode[code]

        override suspend fun byCountry(country: String): List<AirportEntity> = error("不应调用")

        override suspend fun topByPopularity(limit: Int): List<AirportEntity> = error("不应调用")

        override suspend fun random(limit: Int): List<AirportEntity> = error("不应调用")

        override suspend fun count(hasPremium: Boolean): Int = error("不应调用")

        override suspend fun search(
            query: String,
            limit: Int,
            hasPremium: Boolean,
        ): List<AirportEntity> = error("不应调用")

        override suspend fun upsertAll(airports: List<AirportEntity>) = error("不应调用")

        override suspend fun upsert(airport: AirportEntity) = error("不应调用")

        override suspend fun deleteAll() = error("不应调用")
    }

    private class RecordingWidgetPush : WidgetPush {
        val history = mutableListOf<WidgetMirror>()
        val last get() = history.lastOrNull()
        val todayChanged get() = todayCount
        private var todayCount = 0
        override suspend fun push(mirror: WidgetMirror) { history += mirror }
        override suspend fun onTodayChanged() { todayCount++ }
    }










    private class RecordingNotifier : TodayStatsNotifier() {
        var count = 0
            private set
        override fun notifyChanged() { count++ }
    }

    // ==================================================================
    // 装配
    // ==================================================================

    private fun setUp(
        airports: Map<String, AirportEntity> = mapOf("PKX" to pkx(), "KIX" to kix()),
    ) {
        mono = FakeMono()
        controller = SessionController(
            monotonic = mono,
            wallClock = { wallNow },
            idGenerator = { "sess-${nextId++}" },
        )
        historyDao = FakeHistoryDao()
        airportDao = FakeAirportDao(airports)
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.newFolder(), "test.preferences_pb") },
        )
        settings = SettingsRepository(dataStore)
        push = RecordingWidgetPush()
        notifier = RecordingNotifier()
    }

    private fun repo(scope: TestScope): SessionRepository {

        //   也在测试调度器上排队，advanceUntilIdle 能一次推完，不会真的开新线程。
        return SessionRepository(
            controller = controller,
            settings = settings,
            historyDao = historyDao,
            airportDao = airportDao,
            widgetPush = push,
            todayNotifier = notifier,
            io = StandardTestDispatcher(scope.testScheduler),
        )
    }

    @Test fun aPreparedAccountCannotStartAfterItsGenerationChangesWhileWaitingForTheCommandLock() = runTest {
        setUp()
        TokenStore.set("prepared-countdown-account")
        val expected = TokenStore.snapshot()
        val repository = repo(this)
        val plan = repository.preparePlan("PKX", "KIX").copy(flightNumber = "CA1234")
        val oldRoute = repository.currentRoute()
        val mutex = SessionRepository::class.java.getDeclaredField("commandMutex").apply { isAccessible = true }
            .get(repository) as kotlinx.coroutines.sync.Mutex
        mutex.lock()
        val start = async { repository.startPlannedFlight(plan, "guarded-start", expected) }
        runCurrent()
        assertThat(start.isCompleted).isFalse()
        TokenStore.set("prepared-countdown-account")
        mutex.unlock()
        assertThat(start.await()).isFalse()
        assertThat(repository.state.value).isEqualTo(SessionState.Idle)
        assertThat(repository.currentRoute()).isEqualTo(oldRoute)
        assertThat(historyDao.store).isEmpty()
    }

    @Test fun theMatchingPreparedAccountStartsWithItsFrozenNumberAndOriginalOwner() = runTest {
        setUp()
        TokenStore.set("matching-countdown-account")
        val expected = TokenStore.snapshot()
        val repository = repo(this)
        val plan = repository.preparePlan("PKX", "KIX").copy(flightNumber = "CA1234", seat = "1A")
        assertThat(repository.startPlannedFlight(plan, "matching-start", expected)).isTrue()
        assertThat(repository.currentRoute().flightNumber).isEqualTo("CA1234")
        assertThat(historyDao.store.values.single().flightNumber).isEqualTo("CA1234")
        assertThat(historyDao.store.values.single().ownerScope).isEqualTo(TokenStore.storageScope(expected))
    }

    private suspend fun advanceWithTicks(repository: SessionRepository, seconds: Int) {
        repeat(seconds) {
            mono.advance(1_000L)
            repository.tick()
        }
    }

    @Test fun externalPendingReservationBlocksBothCoreStartsAndRestoreCommandCleanup() = runTest {
        setUp()
        val repository = repo(this)
        val owner = repository.openFreeFlightHost("actual-owner-scope")
        assertThat(repository.reserveFreeFlightRuntime("free-pending", owner)).isTrue()
        settings.write(PrefKey.ActiveSessionCategoryId, 345L)
        assertThat(repository.start(SessionRepository.NO_CATEGORY, min45)).isFalse()
        val plan = repository.preparePlan("PKX", "KIX")
        assertThat(repository.startPlannedFlight(plan, "planned-core")).isFalse()
        assertThat(repository.restore()).isEqualTo(SessionState.Idle)
        assertThat(settings.read(PrefKey.ActiveSessionCategoryId)).isEqualTo(345L)
        assertThat(historyDao.store).isEmpty()
        repository.releaseFreeFlightRuntime("free-pending", owner)
        assertThat(repository.startPlannedFlight(plan, "planned-core")).isTrue()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun heldCoreLeaseSerializesActualStartAndHostInvalidationForWholeAction() = runTest {
        setUp()
        val repository = repo(this)
        val owner = repository.openFreeFlightHost("actual-owner-scope")
        assertThat(repository.reserveFreeFlightRuntime("free-held", owner)).isTrue()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val identity = TokenStore.snapshot()
        val held = async { TokenStore.withSnapshotLease(identity) { repository.withFreeFlightRuntimeLease("free-held", owner) { entered.complete(Unit); release.await(); 42 } } }
        entered.await()
        val invalidation = async { repository.invalidateFreeFlightHost(owner) }
        val countdown = async { repository.start(SessionRepository.NO_CATEGORY, min45) }
        runCurrent()
        assertThat(invalidation.isCompleted).isFalse()
        assertThat(countdown.isCompleted).isFalse()
        release.complete(Unit)
        assertThat(held.await()).isEqualTo(42)
        invalidation.await()
        assertThat(countdown.await()).isFalse()
        assertThat(TokenStore.withSnapshotLease(identity) { repository.withFreeFlightRuntimeLease<Int>("free-held", owner) { error("obsolete host must not run") } }).isNull()
        repository.releaseFreeFlightRuntime("free-held", owner)
    }

    @Test fun coldCoreArchivePreventsHostBeforeActivityRestore() = runTest {
        setUp()
        val first = repo(this)
        assertThat(first.start(SessionRepository.NO_CATEGORY, min45)).isTrue()
        controller = SessionController(monotonic = mono, wallClock = { wallNow }, idGenerator = { "after-cold" })
        val cold = repo(this)
        assertThat(cold.state.value).isEqualTo(SessionState.Idle)
        val failure = runCatching { cold.openFreeFlightHost("actual-owner-scope") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(cold.restore()).isInstanceOf(SessionState.Running::class.java)
        assertThat(historyDao.store).hasSize(1)
    }

    @Test fun realAdapterRejectsSameTokenNewAccountGenerationWithoutUnlockingPendingRuntime() = runTest {
        setUp()
        TokenStore.set("own-test-token")
        try {
            val repository = repo(this)
            val ownership = SharedFlightRuntimeOwnership(repository)
            val owner = ownership.openHost()
            assertThat(ownership.acquire("free-account", owner)).isTrue()
            TokenStore.set("own-test-token")
            assertThat(ownership.isHostCurrent(owner)).isFalse()
            assertThat(ownership.withCurrentOwner<Int>("free-account", owner) { error("old account must not execute") }).isNull()
            assertThat(repository.start(SessionRepository.NO_CATEGORY, min45)).isFalse()
            ownership.release("free-account", owner)
            val next = ownership.openHost()
            assertThat(next.generation).isGreaterThan(owner.generation)
            assertThat(ownership.acquire("new-free", next)).isTrue()
            ownership.release("new-free", next)
        } finally { TokenStore.clear() }
    }

    @Test fun realLeaseEffectsRejectCoreReentryIdentityPublicationAndMainThread() = runTest {
        setUp()
        TokenStore.set("own-test-token")
        try {
            val repository = repo(this)
            val ownership = SharedFlightRuntimeOwnership(repository)
            val owner = ownership.openHost()
            assertThat(ownership.acquire("free-guard", owner)).isTrue()
            ownership.withCurrentOwner("free-guard", owner) {
                val guard = FlightRuntimeLeaseGuard.current()
                assertThat(runCatching { repository.tick() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
                assertThat(runCatching { TokenStore.clear() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
                withContext(StandardTestDispatcher(testScheduler)) {
                    assertThat(runCatching { guard.check() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
                }
                guard.check()
                1
            }
            ownership.release("free-guard", owner)
        } finally { TokenStore.clear() }
    }

    @After
    fun tearDown() {
        // PreferenceDataStoreFactory 每实例一个文件，TemporaryFolder 自动清理
    }

    @Test fun realAdmissionCaptureReadsExistingHostAndExactAccountWithoutMintingAnOwner() = runTest {
        setUp()
        TokenStore.set("capture-fixture-account")
        try {
            val repository = repo(this)
            val ownership = SharedFlightRuntimeOwnership(repository)
            assertThat(ownership.captureHostAccount()).isNull()
            val owner = ownership.openHost()
            val snapshot = TokenStore.snapshot()
            repeat(3) {
                val actual = ownership.captureHostAccount()
                assertThat(actual?.owner).isEqualTo(owner)
                assertThat(actual?.account).isEqualTo(snapshot)
                assertThat(ownership.captureHostAccount(owner)?.account).isEqualTo(snapshot)
            }
            assertThat(ownership.captureHostAccount(owner.copy(generation = owner.generation + 1))).isNull()
            ownership.invalidateHost(owner)
            assertThat(ownership.captureHostAccount()).isNull()
        } finally { TokenStore.clear() }
    }

    @Test fun realAdmissionCaptureRejectsSameBearerNewPublicationAndNeverReentersItsLease() = runTest {
        setUp()
        TokenStore.set("capture-fixture-account")
        try {
            val ownership = SharedFlightRuntimeOwnership(repo(this))
            val owner = ownership.openHost()
            val snapshot = TokenStore.snapshot()
            val reentrant = TokenStore.withSnapshotLease(snapshot) {
                runCatching { ownership.captureHostAccount(owner) }.exceptionOrNull()
            }
            assertThat(reentrant).isInstanceOf(IllegalStateException::class.java)
            assertThat(ownership.captureHostAccount(owner)?.account).isEqualTo(snapshot)
            TokenStore.set("capture-fixture-account")
            assertThat(ownership.captureHostAccount(owner)).isNull()
            assertThat(ownership.captureHostAccount()).isNull()
        } finally { TokenStore.clear() }
    }

    @Test fun realAdmissionCaptureWaitsForActualPublicationLeaseInsteadOfReturningASnapshotOnly() = runTest {
        setUp()
        TokenStore.set("capture-fixture-account")
        try {
            val ownership = SharedFlightRuntimeOwnership(repo(this))
            val owner = ownership.openHost()
            val snapshot = TokenStore.snapshot()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val held = async { TokenStore.withSnapshotLease(snapshot) { entered.complete(Unit); release.await() } }
            entered.await()
            val read = async { ownership.captureHostAccount(owner) }
            runCurrent()
            assertThat(read.isCompleted).isFalse()
            release.complete(Unit)
            held.await()
            assertThat(read.await()?.account).isEqualTo(snapshot)
        } finally { TokenStore.clear() }
    }

    // ==================================================================
    // ① start —— 三处必须同时成立
    // ==================================================================

    @Test
    fun `start 之后 Controller、DAO、DataStore 三者状态一致`() = runTest {
        setUp()
        val r = repo(this)

        assertThat(r.start(3, min45, "PKX", "KIX")).isTrue()

        // —— Controller
        val s = r.state.value as SessionState.Running
        assertThat(s.categoryId).isEqualTo(3)
        assertThat(s.plannedDurationMs).isEqualTo(min45)

        // —— DataStore（命令通道，屏蔽引擎靠它工作）
        assertThat(settings.read(PrefKey.ActiveSessionCategoryId)).isEqualTo(3)
        assertThat(settings.read(PrefKey.SessionPaused)).isFalse()

        // —— DAO（存档）
        val ongoing = historyDao.ongoing()
        assertThat(ongoing).isNotNull()
        assertThat(ongoing!!.id).isEqualTo(s.sessionId)
        assertThat(ongoing.status).isEqualTo(SessionRepository.STATUS_ONGOING)
        assertThat(ongoing.endTimeMillis).isNull()
        assertThat(ongoing.durationMinutes).isEqualTo(45)
    }

    @Test
    fun `start 时冻结航线快照，含经纬度`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45, "PKX", "KIX")

        val row = historyDao.ongoing()!!
        assertThat(row.departureAirportCode).isEqualTo("PKX")
        assertThat(row.departureCity).isEqualTo("北京")
        assertThat(row.departureLatitude).isWithin(1e-6).of(39.5098)
        assertThat(row.destinationAirportCode).isEqualTo("KIX")
        assertThat(row.destinationCity).isEqualTo("大阪")
        assertThat(row.destinationLongitude).isWithin(1e-6).of(135.2444)
    }

    @Test
    fun `指定两机场时冻结真实路线距离而非计划时长半径`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45, "PKX", "KIX")
        val row = historyDao.ongoing()!!
        val km = FlightMath.airportDistanceKm(
            row.departureLatitude, row.departureLongitude,
            row.destinationLatitude, row.destinationLongitude,
        ).toDouble()
        assertThat(km).isGreaterThan(1000.0)
        assertThat(row.distanceKm).isEqualTo(km)
    }

    @Test
    fun `结算保留历史冻结距离不重新代入分钟`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45, "PKX", "KIX")
        val row = historyDao.ongoing()!!
        historyDao.upsert(row.copy(distanceKm = 1234.5))
        advanceWithTicks(r, (min45 / 1_000L).toInt())
        assertThat(r.settle()!!.distanceKm).isEqualTo(1234.5)
    }

    @Test
    fun `准备只读机场而确认后的完整计划启动同一份快照`() = runTest {
        setUp()
        val r = repo(this)
        val plan = r.preparePlan("PKX", "KIX").copy(categoryId = 3, seat = "12A")
        assertThat(r.state.value).isEqualTo(SessionState.Idle)
        assertThat(historyDao.store).isEmpty()
        assertThat(r.startPlannedFlight(plan)).isTrue()
        val row = historyDao.ongoing()!!
        assertThat(row.distanceKm).isEqualTo(plan.distanceKm)
        assertThat(row.durationMinutes).isEqualTo(plan.durationMinutes)
        assertThat(row.seat).isEqualTo("12A")
        assertThat(row.categoryId).isEqualTo(3)
        assertThat(r.currentRoute().distanceKm).isEqualTo(row.distanceKm)
        assertThat(r.currentRoute().departure!!.latitude).isEqualTo(row.departureLatitude)
    }

    @Test
    fun `计划缺省分类映射为活动会话的无分类命令且记录不写负数外键`() = runTest {
        setUp()
        val r = repo(this)
        val plan = r.preparePlan("PKX", "KIX")
        assertThat(plan.categoryId).isNull()
        assertThat(r.startPlannedFlight(plan)).isTrue()
        assertThat((r.state.value as SessionState.Running).categoryId)
            .isEqualTo(SessionRepository.NO_CATEGORY)
        assertThat(settings.read(PrefKey.ActiveSessionCategoryId))
            .isEqualTo(SessionRepository.NO_CATEGORY)
        assertThat(historyDao.ongoing()!!.categoryId).isNull()
        advanceWithTicks(r, 120)
        r.abandon()
        assertThat(r.settle()!!.categoryId).isNull()
    }

    @Test
    fun `两个入口并发确认时只启动一个计划且不覆盖赢家航线`() = runTest {
        setUp()
        val r = repo(this)
        val plan = r.preparePlan("PKX", "KIX")
        val results = listOf(
            async { r.startPlannedFlight(plan.copy(seat = "12A")) },
            async { r.startPlannedFlight(plan.copy(seat = "12B")) },
        ).awaitAll()
        assertThat(results.count { it }).isEqualTo(1)
        assertThat(historyDao.store).hasSize(1)
        assertThat(historyDao.ongoing()!!.seat).isEqualTo(r.currentRoute().seat)
    }

    @Test
    fun `取消保留全程里程且显式清空的座位不回退到计划旧值`() = runTest {
        setUp()
        val r = repo(this)
        val plan = r.preparePlan("PKX", "KIX").copy(seat = "12A")
        r.startPlannedFlight(plan)
        val row = historyDao.ongoing()!!
        historyDao.upsert(row.copy(seat = null))
        advanceWithTicks(r, 120)
        r.abandon()
        val cancelled = r.settle()!!
        assertThat(cancelled.status).isEqualTo(SessionRepository.STATUS_CANCELLED)
        assertThat(cancelled.distanceKm).isEqualTo(plan.distanceKm)
        assertThat(cancelled.seat).isNull()
    }

    @Test
    fun `恢复后HUD和结算沿用记录里的冻结路线距离`() = runTest {
        setUp()
        val original = repo(this)
        original.startPlannedFlight(original.preparePlan("PKX", "KIX"))
        val row = historyDao.ongoing()!!
        historyDao.upsert(row.copy(distanceKm = 1234.5,
            actualFocusMinutes = row.durationMinutes))
        controller = SessionController(mono, wallClock = { wallNow })
        val restored = repo(this)
        restored.restore()
        assertThat(restored.currentRoute().distanceKm).isEqualTo(1234.5)
        assertThat(restored.currentRoute().departure!!.name).isEqualTo(row.departureAirportName)
        // 已保存的专注检查点已满，恢复后提交到站记录。
        assertThat(restored.settle()!!.distanceKm).isEqualTo(1234.5)
    }




    @Test
    fun `不指定航线也能开始，落库用占位三字码`() = runTest {
        setUp()
        val r = repo(this)
        assertThat(r.start(3, min45)).isTrue()

        val row = historyDao.ongoing()!!
        assertThat(row.departureAirportCode).isEqualTo(SessionRepository.UNKNOWN_AIRPORT)
        assertThat(row.destinationAirportCode).isEqualTo(SessionRepository.UNKNOWN_AIRPORT)
        assertThat(row.departureLatitude).isEqualTo(0.0)
    }

    @Test
    fun `已在飞时重复 start 被拒绝，且不会写第二条 ONGOING`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)

        assertThat(r.start(9, min45)).isFalse()

        assertThat(historyDao.store.values.count { it.status == "ONGOING" }).isEqualTo(1)
        assertThat((r.state.value as SessionState.Running).categoryId).isEqualTo(3)
    }




    @Test
    fun `距离按计划时长计算，45 分钟等于 600 公里`() = runTest {
        setUp()
        val r = repo(this)
        r.start(1, min45)
        assertThat(historyDao.ongoing()!!.distanceKm).isWithin(1e-9).of(600.0)
    }

    // ==================================================================
    // ② pause / resume
    // ==================================================================

    @Test
    fun `pause 之后 DataStore 标记必须立刻为 true`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)
        advanceWithTicks(r, 60)

        assertThat(r.pause()).isTrue()

        assertThat(settings.read(PrefKey.SessionPaused)).isTrue()
        assertThat((r.state.value as SessionState.Paused).accumulatedMs).isEqualTo(60_000)
    }

    @Test
    fun `resume 之后 DataStore 标记回 false，且累计时长不变`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)
        advanceWithTicks(r, 60)
        r.pause()
        mono.advance(120_000)   // 暂停中，不累计

        assertThat(r.resume()).isTrue()

        assertThat(settings.read(PrefKey.SessionPaused)).isFalse()
        val s = r.state.value as SessionState.Running
        assertThat(s.accumulatedMs).isEqualTo(60_000)
    }

    @Test
    fun `Idle 时 pause 返回 false 且不写脏数据`() = runTest {
        setUp()
        val r = repo(this)
        assertThat(r.pause()).isFalse()
        // 默认值就是 false / -1，不能因为一次无效调用被写成 true
        assertThat(settings.read(PrefKey.SessionPaused)).isFalse()
        assertThat(settings.read(PrefKey.ActiveSessionCategoryId))
            .isEqualTo(SessionRepository.NO_SESSION)
    }

    // ==================================================================
    // ③ tick → 到站
    // ==================================================================


    @Test
    fun `自然到站先写 COMPLETED 并停止屏蔽且界面仍是 Arrived`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)
        val id = (r.state.value as SessionState.Running).sessionId
        advanceWithTicks(r, (min45 / 1_000L).toInt() - 1)
        mono.advance(1_000L)
        assertThat(r.tick()).isTrue()
        assertThat(r.state.value).isInstanceOf(SessionState.Arrived::class.java)
        assertThat(historyDao.ongoing()).isNull()
        assertThat(historyDao.findById(id)!!.status).isEqualTo("COMPLETED")
        assertThat(settings.read(PrefKey.ActiveSessionCategoryId)).isEqualTo(SessionRepository.NO_SESSION)
        assertThat(settings.read(PrefKey.SessionPaused)).isFalse()
    }

    @Test
    fun `未到站时 tick 返回 false`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)
        mono.advance(1_000)
        assertThat(r.tick()).isFalse()
    }

    // ==================================================================
    // ④ settle —— 写历史
    // ==================================================================

    @Test
    fun `R158 整分钟检查点只写新分钟且暂停不累计`() = runTest {
        setUp()
        val r = repo(this)
        val plan = r.preparePlan("PKX", "KIX").copy(seat = "12A")
        r.startPlannedFlight(plan)
        val id = (r.state.value as SessionState.Running).sessionId
        advanceWithTicks(r, 59)
        assertThat(historyDao.findById(id)!!.actualFocusMinutes).isEqualTo(0)
        r.tick()
        mono.advance(1_000L)
        r.tick()
        val checkpoint = historyDao.findById(id)!!
        assertThat(checkpoint.actualFocusMinutes).isEqualTo(1)
        assertThat(checkpoint.distanceKm).isEqualTo(plan.distanceKm)
        assertThat(checkpoint.seat).isEqualTo("12A")
        val writes = historyDao.writeCount
        repeat(5) { r.tick() }
        assertThat(historyDao.writeCount).isEqualTo(writes)
        r.pause()
        mono.advance(600_000L)
        r.tick()
        assertThat(historyDao.findById(id)!!.actualFocusMinutes).isEqualTo(1)
        r.resume()
        advanceWithTicks(r, 60)
        assertThat(historyDao.findById(id)!!.actualFocusMinutes).isEqualTo(2)
    }

    @Test
    fun `R158 取消保留最后保存的整分钟而不四舍五入重算`() = runTest {
        setUp()
        val r = repo(this)
        val plan = r.preparePlan("PKX", "KIX")
        r.startPlannedFlight(plan)
        advanceWithTicks(r, 70)
        val stored = historyDao.ongoing()!!
        assertThat(stored.actualFocusMinutes).isEqualTo(1)
        mono.advance(49_999L)
        r.abandon()
        val cancelled = r.settle()!!
        assertThat(cancelled.status).isEqualTo(SessionRepository.STATUS_CANCELLED)
        assertThat(cancelled.actualFocusMinutes).isEqualTo(stored.actualFocusMinutes)
        assertThat(cancelled.distanceKm).isEqualTo(plan.distanceKm)
    }

    @Test
    fun `R158 自然到达先保存终态且下机不重复写入或发今日通知`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, 60_000L, "PKX", "KIX")
        val id = (r.state.value as SessionState.Running).sessionId
        val distance = historyDao.findById(id)!!.distanceKm
        advanceWithTicks(r, 60)
        assertThat(r.state.value).isInstanceOf(SessionState.Arrived::class.java)
        val landed = historyDao.findById(id)!!
        assertThat(landed.status).isEqualTo(SessionRepository.STATUS_COMPLETED)
        assertThat(landed.actualFocusMinutes).isEqualTo(1)
        assertThat(landed.distanceKm).isEqualTo(distance)
        assertThat(historyDao.ongoing()).isNull()
        assertThat(settings.read(PrefKey.ActiveSessionCategoryId)).isEqualTo(SessionRepository.NO_SESSION)
        assertThat(notifier.count).isEqualTo(1)
        val writes = historyDao.writeCount
        repeat(3) { r.tick() }
        assertThat(r.settle()).isEqualTo(landed)
        assertThat(historyDao.writeCount).isEqualTo(writes)
        assertThat(notifier.count).isEqualTo(1)
        assertThat(r.state.value).isEqualTo(SessionState.Idle)
    }

    @Test
    fun `R158 到站状态只在完成或取消记录提交后才向界面发布`() = runTest {
        for (cancelled in listOf(false, true)) {
            setUp()
            val r = repo(this)
            val actualDao = historyDao
            val seen = mutableListOf<String?>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                r.state.collect { state ->
                    if (state is SessionState.Arrived) seen += actualDao.store[state.sessionId]?.status
                }
            }
            try {
                r.start(3, 60_000L)
                if (cancelled) r.abandon() else advanceWithTicks(r, 60)
                assertThat(seen).containsExactly(if (cancelled) "CANCELLED" else "COMPLETED")
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun `R158 恢复以已存专注检查点为准而不把关机墙钟算成飞行`() = runTest {
        setUp()
        val initial = repo(this)
        initial.start(3, min45, "PKX", "KIX")
        val row = historyDao.ongoing()!!
        val historicalStart = System.currentTimeMillis() - 7 * 24 * 3_600_000L
        historyDao.upsert(row.copy(actualFocusMinutes = 3,
            startTimeMillis = historicalStart))
        controller = SessionController(mono, wallClock = { wallNow })
        val restored = repo(this)
        val state = restored.restore() as SessionState.Running
        assertThat(state.accumulatedMs).isEqualTo(3 * 60_000L)
        assertThat(state.remainingMs(mono.now)).isEqualTo(42 * 60_000L)
        assertThat(restored.currentRoute().distanceKm).isEqualTo(row.distanceKm)
        assertThat(historyDao.ongoing()!!.startTimeMillis)
            .isEqualTo(historicalStart)
    }

    @Test
    fun `R158 到达写库失败时停止屏蔽并保留可重试的到站记录`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, 60_000L)
        val id = (r.state.value as SessionState.Running).sessionId
        historyDao.failOnWrite = true
        advanceWithTicks(r, 60)
        assertThat(r.state.value).isInstanceOf(SessionState.Arrived::class.java)
        assertThat(settings.read(PrefKey.ActiveSessionCategoryId)).isEqualTo(SessionRepository.NO_SESSION)
        assertThat(historyDao.findById(id)!!.status).isEqualTo(SessionRepository.STATUS_ONGOING)
        assertThat(r.settle()).isNull()
        historyDao.failOnWrite = false
        assertThat(r.settle()!!.status).isEqualTo(SessionRepository.STATUS_COMPLETED)
        assertThat(historyDao.store).hasSize(1)
        assertThat(r.state.value).isEqualTo(SessionState.Idle)
    }

    @Test
    fun `settle 写入 COMPLETED 记录并清空命令通道`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45, "PKX", "KIX")
        advanceWithTicks(r, (min45 / 1_000L).toInt())

        val record = r.settle()

        assertThat(record).isNotNull()
        assertThat(record!!.status).isEqualTo("COMPLETED")
        assertThat(record.actualFocusMinutes).isEqualTo(45)
        assertThat(record.endTimeMillis).isNotNull()
        assertThat(historyDao.store[record.id]?.status).isEqualTo("COMPLETED")
        // 命令通道清空 → 屏蔽引擎停止
        assertThat(settings.read(PrefKey.ActiveSessionCategoryId))
            .isEqualTo(SessionRepository.NO_SESSION)
        assertThat(r.state.value).isEqualTo(SessionState.Idle)
    }

    @Test
    fun `settle 保留 start 时冻结的航线，不重新解析机场`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45, "PKX", "KIX")
        advanceWithTicks(r, (min45 / 1_000L).toInt())

        val record = r.settle()!!
        assertThat(record.departureAirportCode).isEqualTo("PKX")
        assertThat(record.destinationCity).isEqualTo("大阪")
    }




    @Test
    fun `放弃写 CANCELLED 且 actualFocusMinutes 是真实时长`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)
        advanceWithTicks(r, 12 * 60)

        assertThat(r.abandon()).isTrue()
        val record = r.settle()!!

        assertThat(record.status).isEqualTo("CANCELLED")
        assertThat(record.actualFocusMinutes).isEqualTo(12)
        // 计划时长与距离不变
        assertThat(record.durationMinutes).isEqualTo(45)
        assertThat(record.distanceKm).isWithin(1e-9).of(600.0)
    }

    @Test
    fun `settle 不在 Arrived 状态时返回 null`() = runTest {
        setUp()
        val r = repo(this)
        assertThat(r.settle()).isNull()
    }






    @Test
    fun `写库失败保留 Arrived 供重试但结束的飞行不再屏蔽`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)
        historyDao.failOnWrite = true
        advanceWithTicks(r, (min45 / 1_000L).toInt())
        assertThat(r.settle()).isNull()
        assertThat(r.state.value).isInstanceOf(SessionState.Arrived::class.java)
        assertThat(settings.read(PrefKey.ActiveSessionCategoryId)).isEqualTo(SessionRepository.NO_SESSION)
        historyDao.failOnWrite = false
        assertThat(r.settle()).isNotNull()
        assertThat(r.state.value).isEqualTo(SessionState.Idle)
    }


    @Test
    fun `restore 从保存的专注分钟恢复 Running 并重置旧暂停命令`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45, "PKX", "KIX")
        advanceWithTicks(r, 10 * 60 + 20)
        r.pause()
        assertThat(settings.read(PrefKey.SessionPaused)).isTrue()
        val ongoing = historyDao.ongoing()!!
        controller = SessionController(mono, wallClock = { wallNow })
        val resumed = repo(this).restore() as SessionState.Running
        assertThat(resumed.categoryId).isEqualTo(3)
        assertThat(resumed.accumulatedMs).isEqualTo(10 * 60_000L)
        assertThat(resumed.sessionId).isEqualTo(ongoing.id)
        assertThat(settings.read(PrefKey.SessionPaused)).isFalse()
    }


    @Test
    fun `restore 已保存的专注分钟达到计划值时提交完成并进入 Arrived`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)
        val ongoing = historyDao.ongoing()!!
        historyDao.upsert(ongoing.copy(actualFocusMinutes = 45))
        controller = SessionController(mono, wallClock = { wallNow })
        val restored = repo(this).restore() as SessionState.Arrived
        assertThat(restored.abandoned).isFalse()
        assertThat(restored.totalElapsedMs).isEqualTo(min45)
        assertThat(historyDao.findById(ongoing.id)!!.status).isEqualTo("COMPLETED")
        assertThat(historyDao.ongoing()).isNull()
    }

    @Test
    fun `restore 清理孤立的屏蔽标记`() = runTest {
        setUp()
        // 直接造一个「没有 ONGOING 但 DataStore 标着在飞」的脏状态
        settings.writeAll(
            listOf(
                PrefKey.ActiveSessionCategoryId to 7L,
                PrefKey.SessionPaused to false,
            ),
        )
        val r = repo(this)

        r.restore()

        assertThat(settings.read(PrefKey.ActiveSessionCategoryId))
            .isEqualTo(SessionRepository.NO_SESSION)
        assertThat(r.state.value).isEqualTo(SessionState.Idle)
    }

    @Test
    fun `restore 在一切正常且无会话时是幂等的`() = runTest {
        setUp()
        val r = repo(this)
        repeat(3) { r.restore() }
        assertThat(r.state.value).isEqualTo(SessionState.Idle)
        assertThat(settings.read(PrefKey.ActiveSessionCategoryId))
            .isEqualTo(SessionRepository.NO_SESSION)
    }

    // ==================================================================
    // ⑥ 小组件镜像
    // ==================================================================

    @Test
    fun `start 推 Flying 镜像，settle 推 Idle`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45, "PKX", "KIX")

        val flying = push.last
        assertThat(flying).isInstanceOf(WidgetMirror.Flying::class.java)
        assertThat((flying as WidgetMirror.Flying).totalMs).isEqualTo(min45)
        assertThat(flying.destinationCode).isEqualTo("KIX")
        assertThat(flying.scenarioLabel).isEqualTo("大阪")

        advanceWithTicks(r, (min45 / 1_000L).toInt())
        r.settle()

        assertThat(push.last).isEqualTo(WidgetMirror.Idle)
    }

    @Test
    fun `pause 推 Paused 镜像且携带已飞时长`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)
        advanceWithTicks(r, 90)
        r.pause()

        val paused = push.last as WidgetMirror.Paused
        assertThat(paused.elapsedMs).isEqualTo(90_000)
    }





    @Test
    fun `tick 只在剩余分钟数变化时才推小组件`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)
        val afterStart = push.history.size


        //   任何真实剩余分钟都「不等于 -1」，于是第一次 tick 必然推一次。
        //   这是**刻意的**：进程重启后小组件可能停在旧状态，
        //   第一次 tick 兜底纠正它。此后才进入节流。
        mono.advance(100); r.tick()
        val afterFirstTick = push.history.size
        assertThat(afterFirstTick).isEqualTo(afterStart + 1)

        repeat(9) { mono.advance(100); r.tick() }   // 同一分钟内再 tick 9 次
        assertThat(push.history.size).isEqualTo(afterFirstTick)

        advanceWithTicks(r, 59)                     // 已接受1秒，再采样59秒跨整分钟
        assertThat(push.history.size).isEqualTo(afterFirstTick + 1)
    }

    // ==================================================================
    // 今日汇总信号（四个事件点里 service-focus 负责的两个）
    // ==================================================================















    @Test
    fun `终态保存后发一次今日信号而下机不重复发送`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)
        advanceWithTicks(r, (min45 / 1_000L).toInt())
        assertThat(notifier.count).isEqualTo(1)
        assertThat(push.todayChanged).isEqualTo(1)
        r.settle()
        assertThat(notifier.count).isEqualTo(1)
        assertThat(push.todayChanged).isEqualTo(1)
    }


    @Test
    fun `终态写库失败时不发今日信号而成功重试后只发一次`() = runTest {
        setUp()
        val r = repo(this)
        r.start(3, min45)
        historyDao.failOnWrite = true
        advanceWithTicks(r, (min45 / 1_000L).toInt())
        assertThat(r.settle()).isNull()
        assertThat(notifier.count).isEqualTo(0)
        assertThat(push.todayChanged).isEqualTo(0)
        historyDao.failOnWrite = false
        assertThat(r.settle()).isNotNull()
        assertThat(notifier.count).isEqualTo(1)
        assertThat(push.todayChanged).isEqualTo(1)
    }

    @Test
    fun `restore 之后发一次今日信号`() = runTest {
        setUp()
        repo(this).start(3, min45, "PKX", "KIX")

        //   所以要把库里的 startTimeMillis 改成「刚刚」，而不是推测试假时钟。
        val ongoing = historyDao.ongoing()!!
        historyDao.store[ongoing.id] =
            ongoing.copy(startTimeMillis = System.currentTimeMillis() - 10 * 60_000L)

        controller = SessionController(
            monotonic = mono,
            wallClock = { wallNow },
            idGenerator = { "new-${nextId++}" },
        )
        val fresh = RecordingNotifier()
        notifier = fresh
        val r = repo(this)
        r.restore()

        //   不清零的话这条断言会变成"≥1"就过 —— 那就什么都没测到。
        assertThat(fresh.count).isEqualTo(1)
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private fun pkx() = AirportEntity(
        id = 1, iataCode = "PKX", icaoCode = "ZBAD",
        name = "北京大兴国际机场", city = "北京", country = "China",
        latitude = 39.5098, longitude = 116.4105, elevation = 98,
        continent = "AS", type = "large_airport", popularity = 100,
        isPremiumOnly = false,
    )

    private fun kix() = AirportEntity(
        id = 2, iataCode = "KIX", icaoCode = "RJBB",
        name = "关西国际机场", city = "大阪", country = "Japan",
        latitude = 34.4347, longitude = 135.2444, elevation = 17,
        continent = "AS", type = "large_airport", popularity = 90,
        isPremiumOnly = false,
    )
}
