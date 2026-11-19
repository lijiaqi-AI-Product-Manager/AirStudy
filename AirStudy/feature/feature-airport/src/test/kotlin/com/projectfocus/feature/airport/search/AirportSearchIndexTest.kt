package com.projectfocus.feature.airport.search

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.core.model.airport.AirportType
import org.junit.Test

















class AirportSearchIndexTest {

    // ── 测试数据 ───────────────────────────────────────────

    private val pek = airport(
        id = 1, iata = "PEK", icao = "ZBAA",
        name = "Beijing Capital International Airport", city = "Beijing",
        popularity = 9500, cityZh = "北京",
    )
    private val pvg = airport(
        id = 2, iata = "PVG", icao = "ZSPD",
        name = "Shanghai Pudong International Airport", city = "Shanghai",
        popularity = 9200,
    )
    private val nrt = airport(
        id = 3, iata = "NRT", icao = "RJAA",
        name = "Narita International Airport", city = "Tokyo",
        popularity = 8800,
    )
    private val smallTown = airport(
        id = 4, iata = "BJS2", icao = "ZZZZ",
        name = "Beijing Miyun Airport", city = "Beijing",
        popularity = 12,
    )
    private val noCode = airport(
        id = 5, iata = "", icao = "",
        name = "Some Private Strip", city = "Nowhere",
        popularity = 1,
    )

    private val all = listOf(pek, pvg, nrt, smallTown, noCode)








    private val fakePinyin = PinyinProvider { text ->
        when {
            text == null -> null
            text.startsWith("Beijing", ignoreCase = true) -> "BJ" to "beijing"
            text.startsWith("Shanghai", ignoreCase = true) -> "SH" to "shanghai"
            text.startsWith("Narita", ignoreCase = true) -> "CL" to "chengtian"
            text.startsWith("Tokyo", ignoreCase = true) -> "DJ" to "dongjing"
            else -> null
        }
    }

    private fun index(pinyin: PinyinProvider = fakePinyin) =
        AirportSearchIndex.build(all, pinyin)

    // ── 1. 四种典型输入 ─────────────────────────────────────

    @Test
    fun `四种典型输入都能搜到首都机场`() {

        //   四种输入覆盖了用户的四种心智模型：
        //     PEK    → 常旅客，记得三字码
        //     北京   → 中文用户，输地名
        //     Beijing→ 英文输入法下的拼音
        //     ZBAA   → 航迷/飞行模拟玩家，记得 ICAO
        //   任何一条搜不到，对应人群就有一整个是废的。
        val idx = index()
        for (q in listOf("PEK", "北京", "Beijing", "ZBAA")) {
            val hits = idx.search(q, all)
            val found = hits.any { it.airport.id == pek.id }
            assertThat(found).isTrue()
        }
    }

    @Test
    fun `北京命中的是中文城市字段而不是英文名`() {

        //   如果哪天有人把 cityZh 从索引里去掉，上一条会红；
        //   但如果他同时把 city 也改成中文（那就破坏了英文检索），
        //   上一条又会绿。这条钉住命中来源是中文层。
        //

        //     是 "Beijing"（英文），中文输入永远不会命中英文 city。

        val idx = index()
        val hit = idx.search("北京", all).first { it.airport.id == pek.id }
        assertThat(hit.tier).isEqualTo(AirportSearchIndex.Tier.CHINESE)
        assertThat(hit.fields).contains(AirportSearchIndex.Field.CHINESE)
    }

    @Test
    fun `中文名里的任意连续片段都能命中`() {
        // 用户不会完整输入「北京首都国际机场」，他输「首都」。
        // 这是中文检索与本文件其它英文层的根本区别：英文靠前缀，中文靠包含。
        val idx = index()
        val withZhName = listOf(
            airport(
                id = 20, iata = "PEK", icao = "ZBAA",
                name = "Beijing Capital International Airport", city = "Beijing",
                popularity = 9500, cityZh = "北京", nameZh = "北京首都国际机场",
            ),
        )
        val i2 = AirportSearchIndex.build(withZhName, PinyinProvider.NONE)
        for (q in listOf("首都", "国际", "北京首都")) {
            assertThat(i2.search(q, withZhName).map { it.airport.id }).contains(20L)
        }
    }

    // ── 2. 层级优先级 ──────────────────────────────────────

    @Test
    fun `精确码匹配排在前缀匹配之前`() {
        // "PVG" 精确命中 PVG；"P" 开头的码里 PVG 也在，但层级不同
        val idx = index()
        val hits = idx.search("PVG", all)
        assertThat(hits.first().airport.displayCode).isEqualTo("PVG")
        assertThat(hits.first().tier).isEqualTo(AirportSearchIndex.Tier.CODE_EXACT)
    }

    @Test
    fun `码前缀匹配排在名称前缀之前`() {
        // 输入 "B"：smallTown 的 IATA 是 "BJS2"（前缀命中），
        // 而 pek 的 name 以 "Beijing" 开头（名称前缀命中）
        val idx = index()
        val hits = idx.search("B", all)
        assertThat(hits).isNotEmpty()
        val codePrefixIdx = hits.indexOfFirst { it.tier == AirportSearchIndex.Tier.CODE_PREFIX }
        val namePrefixIdx = hits.indexOfFirst { it.tier == AirportSearchIndex.Tier.NAME_PREFIX }
        assertThat(codePrefixIdx).isAtLeast(0)
        assertThat(namePrefixIdx).isAtLeast(0)
        assertThat(codePrefixIdx).isLessThan(namePrefixIdx)
    }

    @Test
    fun `完全匹配三字码时结果只有精确匹配排第一`() {
        val idx = index()
        val hits = idx.search("NRT", all)
        assertThat(hits.first().tier).isEqualTo(AirportSearchIndex.Tier.CODE_EXACT)
        assertThat(hits.first().exactCode).isTrue()
    }

    // ── 3. 归一化 ──────────────────────────────────────────

    @Test
    fun `小写输入与小写中间带空格都能搜到`() {
        val idx = index()
        for (q in listOf("pek", "pEk", "PE K", "  PEK  ")) {
            val hits = idx.search(q, all)
            assertThat(hits.any { it.airport.id == pek.id }).isTrue()
        }
    }

    @Test
    fun `中文输入法下的全角字母也能搜到`() {

        //   中文输入法在中文状态直接敲键盘，输出的是 U+FF30 而不是 U+0050。
        //   用户在屏幕上看到的就是 "PEK"，但代码里完全不等。

        val idx = index()
        val fullWidth = "ＰＥＫ"   // U+FF30 U+FF25 U+FF2B
        assertThat(fullWidth[0]).isNotEqualTo('P')   // 先证明这确实是全角
        val hits = idx.search(fullWidth, all)
        assertThat(hits.any { it.airport.id == pek.id }).isTrue()
    }

    @Test
    fun `空查询与纯空白返回空列表而不是全部`() {

        //   但机场列表页在空查询时显示的是**热度推荐**（另一个方法），
        //   如果这里返回全部 83k 条，UI 会拿到一个 83k 的列表去渲染。
        val idx = index()
        assertThat(idx.search("", all)).isEmpty()
        assertThat(idx.search("   ", all)).isEmpty()
        assertThat(idx.search("\u3000\t\n", all)).isEmpty()
    }

    @Test
    fun `归一化把全角空格当成普通空白丢掉`() {
        assertThat(AirportSearchIndex.normalize(" P E K ")).isEqualTo("PEK")
        assertThat(AirportSearchIndex.normalize("\u3000PEK\u3000")).isEqualTo("PEK")
        assertThat(AirportSearchIndex.normalize(null)).isEmpty()
    }

    @Test
    fun `码归一化保留数字并去掉连字符`() {
        // 用户可能输 "ZB-AA"，也可能遇到带数字的虚拟机场码
        assertThat(AirportSearchIndex.normalizeCode("ZB-AA")).isEqualTo("ZBAA")
        assertThat(AirportSearchIndex.normalizeCode("bjs2")).isEqualTo("BJS2")
        assertThat(AirportSearchIndex.normalizeCode("")).isEmpty()
    }

    // ── 4. 排序 ────────────────────────────────────────────

    @Test
    fun `同一层级内热度高的排在前面`() {
        // 输入 "Beijing"："Beijing Capital"(9500) 与 "Beijing Miyun"(12)
        // 都是名称前缀命中，热度高的在前
        val idx = index()
        val hits = idx.search("Beijing", all)
        val ids = hits.map { it.airport.id }
        assertThat(ids.indexOf(pek.id)).isLessThan(ids.indexOf(smallTown.id))
    }

    @Test
    fun `同层级同热度时按码字典序排，保证结果稳定`() {

        //   两条同分结果如果按列表原始顺序排，而原始顺序来自
        //   无 ORDER BY 的 SELECT *，SQLite 不保证稳定。
        val a = airport(10, "AAA", "AAAA", "Same Name", "Same City", popularity = 100)
        val b = airport(11, "BBB", "BBBB", "Same Name", "Same City", popularity = 100)
        val list = listOf(b, a)   // 故意把 BBB 放前面
        val idx = AirportSearchIndex.build(list, PinyinProvider.NONE)
        val hits = idx.search("Same", list)
        // 排序后 AAA 应在 BBB 前，与输入顺序无关
        assertThat(hits.map { it.airport.displayCode }).containsExactly("AAA", "BBB").inOrder()
    }

    // ── 5. 拼音 ────────────────────────────────────────────

    @Test
    fun `拼音首字母能搜到中文名机场`() {
        val idx = index()
        val hits = idx.search("bj", all)
        assertThat(hits.any { it.airport.id == pek.id }).isTrue()
        assertThat(hits.first { it.airport.id == pek.id }.fields)
            .contains(AirportSearchIndex.Field.PINYIN)
    }

    @Test
    fun `单个字母不触发拼音匹配，避免结果被噪音淹没`() {

        //   变成噪音源。所以拼音只在 q.length >= 2 时生效。
        val idx = index()
        val hits = idx.search("b", all)
        assertThat(hits.none { it.tier == AirportSearchIndex.Tier.PINYIN }).isTrue()
    }

    @Test
    fun `没有拼音提供者时拼音输入不命中但码与名称照常`() {
        val idx = index(PinyinProvider.NONE)
        assertThat(idx.search("bj", all).none { it.airport.id == pek.id }).isTrue()
        assertThat(idx.search("PEK", all).any { it.airport.id == pek.id }).isTrue()
        assertThat(idx.search("Beijing", all).any { it.airport.id == pek.id }).isTrue()
    }

    // ── 6. 缺码机场 ────────────────────────────────────────

    @Test
    fun `缺码机场不会被码搜索命中但仍可被名称搜索命中`() {
        // OurAirports 里缺失 IATA 的机场在库里存空串（NOT NULL 列）。
        // 空串 startsWith 非空 q 恒为 false，所以不会被码匹配命中 —— 行为正确。
        val idx = index(PinyinProvider.NONE)
        assertThat(idx.search("ZZZZ", all).any { it.airport.id == noCode.id }).isFalse()
        assertThat(idx.search("Private", all).any { it.airport.id == noCode.id }).isTrue()
    }

    // ── 7. limit 与过滤 ────────────────────────────────────

    @Test
    fun `limit 生效且不改变排序`() {
        val idx = index()
        val full = idx.search("International", all, limit = 100)
        val cut = idx.search("International", all, limit = 2)
        assertThat(cut.size).isEqualTo(2)
        assertThat(cut.map { it.airport.id }).isEqualTo(full.take(2).map { it.airport.id })
    }

    @Test
    fun `tierFilter 只返回指定层级`() {
        val idx = index()
        val onlyExact = idx.search("PEK", all, tierFilter = setOf(AirportSearchIndex.Tier.CODE_EXACT))
        assertThat(onlyExact).isNotEmpty()
        assertThat(onlyExact.all { it.tier == AirportSearchIndex.Tier.CODE_EXACT }).isTrue()
    }

    // ── 8. 性能 ────────────────────────────────────────────

    @Test
    fun `八万条数据下单次搜索在一百毫秒内`() {

        //   这里造 83,799 条**接近真实分布**的数据（不是同一个名字复制 8 万遍，
        //   那样缓存命中率会高得离谱，测出来的时间没有意义）。
        //

        //     一个**宽松**的阈值。真机实测见 docs/search-ranking.md。
        //     这里的作用是**防退化**：如果有人把预归一化改回搜索时归一化，
        //     耗时会出现数量级跳变，这条立刻红。
        val big = buildList {
            val cities = listOf("Beijing", "Shanghai", "Tokyo", "London", "Paris", "New York")
            for (i in 0 until 83_799) {
                val c = cities[i % cities.size]
                add(
                    Airport(
                        id = i.toLong(),
                        iataCode = "%03d".format(i % 1000) + "X",
                        icaoCode = "Z%03d".format(i % 1000) + "X",
                        name = "$c International Airport $i",
                        city = c,
                        country = "CN",
                        latitude = 30.0 + i % 100 * 0.01,
                        longitude = 120.0 + i % 100 * 0.01,
                        elevation = 0,
                        continent = "AS",
                        type = AirportType.LARGE_AIRPORT,
                        popularity = i % 10000,
                        isPremiumOnly = false,
                    ),
                )
            }
        }
        val buildStart = System.nanoTime()
        val idx = AirportSearchIndex.build(big, PinyinProvider.NONE)
        val buildMs = (System.nanoTime() - buildStart) / 1_000_000
        println("[perf] 构建 83,799 条索引耗时 ${buildMs} ms")

        // 预热一次（JIT），不计数
        idx.search("Beijing", big)

        val queries = listOf("Beijing", "PEK", "Tokyo", "Shang", "Internat")
        val measured = queries.map { q ->
            val t0 = System.nanoTime()
            repeat(5) { idx.search(q, big) }
            ((System.nanoTime() - t0) / 5) / 1_000_000
        }
        val worst = measured.max()
        println("[perf] 单次搜索耗时(ms) = ${queries.zip(measured).joinToString { "${it.first}=${it.second}" }}")
        assertThat(worst).isLessThan(100L)
    }

    // ── 9. 防误用 ──────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `传入比索引更短的机场列表时立刻抛错而不是越界`() {

        //   传错了如果静默处理，会得到 IndexOutOfBoundsException，
        //   堆栈指向 search 内部，完全看不出根因在调用方。
        val idx = index()
        idx.search("PEK", all.take(2))
    }

    // ── 辅助 ───────────────────────────────────────────────

    private fun airport(
        id: Long,
        iata: String,
        icao: String,
        name: String,
        city: String,
        popularity: Int,
        cityZh: String = "",
        nameZh: String? = null,
    ) = Airport(
        id = id,
        iataCode = iata,
        icaoCode = icao,
        name = name,
        city = city,
        country = "CN",
        latitude = 40.0,
        longitude = 116.0,
        elevation = 100,
        continent = "AS",
        type = AirportType.LARGE_AIRPORT,
        popularity = popularity,
        isPremiumOnly = false,
        nameZh = nameZh,
        cityZh = cityZh.ifEmpty { null },
    )
}
