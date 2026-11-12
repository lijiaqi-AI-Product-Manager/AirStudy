package com.projectfocus.feature.airport.search

import com.projectfocus.core.model.airport.Airport
import com.projectfocus.core.model.airport.AirportType
import com.google.common.truth.Truth.assertThat
import org.junit.Test



















class IdleRecommenderTest {

    // ─────────────────────────────────────────────────────────
    // 夹具
    // ─────────────────────────────────────────────────────────

    private fun airport(
        id: Long,
        iata: String? = "AAA",
        city: String? = "Alpha",
        cityZh: String? = null,
        name: String = "Alpha Airport",
        nameZh: String? = null,
        popularity: Int = 100,
        premium: Boolean = false,
        type: AirportType = AirportType.LARGE_AIRPORT,
    ): Airport = Airport(
        id = id,
        iataCode = iata,
        icaoCode = null,
        name = name,
        city = city,
        country = "CN",
        latitude = 0.0,
        longitude = 0.0,
        elevation = null,
        continent = "AS",
        type = type,
        popularity = popularity,
        isPremiumOnly = premium,
        nameZh = nameZh,
        cityZh = cityZh,
    )

    // ─────────────────────────────────────────────────────────
    // chipLabel
    // ─────────────────────────────────────────────────────────

    @Test
    fun `中文城市 + 三字码拼成北京 PEK`() {
        val a = airport(1, iata = "PEK", city = "Beijing", cityZh = "北京")
        assertThat(IdleRecommender.chipLabel(a)).isEqualTo("北京 PEK")
    }

    @Test
    fun `没有中文时用英文城市`() {
        val a = airport(1, iata = "PEK", city = "Beijing")
        assertThat(IdleRecommender.chipLabel(a)).isEqualTo("Beijing PEK")
    }

    @Test
    fun `城市缺失时退回机场名`() {
        val a = airport(1, iata = "PEK", city = null, name = "Capital Intl")
        assertThat(IdleRecommender.chipLabel(a)).isEqualTo("Capital Intl PEK")
    }

    @Test
    fun `城市和机场名都是空串时用中文机场名`() {
        val a = airport(1, iata = "PEK", city = " ", name = " ", nameZh = "首都国际机场")
        assertThat(IdleRecommender.chipLabel(a)).isEqualTo("首都国际机场 PEK")
    }

    @Test
    fun `没有 IATA 码时只给城市`() {
        val a = airport(1, iata = null, city = "Beijing")

        assertThat(IdleRecommender.chipLabel(a)).isEqualTo("Beijing")
    }

    @Test
    fun `IATA 是空串等同于没有`() {
        val a = airport(1, iata = "  ", city = "Beijing")
        assertThat(IdleRecommender.chipLabel(a)).isEqualTo("Beijing")
    }

    @Test
    fun `什么都没有时返回 null 而不是空串`() {

        //   用户点了他也不知道点的是什么。null 让调用方跳过它。
        val a = airport(1, iata = null, city = null, name = "")
        assertThat(IdleRecommender.chipLabel(a)).isNull()
    }

    @Test
    fun `城市与码之间保留一个空格`() {
        val a = airport(1, iata = "PEK", cityZh = "北京")
        val label = IdleRecommender.chipLabel(a)!!

        //   而这个 chip 唯一的信息就是"城市 + 码"，粘住就失去辨识度。
        assertThat(label).contains(" ")
        assertThat(label).isEqualTo("北京 PEK")
    }

    // ─────────────────────────────────────────────────────────
    // recent
    // ─────────────────────────────────────────────────────────

    @Test
    fun `最近去过保持调用方给的顺序不按 popularity 重排`() {

        //   "按 popularity 排看着更合理"是一个非常有诱惑力的错误：
        //   用户上次飞的是 id=3（热度 10），重排后它跑到第三位，
        //   用户就要多扫两眼才能找到 —— 推荐区的价值全在"一眼找到"。
        val recents = listOf(
            airport(3, iata = "SHA", cityZh = "上海", popularity = 10),
            airport(4, iata = "CAN", cityZh = "广州", popularity = 900),
            airport(5, iata = "CTU", cityZh = "成都", popularity = 500),
        )
        val out = IdleRecommender.recent(recents)
        assertThat(out.map { it.id }).containsExactly(3L, 4L, 5L).inOrder()
    }

    @Test
    fun `最近去过按上限截断`() {
        val recents = (1L..10L).map { airport(it, iata = "A${it}B") }
        val out = IdleRecommender.recent(recents, limit = 4)
        assertThat(out).hasSize(4)
        assertThat(out.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `最近去过里的重复 id 只保留第一次出现的`() {
        val recents = listOf(
            airport(7, iata = "PEK", cityZh = "北京"),
            airport(8, iata = "SHA", cityZh = "上海"),
            airport(7, iata = "PEK", cityZh = "北京"),
        )
        val out = IdleRecommender.recent(recents)
        assertThat(out.map { it.id }).containsExactly(7L, 8L).inOrder()
    }

    @Test
    fun `已知库里不存在的最去过机场被剔除`() {

        //   但 Room 里已经没有了。显示它 → 点了拿不到机场对象 → 没反应。
        val recents = listOf(
            airport(7, iata = "PEK", cityZh = "北京"),
            airport(9, iata = "NRT", cityZh = "东京"),
        )
        val known = mapOf(7L to recents[0])
        val out = IdleRecommender.recent(recents, known = known)
        assertThat(out.map { it.id }).containsExactly(7L)
    }

    @Test
    fun `known 为 null 时不做存在性校验`() {

        //   也不要让推荐区空着然后突然弹出来（界面跳一下比点了没反应更像 bug）
        val recents = listOf(airport(9, iata = "NRT", cityZh = "东京"))
        val out = IdleRecommender.recent(recents, known = null)
        assertThat(out).hasSize(1)
    }

    @Test
    fun `known 里存在时用 known 的那一份而不是旧快照`() {

        //   chip 上必须显示**最新**的名字。
        val stale = airport(7, iata = "PEK", city = "Peking", cityZh = null)
        val fresh = airport(7, iata = "PEK", city = "Beijing", cityZh = "北京")
        val out = IdleRecommender.recent(listOf(stale), known = mapOf(7L to fresh))
        assertThat(out.single().cityZh).isEqualTo("北京")
        assertThat(IdleRecommender.chipLabel(out.single())).isEqualTo("北京 PEK")
    }

    @Test
    fun `limit 为 0 时返回空`() {
        val recents = listOf(airport(1))
        assertThat(IdleRecommender.recent(recents, limit = 0)).isEmpty()
    }

    // ─────────────────────────────────────────────────────────
    // popular
    // ─────────────────────────────────────────────────────────

    @Test
    fun `热门按 popularity 降序`() {
        val all = listOf(
            airport(1, iata = "AAA", popularity = 10),
            airport(2, iata = "BBB", popularity = 900),
            airport(3, iata = "CCC", popularity = 500),
        )
        val out = IdleRecommender.popular(all)
        assertThat(out.map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun `同分热门按三字码字典序排以保证两次启动一致`() {

        //   而"原顺序"来自 Room 的 `SELECT *` —— 没有 ORDER BY 时不保证稳定。
        //   没有第二排序键的后果是：同一份数据两次启动给出不同的热门列表，
        //   用户会觉得"推荐在乱跳"，然后彻底不用它。
        val a = airport(1, iata = "ZZZ", popularity = 500)
        val b = airport(2, iata = "AAA", popularity = 500)
        val c = airport(3, iata = "MMM", popularity = 500)
        val out = IdleRecommender.popular(listOf(a, b, c))
        assertThat(out.map { it.iataCode }).containsExactly("AAA", "MMM", "ZZZ").inOrder()
    }

    @Test
    fun `热门剔除 exclude 里的 id`() {
        val all = listOf(
            airport(1, iata = "PEK", popularity = 900),
            airport(2, iata = "SHA", popularity = 800),
        )
        val out = IdleRecommender.popular(all, exclude = setOf(1L))
        assertThat(out.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `popularity 为 0 的不进热门`() {

        //   把它们排进去等于按数据库主键顺序推荐 —— 那是最差的一种推荐。
        val all = listOf(
            airport(1, iata = "AAA", popularity = 0),
            airport(2, iata = "BBB", popularity = 5),
        )
        val out = IdleRecommender.popular(all)
        assertThat(out.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `没有 IATA 码的机场不进热门`() {

        //   放进来 chip 上会写 `Heliport`，被截成 `Heli…`。
        val all = listOf(
            airport(1, iata = null, name = "Some Heliport", popularity = 900),
            airport(2, iata = "PEK", popularity = 800),
        )
        val out = IdleRecommender.popular(all)
        assertThat(out.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `付费机场照常进热门`() {

        //   拦截放在点击之后（引导购买），而不是假装它不存在。
        val all = listOf(airport(1, iata = "PEK", popularity = 900, premium = true))
        val out = IdleRecommender.popular(all)
        assertThat(out.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `热门按上限截断`() {
        val all = (1L..20L).map { airport(it, iata = "A${it.toString().padStart(2, '0')}", popularity = 100) }
        val out = IdleRecommender.popular(all, limit = 8)
        assertThat(out).hasSize(8)
    }

    // ─────────────────────────────────────────────────────────
    // build
    // ─────────────────────────────────────────────────────────

    @Test
    fun `最近与热门之间不出现重复机场`() {

        //   怀疑"这是两个不同的北京吗"。
        val beijing = airport(1, iata = "PEK", cityZh = "北京", popularity = 900)
        val shanghai = airport(2, iata = "SHA", cityZh = "上海", popularity = 800)
        val out = IdleRecommender.build(recents = listOf(beijing), all = listOf(beijing, shanghai))
        assertThat(out.recent.map { it.id }).containsExactly(1L)
        assertThat(out.popular.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `两段都空时 isBare 为真`() {
        val out = IdleRecommender.build(emptyList(), emptyList())
        assertThat(out.isBare).isTrue()
    }

    @Test
    fun `只有热门不为空时 isBare 为假`() {
        val out = IdleRecommender.build(emptyList(), listOf(airport(1, iata = "PEK")))
        assertThat(out.isBare).isFalse()
        assertThat(out.recent).isEmpty()
        assertThat(out.popular).hasSize(1)
    }

    @Test
    fun `只有最近不为空时 isBare 为假`() {
        val out = IdleRecommender.build(listOf(airport(1, iata = "PEK")), emptyList())
        assertThat(out.isBare).isFalse()
    }

    @Test
    fun `无障碍概览含两段的数量`() {
        val bj = airport(1, iata = "PEK", cityZh = "北京")
        val sh = airport(2, iata = "SHA", cityZh = "上海")
        val out = IdleRecommender.build(
            recents = listOf(bj),
            all = listOf(bj, sh, airport(3, iata = "CAN", cityZh = "广州")),
        )

        assertThat(out.spokenOverview).contains("最近去过 1 个")
        assertThat(out.spokenOverview).contains("热门城市 2 个")
    }

    @Test
    fun `无障碍概览不念空段`() {
        val out = IdleRecommender.build(listOf(airport(1, iata = "PEK")), emptyList())
        assertThat(out.spokenOverview).isEqualTo("最近去过 1 个")
        assertThat(out.spokenOverview).doesNotContain("热门")
    }

    @Test
    fun `全空时概览是空串`() {
        assertThat(IdleRecommendation.EMPTY.spokenOverview).isEmpty()
        assertThat(IdleRecommendation.EMPTY.isBare).isTrue()
    }
}
