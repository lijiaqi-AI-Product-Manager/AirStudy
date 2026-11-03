package com.projectfocus.core.ui.token

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.ui.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
































@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesignTokenUsageTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ==================================================================
    // ① token 本身有效
    // ==================================================================













    @Test
    fun `所有长度类尺寸 token 都是合法的 dp 值`() {
        val ids = listOf(
            R.dimen.radius_xs, R.dimen.radius_s, R.dimen.radius_m, R.dimen.radius_l,
            R.dimen.stroke_thick, R.dimen.stroke_focus_ring, R.dimen.stroke_hairline,
            R.dimen.touch_target_comfortable, R.dimen.touch_target_min,
            R.dimen.icon_xs, R.dimen.icon_s, R.dimen.icon_m, R.dimen.icon_l, R.dimen.icon_xl,
            R.dimen.text_micro, R.dimen.text_numeric_xl,
            R.dimen.glass_blur_radius, R.dimen.glass_refraction_height,
            R.dimen.glass_refraction_offset,
            R.dimen.elevation_card, R.dimen.elevation_sheet, R.dimen.elevation_dialog,
            R.dimen.avatar_s, R.dimen.avatar_m, R.dimen.avatar_l, R.dimen.avatar_xl,
        )

        val bad = ids.mapNotNull { id ->
            val name = context.resources.getResourceName(id)
            val px = runCatching { context.resources.getDimension(id) }.getOrNull()
            val density = context.resources.displayMetrics.density
            when {
                px == null -> "$name 无法解析"
                px <= 0f -> "$name = $px（必须为正）"
                px / density > 512f -> "$name ≈ ${px / density}dp（超过 512dp 上界，单位可能写错）"
                else -> null
            }
        }

        assertThat(bad).isEmpty()
    }
































    @Test
    fun `无量纲系数 token 是 0 到 1 的浮点`() {
        val coefficients = listOf(
            R.dimen.glass_dispersion,
            R.dimen.glass_liquid_blur,
            R.dimen.glass_tint_alpha,
        )

        val bad = coefficients.mapNotNull { id ->
            val name = context.resources.getResourceName(id)
            val value = runCatching { context.resources.getFloat(id) }.getOrNull()
            when {
                value == null -> "$name 无法按 float 解析"
                value < 0f || value > 1f -> "$name = $value（系数必须在 0~1 之间）"
                else -> null
            }
        }

        assertThat(bad).isEmpty()
    }













    @Test
    fun `关键主题都继承到了基类的窗口背景`() {
        val themes = listOf(
            "Theme.Pf",
            "Theme.Pf.Day",
            "Theme.Pf.Immersive",
            "Theme.Pf.Transparent",
        )

        val missing = themes.mapNotNull { name ->
            val id = context.resources.getIdentifier(name, "style", context.packageName)
            if (id == 0) return@mapNotNull "$name 不存在"


            val ta = context.obtainStyledAttributes(
                id,
                intArrayOf(android.R.attr.windowBackground),
            )
            val has = try {
                ta.hasValue(0)
            } finally {
                ta.recycle()
            }
            if (has) null else "$name 没有 windowBackground（父样式断链？）"
        }

        assertThat(missing).isEmpty()
    }

    // ==================================================================
    // ② 有人在绕开 token 写魔法数字
    // ==================================================================















    @Test
    fun `布局里不允许裸写 dp 数字`() {
        val violations = layoutFiles().flatMap { file ->
            rawDpIn(file).map { (line, snippet) -> "${file.name}:$line → $snippet" }
        }

        assertThat(violations).isEmpty()
    }

    private fun layoutFiles(): List<java.io.File> {
        val root = projectRoot()
        val dirs = buildList {
            add(java.io.File(root, "core/core-ui/src/main/res/layout"))
            add(java.io.File(root, "app/src/main/res/layout"))
            java.io.File(root, "feature").listFiles()?.forEach {
                add(java.io.File(it, "src/main/res/layout"))
            }
        }
        return dirs.filter { it.isDirectory }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.extension == "xml" }
            .sortedBy { it.path }
    }

    /** 从 `values/dimens.xml` 反推工程根 —— 单元测试的 user.dir 是模块目录 */
    private fun projectRoot(): java.io.File {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        repeat(6) {
            val candidate = dir ?: return@repeat
            if (java.io.File(candidate, "settings.gradle.kts").exists()) return candidate
            dir = candidate.parentFile
        }
        error("找不到工程根（settings.gradle.kts）")
    }

    private fun rawDpIn(file: java.io.File): List<Pair<Int, String>> {
        val text = file.readText()
        val hit = mutableListOf<Pair<Int, String>>()
        text.lineSequence().forEachIndexed { idx, line ->
            val value = RAW_DP.find(line)?.groupValues?.getOrNull(1) ?: return@forEachIndexed
            // 白名单：按「资源名」放行
            if (ALLOWED_RAW_DP.any { line.contains(it) }) return@forEachIndexed
            if (value.toFloatOrNull() == 0f) return@forEachIndexed // 0dp 不是魔法数字
            hit += (idx + 1) to line.trim()
        }
        return hit
    }

    private companion object {




        val RAW_DP = Regex("""":[ ]*(\d+(?:\.\d+)?)dp"""")

        /**
         * 允许裸写 dp 的场合 —— **每一条都要能说出理由**：
         *
         * · 1dp / 1.5dp：分隔线、描边。设计系统里最小可感知的线宽是 1dp，
         *   做成 token 反而多一层间接（`stroke_hairline` 已经是它了，
         *   但 Compose 侧的 `Dp` 常量无法复用 XML token，所以保留）。
         * · 48dp：Material 最小点击区硬性规范，**不应该**被设计系统改小 ——
         *   写成 token 反而给了"改成 40dp"的机会。
         */
        val ALLOWED_RAW_DP = listOf(
            "1dp", "1.5dp", "48dp",
        )
    }
}
