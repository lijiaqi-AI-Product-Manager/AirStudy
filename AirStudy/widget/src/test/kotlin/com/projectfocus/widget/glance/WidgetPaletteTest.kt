package com.projectfocus.widget.glance

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory









































class WidgetPaletteTest {

    private val coreColors = File("../core/core-ui/src/main/res/values/colors.xml")

    /** token 名 → 归一化后的 #AARRGGBB */
    private fun coreColorMap(): Map<String, String> {
        assertThat(coreColors.isFile).isTrue()
        val factory = DocumentBuilderFactory.newInstance()

        //   但「测试里跑了一个默认开 XXE 的解析器」本身就是坏示范。
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = factory.newDocumentBuilder().parse(coreColors)
        val nodes = doc.getElementsByTagName("color")
        val result = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            if (name.isEmpty()) continue
            result[name] = normalize(el.textContent?.trim().orEmpty())
        }
        return result
    }

    /** Compose Color → #AARRGGBB，与 xml 写法可直接比较 */
    private fun Color.toHex(): String {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        val r = (red * 255f).toInt().coerceIn(0, 255)
        val g = (green * 255f).toInt().coerceIn(0, 255)
        val b = (blue * 255f).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X%02X".format(a, r, g, b)
    }






















    private fun normalize(raw: String): String {
        val hex = raw.trim().removePrefix("#").uppercase()
        return when (hex.length) {
            3 -> "#FF" + hex.map { "$it$it" }.joinToString("")
            4 -> "#" + hex.map { "$it$it" }.joinToString("")
            6 -> "#FF$hex"
            8 -> "#$hex"
            else -> raw.trim()
        }
    }


    @Test
    fun `normalize 的输出始终以井号开头且长度为 9`() {
        listOf("#0B0E14", "#FF0B0E14", "FF0B0E14", "#0BE", "  #ff0b0e14  ").forEach { input ->
            val out = normalize(input)
            assertThat(out.startsWith("#")).isTrue()
            assertThat(out.length).isEqualTo(9)
        }
        // 具体值也要对
        assertThat(normalize("#0B0E14")).isEqualTo("#FF0B0E14")
        assertThat(normalize("FF0B0E14")).isEqualTo("#FF0B0E14")
        assertThat(normalize("#0BE")).isEqualTo("#FF00BBEE")
    }

    /** 断言某个 token 存在且与调色板值一致 —— 单个断言，失败信息最具体 */
    private fun assertToken(tokens: Map<String, String>, token: String, actual: Color) {
        assertThat(tokens).containsKey(token)
        assertThat(actual.toHex()).isEqualTo(tokens.getValue(token))
    }

    @Test
    fun `bg_base 一致`() = assertToken(coreColorMap(), "bg_base", WidgetPalette.BgBase)

    @Test
    fun `bg_elevated 一致`() = assertToken(coreColorMap(), "bg_elevated", WidgetPalette.BgElevated)

    @Test
    fun `text_primary 一致`() = assertToken(coreColorMap(), "text_primary", WidgetPalette.TextPrimary)

    @Test
    fun `text_secondary 一致`() =
        assertToken(coreColorMap(), "text_secondary", WidgetPalette.TextSecondary)

    @Test
    fun `text_tertiary 一致`() = assertToken(coreColorMap(), "text_tertiary", WidgetPalette.TextTertiary)

    @Test
    fun `semantic_warning 一致`() = assertToken(coreColorMap(), "semantic_warning", WidgetPalette.Warning)








    @Test
    fun `accent_primary 解析间接引用后一致`() {
        val tokens = coreColorMap()
        assertThat(tokens).containsKey("accent_primary")
        val raw = tokens.getValue("accent_primary")
        val resolved = if (raw.startsWith("@color/")) {
            val target = raw.removePrefix("@color/")
            assertThat(tokens).containsKey(target)
            tokens.getValue(target)
        } else {
            raw
        }
        assertThat(WidgetPalette.AccentPrimary.toHex()).isEqualTo(resolved)
    }

    // ═══════════════════════════════════════════════════════════
    // 预览图颜色一致性
    // ═══════════════════════════════════════════════════════════
    //

    //   于是它成了硬编码的**第三处**：WidgetPalette（Kotlin）、preview 色板（XML）、
    //   以及 preview 矢量图里对色板名的引用。前两处之间会漂移，所以要有测试。
    //

    //   「用户第一次添加小组件、看一眼选择器」时被看到一次。
    //   没有任何反馈渠道。→ 只能靠机器守。

    private val widgetColorsFile = File("src/main/res/values/colors.xml")

    private fun widgetPreviewColors(): Map<String, String> {
        assertThat(widgetColorsFile.isFile).isTrue()
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = factory.newDocumentBuilder().parse(widgetColorsFile)
        val nodes = doc.getElementsByTagName("color")
        val result = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            if (name.isEmpty()) continue
            result[name] = normalize(el.textContent?.trim().orEmpty())
        }
        return result
    }

    private fun assertPreview(previewName: String, actual: Color) {
        val tokens = widgetPreviewColors()
        assertThat(tokens).containsKey(previewName)
        assertThat(actual.toHex()).isEqualTo(tokens.getValue(previewName))
    }

    @Test
    fun `预览图底色与调色板一致`() = assertPreview("widget_preview_bg", WidgetPalette.BgBase)

    @Test
    fun `预览图主文字色与调色板一致`() = assertPreview("widget_preview_text_primary", WidgetPalette.TextPrimary)

    @Test
    fun `预览图次文字色与调色板一致`() =
        assertPreview("widget_preview_text_secondary", WidgetPalette.TextSecondary)

    @Test
    fun `预览图轨道色与调色板一致`() = assertPreview("widget_preview_track", WidgetPalette.BgElevated)

    @Test
    fun `预览图强调色与调色板一致`() = assertPreview("widget_preview_accent", WidgetPalette.AccentPrimary)









    @Test
    fun `预览色板里没有未被引用的项`() {
        val used = HashSet<String>()
        File("src/main/res/drawable").listFiles().orEmpty()
            .filter { it.name.startsWith("widget_preview") && it.name.endsWith(".xml") }
            .forEach { f ->
                Regex("@color/([a-z0-9_]+)").findAll(f.readText()).forEach { used += it.groupValues[1] }
            }
        val declared = widgetPreviewColors().keys.filter { it.startsWith("widget_preview_") }
        assertThat(declared).isNotEmpty()
        declared.forEach { name ->
            assertThat(used).contains(name)
        }
    }








    @Test
    fun `每张预览图都被某个 provider 引用`() {
        val referenced = HashSet<String>()
        File("src/main/res/xml").listFiles().orEmpty()
            .filter { it.name.endsWith(".xml") }
            .forEach { f ->
                Regex("@drawable/([a-z0-9_]+)").findAll(f.readText()).forEach { referenced += it.groupValues[1] }
            }
        val previews = File("src/main/res/drawable").listFiles().orEmpty()
            .filter { it.name.startsWith("widget_preview") && it.name.endsWith(".xml") }
            .map { it.nameWithoutExtension }
        // 四个小组件里已实现两个；剩下两个（StreakWidget / 连续天数）
        // 的预览图还没画，所以这里只断言「已有的都被引用」。
        assertThat(previews).isNotEmpty()
        previews.forEach { name ->
            assertThat(referenced).contains(name)
        }
    }











    @Test
    fun `预览图的 pathData 结构合法`() {
        val allowed = "MmLlHhVvAaCcSsQqTtZz"
        File("src/main/res/drawable").listFiles().orEmpty()
            .filter { it.name.startsWith("widget_preview") && it.name.endsWith(".xml") }
            .forEach { f ->
                val text = f.readText()
                Regex("android:pathData=\"([^\"]*)\"").findAll(text).forEach { m ->
                    val d = m.groupValues[1]
                    assertWithMessage("${f.name} 的 pathData 为空").that(d.isNotBlank()).isTrue()
                    // 命令字符合法性
                    val commands = d.filter { it.isLetter() }
                    commands.forEach { c ->
                        assertWithMessage("${f.name} 含非法命令 $c").that(allowed.contains(c)).isTrue()
                    }
                    // 不含 NaN / Infinity（手算坐标时容易写出来）
                    assertWithMessage("${f.name} 含 NaN").that(d.contains("NaN")).isFalse()
                    assertWithMessage("${f.name} 含 Infinity").that(d.contains("Infinity")).isFalse()
                    // 数字必须是合法小数
                    Regex("[0-9.]+").findAll(d).forEach { n ->
                        assertWithMessage("${f.name} 数字格式错误：${n.value}")
                            .that(n.value.toDoubleOrNull() != null).isTrue()
                    }
                }
            }
    }









    @Test
    fun `core-ui 颜色值不含零宽或异常空白字符`() {
        val suspicious = listOf(
            '\u200B', // ZERO WIDTH SPACE
            '\u200C', // ZERO WIDTH NON-JOINER
            '\u200D', // ZERO WIDTH JOINER
            '\uFEFF', // ZERO WIDTH NO-BREAK SPACE / BOM
            '\u00A0', // NBSP
            '\u2028', '\u2029',
        )
        coreColorMap().forEach { (name, value) ->
            suspicious.forEach { ch ->
                assertThat(value.contains(ch)).isFalse()
                assertThat(name.contains(ch)).isFalse()
            }

            //   accent_primary 和 seat_mine 都指向 _accent_primary，这是刻意的
            //   「内部基色 + 语义别名」结构（见 colors.xml 顶部注释）。
            //   它们不是十六进制值，格式校验对它们无意义 —— 例外只有一个：
            //   必须仍然是 @color/ 形式，不能是 @android:color 或别处拼来的裸值。
            if (value.startsWith("@")) {
                assertThat(value.startsWith("@color/")).isTrue()
            } else {
                // 真正的色值只允许 # 与十六进制字符
                assertThat(value.matches(Regex("^#[0-9A-F]{6,8}$"))).isTrue()
            }
        }
    }
}
