package com.projectfocus.service.focus

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

































class DimensParityTest {

    private val moduleDimens = File("src/main/res/values/dimens.xml")
    private val coreUiDimens = File("../core/core-ui/src/main/res/values/dimens.xml")

    /** 本模块自带的刻度必须与 core-ui 同名同值 */
    @Test
    fun `本模块的间距刻度与 core-ui 一致`() {

        //   跳过的检查等于没有检查。
        assertThat(coreUiDimens.isFile)
            .isTrue()

        val ours = readDimens(moduleDimens)
        val theirs = readDimens(coreUiDimens)

        assertThat(ours).isNotEmpty()

        val mismatched = mutableListOf<String>()
        val missing = mutableListOf<String>()
        ours.forEach { (name, value) ->
            val reference = theirs[name]
            when {
                reference == null -> missing += name
                reference != value -> mismatched += "$name: 本模块=$value，core-ui=$reference"
            }
        }

        assertThat(missing)
            .isEmpty()
        assertThat(mismatched)
            .isEmpty()
    }

    /** 重复定义的语义色在日夜两套资源中都必须与设计系统一致。 */
    @Test
    fun `遮挡页日夜颜色与 core-ui 一致`() {
        val tokens = mapOf(
            "focus_blocked_bg" to "bg_base",
            "focus_blocked_title" to "text_primary",
            "focus_blocked_body" to "text_secondary",
            "focus_blocked_hint" to "text_tertiary",
            "focus_blocked_action_bg" to "text_primary",
            "focus_blocked_action_text" to "bg_base",
        )
        for (qualifier in listOf("values", "values-night")) {
            val ours = readColors(File("src/main/res/$qualifier/colors.xml"))
            val theirs = readColors(File("../core/core-ui/src/main/res/$qualifier/colors.xml"))
            for ((name, token) in tokens) {
                assertThat(ours).containsKey(name)
                assertThat(theirs).containsKey(token)
                assertThat(normalizeHex(ours.getValue(name)))
                    .isEqualTo(normalizeHex(theirs.getValue(token)))
            }
        }
    }

    // ==================================================================
    // 解析
    // ==================================================================

    private fun readDimens(file: File): Map<String, String> =
        readNamedResources(file, "dimen")

    private fun readColors(file: File): Map<String, String> =
        readNamedResources(file, "color")

    private fun readNamedResources(file: File, tag: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()

        //   但「测试代码里跑了一个默认开 XXE 的解析器」本身就是个坏示范。
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = factory.newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName(tag)
        val result = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            if (name.isEmpty()) continue
            result[name] = el.textContent?.trim().orEmpty()
        }
        return result
    }

    /**
     * 把 `#RGB` / `#ARGB` / `#RRGGBB` / `#AARRGGBB` 统一成 8 位大写 ARGB。
     * 解析不了就原样返回（让断言报出原始值的差异，比抛异常更好读）。
     */
    private fun normalizeHex(raw: String): String {
        val hex = raw.trim().removePrefix("#").uppercase()
        return when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> raw.trim()
        }
    }
}
