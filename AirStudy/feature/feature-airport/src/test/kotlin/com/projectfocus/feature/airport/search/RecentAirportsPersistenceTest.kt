package com.projectfocus.feature.airport.search

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.datastore.PrefKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder


































@OptIn(ExperimentalCoroutinesApi::class)
class RecentAirportsPersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val stores = mutableListOf<TestSettingsStore>()

    private fun newStore(): TestSettingsStore =
        TestSettingsStore(tempFolder.newFolder()).also { stores += it }

    @Before
    fun setUp() {

        //
        //   本文件全部用 `runBlocking`（原因见类注释：DataStore 的 IO 是真实的，
        //   绑不进虚拟时钟）。而 `StandardTestDispatcher` 会把 ViewModel 里的
        //   每一个 `launch` / `collect` **投递成一个待执行任务**，
        //   要有人调用 `advanceUntilIdle` 才跑。
        //
        //   而 `runBlocking` 在**当前线程**上阻塞等待 —— 没人去推那个队列。
        //   结果：`vm.state` 永远停在 initialValue `Loading`，
        //   探针实测 `before=Loading / afterEnsure=Loading`，
        //   看起来像"索引没建好"，其实是"协程从未被执行"。
        //
        //   `Unconfined` 让每个 `launch` 就地执行，`runBlocking` 才有意义。
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stores.forEach { it.close() }
        stores.clear()
    }

    // ── 夹具 ────────────────────────────────────────────────

    private fun entity(id: Long, iata: String, city: String, cityZh: String? = null) = AirportEntity(
        id = id, iataCode = iata, icaoCode = "", name = "$city Airport", city = city,
        country = "CN", latitude = 0.0, longitude = 0.0, elevation = 0,
        continent = "AS", type = "large_airport", popularity = 50,
        isPremiumOnly = false, nameZh = null, cityZh = cityZh,
    )

    private fun sampleRows() = listOf(
        entity(1, "PEK", "Beijing", "北京"),
        entity(2, "PVG", "Shanghai", "上海"),
        entity(3, "HND", "Tokyo", "东京"),
    )

    private class FakeDao(private val rows: List<AirportEntity>) : AirportDao() {
        override fun observeAll(hasPremium: Boolean): Flow<List<AirportEntity>> =
            MutableStateFlow(rows).map { it }
        override suspend fun getAll(hasPremium: Boolean) = rows
        override suspend fun findByCode(code: String) = rows.firstOrNull { it.iataCode == code }
        override suspend fun byCountry(country: String) = rows.filter { it.country == country }
        override suspend fun topByPopularity(limit: Int) =
            rows.sortedByDescending { it.popularity }.take(limit)
        override suspend fun random(limit: Int) = rows.take(limit)
        override suspend fun count(hasPremium: Boolean) = rows.size
        override suspend fun search(query: String, limit: Int, hasPremium: Boolean) =
            sourceSqlRows(rows, query, limit, hasPremium)
        override suspend fun upsertAll(airports: List<AirportEntity>) = Unit
        override suspend fun upsert(airport: AirportEntity) = Unit
        override suspend fun deleteAll() = Unit
    }









    private suspend fun awaitTrue(reason: String, cond: suspend () -> Boolean) {
        withTimeout(3_000) {
            while (!cond()) {

                //   `runTest` 里的 `delay` 走的是**虚拟时钟**，不会真的等 5ms ——
                //   而且它会让 `runTest` 把时间推进到"下一个任务"，

                //   → 用 `yield` 让出执行权，再靠 `withTimeout` 的真实时间兜底。
                //     实测在 CI 上最多 300ms 就能满足（DataStore 冷启动建文件）。
                kotlinx.coroutines.yield()
                kotlinx.coroutines.delay(1)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // 1. 记录 → 落盘 → 内存刷新
    // ══════════════════════════════════════════════════════

    @Test
    fun `记录后写进偏好`(): Unit = kotlinx.coroutines.runBlocking {
        val store = newStore()
        val vm = AirportSearchViewModel(
            dao = FakeDao(sampleRows()),
            settings = store.repository,
            pinyin = PinyinProvider.NONE,
            computeDispatcher = Dispatchers.Unconfined,
            frameDebounceMs = 0L,
        )
        vm.recordRecent(1)

        val raw = store.repository.read(PrefKey.RecentAirportIds)
        assertThat(RecentAirportsCodec.decode(raw)).containsExactly(1L)
    }

    @Test
    fun `连续记录时最近的排在最前`(): Unit = kotlinx.coroutines.runBlocking {
        val store = newStore()
        val vm = AirportSearchViewModel(
            dao = FakeDao(sampleRows()), settings = store.repository,
            pinyin = PinyinProvider.NONE, computeDispatcher = Dispatchers.Unconfined,
            frameDebounceMs = 0L,
        )
        vm.recordRecent(1)
        vm.recordRecent(2)
        vm.recordRecent(3)
        vm.recordRecent(1)   // 再用一次北京

        val raw = store.repository.read(PrefKey.RecentAirportIds)

        assertThat(RecentAirportsCodec.decode(raw)).containsExactly(1L, 3L, 2L).inOrder()
    }

    @Test
    fun `非法 id 被忽略且不写盘`(): Unit = kotlinx.coroutines.runBlocking {
        val store = newStore()
        val vm = AirportSearchViewModel(
            dao = FakeDao(sampleRows()), settings = store.repository,
            pinyin = PinyinProvider.NONE, computeDispatcher = Dispatchers.Unconfined,
            frameDebounceMs = 0L,
        )

        //   放进去等于往最近列表里塞一个永远查不到的 id。
        vm.recordRecent(0)
        vm.recordRecent(-3)
        assertThat(store.repository.read(PrefKey.RecentAirportIds)).isEmpty()
    }

    // ══════════════════════════════════════════════════════
    // 2. 建索引时自动载入
    // ══════════════════════════════════════════════════════

    @Test
    fun `建完索引后自动把最近去过载入内存`(): Unit = kotlinx.coroutines.runBlocking {
        val store = newStore()

        store.repository.write(
            PrefKey.RecentAirportIds,
            RecentAirportsCodec.encode(listOf(2L)),
        )

        val vm = AirportSearchViewModel(
            dao = FakeDao(sampleRows()), settings = store.repository,
            pinyin = PinyinProvider.NONE, computeDispatcher = Dispatchers.Unconfined,
            frameDebounceMs = 0L,
        )

        //   **没有订阅者时上游根本不启动**，会一直停在 initialValue。
        //   生产里订阅者永远是 Fragment 的 `repeatOnLifecycle(STARTED)`；
        //   测试里必须自己起一个，否则 `vm.state.value` 永远是 Loading。

        //     看起来像"索引建好了但状态没更新"，其实是"上游从来没跑过"。）
        val collector = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Unconfined) {
            vm.state.collect { }
        }


        //   在 init 里读的时候 airports 还是空的，只能得到空列表。
        //   必须在 ensureIndex 完成后才载入。
        kotlinx.coroutines.withTimeout(5_000) { vm.ensureIndex() }

        //   固定 200ms 在 CI 上会偶发失败（典型的 flaky）。
        awaitTrue("Idle 应带上最近去过") {
            (vm.state.value as? AirportSearchState.Idle)?.recent?.size == 1
        }
        val s = vm.state.value as AirportSearchState.Idle
        assertThat(s.recent.single().id).isEqualTo(2L)
        assertThat(s.recent.single().cityZh).isEqualTo("上海")
        collector.cancel()
    }

    @Test
    fun `最近的机场在库里不存在时被剔除并自愈`(): Unit = kotlinx.coroutines.runBlocking {
        val store = newStore()

        store.repository.write(
            PrefKey.RecentAirportIds,
            RecentAirportsCodec.encode(listOf(1L, 99L)),
        )

        val vm = AirportSearchViewModel(
            dao = FakeDao(sampleRows()), settings = store.repository,
            pinyin = PinyinProvider.NONE, computeDispatcher = Dispatchers.Unconfined,
            frameDebounceMs = 0L,
        )
        kotlinx.coroutines.withTimeout(5_000) { vm.ensureIndex() }

        awaitTrue("失效 id 应被摘掉") {
            RecentAirportsCodec.decode(store.repository.read(PrefKey.RecentAirportIds))
                .let { it == listOf(1L) }
        }
        val raw = store.repository.read(PrefKey.RecentAirportIds)

        assertThat(RecentAirportsCodec.decode(raw)).containsExactly(1L)
    }

    @Test
    fun `机场库为空时不写盘只保持现状`(): Unit = kotlinx.coroutines.runBlocking {
        val store = newStore()
        store.repository.write(
            PrefKey.RecentAirportIds,
            RecentAirportsCodec.encode(listOf(1L, 2L)),
        )
        val vm = AirportSearchViewModel(
            dao = FakeDao(emptyList()), settings = store.repository,
            pinyin = PinyinProvider.NONE, computeDispatcher = Dispatchers.Unconfined,
            frameDebounceMs = 0L,
        )
        kotlinx.coroutines.withTimeout(5_000) { vm.ensureIndex() }


        //   那会在"首次启动、机场包还没导入"时把用户半年的历史全抹掉。
        val raw = store.repository.read(PrefKey.RecentAirportIds)
        assertThat(RecentAirportsCodec.decode(raw)).containsExactly(1L, 2L).inOrder()
    }
}
