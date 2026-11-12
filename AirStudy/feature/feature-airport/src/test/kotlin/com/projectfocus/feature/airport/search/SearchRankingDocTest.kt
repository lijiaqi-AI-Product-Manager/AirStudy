package com.projectfocus.feature.airport.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 「文档即数据」第三例：把 `docs/search-ranking.md` §2 的层级表与
 * `AirportSearchIndex.Tier` 枚举**双向**绑定。
 *
 * ─────────────────────────────────────────────────────────────
 * 为什么这张表特别需要机器校验
 *
 *   §2 的层级表规定了 8 个 tier 的**顺序**。这个顺序直接决定搜索结果的排序。
 *   如果有人：
 *     · 在枚举里插入一个新 tier（比如 `ALIAS`），但忘了写进文档
 *     · 或者调整了两个 tier 的相对位置（比如把 `CHINESE` 挪到 `NAME_PREFIX` 前），
 *       文档里还写着旧顺序
 *   那么文档就在**说谎**，而说谎的文档比没有文档更糟 ——
 *   后人会照着一份错的顺序去改代码，改完还觉得很安全。
 *
 *   两张表（枚举 vs Markdown）都活在仓库里，但它们永远不会自己吵架。
 *   这个测试就是让它们吵架。
 * ─────────────────────────────────────────────────────────────
 */
class SearchRankingDocTest {

    private fun docFile(): File {
        // 测试的工作目录是模块目录（feature/feature-airport），
        // 文档在仓库根的 docs/ 下 —— 向上找到含 settings.gradle.kts 的那层。
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "找不到仓库根（settings.gradle.kts）" }
        return File(dir, "docs/search-ranking.md").also {
            require(it.exists()) { "缺少文档：${it.absolutePath}" }
        }
    }











    private fun tiersFromDoc(): List<String> {
        val text = docFile().readText()
        val start = text.indexOf("## 2. 命中层级")
        require(start >= 0) { "文档里找不到 §2 命中层级章节" }
        val end = text.indexOf("## 3.", start).let { if (it < 0) text.length else it }
        val section = text.substring(start, end)

        // 匹配 `| 0 | `CODE_EXACT` |` —— 第一列是数字，第二列是反引号名字
        val re = Regex("""^\|\s*(\d+)\s*\|\s*`([A-Z_]+)`\s*\|""", RegexOption.MULTILINE)
        return re.findAll(section)
            .sortedBy { it.groupValues[1].toInt() }   // 按序数排序，不依赖书写顺序
            .map { it.groupValues[2] }
            .toList()
    }

    @Test
    fun `文档的层级表与 Tier 枚举逐项一致且顺序相同`() {
        val doc = tiersFromDoc()
        val code = AirportSearchIndex.Tier.entries.map { it.name }


        //   子集判断会放过「加了 tier 忘写文档」。
        assertThat(doc).isEqualTo(code)
    }

    @Test
    fun `文档层级表的序数从 0 连续递增`() {

        //   上一条只比较名字序列，如果文档里序数写错但名字顺序对，它仍会绿 ——
        //   而读者会照着序数去理解优先级。
        val text = docFile().readText()
        val section = text.substring(
            text.indexOf("## 2. 命中层级"),
            text.indexOf("## 3.", text.indexOf("## 2. 命中层级")),
        )
        val nums = Regex("""^\|\s*(\d+)\s*\|\s*`[A-Z_]+`\s*\|""", RegexOption.MULTILINE)
            .findAll(section)
            .map { it.groupValues[1].toInt() }
            .toList()
        assertThat(nums).isEqualTo(nums.indices.toList())
    }

    @Test
    fun `文档里写明的性能阈值与测试断言一致`() {

        //   阈值一放松，性能退化就不会被发现，而文档还写着原来的承诺。
        val text = docFile().readText()
        assertThat(text).contains("一百毫秒")
        // 实现里的默认 limit 也必须在文档里出现过（UI 按这个数分页）
        assertThat(text).contains(AirportSearchIndex.DEFAULT_LIMIT.toString())
    }
}
