package com.projectfocus.feature.airport.search

import com.projectfocus.core.model.airport.Airport














































data class IdleRecommendation(
    /** 最近去过的机场（保持调用方给的顺序 —— 见类注释①） */
    val recent: List<Airport>,
    /** 热门机场（已剔除与 [recent] 重复的） */
    val popular: List<Airport>,
) {







    val isBare: Boolean get() = recent.isEmpty() && popular.isEmpty()









    val spokenOverview: String
        get() = buildList {
            if (recent.isNotEmpty()) add("最近去过 ${recent.size} 个")
            if (popular.isNotEmpty()) add("热门城市 ${popular.size} 个")
        }.joinToString("，")

    companion object {
        val EMPTY = IdleRecommendation(emptyList(), emptyList())
    }
}









object IdleRecommender {









    const val RECENT_LIMIT: Int = 4

    /** 热门城市上限。8 个 = 两行 × 4 个，正好一屏够 */
    const val POPULAR_LIMIT: Int = 8













    fun chipLabel(airport: Airport): String? {
        val city = airport.cityZh?.takeIf { it.isNotBlank() }
            ?: airport.city?.takeIf { it.isNotBlank() }
            ?: airport.nameZh?.takeIf { it.isNotBlank() }
            ?: airport.name.takeIf { it.isNotBlank() }
        val code = airport.iataCode?.takeIf { it.isNotBlank() }
        return when {

            city == null && code == null -> null
            city == null -> code
            code == null -> city
            else -> "$city $code"
        }
    }























    fun recent(
        recents: List<Airport>,
        known: Map<Long, Airport>? = null,
        limit: Int = RECENT_LIMIT,
    ): List<Airport> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<Airport>(limit)
        val seen = HashSet<Long>(limit * 2)
        for (a in recents) {
            if (out.size >= limit) break

            //   但同一机场从 Room 和从 DataStore 还原出来的 popularity /
            //   nameZh 可能不一致（一边是旧快照），`==` 会判为不同。
            if (!seen.add(a.id)) continue
            if (known != null && known[a.id] == null) continue

            //   否则 chip 上可能显示一个已经被改名的城市。
            val actual = known?.get(a.id) ?: a
            if (chipLabel(actual) == null) continue
            out += actual
        }
        return out
    }












    fun popular(
        all: List<Airport>,
        exclude: Set<Long> = emptySet(),
        limit: Int = POPULAR_LIMIT,
    ): List<Airport> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<Airport>(limit)
        val seen = HashSet<Long>(limit * 2)
        val candidates = all.asSequence()
            .filter { it.id !in exclude }
            .filter { it.popularity > 0 }

            .filter { !it.iataCode.isNullOrBlank() }

            //   藏起来会让热门榜莫名其妙变短。拦截放在点击之后（引导购买），
            //   而不是在推荐阶段假装它不存在。
            .sortedWith(
                compareByDescending<Airport> { it.popularity }
                    .thenBy { it.iataCode ?: "" },
            )
        for (a in candidates) {
            if (out.size >= limit) break
            if (!seen.add(a.id)) continue
            out += a
        }
        return out
    }

    /**
     * 一步到位：从原始输入算出完整的推荐区。
     *
     * @param recents 最近去过（倒序）
     * @param all 全量机场（热门要从这里算）
     * @param known 存在性校验用（null = 不做校验）
     */
    fun build(
        recents: List<Airport>,
        all: List<Airport>,
        known: Map<Long, Airport>? = null,
    ): IdleRecommendation {
        val recentList = recent(recents, known)
        val popularList = popular(all, exclude = recentList.mapTo(HashSet()) { it.id })
        return IdleRecommendation(recent = recentList, popular = popularList)
    }
}
