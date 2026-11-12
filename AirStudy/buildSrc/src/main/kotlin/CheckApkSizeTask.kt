package com.projectfocus.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile


//
// ═══════════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════════
//
//   包体是**不可逆**的增长：一旦一个 5MB 的字体、一张 2MB 的插画进了包，
//   后面没人会主动删 —— 因为「删了万一哪个页面用到了」。

//   不是某一天突然涨上去的，是每周涨 1~2MB 堆了两年。
//
//   把门禁设在 CI 上、预算写死在构建脚本里，效果是：
//   想让包体多 1MB，必须显式改这个数字 —— 改的时候会有人问「为什么」。
//
// ═══════════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════════
//
//   「超了 3MB」这句话没有行动力。要回答「超在哪」必须分类：

//     · assets/    —— 原始资产（贴图、音频、地图数据）
//     · classes*.dex —— 代码
//     · res/drawable* —— 图片
//     · lib/       —— native so
//     · META-INF/  —— 依赖元数据（正常应 < 200KB，异常说明有重复打包）
//   每类给一行，一眼看出该砍谁。
//
// ═══════════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════════
//


//   这里两者都统计：entry.compressedSize 用于对预算负责，
//   entry.size 用于判断「这个资产的真实体量」。
abstract class CheckApkSizeTask : DefaultTask() {


    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apk: RegularFileProperty


    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty








    @get:Input
    abstract val budgetBytes: Property<Long>

    /** 报告输出 */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val file = resolveApk()
        if (!file.isFile) {
            throw GradleException("找不到 APK：${file.absolutePath}（先跑 assembleDebug / assembleRelease）")
        }
        val budget = budgetBytes.get()
        val actual = file.length()

        val entries = readEntries(file)
        val byCategory = entries.groupBy { category(it.name) }
            .mapValues { (_, list) -> list.sumOf { it.compressed } }
            .toList()
            .sortedByDescending { it.second }

        val top = entries.sortedByDescending { it.compressed }.take(20)

        val text = buildString {
            appendLine("# APK 包体报告")
            appendLine()
            appendLine("- 文件：`${file.name}`")
            appendLine("- 实际：$actual B（${mib(actual)}）")
            appendLine("- 预算：$budget B（${mib(budget)}）")
            appendLine(
                "- 占用：%.1f%%%s".format(
                    actual.toDouble() / budget * 100,
                    if (actual > budget) "  **超预算 ${mib(actual - budget)}**" else "",
                ),
            )
            appendLine()
            appendLine("## 按类别（压缩后）")
            appendLine()
            appendLine("| 类别 | 压缩后 | 解压后 | 条目数 |")
            appendLine("|---|---:|---:|---:|")
            byCategory.forEach { (cat, bytes) ->
                val list = entries.filter { category(it.name) == cat }
                appendLine("| $cat | ${mib(bytes)} | ${mib(list.sumOf { it.uncompressed })} | ${list.size} |")
            }
            appendLine()
            appendLine("## 最大的 20 个条目")
            appendLine()
            appendLine("| 条目 | 压缩后 | 解压后 |")
            appendLine("|---|---:|---:|")
            top.forEach { appendLine("| `${it.name.take(80)}` | ${mib(it.compressed)} | ${mib(it.uncompressed)} |") }
        }

        val out = report.get().asFile
        out.parentFile?.mkdirs()
        out.writeText(text)

        if (actual > budget) {
            throw GradleException(
                "APK 体积超预算：$actual B（${mib(actual)}） > $budget B（${mib(budget)}），超 ${mib(actual - budget)}。\n" +
                    "最大的几类：\n" +
                    byCategory.take(4).joinToString("\n") { "  ${it.first}  ${mib(it.second)}" } +
                    "\n详见 ${out.absolutePath}",
            )
        }
    }

    private fun resolveApk(): File {
        val explicit = apk.orNull?.asFile
        val directory = apkDirectory.orNull?.asFile
        if ((explicit == null) == (directory == null)) {
            throw GradleException("必须且只能配置 apk 或 apkDirectory")
        }
        if (explicit != null) return explicit
        val outputs = directory!!.listFiles { file ->
            file.isFile && file.extension.equals("apk", ignoreCase = true)
        }.orEmpty()
        return outputs.singleOrNull()
            ?: throw GradleException("Release APK 应唯一，实际为 ${outputs.size} 个：${outputs.toList()}")
    }

    private fun readEntries(file: File): List<Entry> = ZipFile(file).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory }
            .map { Entry(it.name, it.compressedSize.coerceAtLeast(0), it.size.coerceAtLeast(0)) }
            .toList()
    }








    private fun category(name: String): String = when {
        name.startsWith("classes") && name.endsWith(".dex") -> "代码 dex"
        name.startsWith("res/font/") -> "字体 res/font"
        name.startsWith("res/drawable") -> "图片 res/drawable"
        name.startsWith("res/mipmap") -> "图标 res/mipmap"
        name.startsWith("res/raw/") -> "资源 res/raw"
        name.startsWith("assets/") -> "资产 assets"
        name.startsWith("lib/") -> "native lib"
        name.startsWith("META-INF/") -> "元数据 META-INF"
        name.startsWith("kotlin/") -> "Kotlin 元数据"
        name.startsWith("res/") -> "其余 res"
        else -> "其他"
    }






    private fun mib(bytes: Long): String = "%.2f MiB".format(bytes.toDouble() / 1024 / 1024)

    data class Entry(val name: String, val compressed: Long, val uncompressed: Long)
}
