package com.projectfocus.feature.airport.search

import com.projectfocus.core.model.airport.Airport

/** 本品搜索状态适配器；不以这些空态或分组规则声明原包UI行为。 */
sealed interface AirportSearchState {

    data object Loading : AirportSearchState

    data class Idle(
        val recent: List<Airport>,
        val popular: List<Airport>,
    ) : AirportSearchState {

        val isBare: Boolean get() = recent.isEmpty() && popular.isEmpty()
    }

    data object Empty : AirportSearchState

    data class TooShort(val keyword: String, val needMore: Int) : AirportSearchState

    data class NoResult(
        val keyword: String,
        val triedNormalization: Boolean,
    ) : AirportSearchState

    data class Content(
        val keyword: String,
        val groups: List<ResultGroup>,

        val truncated: Boolean,
    ) : AirportSearchState {
        val total: Int get() = groups.sumOf { it.hits.size }
    }
}

data class ResultGroup(

    /** null 表示DAO有序平铺结果，不按旧tier分组。 */
    val tier: AirportSearchIndex.Tier?,
    val title: String,
    val hits: List<AirportSearchIndex.Hit>,
)

object AirportSearchProjector {

    /** 只做本品入口门槛判断，不改变SQL的query参数。 */
    fun beforeSearch(
        ready: Boolean,
        hasAirports: Boolean,
        keyword: String,
        recent: List<Airport> = emptyList(),
        popular: List<Airport> = emptyList(),
    ): AirportSearchState? {
        if (!ready) return AirportSearchState.Loading
        if (!hasAirports) return AirportSearchState.Empty
        val norm = AirportSearchIndex.normalize(keyword)
        if (norm.isEmpty()) return AirportSearchState.Idle(recent, popular)
        if (norm.length < MIN_QUERY_LENGTH) {
            return AirportSearchState.TooShort(keyword.trim(), MIN_QUERY_LENGTH - norm.length)
        }
        return null
    }

    /** DAO顺序直接平铺；Hit元数据只用于行高亮，不参与排序或分组。 */
    fun fromSql(
        keyword: String,
        orderedAirports: List<Airport>,
        limit: Int = RESULT_LIMIT,
    ): AirportSearchState {
        require(limit > 0)
        if (orderedAirports.isEmpty()) return AirportSearchState.NoResult(keyword, triedNormalization = false)
        val hits = orderedAirports.take(limit).map { airport ->
            val fields = buildSet {
                if (airport.iataCode?.contains(keyword, ignoreCase = true) == true) add(AirportSearchIndex.Field.IATA)
                if (airport.icaoCode?.contains(keyword, ignoreCase = true) == true) add(AirportSearchIndex.Field.ICAO)
                if (airport.name.contains(keyword, ignoreCase = true)) add(AirportSearchIndex.Field.NAME)
                if (airport.city?.contains(keyword, ignoreCase = true) == true) add(AirportSearchIndex.Field.CITY)
            }
            val tier = when {
                airport.iataCode.equals(keyword, ignoreCase = true) || airport.icaoCode.equals(keyword, ignoreCase = true) -> AirportSearchIndex.Tier.CODE_EXACT
                airport.iataCode?.startsWith(keyword, ignoreCase = true) == true || airport.icaoCode?.startsWith(keyword, ignoreCase = true) == true -> AirportSearchIndex.Tier.CODE_PREFIX
                airport.name.startsWith(keyword, ignoreCase = true) -> AirportSearchIndex.Tier.NAME_PREFIX
                airport.city?.startsWith(keyword, ignoreCase = true) == true -> AirportSearchIndex.Tier.CITY_PREFIX
                AirportSearchIndex.Field.NAME in fields -> AirportSearchIndex.Tier.NAME_CONTAINS
                else -> AirportSearchIndex.Tier.CITY_CONTAINS
            }
            AirportSearchIndex.Hit(airport, tier, fields)
        }
        return AirportSearchState.Content(
            keyword = keyword,
            groups = listOf(ResultGroup(tier = null, title = "机场", hits = hits)),
            truncated = orderedAirports.size > limit,
        )
    }

    const val MIN_QUERY_LENGTH: Int = 2

    const val RESULT_LIMIT: Int = AirportSearchIndex.DEFAULT_LIMIT

    /** 旧内存索引工具的投影，仅供工具验证；生产文本搜索使用fromSql。 */
    fun project(
        index: AirportSearchIndex?,
        airports: List<Airport>,
        keyword: String,
        recent: List<Airport> = emptyList(),
        popular: List<Airport> = emptyList(),
        limit: Int = RESULT_LIMIT,
    ): AirportSearchState {
        if (index == null) return AirportSearchState.Loading
        if (airports.isEmpty()) return AirportSearchState.Empty

        val norm = AirportSearchIndex.normalize(keyword)
        if (norm.isEmpty()) {
            return AirportSearchState.Idle(recent = recent, popular = popular)
        }

        if (norm.length < MIN_QUERY_LENGTH) {
            return AirportSearchState.TooShort(
                keyword = keyword.trim(),
                needMore = MIN_QUERY_LENGTH - norm.length,
            )
        }

        val hits = index.search(keyword, airports, limit = limit + 1)
        if (hits.isEmpty()) {
            return AirportSearchState.NoResult(
                keyword = keyword.trim(),

                triedNormalization = norm != keyword.trim().uppercase(),
            )
        }

        val truncated = hits.size > limit
        val shown = if (truncated) hits.subList(0, limit) else hits

        val groups = shown
            .groupBy { it.tier }
            .toList()
            .sortedBy { (tier, _) -> tier.ordinal }
            .map { (tier, list) ->
                ResultGroup(
                    tier = tier,
                    title = AirportDisplayFormatter.tierLabel(tier),
                    hits = list,
                )
            }

        return AirportSearchState.Content(
            keyword = keyword.trim(),
            groups = groups,
            truncated = truncated,
        )
    }
}
