package com.projectfocus.buildlogic

import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File






















class CheckNoHardcodedColorsTaskTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var reportFile: File

    @Before
    fun setUp() {
        root = tmp.newFolder("proj")
        reportFile = File(tmp.root, "reports/no-hardcoded-colors.md")
    }

    // ==================================================================
    // 正向：干净代码必须通过
    // ==================================================================

    @Test
    fun `引用 token 的布局不报违规`() {
        write(
            "feature/feature-home/src/main/res/layout/f_home.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="@color/bg_base">
                <TextView android:textColor="@color/text_primary"
                    android:layout_width="wrap_content" android:layout_height="wrap_content" />
            </LinearLayout>
            """.trimIndent(),
        )
        assertThat(runTask()).isEmpty()
    }

    @Test
    fun `注释里的色值不算违规`() {
        write(
            "feature/feature-home/src/main/kotlin/Foo.kt",
            """
            package foo

            // 竞品这里是 #0A59F7（航空蓝），我们走 token
            /**
             * 历史实现：Color.parseColor("#0A59F7")
             * 以及 0xFF0A59F7
             */
            class Foo {
                val c: Int = R.color.accent_primary
            }
            """.trimIndent(),
        )
        write(
            "feature/feature-home/src/main/res/values/strings.xml",
            """
            <resources>
                <!-- 说明：#0A59F7 是品牌色 -->
                <string name="x">@color/accent_primary</string>
            </resources>
            """.trimIndent(),
        )
        assertThat(runTask()).isEmpty()
    }

    @Test
    fun `全透明色不算违规`() {
        write(
            "feature/feature-home/src/main/res/layout/f.xml",
            """<View android:background="#00000000" />""",
        )
        assertThat(runTask()).isEmpty()
    }

    @Test
    fun `白名单位置允许写色值`() {
        write("core/core-ui/src/main/res/values/colors.xml", """<color name="bg_base">#FF0B0E14</color>""")
        write("app/src/main/res/drawable/ic.xml", """<path android:fillColor="#FFFFFFFF" />""")
        assertThat(runTask()).isEmpty()
    }









    @Test
    fun `精确文件白名单只放行那一个文件`() {
        val allowed = "widget/src/main/kotlin/com/projectfocus/widget/glance/WidgetPalette.kt"
        write(allowed, """val a = Color(0xFF0B0E14)""")
        // 同目录下的另一个文件，必须被拦
        write(
            "widget/src/main/kotlin/com/projectfocus/widget/glance/WidgetPaletteExtra.kt",
            """val b = Color(0xFF0B0E14)""",
        )
        val violations = runTask()
        assertThat(violations).hasSize(1)
        assertThat(violations.single().relativePath).contains("WidgetPaletteExtra.kt")
    }

    @Test
    fun `精确文件白名单不做子串匹配`() {

        //   白名单项是 `...WidgetPalette.kt`，而 `...WidgetPalette.ktImpl.kt`
        //   以它为子串但不相等 —— 不该被放行。

        //    所以测试恒为空、假绿。写这类「防实现走偏」的测试时，
        //    必须先确认输入真的会被扫到。）
        write(
            "widget/src/main/kotlin/com/projectfocus/widget/glance/WidgetPalette.kt.extra.kt",
            """val b = Color(0xFF0B0E14)""",
        )
        val violations = runTask()
        assertThat(violations).isNotEmpty()
        assertThat(violations.single().relativePath)
            .isEqualTo("widget/src/main/kotlin/com/projectfocus/widget/glance/WidgetPalette.kt.extra.kt")
    }

    // ==================================================================
    // 反向：脏数据必须被抓住（假阴性的防线）
    // ==================================================================

    @Test
    fun `布局属性里的十六进制被抓住`() {
        write(
            "feature/feature-home/src/main/res/layout/f.xml",
            """
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="#FFFFFF" />
            """.trimIndent(),
        )
        val v = runTask()
        assertThat(v).hasSize(1)
        assertThat(v.first().relativePath).isEqualTo("feature/feature-home/src/main/res/layout/f.xml")
        assertThat(v.first().line).isEqualTo(4)
        assertThat(v.first().snippet).contains("#FFFFFF")
    }

    @Test
    fun `vector fillColor 被抓住`() {
        write(
            "feature/feature-home/src/main/res/drawable/i.xml",
            """<vector><path android:fillColor="#0A59F7" /></vector>""",
        )
        assertThat(runTask().map { it.snippet }).contains("android:fillColor=\"#0A59F7\"")
    }

    @Test
    fun `Kotlin 八位十六进制字面量被抓住`() {
        write(
            "feature/feature-stats/src/main/kotlin/Chart.kt",
            """
            package chart
            class Chart {
                val line = 0xFF34C77B.toInt()
                val grid = 0x1FFFFFFF
            }
            """.trimIndent(),
        )
        val v = runTask()
        // 0x1FFFFFFF 末两位不是 00，也应被抓
        assertThat(v).hasSize(2)
    }

    @Test
    fun `Color parseColor 与 rgb argb 都被抓住`() {
        write(
            "app/src/main/kotlin/A.kt",
            """
            package a
            val a = Color.parseColor("#123456")
            val b = Color.rgb(1, 2, 3)
            val c = Color.argb(255, 1, 2, 3)
            """.trimIndent(),
        )
        assertThat(runTask()).hasSize(3)
    }

    @Test
    fun `业务模块里的 color 定义被抓住`() {
        write(
            "feature/feature-home/src/main/res/values/colors.xml",
            """
            <resources>
                <color name="home_title">#333333</color>
            </resources>
            """.trimIndent(),
        )
        val v = runTask()
        assertThat(v).hasSize(1)
        assertThat(v.first().snippet).contains("应在 core-ui 的 token 里定义后引用")
    }

    @Test
    fun `三位短色值同样被抓住`() {
        write("feature/feature-home/src/main/res/layout/f.xml", """<View android:background="#FFF" />""")
        assertThat(runTask()).hasSize(1)
    }

    // ==================================================================
    // 报告与失败信息
    // ==================================================================

    @Test
    fun `违规时报告文件被写出且列出明细`() {
        write("feature/feature-home/src/main/res/layout/f.xml", """<View android:background="#FFF" />""")
        val v = runTask()
        assertThat(reportFile.exists()).isTrue()
        val text = reportFile.readText()
        assertThat(text).contains("结果：**失败**（${v.size} 处违规）")
        assertThat(text).contains("f.xml")
        assertThat(text).contains("修复方式")
    }

    @Test
    fun `通过时报告写明 0 处违规`() {
        write("feature/feature-home/src/main/res/layout/f.xml", """<View android:background="@color/bg_base" />""")
        runTask()
        assertThat(reportFile.readText()).contains("结果：**通过**（0 处违规）")
    }











    @Test
    fun `失败摘要按文件聚合而不是平铺`() {
        val body = (1..25).joinToString("\n") { """<View android:background="#FFF" />""" }
        write("feature/feature-home/src/main/res/layout/f.xml", body)
        write("feature/feature-stats/src/main/kotlin/Chart.kt", """val c = 0xFF34C77B""")

        val (_, ex) = runTaskCatching()
        assertThat(ex).isInstanceOf(GradleException::class.java)
        assertThat(ex!!.message).contains("发现 26 处硬编码颜色，涉及 2 个文件")
        // 聚合后的摘要必须写明「该文件 25 处」和行号范围
        assertThat(ex.message).contains("feature/feature-home/src/main/res/layout/f.xml  —— 25 处（行 1,2,3")
        assertThat(ex.message).contains("feature/feature-stats/src/main/kotlin/Chart.kt  —— 1 处（行 1）")
    }





    @Test
    fun `同一行多处违规在摘要里行号去重`() {
        // 一行里 3 个色值
        write(
            "feature/feature-home/src/main/res/layout/f.xml",
            """<View android:a="#FFF" android:b="#000" android:c="#00F" />""",
        )
        val (_, ex) = runTaskCatching()
        assertThat(ex).isInstanceOf(GradleException::class.java)
        assertThat(ex!!.message).contains("—— 3 处（行 1）")
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private fun write(relativePath: String, content: String) {
        val f = File(root, relativePath)
        f.parentFile?.mkdirs()
        f.writeText(content.trimIndent())
    }








    private fun runTask(): List<ViolationDto> = runTaskCatching().first










    private fun runTaskCatching(): Pair<List<ViolationDto>, GradleException?> {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.create("checkNoHardcodedColors", CheckNoHardcodedColorsTask::class.java)

        val files = project.fileTree(root) {
            include("app/src/**/*.kt", "app/src/**/*.java", "app/src/**/*.xml")
            include("feature/*/src/**/*.kt", "feature/*/src/**/*.java", "feature/*/src/**/*.xml")
            include("widget/src/**/*.kt", "widget/src/**/*.java", "widget/src/**/*.xml")
            include("service-focus/src/**/*.kt", "service-focus/src/**/*.java", "service-focus/src/**/*.xml")
            exclude("**/build/**")
        }
        task.sources.from(files)
        task.allowedPathPrefixes.set(
            setOf(
                "core/core-ui/src/main/res/values",
                "core/core-ui/src/main/res/values-night",
                "app/src/main/res/drawable",
                "app/src/main/res/drawable-anydpi-v26",
            ),
        )

        //   会出现「本地测试绿、真实 CI 红」这种最消耗信任的状态。
        task.allowedExactFiles.set(
            setOf("widget/src/main/kotlin/com/projectfocus/widget/glance/WidgetPalette.kt"),
        )
        task.rootDirPath.set(root.absolutePath)
        task.report.set(reportFile)

        val error = runCatching { task.check() }.exceptionOrNull()
        if (error != null && error !is GradleException) throw error

        return parseReport() to (error as GradleException?)
    }









    private fun parseReport(): List<ViolationDto> {
        if (!reportFile.exists()) return emptyList()
        val lines = reportFile.readLines()
        val start = lines.indexOfFirst { it.startsWith("| 文件 |") }
        if (start < 0) return emptyList()
        return lines.drop(start + 2)
            .takeWhile { it.startsWith("|") }
            .map { row ->
                val cells = row.trim('|').split("|").map { it.trim().trim('`') }
                ViolationDto(
                    relativePath = cells.getOrElse(0) { "" },
                    line = cells.getOrElse(1) { "0" }.toIntOrNull() ?: 0,
                    snippet = cells.getOrElse(2) { "" },
                )
            }
    }

    data class ViolationDto(val relativePath: String, val line: Int, val snippet: String)
}
