package com.projectfocus.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

//
// 扫描业务模块里的硬编码颜色。
//
// ═══════════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════════
//

//   而且大量是「按用途起名的裸色」：
//
//     account_entry_text      #333333
//     account_divider         #c7c7cc
//     account_section_title   #8e8e93
//     auth_divider            #e0e0e0
//
//   后果（不是「代码难看」，是**真实的功能缺陷**）：
//     · 同一个 #333333 被 9 个名字分别定义，改深色模式时改了 8 个还剩 1 个
//     · 深色主题下 `auth_input_hint = #80000000`（半透明黑）压在深色输入框上
//       → 提示文字看不见

//
//   所以这条门禁是**硬约束**：业务代码里出现 `#RRGGBB` 就构建失败。
//
// ═══════════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════════
//
//   1. `core/core-ui/src/main/res/values*/colors.xml` —— token 的**定义处**，
//      这里是唯一允许写十六进制的地方
//   2. `*.agsl` 着色器 —— GLSL 没有资源系统，颜色必须内联
//   3. `#00000000` / `@android:color/transparent` —— 全透明不是「颜色选择」
//   4. `res/values/*.xml` 里的 `<color name="...">` 定义（不是引用）
//
// ═══════════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════════
//
//   `#` 在 Kotlin 里出现的地方太多：注释、KDoc、字符串模板、URL 片段、
//   issue 编号（#123）、颜色（#RRGGBB）。用一条粗暴的 `#[0-9a-fA-F]{6}`
//   会把 `"见 issue #123456"` 这种注释也算进去。
//
//   正确做法是**按文件类型分别处理**：
//     · Kotlin/Java：先去掉行注释与块注释，再找 `Color.parseColor("` 开头的调用，
//       以及 `0xAARRGGBB` 形式的字面量
//     · XML：只扫属性值（等号后的双引号内），不扫注释、不扫 color 标签定义
//
//   这样误报率能压到接近零 —— 一条会误报的门禁，最后一定会被人用
//   `@Suppress` 或直接注释掉，等于没有。
//
abstract class CheckNoHardcodedColorsTask : DefaultTask() {

    /** 要扫描的源码与资源文件 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection









    @get:Input
    abstract val allowedPathPrefixes: SetProperty<String>






















    @get:Input
    abstract val allowedExactFiles: SetProperty<String>

    /** 相对路径的计算基准（根项目目录） */
    @get:Internal
    abstract val rootDirPath: Property<String>

    /** 报告输出（构建失败时供人查看在哪一行） */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val violations = mutableListOf<Violation>()
        var scanned = 0

        sources.files.filter { it.isFile }.forEach { file ->
            scanned++

            //   早期版本只在「<color> 定义」这一条分支里判断白名单，
            //   结果 app/src/main/res/drawable/*.xml 里的 fillColor="#FFFFFF" 照样报违规 ——
            //   白名单形同虚设。这个 bug 是被测试抓出来的（见 CheckNoHardcodedColorsTaskTest
            //   的「白名单位置允许写色值」用例）。判断必须放在**文件级**，在分派之前。
            if (isAllowed(file.relativePath())) return@forEach
            when (file.extension.lowercase()) {
                "kt", "kts", "java" -> violations += scanCode(file)
                "xml" -> violations += scanXml(file)
                else -> Unit // agsl / png / ttf 等不扫
            }
        }

        val reportText = buildString {
            appendLine("# 硬编码颜色扫描报告")
            appendLine()
            appendLine("扫描文件数：$scanned")
            appendLine("白名单目录：${allowedPathPrefixes.getOrElse(emptySet()).sorted().joinToString { "`$it`" }}")
            appendLine("白名单文件：${allowedExactFiles.getOrElse(emptySet()).sorted().joinToString { "`$it`" }}")
            appendLine()
            if (violations.isEmpty()) {
                appendLine("结果：**通过**（0 处违规）")
            } else {
                appendLine("结果：**失败**（${violations.size} 处违规）")
                appendLine()
                appendLine("| 文件 | 行 | 内容 |")
                appendLine("|---|---|---|")
                violations.forEach { v ->
                    appendLine("| `${v.relativePath}` | ${v.line} | `${v.snippet}` |")
                }
                appendLine()
                appendLine("## 修复方式")
                appendLine()
                appendLine("把色值换成 `@color/语义名`（XML）或 `ThemeTokens.xxx`（Kotlin）。")
                appendLine("如果确实需要新颜色，先在 `core-ui/src/main/res/values/colors.xml` 里")
                appendLine("加一个**语义命名**的 token（例如 `semantic_warning_container`），再引用它。")
            }
        }
        val out = report.get().asFile
        out.parentFile?.mkdirs()
        out.writeText(reportText)

        if (violations.isNotEmpty()) {


            //   只有 1 条（提示信息被重复 20 遍挤在一起），读者会以为「就一处」。
            //   聚合后写「该文件 25 处」，一眼看得出严重程度。
            val summary = violations.groupBy { it.relativePath }
                .entries
                .sortedByDescending { it.value.size }
                .take(10)
                .joinToString("\n") { (path, list) ->
                    val lines = list.map { it.line }.distinct().sorted().take(12).joinToString(",")
                    "  $path  —— ${list.size} 处（行 $lines${if (list.map { it.line }.distinct().size > 12) " …" else ""}）"
                }
            throw GradleException(
                "发现 ${violations.size} 处硬编码颜色，涉及 ${violations.map { it.relativePath }.distinct().size} 个文件。" +
                    "详见 ${out.absolutePath}\n$summary",
            )
        }
    }

    // ==================================================================
    // Kotlin / Java
    // ==================================================================

    private fun scanCode(file: File): List<Violation> {
        val raw = file.readText()
        val stripped = stripComments(raw)
        val result = mutableListOf<Violation>()

        // ① Color.parseColor("#RRGGBB") / Color.rgb(...) / Color.argb(...)
        val parseColor = Regex("""Color\s*\.\s*(parseColor|rgb|argb)\s*\(""")
        parseColor.findAll(stripped).forEach { m ->
            val line = lineOf(stripped, m.range.first)
            result += Violation(file.relativePath(), line, snippetAt(stripped, m.range.first))
        }

        // ② 0xAARRGGBB / 0xRRGGBB 字面量（8 位或 6 位十六进制，后面不能再跟十六进制字符）

        val hexLiteral = Regex("""(?<![\w.])0x([0-9a-fA-F]{8})(?![0-9a-fA-F])""")
        hexLiteral.findAll(stripped).forEach { m ->
            val hex = m.groupValues[1]
            // 排除常见掩码：全 0、全 F、0xFFFFFF00（位掩码风格，末两位为 00）
            val isMaskLike = hex.equals("00000000", true) ||
                hex.equals("ffffffff", true) ||
                hex.endsWith("00", true) && hex.startsWith("ffffff", true)
            if (!isMaskLike) {
                val line = lineOf(stripped, m.range.first)
                result += Violation(file.relativePath(), line, snippetAt(stripped, m.range.first))
            }
        }

        // ③ android.graphics.Color 的常量里带颜色的（RED/BLUE/... 不算，那是语义引用）
        //    这里只扫 `Color.rgb(` 已覆盖，跳过。

        return result
    }














    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var inString = false
        var inChar = false
        var inRawString = false

        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'

            when {
                inLine -> {
                    if (c == '\n') { inLine = false; sb.append(c) } else sb.append(' ')
                }
                inBlock -> {
                    if (c == '*' && next == '/') { inBlock = false; sb.append("  "); i++ } else {
                        sb.append(if (c == '\n') c else ' ')
                    }
                }
                inRawString -> {
                    if (c == '"' && next == '"' && i + 2 < src.length && src[i + 2] == '"') {
                        inRawString = false; sb.append("\"\"\""); i += 2
                    } else sb.append(c)
                }
                inString -> {
                    if (c == '\\') { sb.append(c); if (next != '\u0000') { sb.append(next); i++ } } else if (c == '"') {
                        inString = false; sb.append(c)
                    } else sb.append(c)
                }
                inChar -> {
                    if (c == '\\') { sb.append(c); if (next != '\u0000') { sb.append(next); i++ } } else if (c == '\'') {
                        inChar = false; sb.append(c)
                    } else sb.append(c)
                }
                else -> when {
                    c == '/' && next == '/' -> { inLine = true; sb.append("  "); i++ }
                    c == '/' && next == '*' -> { inBlock = true; sb.append("  "); i++ }
                    c == '"' && next == '"' && i + 2 < src.length && src[i + 2] == '"' -> {
                        inRawString = true; sb.append("\"\"\""); i += 2
                    }
                    c == '"' -> { inString = true; sb.append(c) }
                    c == '\'' -> { inChar = true; sb.append(c) }
                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    // ==================================================================
    // XML
    // ==================================================================










    private fun scanXml(file: File): List<Violation> {
        val rel = file.relativePath()
        val result = mutableListOf<Violation>()

        // ① 先去掉注释
        val withoutComments = file.readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)) { m ->
            "\n".repeat(m.value.count { it == '\n' })
        }

        // ② 找所有 attr="value" 形式的属性
        val attrPattern = Regex("""([\w:]+)\s*=\s*"([^"]*)"""")
        attrPattern.findAll(withoutComments).forEach { m ->
            val value = m.groupValues[2]
            if (looksLikeHardcodedColor(value)) {
                val line = lineOf(withoutComments, m.range.first)
                result += Violation(rel, line, "${m.groupValues[1]}=\"$value\"")
            }
        }

        // ③ <color name="...">#RRGGBB</color> 的定义：只允许出现在白名单路径里。
        //    feature / widget 模块里不允许 —— 那是「第二套色值表」的起点。
        //

        //       它是「代码里再写一遍配置」，改了白名单还得改这里，两处必然漂移。
        //       统一走 allowedPathPrefixes。
        if (!isAllowed(rel)) {
            val colorDef = Regex("""<color\b[^>]*>([^<]*)</color>""")
            colorDef.findAll(withoutComments).forEach { m ->
                val value = m.groupValues[1].trim()
                if (looksLikeHardcodedColor(value)) {
                    val line = lineOf(withoutComments, m.range.first)
                    result += Violation(
                        rel, line,
                        "颜色定义 ${m.value.replace(Regex("\\s+"), " ").take(80)} —— 应在 core-ui 的 token 里定义后引用",
                    )
                }
            }
        }

        return result
    }










    private fun isAllowed(relativePath: String): Boolean {
        if (allowedExactFiles.getOrElse(emptySet()).any { it == relativePath }) return true
        return allowedPathPrefixes.getOrElse(emptySet()).any { prefix ->
            relativePath.startsWith(prefix.trimEnd('/') + "/")
        }
    }


    /** #RRGGBB / #AARRGGBB / #RGB / #ARGB */
    private fun looksLikeHardcodedColor(value: String): Boolean {
        val v = value.trim()
        // @color/xxx 或 ?attr/xxx 是引用，允许
        if (v.startsWith("@") || v.startsWith("?")) return false
        // 全透明允许（它不是「选择了一个颜色」）
        if (v.equals("#00000000", true) || v.equals("#0000", true)) return false
        return Regex("""^#([0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$""").matches(v)
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private fun lineOf(text: String, index: Int): Int {
        var line = 1
        for (i in 0 until minOf(index, text.length)) if (text[i] == '\n') line++
        return line
    }

    private fun snippetAt(text: String, index: Int): String {
        val start = maxOf(0, index - 20)
        val end = minOf(text.length, index + 60)
        return text.substring(start, end)
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }








    private fun File.relativePath(): String {
        val root = rootDirPath.getOrElse("").replace('\\', '/').trimEnd('/')
        val self = path.replace('\\', '/')
        return if (root.isNotEmpty() && self.startsWith("$root/")) {
            self.removePrefix("$root/")
        } else {
            self
        }
    }

    data class Violation(val relativePath: String, val line: Int, val snippet: String)
}
