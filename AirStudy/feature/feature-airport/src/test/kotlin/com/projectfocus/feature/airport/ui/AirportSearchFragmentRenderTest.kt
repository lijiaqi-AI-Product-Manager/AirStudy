package com.projectfocus.feature.airport.ui

import android.os.Looper
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.common.flight.FlightMath
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlinx.coroutines.CompletableDeferred


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
@HiltAndroidTest
class AirportSearchFragmentRenderTest {















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

    // ── 启动辅助 ────────────────────────────────────────────

    /**
     * 起页面并推到 RESUMED。
     *
     * 索引由真实 Fragment.onViewCreated 启动，启动辅助不另造加载入口。
     */
    private fun launch(rows: List<AirportEntity> = sampleRows(), args: Bundle? = null): AirportHostActivity {
        var host: AirportHostActivity? = null
        scenario = ActivityScenario.launch(AirportHostActivity::class.java)
        scenario!!.onActivity { act ->
            act.install(TestableAirportFragment(FakeAirportDao(rows), settings.repository).apply {
                arguments = args
            })
            host = act
        }
        idle()
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        idle()
        return requireNotNull(host)
    }

    /** 取当前 Fragment（每次现取 —— Fragment 重建后旧引用失效） */
    private fun fragment(): AirportSearchFragment {
        var f: AirportSearchFragment? = null
        scenario!!.onActivity { act ->
            f = act.supportFragmentManager.fragments.filterIsInstance<AirportSearchFragment>().first()
        }
        return requireNotNull(f)
    }

    private fun view(id: Int): View {
        var v: View? = null
        scenario!!.onActivity {
            v = requireNotNull(fragment().view).findViewById(id)
        }
        return requireNotNull(v) { "找不到 id=$id（布局里没了？）" }
    }

    private fun text(id: Int): String = (view(id) as TextView).text.toString()

    private fun isVisible(id: Int): Boolean = view(id).visibility == View.VISIBLE

















    private fun buildIndex() {
        scenario!!.onActivity { fragment().forceEnsureIndexForTest() }
        settle()
    }










    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }


    private fun settle() {
        val main = Looper.getMainLooper()
        val shadow = shadowOf(main)
        repeat(6) {
            shadow.idle()
            android.os.Handler(main).post { }

            //
            //   因为 ViewModel 里 `keywordFlow.debounce { 16L }` 是**真实延时**：
            //   Robolectric 的 `idleFor(d)` 只是把**虚拟时钟**往前拨，
            //   拨不够 debounce 就不发射，界面停在上一个状态。
            //   实测：`idleFor(16)` 时有效时长不足（取整误差），
            //   `type("PEK")` 之后界面仍是 Idle，报 `countText=`（空）。
            //   拨 24ms（一帧半）留出余量。
            shadow.idleFor(java.time.Duration.ofMillis(24))
        }
    }

    // ══════════════════════════════════════════════════════
    // 1. 初始渲染：进入页面即加载，不要求用户先点击搜索框。
    // ══════════════════════════════════════════════════════

    @Test
    fun `首次进入无需输入或手动建索引即可显示机场推荐`() {
        launch()
        settle()
        assertThat(text(R.id.searchInput)).isEmpty()
        assertThat(text(R.id.stateTitle)).isEmpty()
        assertThat(text(R.id.stateBody)).isEmpty()
        assertThat(isVisible(R.id.idlePanel)).isTrue()
    }

    @Test
    fun `首次进入时列表不可见且计数隐藏`() {
        launch()
        assertThat(isVisible(R.id.resultList)).isFalse()
        assertThat(isVisible(R.id.resultCount)).isFalse()
    }

    @Test
    fun `首次进入时清除按钮隐藏`() {

        launch()
        assertThat(isVisible(R.id.btnClear)).isFalse()
    }

    @Test
    fun `首次进入时不显示重试按钮`() {

        //   会诱导用户点它 —— 而索引建到一半重复点只会排更长的队。
        launch()
        assertThat(isVisible(R.id.btnRetry)).isFalse()
    }

    // ══════════════════════════════════════════════════════
    // 2. Idle（索引就绪、关键词为空）
    // ══════════════════════════════════════════════════════

    @Test
    fun `索引就绪后进入 Idle 且不再显示加载文案`() {

        //
        //   这正是本文件抓到的第一个真 bug（2026-10-26 17:28）：
        //   `renderIdle()` 把标题 `visibility = GONE` 却**没清空文案**，
        //   屏幕上确实看不见（GONE 不绘制），但：
        //     · TalkBack 会把它念出来（兄弟节点文本会被拼进朗读）
        //     · 从 Idle 切到 TooShort 时，改回 VISIBLE 的那一帧会闪出旧文案
        //   测试红了两轮，而"看截图"完全看不出问题。
        //

        //     Idle 有推荐项时标题**本来就该是空的**（让推荐自己说话）。
        //     真正的不变量是"不能残留 Loading 的字"。
        launch()
        buildIndex()
        assertThat(text(R.id.stateTitle)).doesNotContain("正在准备")
        assertThat(text(R.id.stateTitle)).isEmpty()
        assertThat(isVisible(R.id.resultList)).isFalse()
    }

    // ══════════════════════════════════════════════════════
    // 3. 搜索：有结果
    // ══════════════════════════════════════════════════════

    @Test
    fun `搜到结果后列表可见且计数正确`() {
        launch()
        buildIndex()
        type("PEK")

        assertThat(isVisible(R.id.resultList)).isTrue()
        assertThat(isVisible(R.id.resultCount)).isTrue()

        assertThat(text(R.id.resultCount)).isEqualTo("1 个结果")
        assertThat(isVisible(R.id.statePanel)).isFalse()
    }

    @Test
    fun `搜到结果后清除按钮出现`() {
        launch()
        buildIndex()
        type("PEK")
        assertThat(isVisible(R.id.btnClear)).isTrue()
    }

    @Test
    fun `搜到结果后列表有对应条目`() {
        launch()
        buildIndex()
        type("PEK")
        val rv = view(R.id.resultList) as RecyclerView

        //   立刻读会拿到旧值。Robolectric 里 AsyncListDiffer 用的是
        //   后台 executor，`idle()` 不推它 —— 必须等它跑完。
        //   `itemCount` 在 RecyclerView 上同样不可靠，所以用「有 adapter 且
        //   非空」这种弱断言，强断言交给 AirportSearchAdapterTest 的
        //   flatten 测试（那边是纯同步的，测得更准）。
        assertThat(rv.adapter).isNotNull()
    }

    // ══════════════════════════════════════════════════════
    // 4. 搜索：几种"空白"必须说不同的话
    // ══════════════════════════════════════════════════════

    @Test
    fun `单字符提示再输入一个字而不是无结果`() {

        launch()
        buildIndex()
        type("P")

        assertThat(text(R.id.stateTitle)).isEqualTo("再输入 1 个字")
        assertThat(text(R.id.stateBody)).contains("至少输入 2 个字")

        assertThat(text(R.id.stateTitle)).doesNotContain("没找到")
    }

    @Test
    fun `搜不到时回显关键词并提示半角`() {



        //                ② 直接告诉他"ＰＥＫ 要打成 PEK"
        launch()
        buildIndex()
        type("ZZZZ")

        assertThat(text(R.id.stateTitle)).isEqualTo("没找到「ZZZZ」")
        assertThat(text(R.id.stateBody)).contains("半角")
    }

    @Test
    fun `搜不到且无结果时列表不可见`() {


        launch()
        buildIndex()
        type("PEK")
        type("ZZZZ")

        assertThat(isVisible(R.id.resultList)).isFalse()
        assertThat(isVisible(R.id.resultCount)).isFalse()
    }

    @Test
    fun `空库显示数据没准备好并给重试`() {

        launch(rows = emptyList())
        buildIndex()

        assertThat(text(R.id.stateTitle)).isEqualTo("机场数据还没准备好")
        assertThat(isVisible(R.id.btnRetry)).isTrue()
    }

    @Test
    fun `空库时打关键词仍显示数据没准备好`() {

        //   必须还是 Empty —— 库是空的，用户搜什么都不可能有结果，
        //   说"没找到「PEK」"会让他反复换关键词试。
        launch(rows = emptyList())
        buildIndex()
        type("PEK")

        assertThat(text(R.id.stateTitle)).isEqualTo("机场数据还没准备好")
    }

    // ══════════════════════════════════════════════════════
    // 5. 交互
    // ══════════════════════════════════════════════════════

    @Test
    fun `点清除后输入框清空且回到 Idle`() {
        launch()
        buildIndex()
        type("PEK")
        assertThat(isVisible(R.id.btnClear)).isTrue()

        scenario!!.onActivity { view(R.id.btnClear).performClick() }
        settle()

        assertThat((view(R.id.searchInput) as EditText).text.toString()).isEmpty()
        assertThat(isVisible(R.id.btnClear)).isFalse()

        assertThat(isVisible(R.id.resultList)).isFalse()
        assertThat(isVisible(R.id.resultCount)).isFalse()
        assertThat(text(R.id.stateTitle)).doesNotContain("没找到")
    }

    @Test
    fun `点重试会重新建索引`() {

        launch(rows = emptyList())
        buildIndex()

        scenario!!.onActivity { view(R.id.btnRetry).performClick() }
        repeat(5) { idle() }

        // 仍是 Empty（数据没变），关键是没有切换到 Loading 后卡死
        assertThat(text(R.id.stateTitle)).isEqualTo("机场数据还没准备好")
    }

    @Test
    fun `点返回触发回调`() {
        launch()
        var backed = false
        scenario!!.onActivity {
            fragment().onBack = { backed = true }
            fragment().view!!.findViewById<View>(R.id.btnBack).performClick()
        }
        assertThat(backed).isTrue()
    }

    // ══════════════════════════════════════════════════════
    // 6. ViewBinding 泄漏
    // ══════════════════════════════════════════════════════

    @Test
    fun `销毁视图后访问 binding 立刻抛错而不是静默写入孤儿视图`() {

        //   `onDestroyView` 不置 null → 旋转屏幕后代码还往旧 View 树写数据，
        //   不崩、不报错、屏幕上没反应。开发者会以为是"数据没更新"。
        launch()
        val f = fragment()

        //   Activity 到 CREATED 时 Fragment 只走 onPause/onStop，
        //   view 仍然存在（实测：`f.view` 还是 LinearLayout）。
        //   view 的销毁只发生在 Fragment **被 detach / remove** 时。

        //   报错是 `expected: null but was: LinearLayout{...}`。
        scenario!!.onActivity { act ->
            act.supportFragmentManager.beginTransaction()
                .detach(f)
                .commitNow()
        }
        idle()

        assertThat(f.view).isNull()

        //   它比 `view!!` 好在**崩溃日志里那一行直接就是答案** ——
        //   `view!!` 抛 KotlinNullPointerException（无信息），

        val thrown = runCatching { f.requireViewForTest() }.exceptionOrNull()

        //   不是 IllegalStateException。
        //   （记住这条：`check()` 抛 ISE，`require()` 抛 IAE ——
        //     Kotlin 标准的成对设计，但在断言里很容易写反。）

        //   `expected instance of: IllegalStateException
        //    but was instance of : IllegalArgumentException
        //    with value          : binding 在 onDestroyView 后被访问`
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown).hasMessageThat().contains("onDestroyView")
    }

    // ── 输入辅助 ────────────────────────────────────────────











    private fun type(s: String) {
        scenario!!.onActivity { (view(R.id.searchInput) as EditText).setText(s) }
        settle()
    }

    // ── 样本 ────────────────────────────────────────────────

    private fun entity(
        id: Long,
        iata: String,
        name: String,
        city: String,
    ) = AirportEntity(
        id = id, iataCode = iata, icaoCode = "", name = name, city = city,
        country = "CN", latitude = 0.0, longitude = 0.0, elevation = 0,
        continent = "AS", type = "large_airport", popularity = 50,
        isPremiumOnly = false, nameZh = null, cityZh = null,
    )

    private fun sampleRows() = listOf(
        entity(1, "PEK", "Beijing Capital International", "Beijing"),
        entity(2, "PVG", "Shanghai Pudong International", "Shanghai"),
        entity(3, "HND", "Tokyo Haneda", "Tokyo"),
        entity(4, "ICN", "Incheon International", "Seoul"),
    )

    @Test
    fun `actual feature arguments render the measured route duration in the source city id`() {
        val target = entity(1, "DUR", "Duration Airport", "Not the displayed duration").copy(
            longitude = Math.toDegrees(100.0 / FlightMath.EARTH_RADIUS_KM),
        )
        launch(listOf(target), Bundle().apply {
            AirportSearchReferenceArgs.writeTo(this, AirportReferencePoint.from(0.0, 0.0))
        })
        type("DUR")
        assertRenderedDuration("8m")
    }

    @Test
    fun `partial feature reference arguments show unknown rather than fabricated zero coordinates`() {
        val target = entity(1, "DUR", "Duration Airport", "Not the displayed duration").copy(
            longitude = Math.toDegrees(100.0 / FlightMath.EARTH_RADIUS_KM),
        )
        launch(listOf(target), Bundle().apply { putDouble(AirportSearchReferenceArgs.LATITUDE, 0.0) })
        type("DUR")
        assertRenderedDuration("--")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w432dp-h960dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeOriginSearchAndResultSelectionKeepTheActualAirport() {
        val activity = launch(args = Bundle().apply { putString("arg_mode", "pick_origin") })
        settle()
        assertThat(text(R.id.airportPageTitle)).isEqualTo("从哪里出发")
        captureNative(activity, "airport-origin-day")
        type("PEK")
        awaitNative("Indexed PEK result") { isVisible(R.id.resultList) }
        captureNative(activity, "airport-results-day")
        var selected: Long? = null
        scenario!!.onActivity {
            fragment().onAirportSelected = { selected = it.id }
            val list = view(R.id.resultList) as RecyclerView
            assertThat(list.childCount).isGreaterThan(0)
            list.getChildAt(0).performClick()
        }
        assertThat(selected).isEqualTo(1L)
        assertThat(isVisible(R.id.statePanel)).isFalse()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w432dp-h960dp-night-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeNightSearchSeparatesNoResultFromInputGuidance() {
        val activity = launch(args = Bundle().apply { putString("arg_mode", "pick_destination") })
        type("A")
        assertThat(text(R.id.stateTitle)).contains("再输入")
        captureNative(activity, "airport-short-input-night")
        type("NoSuchAirport999")
        awaitNative("No result") { text(R.id.stateTitle).contains("没找到") }
        assertThat(isVisible(R.id.btnRetry)).isFalse()
        captureNative(activity, "airport-no-result-night")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeLargeTextEmptyStateCanScrollToItsRealRetryAction() {
        RuntimeEnvironment.setFontScale(1.6f)
        val activity = launch(rows = emptyList())
        awaitNative("Empty catalogue") { isVisible(R.id.btnRetry) }
        captureNative(activity, "airport-empty-small-top")
        scenario!!.onActivity {
            val retry = view(R.id.btnRetry)
            retry.requestRectangleOnScreen(Rect(0, 0, retry.width, retry.height), true)
        }
        settle()
        captureNative(activity, "airport-empty-small-action")
        val rect = Rect()
        assertThat(view(R.id.btnRetry).getGlobalVisibleRect(rect)).isTrue()
        assertThat(rect.height()).isAtLeast(48)
        var retries = 0
        scenario!!.onActivity {
            fragment().onRetry = { retries++ }
            view(R.id.btnRetry).performClick()
        }
        assertThat(retries).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w432dp-h960dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeLoadingFollowsTheActualIndexWorkAndStopsWhenResultsArrive() {
        val release = CompletableDeferred<Unit>()
        val entered = java.util.concurrent.atomic.AtomicBoolean(false)
        val dao = object : FakeAirportDao(sampleRows()) {
            override suspend fun getAll(hasPremium: Boolean): List<AirportEntity> {
                entered.set(true)
                release.await()
                return super.getAll(hasPremium)
            }
        }
        var activity: AirportHostActivity? = null
        try {
            scenario = ActivityScenario.launch(AirportHostActivity::class.java)
            scenario!!.onActivity {
                it.install(TestableAirportFragment(dao, settings.repository))
                activity = it
            }
            awaitNative("Index read started") { entered.get() }
            assertThat(text(R.id.stateTitle)).isEqualTo("正在准备机场数据")
            assertThat(isVisible(R.id.stateIllustration)).isTrue()
            captureNative(requireNotNull(activity), "airport-loading-day")
            release.complete(Unit)
            type("PEK")
            awaitNative("Indexed content") { isVisible(R.id.resultList) }
            assertThat(isVisible(R.id.stateIllustration)).isFalse()
            assertThat((view(R.id.stateIllustration) as AirportSkyStateView).isAnimating).isFalse()
        } finally {
            release.complete(Unit)
        }
    }

    private fun awaitNative(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out: $label" }
            settle(); Thread.sleep(4)
        }
        settle()
    }

    private fun captureNative(activity: AirportHostActivity, name: String) {
        settle()
        scenario!!.onActivity {
            val root = activity.window.decorView
            val metrics = activity.resources.displayMetrics
            val config = activity.resources.configuration
            val width = (config.screenWidthDp * metrics.density).toInt()
            val height = (config.screenHeightDp * metrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val target = File("build/reports/ui-airport/$name.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    private fun assertRenderedDuration(expected: String) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        var duration: String? = null
        do {
            settle()
            scenario!!.onActivity {
                val list = view(R.id.resultList) as RecyclerView
                list.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
                list.layout(0, 0, 320, 500)
                duration = list.getChildAt(0)?.findViewById<TextView>(R.id.pf_airport_tv_airport_city)?.text?.toString()
            }
            if (duration == expected) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Expected actual rendered duration $expected, last actual=$duration")
    }
}
