package com.projectfocus.feature.airport.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.feature.airport.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
@HiltAndroidTest
class IdleRecommendationRenderTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    /**
     * 真实 DataStore（临时文件）。见 TestSettingsStore 的注释 ——
     * 为什么不用内存 fake。
     */
    @get:Rule
    val tempFolder = org.junit.rules.TemporaryFolder()

    private lateinit var settings: com.projectfocus.feature.airport.search.TestSettingsStore

    @Before
    fun setUpHilt() {
        hiltRule.inject()
        settings = com.projectfocus.feature.airport.search.TestSettingsStore(tempFolder.newFolder())
    }

    private var scenario: ActivityScenario<AirportHostActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        settings.close()
    }

    // ── 夹具 ────────────────────────────────────────────────

    private fun entity(
        id: Long,
        iata: String,
        name: String,
        city: String,
        cityZh: String? = null,
        popularity: Int = 50,
    ) = AirportEntity(
        id = id, iataCode = iata, icaoCode = "", name = name, city = city,
        country = "CN", latitude = 0.0, longitude = 0.0, elevation = 0,
        continent = "AS", type = "large_airport", popularity = popularity,
        isPremiumOnly = false, nameZh = null, cityZh = cityZh,
    )

    private fun launch(rows: List<AirportEntity>) {
        scenario = ActivityScenario.launch(AirportHostActivity::class.java)
        scenario!!.onActivity { act ->
            act.install(TestableAirportFragment(FakeAirportDao(rows), settings.repository))
        }
        settle()
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        settle()
    }

    private fun fragment(): AirportSearchFragment {
        var f: AirportSearchFragment? = null
        scenario!!.onActivity { act ->
            f = act.supportFragmentManager.fragments
                .filterIsInstance<AirportSearchFragment>().first()
        }
        return requireNotNull(f)
    }

    private fun view(id: Int): View {
        var v: View? = null
        scenario!!.onActivity { v = requireNotNull(fragment().view).findViewById(id) }
        return requireNotNull(v) { "找不到 id=$id" }
    }

    private fun panel(): IdleRecommendationPanel = view(R.id.idlePanel) as IdleRecommendationPanel

    private fun buildIndex() {
        scenario!!.onActivity { fragment().forceEnsureIndexForTest() }
        settle()
    }

    private fun type(s: String) {
        scenario!!.onActivity { (view(R.id.searchInput) as EditText).setText(s) }
        settle()
    }









    private fun settle() {
        val main = Looper.getMainLooper()
        val shadow = shadowOf(main)
        repeat(6) {
            shadow.idle()
            Handler(main).post { }
            shadow.idleFor(java.time.Duration.ofMillis(24))
        }
    }

    // ══════════════════════════════════════════════════════
    // 1. 有推荐时
    // ══════════════════════════════════════════════════════

    @Test
    fun `索引就绪后推荐区可见`() {
        launch(sampleRows())
        buildIndex()

        assertThat(panel().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `推荐区真的铺出了 chip 而不是一个空壳`() {


        //   逻辑也全对，就是屏幕上什么都没有。
        launch(sampleRows())
        buildIndex()
        assertThat(panel().chips).isNotEmpty()
    }

    @Test
    fun `chip 上的文案是城市加三字码`() {
        launch(sampleRows())
        buildIndex()
        val labels = panel().chips.map { it.contentDescription.toString() }

        //   `chipLabel` 的优先级（中文城市 > 英文城市 > 中文机场名 > 英文机场名）。

        //     错的是**断言**不是代码：判定优先级的那 8 条测试在
        //     `IdleRecommenderTest` 里，这里只该验证"文案确实被画到了 chip 上"。
        assertThat(labels).contains("北京 PEK")

        assertThat(labels).containsExactly("北京 PEK", "上海 PVG", "广州 CAN", "成都 CTU").inOrder()
    }

    @Test
    fun `有中文城市名时 chip 用中文`() {
        launch(
            listOf(
                entity(1, "PEK", "Beijing Capital International", "Beijing", cityZh = "北京", popularity = 900),
            ),
        )
        buildIndex()
        val labels = panel().chips.map { it.contentDescription.toString() }
        assertThat(labels).contains("北京 PEK")
    }

    @Test
    fun `点 chip 回调带着完整机场对象`() {
        launch(sampleRows())
        buildIndex()

        var got: Airport? = null
        scenario!!.onActivity { fragment().onAirportSelected = { got = it } }

        val chip = panel().chips.first()
        scenario!!.onActivity { chip.performClick() }
        settle()


        assertThat(got).isNotNull()
        assertThat(got!!.iataCode).isNotNull()
        assertThat(got!!.name).isNotEmpty()
        assertThat(got!!.latitude).isEqualTo(0.0)
    }

    @Test
    fun `点 chip 会收起键盘并清焦点`() {

        //   看起来像"点错了"。
        launch(sampleRows())
        buildIndex()
        val input = view(R.id.searchInput) as EditText
        scenario!!.onActivity { input.requestFocus() }
        assertThat(input.hasFocus()).isTrue()

        val chip = panel().chips.first()
        scenario!!.onActivity { chip.performClick() }
        settle()
        assertThat(input.hasFocus()).isFalse()
    }

    // ══════════════════════════════════════════════════════
    // 2. 推荐区必须消失的场景
    // ══════════════════════════════════════════════════════

    @Test
    fun `打字后推荐区必须收起来`() {

        //   漏了的表现：用户输 1 个字 → 屏幕说"再输入 1 个字"，
        //   下面却有 4 个可点的城市 chip。用户点了直接跳走。
        launch(sampleRows())
        buildIndex()
        assertThat(panel().visibility).isEqualTo(View.VISIBLE)

        type("P")
        assertThat(panel().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `搜到结果后推荐区收起来`() {
        launch(sampleRows())
        buildIndex()
        type("PEK")
        assertThat(panel().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `搜不到时推荐区也收起来`() {

        //   两者同时出现会让用户不知道该走哪条路。
        launch(sampleRows())
        buildIndex()
        type("ZZZZ")
        assertThat(panel().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `清空关键词后推荐区回来`() {
        launch(sampleRows())
        buildIndex()
        type("PEK")
        assertThat(panel().visibility).isEqualTo(View.GONE)

        type("")
        assertThat(panel().visibility).isEqualTo(View.VISIBLE)
        assertThat(panel().chips).isNotEmpty()
    }

    @Test
    fun `全空机场库时推荐区隐藏`() {

        //   留两个空标题（"最近去过"下面什么都没有）比什么都不显示更困惑。
        launch(emptyList())
        buildIndex()
        assertThat(panel().visibility).isEqualTo(View.GONE)
        assertThat(panel().chips).isEmpty()
    }

    // ══════════════════════════════════════════════════════
    // 3. 无障碍
    // ══════════════════════════════════════════════════════

    @Test
    fun `推荐区有整体概览描述`() {
        launch(sampleRows())
        buildIndex()

        assertThat(panel().contentDescription?.toString()).contains("热门城市")
    }

    @Test
    fun `每个 chip 都是可点节点`() {
        launch(
            listOf(entity(1, "PEK", "Capital", "Beijing", cityZh = "北京", popularity = 900)),
        )
        buildIndex()
        val chip = panel().chips.first()

        //   不会报"按钮"，用户根本不知道能点。
        assertThat(chip.isClickable).isTrue()
        assertThat(chip.contentDescription).isNotNull()
    }

    // ══════════════════════════════════════════════════════
    // 4. 顺序与去重（渲染侧的复核）
    // ══════════════════════════════════════════════════════

    @Test
    fun `热门按 popularity 降序出现在屏幕上`() {
        launch(
            listOf(
                entity(1, "AAA", "A Airport", "Alpha", cityZh = "甲", popularity = 10),
                entity(2, "BBB", "B Airport", "Bravo", cityZh = "乙", popularity = 900),
                entity(3, "CCC", "C Airport", "Charlie", cityZh = "丙", popularity = 500),
            ),
        )
        buildIndex()
        val labels = panel().chips.map { it.contentDescription.toString() }

        assertThat(labels).containsExactly("乙 BBB", "丙 CCC", "甲 AAA").inOrder()
    }

    @Test
    fun `热门数量不超过 8 个`() {
        val rows = (1L..20L).map {
            entity(
                it,
                "A${it.toString().padStart(2, '0')}",
                "Airport $it",
                "City$it",
                popularity = 100 - it.toInt(),
            )
        }
        launch(rows)
        buildIndex()

        assertThat(panel().chips.size).isAtMost(8)
    }

    private fun sampleRows() = listOf(
        entity(1, "PEK", "Beijing Capital International", "Beijing", cityZh = "北京", popularity = 900),
        entity(2, "PVG", "Shanghai Pudong International", "Shanghai", cityZh = "上海", popularity = 800),
        entity(3, "CAN", "Guangzhou Baiyun", "Guangzhou", cityZh = "广州", popularity = 700),
        entity(4, "CTU", "Chengdu Shuangliu", "Chengdu", cityZh = "成都", popularity = 600),
    )
}
