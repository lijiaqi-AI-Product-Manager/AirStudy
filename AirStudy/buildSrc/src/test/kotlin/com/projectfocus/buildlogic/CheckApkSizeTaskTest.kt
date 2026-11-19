package com.projectfocus.buildlogic

import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


class CheckApkSizeTaskTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var apk: File
    private lateinit var reportFile: File

    @Before
    fun setUp() {
        apk = File(tmp.root, "app-debug.apk")
        reportFile = File(tmp.root, "reports/apk-size.md")
    }

    @Test
    fun `未超预算时通过并写出报告`() {
        buildApk(
            "classes.dex" to ByteArray(1024) { 0 },
            "res/font/pf_sans.ttf" to ByteArray(2048) { 1 },
        )
        val ex = run(apk, budget = 10_000_000L)
        assertThat(ex).isNull()
        val text = reportFile.readText()
        assertThat(text).contains("占用：")
        assertThat(text).contains("字体 res/font")
        assertThat(text).contains("代码 dex")
    }

    @Test
    fun `超预算时抛异常且信息含超出的量`() {
        buildApk("classes.dex" to ByteArray(200_000) { 0 })
        val ex = run(apk, budget = 1000L)
        assertThat(ex).isInstanceOf(GradleException::class.java)
        assertThat(ex!!.message).contains("APK 体积超预算")
        assertThat(ex.message).contains("超")
    }

    @Test
    fun `字体被归到 res font 而不是泛 res`() {
        buildApk(
            "res/font/a.ttf" to ByteArray(4096) { 0 },
            "res/values_strings.arsc.flat" to ByteArray(16) { 0 },
        )
        run(apk, budget = 10_000_000L)
        val text = reportFile.readText()
        assertThat(text).contains("| 字体 res/font |")
        // 该行必须记录 1 个条目（4KB 太小，MiB 两位小数显示为 0.00，是正常精度）
        val fontLine = text.lines().first { it.startsWith("| 字体 res/font |") }
        assertThat(fontLine).contains("| 1 |")
    }

    @Test
    fun `classes2 dex 与 classes dex 归为同类`() {
        buildApk(
            "classes.dex" to ByteArray(100) { 0 },
            "classes2.dex" to ByteArray(100) { 0 },
            "classes24.dex" to ByteArray(100) { 0 },
        )
        run(apk, budget = 10_000_000L)
        val line = reportFile.readText().lines().first { it.startsWith("| 代码 dex |") }
        // 3 个 dex 条目
        assertThat(line).contains("| 3 |")
    }

    @Test
    fun `目录条目不计入条目数`() {
        ZipOutputStream(apk.outputStream()).use { zip ->
            // 显式写一个目录条目
            zip.putNextEntry(ZipEntry("res/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("res/font/"))
            zip.closeEntry()
            val e = ZipEntry("res/font/a.ttf")
            zip.putNextEntry(e)
            zip.write(ByteArray(64))
            zip.closeEntry()
        }
        run(apk, budget = 10_000_000L)
        val text = reportFile.readText()
        // 最大的 20 个条目表格里不应出现以 / 结尾的目录
        assertThat(text).doesNotContain("| `res/font/` |")
    }

    @Test
    fun `APK 不存在时报错并提示先 assemble`() {
        val missing = File(tmp.root, "nope.apk")
        val ex = run(missing, budget = 1000L)
        assertThat(ex).isInstanceOf(GradleException::class.java)
        assertThat(ex!!.message).contains("找不到 APK")
        assertThat(ex.message).contains("assembleDebug")
    }

    @Test
    fun `报告按压缩后体积降序排列类别`() {
        buildApk(
            "assets/small.txt" to ByteArray(100) { 0 },
            "classes.dex" to ByteArray(300_000) { 0 },
        )
        run(apk, budget = 10_000_000L)
        val lines = reportFile.readText().lines()
        val header = lines.indexOfFirst { it.startsWith("| 类别 |") }
        val first = lines[header + 2]
        // 第一行必须是压缩后最大的那一类（代码 dex）
        assertThat(first).contains("代码 dex")
    }

    @Test
    fun `发布目录支持 signed 与 unsigned 两种 APK 文件名`() {
        for (name in listOf("app-release.apk", "app-release-unsigned.apk")) {
            apk = File(tmp.root, name)
            buildApk("classes.dex" to ByteArray(64))
            assertThat(run(null, budget = 10_000L, apkDir = tmp.root)).isNull()
            assertThat(reportFile.readText()).contains(name)
            assertThat(apk.delete()).isTrue()
        }
    }

    @Test
    fun `发布目录没有 APK 时失败而不是跳过`() {
        val error = run(null, budget = 10_000L, apkDir = tmp.root)
        assertThat(error).isInstanceOf(GradleException::class.java)
        assertThat(error!!.message).contains("实际为 0 个")
    }

    @Test
    fun `发布目录有多个 APK 时拒绝检查任意一个`() {
        buildApk("classes.dex" to ByteArray(64))
        apk.copyTo(File(tmp.root, "app-release.apk"))
        val error = run(null, budget = 10_000L, apkDir = tmp.root)
        assertThat(error).isInstanceOf(GradleException::class.java)
        assertThat(error!!.message).contains("实际为 2 个")
    }

    @Test
    fun `输入文件和目录必须且只能指定一个`() {
        for ((file, directory) in listOf(null to null, apk to tmp.root)) {
            val error = run(file, budget = 10_000L, apkDir = directory)
            assertThat(error).isInstanceOf(GradleException::class.java)
            assertThat(error!!.message).contains("必须且只能配置")
        }
    }

    // ==================================================================


    private fun buildApk(vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(apk.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)

                entry.method = ZipEntry.DEFLATED
                zip.putNextEntry(entry)
                zip.write(uncompressible(bytes.size))
                zip.closeEntry()
            }
        }
    }

    private fun uncompressible(size: Int): ByteArray {
        val rnd = java.util.Random(0xC0FFEE)
        return ByteArray(size).also { rnd.nextBytes(it) }
    }

    private fun run(apkFile: File?, budget: Long, apkDir: File? = null): Throwable? {
        val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
        val task = project.tasks.create("checkApkSize", CheckApkSizeTask::class.java)
        if (apkFile != null) task.apk.set(apkFile)
        if (apkDir != null) task.apkDirectory.set(apkDir)
        task.budgetBytes.set(budget)
        task.report.set(reportFile)
        val error = runCatching { task.check() }.exceptionOrNull()
        if (error != null && error !is GradleException) throw error
        return error
    }
}
