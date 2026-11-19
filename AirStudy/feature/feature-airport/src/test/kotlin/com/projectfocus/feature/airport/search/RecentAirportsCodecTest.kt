package com.projectfocus.feature.airport.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test


























class RecentAirportsCodecTest {

    // ─────────────────────────────────────────────────────────
    // 往返
    // ─────────────────────────────────────────────────────────

    @Test
    fun `编码后解码保持原顺序`() {
        val ids = listOf(12L, 7L, 33L)
        val encoded = RecentAirportsCodec.encode(ids)
        assertThat(RecentAirportsCodec.decode(encoded)).containsExactly(12L, 7L, 33L).inOrder()
    }

    @Test
    fun `编码结果本身是无序集合但元素自带序号`() {

        //   如果哪天真把它换成 Set.toList()，这条会红。
        val encoded = RecentAirportsCodec.encode(listOf(5L, 9L))
        assertThat(encoded).containsExactly("0000#5", "0001#9")
    }

    @Test
    fun `十位以上的序号也按字典序正确`() {

        //   第 11 项会跑到第 3 项前面。虽然当前上限是 10，
        //   但如果有人把 MAX_ENTRIES 调大，不补零就会静默出错。
        val ids = (1L..10L).toList()
        val out = RecentAirportsCodec.decode(RecentAirportsCodec.encode(ids))
        assertThat(out).isEqualTo(ids)
    }

    @Test
    fun `空列表往返`() {
        assertThat(RecentAirportsCodec.encode(emptyList())).isEmpty()
        assertThat(RecentAirportsCodec.decode(emptySet())).isEmpty()
    }

    // ─────────────────────────────────────────────────────────
    // 上限与去重
    // ─────────────────────────────────────────────────────────

    @Test
    fun `超过上限时截断尾部保留最近的`() {
        val ids = (1L..20L).toList()
        val out = RecentAirportsCodec.decode(RecentAirportsCodec.encode(ids))
        assertThat(out).hasSize(RecentAirportsCodec.MAX_ENTRIES)

        assertThat(out.first()).isEqualTo(1L)
        assertThat(out.last()).isEqualTo(10L)
    }

    @Test
    fun `编码时重复 id 只保留第一次出现的位置`() {

        //   `0000#7` 和 `0002#7` —— 解码出两个 7，推荐区显示两个"北京"。
        val encoded = RecentAirportsCodec.encode(listOf(7L, 3L, 7L))
        assertThat(RecentAirportsCodec.decode(encoded)).containsExactly(7L, 3L).inOrder()
    }

    // ─────────────────────────────────────────────────────────
    // 脏数据
    // ─────────────────────────────────────────────────────────

    @Test
    fun `空串被丢弃`() {
        assertThat(RecentAirportsCodec.decode(setOf(""))).isEmpty()
    }

    @Test
    fun `纯空格被丢弃`() {
        assertThat(RecentAirportsCodec.decode(setOf("   "))).isEmpty()
    }

    @Test
    fun `没有分隔符的项被丢弃`() {
        assertThat(RecentAirportsCodec.decode(setOf("hello"))).isEmpty()
    }

    @Test
    fun `序号不是数字的项被丢弃`() {
        assertThat(RecentAirportsCodec.decode(setOf("abc#12"))).isEmpty()
    }

    @Test
    fun `id 不是数字的项被丢弃`() {
        assertThat(RecentAirportsCodec.decode(setOf("0000#abc"))).isEmpty()
    }

    @Test
    fun `id 为负数的项被丢弃`() {

        //   负数 id 在数据库里不存在，放进去只会在查库时永远 miss。
        assertThat(RecentAirportsCodec.decode(setOf("0000#-5"))).isEmpty()
    }

    @Test
    fun `超长 id 溢出 Long 时被丢弃而不是崩溃`() {

        //   如果用 `toLong()`，这里会抛 NumberFormatException → 搜索页打不开。
        assertThat(RecentAirportsCodec.decode(setOf("0000#99999999999999999999"))).isEmpty()
    }

    @Test
    fun `坏项不影响好项`() {
        val raw = setOf("0000#12", "hello", "0001#7", "0002#oops")
        assertThat(RecentAirportsCodec.decode(raw)).containsExactly(12L, 7L).inOrder()
    }

    @Test
    fun `解码永不抛异常`() {

        val garbage = setOf(
            "", " ", "#", "#1", "1#", "##", "a#b", "-1#2", "0000#", "0000##1",
            "999999999999999999999999#1", "0000#999999999999999999999999",
            "\u0000#1", "0000#1\n",
        )
        // 只要不抛异常就算过 —— 返回什么不重要
        RecentAirportsCodec.decode(garbage)
    }

    @Test
    fun `null 解码成空列表`() {
        assertThat(RecentAirportsCodec.decode(null)).isEmpty()
    }

    @Test
    fun `同一 id 的两个序号只保留最小的那个`() {

        val raw = setOf("0000#7", "0003#7")
        assertThat(RecentAirportsCodec.decode(raw)).containsExactly(7L)
    }

    // ─────────────────────────────────────────────────────────
    // promote
    // ─────────────────────────────────────────────────────────

    @Test
    fun `promote 把已有项移到最前`() {

        //   留在原位就不叫"最近"了。
        val out = RecentAirportsCodec.promote(listOf(1L, 2L, 3L), 3L)
        assertThat(out).containsExactly(3L, 1L, 2L).inOrder()
    }

    @Test
    fun `promote 新项放到最前`() {
        val out = RecentAirportsCodec.promote(listOf(1L, 2L), 9L)
        assertThat(out).containsExactly(9L, 1L, 2L).inOrder()
    }

    @Test
    fun `promote 在满的时候挤掉最老的`() {
        val existing = (1L..10L).toList()
        val out = RecentAirportsCodec.promote(existing, 99L)
        assertThat(out).hasSize(10)
        assertThat(out.first()).isEqualTo(99L)

        assertThat(out).doesNotContain(10L)
        assertThat(out).contains(1L)
    }

    @Test
    fun `promote 到空列表`() {
        assertThat(RecentAirportsCodec.promote(emptyList(), 5L)).containsExactly(5L)
    }

    @Test
    fun `promote 后 encode decode 往返仍然一致`() {
        var list = emptyList<Long>()
        list = RecentAirportsCodec.promote(list, 1L)
        list = RecentAirportsCodec.promote(list, 2L)
        list = RecentAirportsCodec.promote(list, 3L)
        list = RecentAirportsCodec.promote(list, 1L)  // 再用一次 1
        val out = RecentAirportsCodec.decode(RecentAirportsCodec.encode(list))
        assertThat(out).containsExactly(1L, 3L, 2L).inOrder()
    }

    // ─────────────────────────────────────────────────────────
    // remove
    // ─────────────────────────────────────────────────────────

    @Test
    fun `remove 删掉指定 id`() {
        assertThat(RecentAirportsCodec.remove(listOf(1L, 2L, 3L), 2L)).containsExactly(1L, 3L)
    }

    @Test
    fun `remove 不存在的 id 时原样返回`() {
        assertThat(RecentAirportsCodec.remove(listOf(1L, 2L), 9L)).containsExactly(1L, 2L)
    }
}
