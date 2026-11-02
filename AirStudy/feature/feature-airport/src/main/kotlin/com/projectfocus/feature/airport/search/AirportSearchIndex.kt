package com.projectfocus.feature.airport.search

import com.projectfocus.core.model.airport.Airport
import com.projectfocus.core.model.airport.AirportType


class AirportSearchIndex private constructor(
    private val items: List<Item>,
) {

    /**
     * 一条索引项。**不持有机场对象**，只持下标 —— 见类注释的内存账。
     *
     * 所有 `norm*` 字段都是**预归一化**的，搜索时不再对每条做
     * `uppercase()`。这是「< 100 ms」的关键：83k 条 × 4 个字段
     * 每次按键做 uppercase，光字符串分配就是几百 MB/s 的垃圾产生率。
     */
    internal class Item(
        val index: Int,
        val normIata: String,
        val normIcao: String,
        val normName: String,
        val normCity: String,
        /**
         * 中文名/城市名（**不做 uppercase** —— 中文没有大小写，
         * 而 `uppercase()` 会走一遍 Unicode 大小写表，83k × 2 次纯属浪费）。
         */
        val normNameZh: String,
        val normCityZh: String,
        /** 名称/城市里每个词的拼音首字母缩写，空格分隔。空串表示无拼音数据 */
        val pinyinInitials: String,
        /** 名称/城市全拼（不含声调、不含空格）。空串表示无拼音数据 */
        val pinyinFull: String,
        val popularity: Int,
        val type: AirportType,
    )

    /** 搜索结果 */
    data class Hit(
        val airport: Airport,
        /** 命中层级，越小越优先。见 [Tier] */
        val tier: Tier,
        /**
         * 命中的字段，用于**给用户看的高亮依据**。
         * 例如搜 `pek` 命中 IATA，UI 就把 `PEK` 三个字母高亮。
         */
        val fields: Set<Field>,
    ) {
        /** 精确码匹配 —— UI 可以用它把这一条渲染成「快捷直达」样式 */
        val exactCode: Boolean get() = tier == Tier.CODE_EXACT
    }










    enum class Tier {
        CODE_EXACT,
        CODE_PREFIX,
        NAME_PREFIX,
        CITY_PREFIX,








        CHINESE,
        PINYIN,
        NAME_CONTAINS,
        CITY_CONTAINS,
        ;
    }

    enum class Field { IATA, ICAO, NAME, CITY, PINYIN, CHINESE }

    /** 是否已构建（空索引也算"构建过"，用于避免重复构建） */
    val size: Int get() = items.size

    // ─────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────

    /**
     * 搜索。
     *
     * @param query 原始输入，任意大小写、任意空白
     * @param airports 与构造时**同一个列表**（索引只存下标，必须传回来）
     * @param limit 结果上限
     * @param tierFilter 只返回指定层级（供 UI 做分组，如"精确匹配"区）
     */
    fun search(
        query: String,
        airports: List<Airport>,
        limit: Int = DEFAULT_LIMIT,
        tierFilter: Set<Tier>? = null,
    ): List<Hit> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        require(airports.size >= items.size) {
            "airports 列表与索引不匹配（索引 ${items.size} 条，传入 ${airports.size} 条）"
        }

        val out = ArrayList<Hit>(minOf(limit, 32))
        for (item in items) {
            val tier = match(item, q) ?: continue
            if (tierFilter != null && tier !in tierFilter) continue
            out += Hit(airports[item.index], tier, fieldsOf(item, q, tier))
        }


        //   没有最后这一层的话，两条层级与热度都相同的机场顺序取决于
        //   列表原始顺序，而原始顺序来自 SQL 的 `SELECT *`（无 ORDER BY）
        //   —— SQLite 不保证稳定。表现是：同一关键词、同一时刻、
        //   刷新两次，两条同分结果互换位置。用户会以为列表在"跳"。
        out.sortWith(
            compareBy<Hit> { it.tier.ordinal }
                .thenByDescending { it.airport.popularity }
                .thenBy { it.airport.displayCode },
        )
        return if (out.size <= limit) out else out.subList(0, limit)
    }






    internal fun match(item: Item, q: String): Tier? {
        if (item.normIata == q || item.normIcao == q) return Tier.CODE_EXACT
        if (item.normIata.startsWith(q) || item.normIcao.startsWith(q)) return Tier.CODE_PREFIX
        if (item.normName.startsWith(q)) return Tier.NAME_PREFIX
        if (item.normCity.startsWith(q)) return Tier.CITY_PREFIX
        // 拼音只在「至少 2 个字符」时才匹配。
        // 单个字母的拼音命中面太大（"b" 能中北京/巴黎/柏林/波士顿…几百条），
        // 会把这个本来只是"补充手段"的层级变成噪音源。
        if (q.length >= 2 && item.pinyinInitials.isNotEmpty() &&
            (item.pinyinInitials.contains(q) || item.pinyinFull.startsWith(q))
        ) {
            return Tier.PINYIN
        }

        //   排序理由：中文输入是最明确的意图（用户在中文界面输中文），
        //   比"英文名里恰好包含这几个字母"可信得多；但排在拼音之后，
        //   因为拼音是"中文用户的英文键盘输入"，与英文名匹配同等可信。
        if (item.normNameZh.contains(q) || item.normCityZh.contains(q)) return Tier.CHINESE
        if (item.normName.contains(q)) return Tier.NAME_CONTAINS
        if (item.normCity.contains(q)) return Tier.CITY_CONTAINS
        return null
    }

    private fun fieldsOf(item: Item, q: String, tier: Tier): Set<Field> {
        val s = LinkedHashSet<Field>(3)
        when (tier) {
            Tier.CODE_EXACT, Tier.CODE_PREFIX -> {
                if (item.normIata.startsWith(q)) s += Field.IATA
                if (item.normIcao.startsWith(q)) s += Field.ICAO
            }
            Tier.NAME_PREFIX, Tier.NAME_CONTAINS -> s += Field.NAME
            Tier.CITY_PREFIX, Tier.CITY_CONTAINS -> s += Field.CITY
            Tier.CHINESE -> s += Field.CHINESE
            Tier.PINYIN -> s += Field.PINYIN
        }
        return s
    }

    // ─────────────────────────────────────────────────────────
    // 构建
    // ─────────────────────────────────────────────────────────

    companion object {
        const val DEFAULT_LIMIT: Int = 25


        fun build(
            airports: List<Airport>,
            pinyin: PinyinProvider = PinyinProvider.NONE,
        ): AirportSearchIndex {
            val items = ArrayList<Item>(airports.size)
            for ((i, a) in airports.withIndex()) {
                val namePy = pinyin.of(a.name)
                val cityPy = pinyin.of(a.city)
                items += Item(
                    index = i,
                    normIata = normalizeCode(a.iataCode),
                    normIcao = normalizeCode(a.icaoCode),
                    normName = normalize(a.name),
                    normCity = normalize(a.city),
                    normNameZh = normalizeZh(a.nameZh),
                    normCityZh = normalizeZh(a.cityZh),
                    pinyinInitials = listOfNotNull(
                        namePy?.first?.takeIf { it.isNotEmpty() },
                        cityPy?.first?.takeIf { it.isNotEmpty() },
                    ).joinToString(" "),
                    pinyinFull = listOfNotNull(
                        namePy?.second?.takeIf { it.isNotEmpty() },
                        cityPy?.second?.takeIf { it.isNotEmpty() },
                    ).firstOrNull().orEmpty(),
                    popularity = a.popularity,
                    type = a.type,
                )
            }
            return AirportSearchIndex(items)
        }


        fun normalize(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            val sb = StringBuilder(raw.length)
            for (ch in raw) {
                var c = ch
                // 全角 ASCII（U+FF01..U+FF5E）→ 半角
                if (c in '\uFF01'..'\uFF5E') c = (c.code - 0xFEE0).toChar()
                // 全角空格
                if (c == '\u3000') c = ' '
                if (c.isWhitespace()) continue
                sb.append(c)
            }
            return sb.toString().uppercase()
        }















        fun normalizeZh(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            val sb = StringBuilder(raw.length)
            for (ch in raw) {
                if (ch.isWhitespace()) continue
                if (ch == '\u3000') continue
                sb.append(ch)
            }
            return sb.toString()
        }













        fun normalizeCode(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            val sb = StringBuilder(raw.length)
            for (ch in raw) {
                if (ch.isLetterOrDigit()) sb.append(ch)
            }
            return sb.toString().uppercase()
        }
    }
}











fun interface PinyinProvider {
    /** @return (首字母缩写, 全拼)；无拼音（纯英文/数字/空）返回 null */
    fun of(text: String?): Pair<String, String>?

    companion object {
        /** 空实现：关闭拼音匹配 */
        val NONE: PinyinProvider = PinyinProvider { null }
    }
}
