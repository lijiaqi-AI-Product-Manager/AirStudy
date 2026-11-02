package com.projectfocus.feature.airport.search

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.database.entity.AirportEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder


@OptIn(ExperimentalCoroutinesApi::class)
class AirportSearchViewModelTest {

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Before
    fun setUp() {

    }













    /**
     * 每个用例一个独立的临时目录 —— 用例之间的偏好文件必须隔离，
     * 否则"上一个用例写了最近机场"会污染下一个用例。
     */
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val stores = mutableListOf<TestSettingsStore>()

    private fun testStore(): TestSettingsStore =
        TestSettingsStore(tempFolder.newFolder()).also { stores += it }

    @After
    fun closeStores() {

        //   删不掉（文件被占用），在 Linux 上是句柄泄漏。
        stores.forEach { it.close() }
        stores.clear()
    }

    private fun TestScope.newVm(
        rows: List<AirportEntity> = sampleRows(),
        pinyin: PinyinProvider = PinyinProvider.NONE,
        dao: AirportDao = FakeAirportDao(rows),

        settings: com.projectfocus.core.datastore.SettingsRepository = testStore().repository,
    ): AirportSearchViewModel {

        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        return AirportSearchViewModel(dao, settings, pinyin, testDispatcher)
    }

    /** 收流（Unconfined 才能"所见即所得"） */
    private fun TestScope.collect(target: AirportSearchViewModel): MutableList<AirportSearchState> {
        val sink = mutableListOf<AirportSearchState>()
        backgroundScope.launch(Dispatchers.Unconfined) { target.state.collect { sink += it } }
        advanceUntilIdle()
        return sink
    }

    // ══════════════════════════════════════════════════════
    // 成因 A / B
    // ══════════════════════════════════════════════════════

    @Test
    fun `还没建索引时是 Loading`() = runTest {
        val vm = newVm()
        collect(vm)
        assertThat(vm.state.value).isEqualTo(AirportSearchState.Loading)
    }

    @Test
    fun `索引就绪后自动从 Loading 变成 Idle`() = runTest {

        //   如果实现里用 `keywordFlow.value = keywordFlow.value` 强制触发，
        //   StateFlow 会去重 → 这条会红，且报错是
        //   `expected Idle but was Loading` —— 一眼看出"没触发重算"。
        val vm = newVm()
        collect(vm)
        assertThat(vm.state.value).isEqualTo(AirportSearchState.Loading)

        vm.ensureIndex()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(AirportSearchState.Idle::class.java)
    }

    @Test
    fun `ensureIndex 第一次返回 true 第二次返回 false`() = runTest {
        val vm = newVm()
        collect(vm)
        assertThat(vm.ensureIndex()).isTrue()
        advanceUntilIdle()

        //   不该重建索引（83k 条 = 400ms 白等）。
        assertThat(vm.ensureIndex()).isFalse()
        advanceUntilIdle()
    }

    @Test
    fun `空库时是 Empty 而不是 NoResult`() = runTest {
        val vm = newVm(rows = emptyList())
        collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(AirportSearchState.Empty)
    }

    @Test
    fun `空库时 ensureIndex 返回 false`() = runTest {
        val vm = newVm(rows = emptyList())
        collect(vm)
        assertThat(vm.ensureIndex()).isFalse()
    }

    // ══════════════════════════════════════════════════════
    // 搜索
    // ══════════════════════════════════════════════════════

    @Test
    fun `索引就绪后能搜到结果`() = runTest {
        val vm = newVm()
        collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()

        vm.onKeywordChange("PEK")
        advanceUntilIdle()

        val s = vm.state.value
        assertThat(s).isInstanceOf(AirportSearchState.Content::class.java)
        val c = s as AirportSearchState.Content
        assertThat(c.groups.flatMap { it.hits }.map { it.airport.iataCode }).contains("PEK")
    }

    @Test
    fun `空关键词回到 Idle`() = runTest {
        val vm = newVm()
        collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()

        vm.onKeywordChange("PEK")
        advanceUntilIdle()
        vm.clearKeyword()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(AirportSearchState.Idle::class.java)
    }

    @Test
    fun `关键词超长被截断`() = runTest {
        val vm = newVm()
        collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()

        // 粘一整段文章
        vm.onKeywordChange("北".repeat(500))
        advanceUntilIdle()

        // 不崩、不误命中（32 个连续"北"不在任何字段里）
        assertThat(vm.state.value).isInstanceOf(AirportSearchState.NoResult::class.java)
    }

    @Test
    fun `单字符是 TooShort`() = runTest {
        val vm = newVm()
        collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()

        vm.onKeywordChange("P")
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(AirportSearchState.TooShort::class.java)
    }

    @Test
    fun `文本搜索把全角输入原样传给SQL而不伪称归一化成功`() = runTest {
        val dao = RecordingSearchDao(sampleRows())
        val vm = newVm(dao = dao)
        collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()

        vm.onKeywordChange("ＰＥＫ")
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(AirportSearchState.NoResult("ＰＥＫ", triedNormalization = false))
        assertThat(dao.calls.single().first).isEqualTo("ＰＥＫ")
    }

    @Test
    fun `文本查询实际消费DAO顺序而不把ICAO精确匹配重新提到前面`() = runTest {
        val prefix = entity(10, "ABC", "XP1", "Other", "Else").copy(popularity = 1)
        val name = entity(11, "DEF", "XP2", "AB Airport", "Else").copy(popularity = 10)
        val icao = entity(12, "GHI", "AB", "Other", "Else").copy(popularity = 999)
        val ordered = listOf(prefix, name, icao)
        val models = ordered.map { it.toModel() }
        assertThat(AirportSearchIndex.build(models).search("AB", models).first().airport.id).isEqualTo(12L)
        val dao = RecordingSearchDao(ordered, ordered)
        val vm = newVm(dao = dao)
        collect(vm)
        vm.ensureIndex()
        vm.onKeywordChange("AB")
        advanceUntilIdle()

        val state = vm.state.value as AirportSearchState.Content
        assertThat(state.groups.flatMap { it.hits }.map { it.airport.id }).containsExactly(10L, 11L, 12L).inOrder()
        assertThat(dao.calls).containsExactly(Triple("AB", AirportSearchProjector.RESULT_LIMIT + 1, false))
    }

    @Test
    fun `SQL绑定保留空格和原大小写且不查短输入`() = runTest {
        val dao = RecordingSearchDao(sampleRows())
        val vm = newVm(dao = dao)
        collect(vm)
        vm.ensureIndex()
        vm.onKeywordChange("P")
        advanceUntilIdle()
        assertThat(dao.calls).isEmpty()
        vm.onKeywordChange("  pEk  ")
        advanceUntilIdle()
        assertThat(dao.calls.single().first).isEqualTo("  pEk  ")
        assertThat(vm.state.value).isEqualTo(AirportSearchState.NoResult("  pEk  ", triedNormalization = false))
    }

    @Test
    fun `旧DAO结果在新输入尚未通过debounce时也不能覆盖界面`() = runTest {
        val old = entity(10, "ABC", "XAB", "AB Old", "Else")
        val latest = entity(11, "CDX", "XCD", "CD New", "Else")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = object : FakeAirportDao(listOf(old, latest)) {
            override suspend fun search(query: String, limit: Int, hasPremium: Boolean): List<AirportEntity> {
                if (query == "AB") {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    return listOf(old)
                }
                return listOf(latest)
            }
        }
        val vm = newVm(dao = dao)
        val sink = collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()
        vm.onKeywordChange("AB")
        advanceTimeBy(17)
        runCurrent()
        assertThat(started.isCompleted).isTrue()
        vm.onKeywordChange("CD")
        release.complete(Unit)
        runCurrent()
        assertThat(sink.filterIsInstance<AirportSearchState.Content>().flatMap { state ->
            state.groups.flatMap { it.hits }.map { it.airport.id }
        }).doesNotContain(old.id)
        advanceUntilIdle()
        assertThat((vm.state.value as AirportSearchState.Content).groups.flatMap { it.hits }.map { it.airport.id })
            .containsExactly(latest.id)
    }

    @Test
    fun `空库后仍能重试并执行真实文本DAO查询`() = runTest {
        val dao = RecordingSearchDao(emptyList())
        val vm = newVm(dao = dao)
        collect(vm)
        assertThat(vm.ensureIndex()).isFalse()
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(AirportSearchState.Empty)
        dao.upsertAll(sampleRows())
        assertThat(vm.ensureIndex()).isTrue()
        vm.onKeywordChange("PEK")
        advanceUntilIdle()
        assertThat(dao.calls.single().first).isEqualTo("PEK")
        assertThat((vm.state.value as AirportSearchState.Content).groups.flatMap { it.hits }.map { it.airport.iataCode })
            .containsExactly("PEK")
    }

    @Test
    fun `新文本查询取消旧DAO查询并继续发布最新结果`() = runTest {
        val old = entity(10, "ABC", "XAB", "AB Old", "Else")
        val latest = entity(11, "CDX", "XCD", "CD New", "Else")
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<List<AirportEntity>>()
        var wasCancelled = false
        val dao = object : FakeAirportDao(listOf(old, latest)) {
            override suspend fun search(query: String, limit: Int, hasPremium: Boolean): List<AirportEntity> {
                if (query != "AB") return listOf(latest)
                started.complete(Unit)
                try {
                    return gate.await()
                } catch (cancelled: CancellationException) {
                    wasCancelled = true
                    throw cancelled
                }
            }
        }
        val vm = newVm(dao = dao)
        val sink = collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()
        try {
            vm.onKeywordChange("AB")
            advanceTimeBy(17)
            runCurrent()
            assertThat(started.isCompleted).isTrue()
            vm.onKeywordChange("CD")
            advanceTimeBy(17)
            runCurrent()
            assertThat(wasCancelled).isTrue()
            assertThat((vm.state.value as AirportSearchState.Content).groups.flatMap { it.hits }.map { it.airport.id })
                .containsExactly(latest.id)
            assertThat(sink).doesNotContain(AirportSearchState.Empty)
        } finally {
            gate.complete(emptyList())
        }
    }

    @Test
    fun `推荐项在 Idle 态出现`() = runTest {
        val vm = newVm()
        collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()

        // 热门由 VM 自己按 popularity 取，应该有 4 条（样本里 4 条）
        val idle = vm.state.value as AirportSearchState.Idle
        assertThat(idle.popular).isNotEmpty()
        // 热度降序
        assertThat(idle.popular.map { it.popularity })
            .isInOrder(compareByDescending<Int> { it })
    }

    @Test
    fun `注入最近去过后 Idle 态带上它们`() = runTest {
        val vm = newVm()
        collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()

        val recent = listOf(
            com.projectfocus.core.model.airport.Airport(
                id = 1, iataCode = "PEK", icaoCode = "ZBAA", name = "Beijing Capital",
                city = "Beijing", country = "CN", latitude = 0.0, longitude = 0.0,
                elevation = null, continent = null,
                type = com.projectfocus.core.model.airport.AirportType.LARGE_AIRPORT,
                popularity = 0, isPremiumOnly = false,
            ),
        )
        vm.setRecent(recent)

        //   所以下一次关键词变化才会带上它。
        //   这在生产里是对的（Idle 态本来就只在关键词为空时出现，
        //   而用户从"最近去过"回来时关键词已清空）。
        //   这里显式触发一次：清空关键词。
        vm.clearKeyword()
        advanceUntilIdle()

        val idle = vm.state.value as AirportSearchState.Idle
        assertThat(idle.recent).hasSize(1)
        assertThat(idle.recent[0].iataCode).isEqualTo("PEK")
    }


    // ══════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════

    @Test
    fun `空库时索引不是 null 而是空索引`() = runTest {

        //

        //   tick 确实触发了重算，但 `project` 第一行是
        //   `if (index == null) return Loading` —— **算出来还是 Loading**，
        //   界面毫无变化。探针实测：
        //   ```
        //   PROBE ensureIndex=false
        //   PROBE after idle: value=Loading
        //   ```
        //
        //   修法：空库 = 一个**合法的空索引**。这样 `index == null`
        //   只表示"还没开始建"，语义唯一。
        val vm = newVm(rows = emptyList())
        collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(AirportSearchState.Empty)

        assertThat(vm.state.value).isNotEqualTo(AirportSearchState.Loading)
    }

    @Test
    fun `setRecent 会立刻反映到 Idle 态而不需要改关键词`() = runTest {

        //

        //   不是 `combine` 的输入 → 它变了 `map` 不重跑。
        //   而 `setRecent` 之后用户通常不会改关键词
        //   （关键词本来就是空，`clearKeyword()` 也会被 StateFlow 去重）——
        //   于是那一次投影永远不会重算。
        val vm = newVm()
        collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()
        assertThat((vm.state.value as AirportSearchState.Idle).recent).isEmpty()

        vm.setRecent(listOf(beijing))
        advanceUntilIdle()

        val idle = vm.state.value as AirportSearchState.Idle
        assertThat(idle.recent).hasSize(1)
        assertThat(idle.recent[0].iataCode).isEqualTo("PEK")
    }

    @Test
    fun `关键词变化在一帧之内合并成一次投影`() = runTest {

        //   同一帧内连打三个字母，只应产生**一次** Content 投影，
        //   而不是三次（"P"→TooShort、"PE"→NoResult、"PEK"→Content）。

        val vm = newVm()
        val sink = collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()
        val before = sink.size

        vm.onKeywordChange("P")
        vm.onKeywordChange("PE")
        vm.onKeywordChange("PEK")
        advanceUntilIdle()

        val after = sink.drop(before)

        assertThat(after).hasSize(1)
        assertThat(after.single()).isInstanceOf(AirportSearchState.Content::class.java)
    }

    @Test
    fun `clearKeyword 在关键词本来就空时不会触发重算`() = runTest {

        //   `MutableStateFlow<String>("")` 赋 `""` 用 equals 去重 → 不发射。
        //   这不是 bug，是 StateFlow 的正确语义；但**依赖它来"刷新"
        //   其它状态就是 bug**。这里把语义钉住，防止有人误用。
        val vm = newVm()
        val sink = collect(vm)
        vm.ensureIndex()
        advanceUntilIdle()
        val before = sink.size

        vm.clearKeyword()
        advanceUntilIdle()

        assertThat(sink.size).isEqualTo(before)
    }

    // ══════════════════════════════════════════════════════
    // 并发 / 幂等
    // ══════════════════════════════════════════════════════

    @Test
    fun `并发调用 ensureIndex 只建一次索引`() = runTest {

        //   不加锁的话会建两个 400ms 的索引，期间搜索全返回 Loading。
        val dao = CountingAirportDao(sampleRows())
        val vm = newVm(dao = dao)
        collect(vm)

        launch { vm.ensureIndex() }
        launch { vm.ensureIndex() }
        launch { vm.ensureIndex() }
        advanceUntilIdle()


        assertThat(dao.getAllCalls).isEqualTo(1)
    }

    // ══════════════════════════════════════════════════════
    // 实体映射
    // ══════════════════════════════════════════════════════

    @Test
    fun `空串码映射为 null`() {

        //   不转 null 的话 `displayCode` 会返回空串，列表里出现"空码"。
        val m = entity(iata = "", icao = "  ").toModel()
        assertThat(m.iataCode).isNull()
        assertThat(m.icaoCode).isNull()
    }

    @Test
    fun `未知机场类型降级为 UNKNOWN`() {
        val m = entity(type = "totally_unknown_type").toModel()
        assertThat(m.type).isEqualTo(com.projectfocus.core.model.airport.AirportType.UNKNOWN)
    }

    @Test
    fun `空城市名映射为 null`() {
        val m = entity(city = "").toModel()
        assertThat(m.city).isNull()
    }

    // ══════════════════════════════════════════════════════
    // 样本
    // ══════════════════════════════════════════════════════

    private fun entity(
        id: Long = 1,
        iata: String? = "PEK",
        icao: String? = "ZBAA",
        name: String = "Beijing Capital International",
        city: String? = "Beijing",
        type: String = "large_airport",
    ) = AirportEntity(
        id = id, iataCode = iata.orEmpty(), icaoCode = icao.orEmpty(),
        name = name, city = city.orEmpty(), country = "CN",
        latitude = 40.08, longitude = 116.58, elevation = 35,
        continent = "AS", type = type, popularity = 95, isPremiumOnly = false,
        nameZh = null, cityZh = null,
    )

    private val beijing = com.projectfocus.core.model.airport.Airport(
        id = 1, iataCode = "PEK", icaoCode = "ZBAA", name = "Beijing Capital",
        city = "Beijing", country = "CN", latitude = 0.0, longitude = 0.0,
        elevation = 0, continent = "AS",
        type = com.projectfocus.core.model.airport.AirportType.LARGE_AIRPORT,
        popularity = 95, isPremiumOnly = false,
    )

    private fun sampleRows() = listOf(
        entity(1, "PEK", "ZBAA", "Beijing Capital International", "Beijing"),
        entity(2, "PVG", "ZSPD", "Shanghai Pudong International", "Shanghai"),
        entity(3, "HND", "RJTT", "Tokyo Haneda", "Tokyo"),
        entity(4, "ICN", "RKSI", "Incheon International", "Seoul"),
    )

    private open class FakeAirportDao(rows: List<AirportEntity>) : AirportDao() {
        protected val rows = MutableStateFlow(rows)

        override fun observeAll(hasPremium: Boolean): Flow<List<AirportEntity>> = rows.map { list -> list.filter { hasPremium || !it.isPremiumOnly } }
        override suspend fun getAll(hasPremium: Boolean): List<AirportEntity> = rows.value.filter { hasPremium || !it.isPremiumOnly }
        override suspend fun findByCode(code: String): AirportEntity? =
            rows.value.firstOrNull { it.iataCode == code || it.icaoCode == code }
        override suspend fun byCountry(country: String) = rows.value.filter { it.country == country }
        override suspend fun topByPopularity(limit: Int) =
            rows.value.sortedByDescending { it.popularity }.take(limit)
        override suspend fun random(limit: Int) = rows.value.take(limit)
        override suspend fun count(hasPremium: Boolean) = rows.value.size
        override suspend fun search(query: String, limit: Int, hasPremium: Boolean) =
            sourceSqlRows(rows.value, query, limit, hasPremium)
        override suspend fun upsertAll(airports: List<AirportEntity>) { rows.value = airports }
        override suspend fun upsert(airport: AirportEntity) { rows.value = rows.value.filterNot { it.id == airport.id } + airport }
        override suspend fun deleteAll() { rows.value = emptyList() }
    }

    private class RecordingSearchDao(
        rows: List<AirportEntity>,
        private val ordered: List<AirportEntity>? = null,
    ) : FakeAirportDao(rows) {
        val calls = mutableListOf<Triple<String, Int, Boolean>>()
        override suspend fun search(query: String, limit: Int, hasPremium: Boolean): List<AirportEntity> {
            calls += Triple(query, limit, hasPremium)
            return ordered ?: super.search(query, limit, hasPremium)
        }
    }

    private class CountingAirportDao(rows: List<AirportEntity>) : FakeAirportDao(rows) {
        var getAllCalls = 0
            private set

        override suspend fun getAll(hasPremium: Boolean): List<AirportEntity> {
            getAllCalls++
            return super.getAll(hasPremium)
        }
    }
}
