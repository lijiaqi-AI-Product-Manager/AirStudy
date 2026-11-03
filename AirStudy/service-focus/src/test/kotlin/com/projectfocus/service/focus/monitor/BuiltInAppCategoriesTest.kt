package com.projectfocus.service.focus.monitor

import com.google.common.truth.Truth.assertThat
import org.junit.Test




























class BuiltInAppCategoriesTest {

    @Test
    fun `已知分类能展开成非空包名集合`() {
        BuiltInAppCategories.knownCategories().forEach { category ->
            assertThat(BuiltInAppCategories.expand(category)).isNotEmpty()
        }
    }

    @Test
    fun `未知分类展开为空集合而不是抛异常`() {
        assertThat(BuiltInAppCategories.expand("不存在的分类")).isEmpty()
        assertThat(BuiltInAppCategories.expand("")).isEmpty()
    }

    @Test
    fun `分类名大小写不敏感`() {
        assertThat(BuiltInAppCategories.expand("SOCIAL"))
            .isEqualTo(BuiltInAppCategories.expand("social"))
    }











    @Test
    fun `同一个包名不能出现在两个分类里`() {
        val seen = mutableMapOf<String, String>()
        val duplicates = mutableListOf<String>()

        BuiltInAppCategories.knownCategories().sorted().forEach { category ->
            BuiltInAppCategories.expand(category).forEach { pkg ->
                val previous = seen.put(pkg, category)
                if (previous != null && previous != category) {
                    duplicates += "$pkg 同时在「$previous」和「$category」"
                }
            }
        }

        assertThat(duplicates).isEmpty()
    }

    /** 反查：抖音应该只在「video」里 */
    @Test
    fun `抖音只属于视频分类`() {
        assertThat(BuiltInAppCategories.categoriesOf("com.ss.android.ugc.aweme"))
            .containsExactly("video")
    }

    /** 微信属于社交 */
    @Test
    fun `微信属于社交分类`() {
        assertThat(BuiltInAppCategories.categoriesOf("com.tencent.mm"))
            .containsExactly("social")
    }

    @Test
    fun `没收录的包名反查为空`() {
        assertThat(BuiltInAppCategories.categoriesOf("com.example.unknown")).isEmpty()
    }


























    @Test
    fun `内置包名不含空白与非法字符`() {
        // Java 包名字符集：字母、数字、下划线、美元符、点。applicationId 实际只用前三 + 点
        val legalChar = Regex("^[A-Za-z0-9_.$]+$")
        val bad = mutableListOf<String>()
        BuiltInAppCategories.knownCategories().forEach { category ->
            BuiltInAppCategories.expand(category).forEach { pkg ->
                val reason = when {
                    pkg.isBlank() -> "空串"
                    pkg.any { it.isWhitespace() } -> "含空白字符"
                    !legalChar.matches(pkg) -> "含非法字符"
                    '.' !in pkg -> "不含包名分隔点"
                    else -> null
                }
                if (reason != null) bad += "$category → 「$pkg」（$reason）"
            }
        }
        assertThat(bad).isEmpty()
    }
}
