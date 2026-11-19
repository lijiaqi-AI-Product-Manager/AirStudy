package com.projectfocus.feature.airport.search


object RecentAirportsCodec {








    const val MAX_ENTRIES: Int = 10

    /** 序号宽度。4 位够到 9999 项，远超 [MAX_ENTRIES] */
    private const val SEQ_WIDTH = 4

    private const val SEP = '#'











    fun encode(ids: List<Long>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>(minOf(ids.size, MAX_ENTRIES) * 2)

        //   只保留**第一次**（最新）的位置。
        //   否则 Set 里会出现 `0000#7` 和 `0003#7` —— 两个都合法，
        //   解码出两个 7，推荐区显示两个"北京"。
        val seen = HashSet<Long>(ids.size * 2)
        var seq = 0
        for (id in ids) {
            if (seq >= MAX_ENTRIES) break
            if (!seen.add(id)) continue
            out += format(seq, id)
            seq++
        }
        return out
    }

    /**
     * 解码。永不抛异常，坏项丢弃，按序号还原顺序。
     *
     * @return 机场 id，最近优先
     */
    fun decode(raw: Set<String>?): List<Long> {
        if (raw.isNullOrEmpty()) return emptyList()

        //   排序在解析**之前**做：这样即使有坏项也不影响好项的顺序。
        //   （如果先解析成 (seq, id) 对再排序，坏项要被跳过两次 ——
        //     一次解析、一次排序，容易漏。）
        val sorted = raw.filter { it.isNotBlank() }.sorted()
        val out = ArrayList<Long>(minOf(sorted.size, MAX_ENTRIES))
        val seen = HashSet<Long>(sorted.size * 2)
        for (entry in sorted) {
            val id = parseId(entry) ?: continue

            //   并发写入会产生 `0000#7` / `0003#7`，不在这里去重的话
            //   推荐区会出现两个"北京" —— 用户以为 App 出错了。
            if (!seen.add(id)) continue
            out += id
            if (out.size >= MAX_ENTRIES) break
        }
        return out
    }

    /**
     * 把「刚用过某个机场」推到最前面。
     *
     * 这是唯一的写入口 —— 所有"更新最近"的场景都走这里，
     * 而不是让调用方自己 `listOf(new) + old`（那会漏掉截断和去重）。
     *
     * @return 新的完整列表（最近优先），调用方拿去 [encode] 即可
     */
    fun promote(existing: List<Long>, id: Long): List<Long> {

        //   用户第二次用同一个机场，它要回到第一位，
        //   而不是留在原来那一格（那就不叫"最近"了）。
        val out = ArrayList<Long>(minOf(existing.size + 1, MAX_ENTRIES))
        out += id
        for (e in existing) {
            if (e == id) continue
            if (out.size >= MAX_ENTRIES) break
            out += e
        }
        return out
    }

    /** 从列表里移除（用户清空了某个机场记录时用） */
    fun remove(existing: List<Long>, id: Long): List<Long> = existing.filter { it != id }

    // ─────────────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────────────

    private fun format(seq: Int, id: Long): String =
        seq.toString().padStart(SEQ_WIDTH, '0') + SEP + id








    private fun parseId(entry: String): Long? {
        val sep = entry.indexOf(SEP)
        if (sep < 0) return null

        //   校验它而不是直接忽略 —— 一个 `"abc#12"` 会被 `sorted` 排到最前，
        //   然后解析出 12 并占据"最近第一位"。这是最糟的一种脏数据：
        //   **它看起来是合法的。**
        val head = entry.substring(0, sep)
        if (head.isEmpty() || head.any { it !in '0'..'9' }) return null
        val tail = entry.substring(sep + 1)
        if (tail.isEmpty() || tail.any { it !in '0'..'9' }) return null
        return tail.toLongOrNull()
    }
}
