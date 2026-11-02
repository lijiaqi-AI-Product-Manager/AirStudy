package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File






























class ApiContractDocTest {

    private val doc = File("../../docs/api-contract.md").let {
        // server/ 的工作目录是模块根，docs 在仓库根的 docs/
        if (it.isFile) it else File("../docs/api-contract.md")
    }

    private val routesDirectory = File("src/main/kotlin/com/projectfocus/server/route")

    // ==================================================================
    // 解析
    // ==================================================================








    private fun endpointsFromDoc(): Set<String> {
        assertThat(doc.isFile).isTrue()
        val lines = doc.readLines()
        val begin = lines.indexOfFirst { it.contains("ENDPOINTS:BEGIN") }
        val end = lines.indexOfFirst { it.contains("ENDPOINTS:END") }
        assertThat(begin).isAtLeast(0)
        assertThat(end).isGreaterThan(begin)

        return lines.subList(begin, end)
            .filter { it.startsWith("|") }
            .mapNotNull { line ->
                val cells = line.split("|").map { it.trim() }
                if (cells.size < 4) return@mapNotNull null
                val method = cells[1].uppercase()
                val path = cells[2]
                // 跳过表头分隔行
                if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) return@mapNotNull null
                // 文档里路径带反引号
                "$method ${path.removeSurrounding("`")}"
            }
            .toSet()
    }










    private fun endpointsFromCode(): Set<String> {
        assertThat(routesDirectory.isDirectory).isTrue()
        val files = routesDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith("Routes.kt") }.sortedBy { it.name }
        assertThat(files).isNotEmpty()
        // Routes were split into files. Checking only Routes.kt silently omitted
        // real registered REST endpoints such as subscription/status and promo.
        return files.flatMap { endpointsFromFile(it) }.toSet()
    }

    private fun endpointsFromFile(routesFile: File): Set<String> {
        assertThat(routesFile.isFile).isTrue()
        return endpointsFromText(routesFile.readText())
    }

    private fun endpointsFromText(text: String): Set<String> {
        val result = linkedSetOf<String>()

        // 按行扫，维护 route("x") 的前缀栈
        val prefixStack = ArrayDeque<String>()
        var depth = 0
        val depthAtRouteStart = ArrayDeque<Int>()


        //   但 Kotlin DSL 里还有 `buildJsonObject { put("key", value) }` ——
        //   后者的 `put("...")` 会被裸的正则当成 `PUT` 端点。

        //   真实路由方法调用前面**要么是行首，要么是 `{` 或 `}` 或 `else`**，
        //   而且后面紧跟的括号内容必须是**单参数字符串**（逗号是分界）。
        //   更可靠的判据：Ktor 的路由方法后面一定跟 ` {`（lambda 块），
        //   `put("key", value)` 后面跟的是 `)`。
        val methodRe = Regex("""(?:^|[{}\s])(get|post|put|patch|delete)\s*\(\s*"([^"]*)"\s*\)\s*\{""")
        val bareMethodRe = Regex("""(?:^|[{}\s])(get|post|put|patch|delete)\s*\{""")
        val routeRe = Regex("""\broute\s*\(\s*"([^"]*)"""")
        val blockRe = Regex("""\b(routing|authenticated)\s*\{""")

        text.lines().forEach { raw ->
            val line = raw.substringBefore("//")   // 去掉行注释，避免注释里的假端点

            // 先处理进入
            routeRe.find(line)?.let { m ->
                prefixStack.addLast(m.groupValues[1])
                depthAtRouteStart.addLast(depth)
            }

            (methodRe.find(line) ?: bareMethodRe.find(line))?.let { m ->
                val method = m.groupValues[1].uppercase()
                val path = m.groupValues.getOrNull(2).orEmpty()

                //   不能与 route 前缀拼接 —— 否则得到 "//health"。
                val full = if (path.startsWith("/")) {
                    path
                } else {
                    (prefixStack.map { it.trim('/') } + path)
                        .filter { it.isNotEmpty() }
                        .joinToString("/")
                        .let { "/$it" }
                }
                result += "$method $full"
            }

            // 统计花括号深度
            depth += line.count { it == '{' } - line.count { it == '}' }

            // 退出已闭合的 route 块
            while (depthAtRouteStart.isNotEmpty() && depth <= depthAtRouteStart.last()) {
                depthAtRouteStart.removeLast()
                prefixStack.removeLast()
            }
        }

        // blockRe 只是为了防止解析器被「忘记写 routing{}」的伪代码误导
        assertThat(blockRe.containsMatchIn(text)).isTrue()
        return result
    }

    // ==================================================================
    // 断言
    // ==================================================================





    @Test
    fun `文档里的每个端点都在代码里注册了`() {
        val docSet = endpointsFromDoc()
        val codeSet = endpointsFromCode()

        val missing = docSet - codeSet
        assertThat(missing).isEmpty()
    }






    @Test
    fun `代码里的每个端点都写进文档了`() {
        val docSet = endpointsFromDoc()
        val codeSet = endpointsFromCode()

        val undocumented = codeSet - docSet
        assertThat(undocumented).isEmpty()
    }

    @Test
    fun `端点解析器确实抽到了端点（防止解析器失效后全绿地空跑）`() {

        //   如果解析器坏了，上面两条会变成 空集 vs 空集 → 都通过。
        //   有了这条，解析器失效时它会先红。
        val codeSet = endpointsFromCode()
        assertThat(codeSet.size).isAtLeast(20)
        assertThat(codeSet).contains("POST /auth/login")
        assertThat(codeSet).contains("GET /session/history")
        assertThat(codeSet).contains("GET /events")
        assertThat(codeSet).contains("GET /subscription/status")
        assertThat(codeSet).contains("GET /auth/promo-image")
        assertThat(codeSet).contains("POST /auth/password-reset/code")
        assertThat(codeSet).contains("POST /auth/password-reset")
    }

    @Test
    fun bareRouteHandlerAndAbsolutePrefixDoNotHideResetOrInventJsonEndpoints() {
        val text = """
            routing {
                route("/auth/password-reset") {
                    post("code") {
                        val body = buildJsonObject {
                            put("not-a-route", "value")
                        }
                    }
                    post {
                        call.respond("done")
                    }
                    route("nested") {
                        get("history") {
                            call.respond("done")
                        }
                    }
                }
                get("/health") {
                    call.respond("ready")
                }
            }
        """.trimIndent()
        assertThat(endpointsFromText(text)).containsExactly(
            "POST /auth/password-reset/code", "POST /auth/password-reset",
            "GET /auth/password-reset/nested/history", "GET /health")
    }

    @Test
    fun `文档里的错误码段位表覆盖了客户端 mapper 认识的全部段位`() {
        // 客户端按段位做统一处理，段位表缺一段会导致「未知错误」的兜底路径被频繁走到
        val text = doc.readText()
        listOf("`1xxx`", "`2xxx`", "`3xxx`", "`4xxx`", "`5xxx`").forEach { marker ->
            assertThat(text).contains(marker)
        }
    }

    @Test
    fun `文档声明了冻结状态`() {
        // 冻结标记不是装饰：它是「改这里要走评审」的唯一依据
        assertThat(doc.readText()).contains("状态：FROZEN")
    }
}
