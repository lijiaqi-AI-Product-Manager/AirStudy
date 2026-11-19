package com.projectfocus.feature.airport.search

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.core.model.airport.AirportType
import org.junit.Test


class AirportSearchProjectorTest {

    @Test
    fun `SQL projection preserves DAO order instead of regrouping ICAO exact first`() {
        val prefix = airport(10, "ABC", "XP1", "Other", "Else", 1)
        val name = airport(11, "DEF", "XP2", "AB Airport", "Else", 10)
        val icao = airport(12, "GHI", "AB", "Other", "Else", 999)
        val ordered = listOf(prefix, name, icao)
        // This is a real counterexample to the old in-memory priority.
        assertThat(AirportSearchIndex.build(ordered).search("AB", ordered).first().airport.id).isEqualTo(12L)
        val state = AirportSearchProjector.fromSql("AB", ordered) as AirportSearchState.Content
        assertThat(state.groups).hasSize(1)
        assertThat(state.groups.single().tier).isNull()
        assertThat(state.groups.flatMap { it.hits }.map { it.airport.id }).containsExactly(10L, 11L, 12L).inOrder()
        assertThat(state.groups.single().hits.last().exactCode).isTrue()
        assertThat(state.truncated).isFalse()
        val limited = AirportSearchProjector.fromSql("AB", ordered, limit = 2) as AirportSearchState.Content
        assertThat(limited.groups.flatMap { it.hits }.map { it.airport.id }).containsExactly(10L, 11L).inOrder()
        assertThat(limited.truncated).isTrue()
    }

    @Test
    fun `SQL empty result does not claim query normalization`() {
        val state = AirportSearchProjector.fromSql(" ＰＥＫ ", emptyList()) as AirportSearchState.NoResult
        assertThat(state.keyword).isEqualTo(" ＰＥＫ ")
        assertThat(state.triedNormalization).isFalse()
    }

    private val beijing = airport(1, "PEK", "ZBAA", "Beijing Capital International", "Beijing", 95)
    private val beijingDaxing = airport(2, "PKX", "ZBAD", "Beijing Daxing International", "Beijing", 88)
    private val shanghai = airport(3, "PVG", "ZSPD", "Shanghai Pudong International", "Shanghai", 92)
    private val shanghaiHongqiao = airport(4, "SHA", "ZSSS", "Shanghai Hongqiao International", "Shanghai", 80)
    private val tokyo = airport(5, "HND", "RJTT", "Tokyo Haneda", "Tokyo", 90)
    private val osaka = airport(6, null, "RJOO", "Osaka Itami", "Osaka", 70)
    private val nowhere = airport(7, null, null, "Some Heliport", null, 1)

    private val all = listOf(
        beijing, beijingDaxing, shanghai, shanghaiHongqiao, tokyo, osaka, nowhere,
    )

    private val index = AirportSearchIndex.build(all)

    private fun airport(
        id: Long,
        iata: String?,
        icao: String?,
        name: String,
        city: String?,
        popularity: Int,
    ) = Airport(
        id = id,
        iataCode = iata,
        icaoCode = icao,
        name = name,
        city = city,
        country = null,
        latitude = 0.0,
        longitude = 0.0,
        elevation = null,
        continent = null,
        type = AirportType.LARGE_AIRPORT,
        popularity = popularity,
        isPremiumOnly = false,
    )

    // ══════════════════════════════════════════════════════
    // Loading — 索引没建好
    // ══════════════════════════════════════════════════════

    @Test
    fun `索引为 null 时是 Loading 而不是空结果`() {


        //   用户在 400ms 的窗口里打出第一个字，看到"没找到"就退出了。
        val s = AirportSearchProjector.project(index = null, airports = all, keyword = "PEK")
        assertThat(s).isEqualTo(AirportSearchState.Loading)
    }

    @Test
    fun `索引为 null 且关键词为空时仍是 Loading`() {

        //   反过来的话，首启时先显示"最近去过/热门"（空），
        //   400ms 后才变成真正的内容 —— 相当于闪了一下空态。
        val s = AirportSearchProjector.project(index = null, airports = all, keyword = "")
        assertThat(s).isEqualTo(AirportSearchState.Loading)
    }

    // ══════════════════════════════════════════════════════
    // Empty — 数据问题
    // ══════════════════════════════════════════════════════

    @Test
    fun `机场库为空时是 Empty 而不是 NoResult`() {

        //   两者界面上都应给"重试"，但文案完全不同：
        //     Empty    → "正在准备机场数据…（首次启动约需 10 秒）"
        //     NoResult → "没找到「xxx」"
        val s = AirportSearchProjector.project(index, airports = emptyList(), keyword = "PEK")
        assertThat(s).isEqualTo(AirportSearchState.Empty)
    }

    // ══════════════════════════════════════════════════════
    // Idle — 无关键词
    // ══════════════════════════════════════════════════════

    @Test
    fun `空关键词是 Idle 并带推荐项`() {
        val s = AirportSearchProjector.project(
            index, all, keyword = "",
            recent = listOf(beijing), popular = listOf(shanghai, tokyo),
        )
        assertThat(s).isInstanceOf(AirportSearchState.Idle::class.java)
        val idle = s as AirportSearchState.Idle
        assertThat(idle.recent).containsExactly(beijing)
        assertThat(idle.popular).containsExactly(shanghai, tokyo).inOrder()
        assertThat(idle.isBare).isFalse()
    }

    @Test
    fun `纯空格关键词也是 Idle 而不是 NoResult`() {

        //   用户长按删除时很容易留下空格（尤其在拼音输入法里）。
        val s = AirportSearchProjector.project(index, all, keyword = "   ")
        assertThat(s).isInstanceOf(AirportSearchState.Idle::class.java)
    }

    @Test
    fun `全新用户没有任何推荐时 Idle 标记为 bare`() {
        val s = AirportSearchProjector.project(index, all, keyword = "")
        val idle = s as AirportSearchState.Idle
        assertThat(idle.isBare).isTrue()
    }

    // ══════════════════════════════════════════════════════
    // TooShort — 1 个字符
    // ══════════════════════════════════════════════════════

    @Test
    fun `单个字符是 TooShort 而不是 NoResult`() {

        //   用户会认为"这个 App 连北京都搜不到"。
        val s = AirportSearchProjector.project(index, all, keyword = "b")
        assertThat(s).isInstanceOf(AirportSearchState.TooShort::class.java)
        val t = s as AirportSearchState.TooShort
        assertThat(t.keyword).isEqualTo("b")
        assertThat(t.needMore).isEqualTo(1)
    }

    @Test
    fun `单个中文字也是 TooShort`() {
        val s = AirportSearchProjector.project(index, all, keyword = "北")
        assertThat(s).isInstanceOf(AirportSearchState.TooShort::class.java)
    }

    @Test
    fun `空格加单字符仍算一个有效字符`() {

        //   如果按原始长度判断（2），会走到搜索分支然后在 83k 条里扫一遍
        //   —— 白费一次全表扫描。
        val s = AirportSearchProjector.project(index, all, keyword = " b")
        assertThat(s).isInstanceOf(AirportSearchState.TooShort::class.java)
    }

    // ══════════════════════════════════════════════════════
    // NoResult
    // ══════════════════════════════════════════════════════

    @Test
    fun `两字符以上无命中是 NoResult`() {
        val s = AirportSearchProjector.project(index, all, keyword = "zzzz")
        assertThat(s).isInstanceOf(AirportSearchState.NoResult::class.java)
        assertThat((s as AirportSearchState.NoResult).keyword).isEqualTo("zzzz")
    }

    @Test
    fun `归一化修正过但仍无结果时标记 triedNormalization`() {
        // 全角输入：ＰＥＫＫ（多一个 K）→ 归一化后 PEKK → 仍无结果
        val s = AirportSearchProjector.project(index, all, keyword = "ＰＥＫＫ")
        assertThat(s).isInstanceOf(AirportSearchState.NoResult::class.java)
        assertThat((s as AirportSearchState.NoResult).triedNormalization).isTrue()
    }

    @Test
    fun `全角输入能直接命中而不算无结果`() {

        //   中文输入法在中文状态打英文会出全角 ＰＥＫ，用户自己看不出来。
        val s = AirportSearchProjector.project(index, all, keyword = "ＰＥＫ")
        assertThat(s).isInstanceOf(AirportSearchState.Content::class.java)
        val c = s as AirportSearchState.Content
        assertThat(c.groups.flatMap { it.hits }.map { it.airport.iataCode })
            .containsExactly("PEK")
    }

    @Test
    fun `词中带空格也能命中`() {
        // `PE K` → 归一化 `PEK`
        val s = AirportSearchProjector.project(index, all, keyword = "PE K")
        assertThat(s).isInstanceOf(AirportSearchState.Content::class.java)
    }

    // ══════════════════════════════════════════════════════
    // Content
    // ══════════════════════════════════════════════════════

    @Test
    fun `精确码匹配排在第一组`() {
        val s = AirportSearchProjector.project(index, all, keyword = "PEK")
            as AirportSearchState.Content
        assertThat(s.groups.first().tier).isEqualTo(AirportSearchIndex.Tier.CODE_EXACT)
        assertThat(s.groups.first().hits.first().airport.iataCode).isEqualTo("PEK")
    }

    @Test
    fun `分组标题用的是中文文案`() {
        val s = AirportSearchProjector.project(index, all, keyword = "PEK")
            as AirportSearchState.Content
        assertThat(s.groups.map { it.title }).containsExactly("精确匹配")
    }

    @Test
    fun `城市名命中会归到城市或名称层级`() {
        val s = AirportSearchProjector.project(index, all, keyword = "Shanghai")
            as AirportSearchState.Content
        val codes = s.groups.flatMap { it.hits }.map { it.airport.iataCode }
        assertThat(codes).containsAtLeast("PVG", "SHA")
    }

    @Test
    fun `多个层级时分组按优先级排序`() {
        // "Osaka" 会命中 Osaka Itami 的 city 前缀；同时 "O" 不只一个。
        // 用一个能同时命中多层的词：`SHA` 命中 SHA 的精确码 + Shanghai 的城市前缀
        val s = AirportSearchProjector.project(index, all, keyword = "SHA")
            as AirportSearchState.Content
        // 至少两层：CODE_EXACT(SHA) + CITY_PREFIX/NAME_PREFIX(Shanghai…)
        assertThat(s.groups.size).isAtLeast(2)
        val ordinals = s.groups.map { requireNotNull(it.tier).ordinal }
        assertThat(ordinals).isInOrder()
    }

    @Test
    fun `没有 IATA 的机场在无码搜索时不会命中码层级`() {
        // osaka 有 RJOO 但没有 IATA。搜 RJOO 应该命中 CODE_EXACT。
        val s = AirportSearchProjector.project(index, all, keyword = "RJOO")
            as AirportSearchState.Content
        assertThat(s.groups.first().tier).isEqualTo(AirportSearchIndex.Tier.CODE_EXACT)
        assertThat(s.groups.first().hits.first().airport.id).isEqualTo(osaka.id)
    }

    @Test
    fun `total 等于各组条目数之和`() {
        val s = AirportSearchProjector.project(index, all, keyword = "Beijing")
            as AirportSearchState.Content
        assertThat(s.total).isEqualTo(s.groups.sumOf { it.hits.size })
        assertThat(s.total).isEqualTo(2)
    }

    // ══════════════════════════════════════════════════════
    // 截断判定
    // ══════════════════════════════════════════════════════

    @Test
    fun `结果超过上限时标记 truncated`() {
        val many = (1..40).map {
            airport(it.toLong(), "X%02d".format(it), null, "Test Field $it", "Testville", 50)
        }
        val bigIndex = AirportSearchIndex.build(many)
        val s = AirportSearchProjector.project(bigIndex, many, keyword = "Testville", limit = 10)
            as AirportSearchState.Content
        assertThat(s.total).isEqualTo(10)
        assertThat(s.truncated).isTrue()
    }

    @Test
    fun `恰好等于上限时不标记 truncated`() {

        //   错误实现：`hits.size == limit` 判定截断 → 恰好 10 条时报"还有更多"，
        //   用户继续打字试图缩小范围，但本来就只有 10 条 —— 白费功夫。
        //   正确实现：多取一条（limit + 1），`size > limit` 才算截断。
        val exactly = (1..10).map {
            airport(it.toLong(), "Y%02d".format(it), null, "Exact Field $it", "Exactville", 50)
        }
        val idx = AirportSearchIndex.build(exactly)
        val s = AirportSearchProjector.project(idx, exactly, keyword = "Exactville", limit = 10)
            as AirportSearchState.Content
        assertThat(s.total).isEqualTo(10)
        assertThat(s.truncated).isFalse()
    }

    @Test
    fun `结果少于上限时不标记 truncated`() {
        val s = AirportSearchProjector.project(index, all, keyword = "PEK")
            as AirportSearchState.Content
        assertThat(s.truncated).isFalse()
    }

    // ══════════════════════════════════════════════════════
    // 边界：机场名/城市名缺失
    // ══════════════════════════════════════════════════════

    @Test
    fun `城市为空的机场仍可按机场名搜到`() {
        // nowhere 没有 city、没有 iata、没有 icao，只有 name
        val s = AirportSearchProjector.project(index, all, keyword = "Heliport")
        assertThat(s).isInstanceOf(AirportSearchState.Content::class.java)
        val c = s as AirportSearchState.Content
        assertThat(c.groups.flatMap { it.hits }.map { it.airport.id }).contains(nowhere.id)
    }
}
