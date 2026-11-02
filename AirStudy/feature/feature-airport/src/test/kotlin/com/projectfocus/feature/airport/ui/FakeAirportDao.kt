package com.projectfocus.feature.airport.ui

import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.feature.airport.search.AirportSearchViewModel
import com.projectfocus.feature.airport.search.PinyinProvider
import com.projectfocus.feature.airport.search.sourceSqlRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map














class FakeAirportDao(rows: List<AirportEntity> = emptyList()) : AirportDao() {

    val rows = MutableStateFlow(rows)

    /** 记录 `getAll` 被调用了几次 —— 用于验证"索引只建一次" */
    var getAllCalls = 0
        private set

    override suspend fun getAll(hasPremium: Boolean): List<AirportEntity> {
        getAllCalls++
        return rows.value.filter { hasPremium || !it.isPremiumOnly }
    }

    override fun observeAll(hasPremium: Boolean): Flow<List<AirportEntity>> = rows.map { list -> list.filter { hasPremium || !it.isPremiumOnly } }

    override suspend fun findByCode(code: String): AirportEntity? =
        rows.value.firstOrNull { it.iataCode == code || it.icaoCode == code }

    override suspend fun byCountry(country: String): List<AirportEntity> =
        rows.value.filter { it.country == country }

    override suspend fun topByPopularity(limit: Int): List<AirportEntity> =
        rows.value.sortedByDescending { it.popularity }.take(limit)

    override suspend fun random(limit: Int): List<AirportEntity> = rows.value.take(limit)

    override suspend fun count(hasPremium: Boolean): Int = rows.value.size

    override suspend fun search(query: String, limit: Int, hasPremium: Boolean): List<AirportEntity> =
        sourceSqlRows(rows.value, query, limit, hasPremium)

    override suspend fun upsertAll(airports: List<AirportEntity>) {
        rows.value = airports
    }

    override suspend fun upsert(airport: AirportEntity) {
        rows.value = rows.value.filterNot { it.id == airport.id } + airport
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}



















class TestableAirportFragment(
    private val dao: AirportDao,






    private val settings: com.projectfocus.core.datastore.SettingsRepository,
) : AirportSearchFragment() {

    override fun createViewModel(): AirportSearchViewModel =

        //

        //   10 条里有 6 条报 `expected 想去哪里 but was 正在准备机场数据`。
        //
        //   根因是 Robolectric 的两套时钟：
        //   ```
        //   索引在 Default 线程池上建完
        //        ↓  （要投回 Main 才能让 stateIn 重算）
        //   Main Looper —— Robolectric 里的 Main 是 PAUSED 的
        //        ↓
        //   idle() 推完一批 message，但 Default 上的任务**还没回投**
        //        ↓
        //   断言时界面仍是 Loading
        //   ```
        //   `idle()` 是**同步**的，它推一次就返回，不会等真实线程池。
        //   而 Default 上任务的完成时间不受控 → 无论 idle 几次都可能不够。
        //
        //   `Unconfined` 让 `withContext(computeDispatcher)` 在**调用线程上
        //   立即执行**（这里是测试主线程），索引建完、tick 写入都同步完成，
        //   之后一次 `idle()` 就能把 render 推进去。
        //

        //     **它不只让单元测试失去时序控制，也让 Robolectric 渲染测试
        //       变成靠运气。** 而渲染测试连 advanceUntilIdle 都没有，
        //       连"等一等"的手段都不存在。
        AirportSearchViewModel(
            dao = dao,
            settings = settings,
            pinyin = PinyinProvider.NONE,
            computeDispatcher = Dispatchers.Unconfined,

            //   本文件测的是"输入 → 界面"这条因果链，时序由
            //   AirportSearchViewModelTest 精确验证。
            frameDebounceMs = 0L,
        )
}
