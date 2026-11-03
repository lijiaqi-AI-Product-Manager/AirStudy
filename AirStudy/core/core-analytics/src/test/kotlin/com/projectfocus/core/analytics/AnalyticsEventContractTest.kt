package com.projectfocus.core.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File




















class AnalyticsEventContractTest {

    /** 本测试能构造出来的全部事件样例（新增事件时**必须**加进来） */
    private fun allSamples(): List<AnalyticsEvent> = listOf(
        AnalyticsEvent.AppOpen(coldStart = true, firstFrameMs = 820),
        AnalyticsEvent.AppOpen(coldStart = false, firstFrameMs = null),
        AnalyticsEvent.SessionStart(plannedDurationMin = 45, customCategory = false, blockedAppCount = 3),
        AnalyticsEvent.SessionPause(elapsedMin = 12, pauseIndex = 1),
        AnalyticsEvent.SessionEnd(
            plannedDurationMin = 45,
            actualDurationMin = 38,
            abandoned = true,
            distanceKm = 1180,
            pauseCount = 2,
            blockedCount = 5,
        ),
        AnalyticsEvent.BlockTriggered(elapsedMin = 7, ruleType = "PACKAGE", blockIndex = 1),
        AnalyticsEvent.PermissionStep(permission = "usage_stats", result = "granted", attempt = 2),
        AnalyticsEvent.GlassLevelReported(level = "BLUR", deviceHash = "a1b2c3"),
    )

    // ==================================================================
    // 结构性
    // ==================================================================

    @Test
    fun `注册表自检通过`() {
        assertThat(AnalyticsEvent.validateRegistry()).isEmpty()
    }














    @Test
    fun `事件名与事件类一一对应`() {
        val samples = allSamples()
        val distinctNames = samples.map { it.name }.distinct()
        // 去重后数量必须等于 ALL_NAMES 的数量（= 事件种类数）
        assertThat(distinctNames).hasSize(AnalyticsEvent.ALL_NAMES.size)
        assertThat(distinctNames.none { it.isBlank() }).isTrue()

        // 同一事件名下的样例必须是同一个类
        samples.groupBy { it.name }.forEach { (name, group) ->
            val classes = group.map { it.javaClass }.distinct()
            assertThat(classes).hasSize(1)
            // 名字不能空
            assertThat(name.isNotBlank()).isTrue()
        }
    }








    @Test
    fun `ALL_NAMES 与真实事件样例完全对齐`() {
        val actual = allSamples().map { it.name }.toSet()
        val registered = AnalyticsEvent.ALL_NAMES.toSet()
        assertThat(registered).isEqualTo(actual)
    }

    @Test
    fun `事件名符合小写下划线规范`() {
        AnalyticsEvent.ALL_NAMES.forEach { name ->
            assertThat(name.matches(Regex("^[a-z][a-z0-9_]*$"))).isTrue()
        }
    }

    // ==================================================================
    // 语义性
    // ==================================================================

    @Test
    fun `每个事件都有至少一个参数`() {

        //   「只记发生了，不记任何上下文」的事件在分析里几乎没有用 ——

        //   强迫每个事件至少带一个维度，是在逼设计者想清楚「这个点要回答什么问题」。
        allSamples().forEach { event ->
            assertThat(event.params()).isNotEmpty()
        }
    }

    @Test
    fun `参数值类型只允许 String Long Int Double Boolean 与 null`() {

        //   装箱类型（Integer / java.lang.Boolean）与 Kotlin 的 Int::class.javaObjectType
        //   在 `Class<*>` 集合里的相等判断受泛型推断影响，写起来很别扭

        //   类名字符串比较同样严格，但没有这些边界问题。
        val allowedNames = setOf(
            "java.lang.String",
            "java.lang.Long",
            "java.lang.Integer",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Boolean",
        )
        val bad = mutableListOf<String>()
        allSamples().forEach { event ->
            event.params().forEach { (k, v) ->
                val typeName = v?.javaClass?.name ?: return@forEach
                if (typeName !in allowedNames) {
                    bad += "${event.name}.$k = $typeName"
                }
            }
        }
        assertThat(bad).isEmpty()
    }

    @Test
    fun `参数键名统一小写下划线`() {
        val bad = mutableListOf<String>()
        allSamples().forEach { event ->
            event.params().keys.forEach { k ->
                if (!k.matches(Regex("^[a-z][a-z0-9_]*$"))) bad += "${event.name}.$k"
            }
        }
        assertThat(bad).isEmpty()
    }

    // ==================================================================
    // 隐私红线
    // ==================================================================












    @Test
    fun `参数键名不含隐私敏感词`() {
        val forbidden = listOf(
            "package", "pkg",           // 用户装了什么 App
            "imei", "imsi", "oaid", "idfa", "android_id", "device_id", "mac",
            "phone", "mobile", "email", "account", "user_id", "uid",
            "lat", "lon", "location", "gps",
            "ssid", "wifi", "ip",
            "text", "content", "input", "query",  // 用户输入的任何内容
        )
        val violations = mutableListOf<String>()
        allSamples().forEach { event ->
            event.params().keys.forEach { key ->
                forbidden.forEach { word ->
                    if (key.contains(word, ignoreCase = true)) {
                        violations += "${event.name}.$key 命中敏感词「$word」"
                    }
                }
            }
        }
        assertThat(violations).isEmpty()
    }





    @Test
    fun `参数表不能全为 null`() {
        allSamples().forEach { event ->
            val params = event.params()
            val nonNull = params.values.count { it != null }
            assertThat(nonNull).isGreaterThan(0)
        }
    }

    // ==================================================================
    // 实现约束
    // ==================================================================

    @Test
    fun `NoOp 实现永远是 disabled`() {
        assertThat(NoOpAnalytics.enabled).isFalse()
        // 调用不崩、不记录
        NoOpAnalytics.track(AnalyticsEvent.AppOpen(true, null))
        NoOpAnalytics.setUserProperty("k", "v")
        NoOpAnalytics.flush()
    }

    @Test
    fun `FakeAnalytics 关闭时既不收集也不记属性`() {
        val fake = FakeAnalytics(enabled = false)
        fake.track(AnalyticsEvent.AppOpen(true, 100))
        fake.setUserProperty("k", "v")
        assertThat(fake.events).isEmpty()
        assertThat(fake.properties).isEmpty()
    }

    @Test
    fun `FakeAnalytics 按名筛选与取参数`() {
        val fake = FakeAnalytics()
        fake.track(AnalyticsEvent.SessionPause(elapsedMin = 3, pauseIndex = 1))
        fake.track(AnalyticsEvent.SessionPause(elapsedMin = 9, pauseIndex = 2))
        fake.track(AnalyticsEvent.AppOpen(true, 50))

        assertThat(fake.eventsNamed("session_pause")).hasSize(2)
        assertThat(fake.paramsOf("session_pause").map { it["elapsed_min"] })
            .containsExactly(3, 9)
            .inOrder()
        assertThat(fake.lastParams()!!["cold_start"]).isEqualTo(true)
    }

    @Test
    fun `FakeAnalytics flush 计数`() {
        val fake = FakeAnalytics()
        fake.flush()
        fake.flush()
        assertThat(fake.flushCount).isEqualTo(2)
    }


    @Test
    fun `FakeAnalytics 没有被生产代码引用`() {
        val projectRoot = File("..").canonicalFile
        val mainSourceDirs = listOf(
            "app/src/main",
            "core/core-analytics/src/main",
            "core/core-common/src/main",
            "core/core-database/src/main",
            "core/core-datastore/src/main",
            "core/core-model/src/main",
            "core/core-network/src/main",
            "core/core-ui/src/main",
            "service-focus/src/main",
            "widget/src/main",
        )

        val offenders = mutableListOf<String>()
        mainSourceDirs.forEach { rel ->
            val dir = File(projectRoot, rel)
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                // 定义它的文件本身不算
                .filter { it.name != "Analytics.kt" }
                .forEach { file ->
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    if (text.contains("FakeAnalytics")) {
                        offenders += file.relativeTo(projectRoot).path
                    }
                }
        }

        assertThat(offenders).isEmpty()
    }
}
