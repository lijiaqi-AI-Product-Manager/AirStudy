package com.projectfocus.feature.onboarding.permission

import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.feature.onboarding.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config














































@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
@HiltAndroidTest
class PermissionGuideFragmentRenderTest {


















    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUpHilt() {

        hiltRule.inject()
    }


















    private val fake = TestPermissionPlatform()

    private val platform: PermissionPlatform get() = fake









    private var lastScenario: HostedFragment<TestableFragment>? = null

    @After
    fun tearDown() {
        lastScenario?.close()
        lastScenario = null
    }

    // ── 启动辅助 ────────────────────────────────────────────











    private fun launch(statuses: Map<PermissionItem, PermissionStatus> = emptyMap()):
        HostedFragment<TestableFragment> {
        fake.statuses.putAll(statuses)
        lastScenario = launchHiltFragment(TestableFragment::class.java, platform)
        return lastScenario!!
    }

    // ── 1. 初始渲染 ────────────────────────────────────────

    @Test
    fun `首次进入时渲染出三个未开启的必需项`() {
        val scenario = launch()

        val btnUsage = scenario.view<MaterialButton>(R.id.btnUsageStats)
        val btnOverlay = scenario.view<MaterialButton>(R.id.btnOverlay)
        val btnNotif = scenario.view<MaterialButton>(R.id.btnNotification)


        assertThat(btnUsage.text.toString()).isEqualTo("开启")
        assertThat(btnOverlay.text.toString()).isEqualTo("开启")
        assertThat(btnNotif.text.toString()).isEqualTo("开启")
        assertThat(btnUsage.isEnabled).isTrue()
        assertThat(btnOverlay.isEnabled).isTrue()
        assertThat(btnNotif.isEnabled).isTrue()
    }

    @Test
    fun `首次进入时进度是零除以三`() {
        val scenario = launch()
        assertThat(scenario.text(R.id.progressText)).isEqualTo("0 / 3 已开启")
    }

    @Test
    fun `首次进入时标题是起飞前检查`() {
        val scenario = launch()
        assertThat(scenario.text(R.id.titleText)).isEqualTo("起飞前检查")
    }

    // ── 2. 部分开启 ────────────────────────────────────────

    @Test
    fun `开了一项后进度变成一除以三且标题变成还差两项`() {
        val scenario = launch(mapOf(PermissionItem.USAGE_STATS to PermissionStatus.GRANTED))

        assertThat(scenario.text(R.id.progressText)).isEqualTo("1 / 3 已开启")
        assertThat(scenario.text(R.id.titleText)).isEqualTo("还差 2 项")

        assertThat(scenario.view<MaterialButton>(R.id.btnUsageStats).isEnabled).isFalse()
        assertThat(scenario.view<MaterialButton>(R.id.btnUsageStats).text.toString()).isEqualTo("已开启")
    }

    @Test
    fun `已开启项的序号列变成对勾`() {
        val scenario = launch(mapOf(PermissionItem.USAGE_STATS to PermissionStatus.GRANTED))
        assertThat(scenario.text(R.id.stepUsageStats)).isEqualTo("✓")
        // 未开启的仍是数字
        assertThat(scenario.text(R.id.stepOverlay)).isEqualTo("2")
    }

    // ── 3. 全部开启 ────────────────────────────────────────

    @Test
    fun `全部开启后标题变成检查完毕`() {
        val scenario = launch(PermissionItem.CORE.associateWith { PermissionStatus.GRANTED })

        assertThat(scenario.text(R.id.titleText)).isEqualTo("检查完毕")
        assertThat(scenario.text(R.id.progressText)).isEqualTo("3 / 3 已开启")
        assertThat(scenario.view<MaterialButton>(R.id.btnUsageStats).isEnabled).isFalse()
        assertThat(scenario.view<MaterialButton>(R.id.btnOverlay).isEnabled).isFalse()
        assertThat(scenario.view<MaterialButton>(R.id.btnNotification).isEnabled).isFalse()
    }

    @Test
    fun `电池优化不算进核心进度`() {

        //   如果它被算进去，会变成 x / 4 —— 用户会觉得"怎么都开不完"。
        val scenario = launch(
            PermissionItem.CORE.associateWith { PermissionStatus.GRANTED } +
                (PermissionItem.BATTERY_OPTIMIZATION to PermissionStatus.DENIED),
        )

        assertThat(scenario.text(R.id.progressText)).isEqualTo("3 / 3 已开启")
        assertThat(scenario.text(R.id.titleText)).isEqualTo("检查完毕")
    }

    // ── 4. 兜底提示的显隐 ──────────────────────────────────

    @Test
    fun `没找到提示默认隐藏`() {
        val scenario = launch()
        assertThat(scenario.view<View>(R.id.hintCard).visibility).isEqualTo(View.GONE)
    }

    // ── 5. 预览模式 ────────────────────────────────────────

    @Test
    fun `预览模式警告默认隐藏`() {
        val scenario = launch()
        assertThat(scenario.view<View>(R.id.previewWarning).visibility).isEqualTo(View.GONE)
        assertThat(scenario.view<MaterialButton>(R.id.btnPreview).text.toString()).isEqualTo("先看看，稍后再设置")
    }

    // ── 6. 无障碍 ──────────────────────────────────────────

    @Test
    fun `进度文本有 contentDescription`() {

        //   有 contentDescription 时读的是「权限开启进度」，
        //   但这样又丢了具体数字 —— 所以两者的组合要能说清。
        //   这里只钉住"有描述"，具体措辞由 UX 评审。
        val scenario = launch()
        assertThat(scenario.view<View>(R.id.progressText).contentDescription).isNotNull()
    }

    @Test
    fun `每个开启按钮都有非空文案`() {

        val scenario = launch()
        listOf(R.id.btnUsageStats, R.id.btnOverlay, R.id.btnNotification, R.id.btnBattery)
            .forEach { id ->
                val t = scenario.view<MaterialButton>(id).text?.toString().orEmpty()
                assertThat(t).isNotEmpty()
            }
    }
}
