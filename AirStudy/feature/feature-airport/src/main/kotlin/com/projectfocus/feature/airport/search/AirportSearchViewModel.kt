package com.projectfocus.feature.airport.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.database.dao.AirportDao
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.feature.airport.di.AirportCompute
import com.projectfocus.feature.airport.di.AirportFrameDebounceMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * 文本搜索由 DAO 的原SQL筛选和排序；ensureIndex 仅保留既有资源加载入口。
 * Idle、两字符门槛、16ms帧对齐、32字符上限与多取一条截断提示是本品适配器。
 * 这些入口策略未被声明为原包调用者行为；SQL绑定值不作归一化。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AirportSearchViewModel @Inject constructor(
    private val dao: AirportDao,

    private val settings: com.projectfocus.core.datastore.SettingsRepository,
    private val pinyin: PinyinProvider,

    @AirportCompute
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,

    @AirportFrameDebounceMs
    private val frameDebounceMs: Long = FRAME_MS,
) : ViewModel() {

    private data class KeywordRequest(val raw: String, val revision: Long)
    private val keywordFlow = MutableStateFlow(KeywordRequest("", 0))

    private val indexLock = Mutex()

    @Volatile
    private var index: AirportSearchIndex? = null

    @Volatile
    private var airports: List<Airport> = emptyList()

    private val recentAirports = MutableStateFlow<List<Airport>>(emptyList())

    @Volatile
    private var popularAirports: List<Airport> = emptyList()

    private val resourceTick = MutableStateFlow<Any?>(null)

    val state: StateFlow<AirportSearchState> = combine(
        keywordFlow.debounce { if (it.raw.isEmpty()) 0L else frameDebounceMs },
        recentAirports,
        resourceTick,
    ) { kw, recent, _ -> kw to recent }
        .transformLatest { (request, recent) ->
            try {
                val state = AirportSearchProjector.beforeSearch(
                    ready = index != null,
                    hasAirports = airports.isNotEmpty(),
                    keyword = request.raw,
                    recent = recent,
                    popular = popularAirports,
                ) ?: AirportSearchProjector.fromSql(
                    keyword = request.raw,
                    orderedAirports = dao.search(
                        query = request.raw,
                        limit = AirportSearchProjector.RESULT_LIMIT + 1,

                        hasPremium = false,
                    ).map { it.toModel() },
                )
                currentCoroutineContext().ensureActive()

                // 新输入即使仍在帧对齐等待中，也使旧结果失效。
                if (request == keywordFlow.value) emit(state)
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                Timber.w(error, "机场搜索失败，keyword=%s", request.raw)
                currentCoroutineContext().ensureActive()
                if (request == keywordFlow.value) emit(AirportSearchState.Empty)
            }
        }
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AirportSearchState.Loading)

    /** 载入Idle/最近所需资源；空库或失败后仍可重试，Mutex保证单次加载。 */
    suspend fun ensureIndex(): Boolean {
        if (index != null && airports.isNotEmpty()) return false
        return indexLock.withLock {
            if (index != null && airports.isNotEmpty()) return@withLock false
            try {

                val entities: List<AirportEntity> = withContext(computeDispatcher) {
                    dao.getAll(hasPremium = false)
                }
                if (entities.isEmpty()) {

                    Timber.w("机场库为空：需要先执行机场数据导入")
                    val empty = withContext(computeDispatcher) {
                        AirportSearchIndex.build(emptyList(), pinyin)
                    }
                    airports = emptyList()
                    popularAirports = emptyList()
                    index = empty
                    resourceTick.value = Any()
                    return@withLock false
                }

                val mapped = withContext(computeDispatcher) {
                    entities.map { it.toModel() }
                }
                val built = withContext(computeDispatcher) {
                    AirportSearchIndex.build(mapped, pinyin)
                }

                val popular = withContext(computeDispatcher) {
                    mapped.sortedByDescending { it.popularity }.take(POPULAR_COUNT)
                }

                airports = mapped
                index = built
                popularAirports = popular

                resourceTick.value = Any()

                loadRecentIntoMemory()
                true
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Exception) {
                Timber.e(t, "机场索引构建失败")
                airports = emptyList()
                popularAirports = emptyList()
                index = AirportSearchIndex.build(emptyList(), pinyin)
                resourceTick.value = Any()
                false
            }
        }
    }

    fun setRecent(list: List<Airport>) {
        recentAirports.value = list
    }

    /** 已确认的最近记录写盘跨越View销毁完成；其余取消继续传播。 */
    suspend fun recordRecent(id: Long) {
        if (id <= 0L) {
            Timber.w("recordRecent 收到非法 id=%d，已忽略", id)
            return
        }
        try {

            withContext(kotlinx.coroutines.NonCancellable) {
                val current = RecentAirportsCodec.decode(
                    settings.read(PrefKey.RecentAirportIds),
                )
                val updated = RecentAirportsCodec.promote(current, id)
                settings.write(
                    PrefKey.RecentAirportIds,
                    RecentAirportsCodec.encode(updated),
                )
            }

            loadRecentIntoMemory()
        } catch (t: kotlinx.coroutines.CancellationException) {

            throw t
        } catch (t: Throwable) {

            Timber.w(t, "记录最近机场失败（id=%d），忽略", id)
        }
    }

    private suspend fun loadRecentIntoMemory() {
        val ids = try {
            RecentAirportsCodec.decode(settings.read(PrefKey.RecentAirportIds))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Timber.w(t, "读取最近机场失败，保持现状")
            return
        }
        if (ids.isEmpty()) {
            recentAirports.value = emptyList()
            return
        }

        val pool = airports
        if (pool.isEmpty()) return

        val byId = pool.associateBy { it.id }
        val mapped = ArrayList<Airport>(ids.size)
        val missing = ArrayList<Long>(0)
        for (id in ids) {
            val a = byId[id]
            if (a == null) missing += id else mapped += a
        }
        recentAirports.value = mapped

        if (missing.isNotEmpty()) {
            runCatching {
                val cleaned = ids.filter { it in byId.keys }
                settings.write(
                    PrefKey.RecentAirportIds,
                    RecentAirportsCodec.encode(cleaned),
                )
                Timber.i("最近机场自愈：移除 %d 个失效 id", missing.size)
            }.onFailure {
                if (it is CancellationException) throw it
                Timber.w(it, "最近机场自愈写回失败")
            }
        }
    }

    fun onKeywordChange(raw: String) {

        val query = raw.take(MAX_KEYWORD)
        keywordFlow.update { current ->
            if (current.raw == query) current else KeywordRequest(query, current.revision + 1)
        }
    }

    fun clearKeyword() {
        onKeywordChange("")
    }

    private companion object {

        const val FRAME_MS = 16L

        const val MAX_KEYWORD = 32
        const val POPULAR_COUNT = 8
    }
}

internal fun AirportEntity.toModel(): Airport = Airport(
    id = id,
    iataCode = iataCode.takeIf { it.isNotBlank() },
    icaoCode = icaoCode.takeIf { it.isNotBlank() },
    name = name,
    city = city.takeIf { it.isNotBlank() },
    country = country.takeIf { it.isNotBlank() },
    latitude = latitude,
    longitude = longitude,
    elevation = elevation,
    continent = continent.takeIf { it.isNotBlank() },

    type = com.projectfocus.core.model.airport.AirportType.fromRaw(type),
    popularity = popularity,
    isPremiumOnly = isPremiumOnly,
    nameZh = nameZh?.takeIf { it.isNotBlank() },
    cityZh = cityZh?.takeIf { it.isNotBlank() },
)
