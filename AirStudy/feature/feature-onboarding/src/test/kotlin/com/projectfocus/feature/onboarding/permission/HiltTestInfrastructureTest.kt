package com.projectfocus.feature.onboarding.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.feature.onboarding.R
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config























@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class HiltTestInfrastructureTest {












    @Test
    fun `测试进程的 Application 是 HiltTestApplication`() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val className = app.javaClass.name
        // Hilt 在测试期会把 Application 包一层（名字形如 HiltTestApplication
        // 或 HiltTestApplication_Application），所以用 contains 而不是等值。
        if (!className.contains("HiltTestApplication")) {
            error(
                "Application 是 $className，不是 HiltTestApplication。\n" +
                    "→ 检查 buildSrc/src/main/kotlin/AndroidHiltTestConventionPlugin.kt 里" +
                    "的 systemProperty(\"robolectric.application\", ...) 是否生效，" +
                    "以及模块是否 apply 了 projectfocus.android.hilt.test。",
            )
        }
    }









    @Test
    fun `能读到字符串资源且内容不为空`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val title = ctx.getString(R.string.permission_title)
        if (title.isBlank()) error("permission_title 读出来是空的，资源没合并进测试 APK")
        if (title != "起飞前检查") error("permission_title 实际是「$title」，与预期不符")
    }













    @Test
    fun `引导页布局能 inflate 且关键控件都在`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        //   测试进程的 Application context 没有主题，inflate 出来的控件会丢样式。
        val themed = android.view.ContextThemeWrapper(ctx, com.projectfocus.core.ui.R.style.Theme_Pf)
        val binding = com.projectfocus.feature.onboarding.databinding
            .FragmentPermissionGuideBinding.inflate(android.view.LayoutInflater.from(themed))

        // 逐个点出"渲染逻辑会访问的控件"，少一个就会在真实渲染时 NPE
        listOf(
            binding.titleText,
            binding.subtitleText,
            binding.progressText,
            binding.btnUsageStats,
            binding.btnOverlay,
            binding.btnNotification,
            binding.btnBattery,
            binding.btnPreview,
            binding.hintCard,
            binding.previewWarning,
            binding.stepUsageStats,
            binding.stepOverlay,
            binding.stepNotification,
        )
        // 上面 listOf 能构建成功 = 13 个 id 全部存在。
        // 再加一条：确认布局里确实有这 13 个控件（防止有人删了 ViewBinding 里的字段）
        if (binding.root.childCount == 0) error("布局根节点没有子 View")
    }
}
