package com.projectfocus.feature.record.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.list.RecordListViewModel
import com.projectfocus.service.focus.session.TodayStatsNotifier
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId






































@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
@HiltAndroidTest
class RecordListFragmentTest {











    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUpHilt() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    private var scenario: ActivityScenario<RecordHostActivity>? = null

    // ═══════════════════════════════════════════════════════════
    // 固定时间
    // ═══════════════════════════════════════════════════════════
    //

    //   这个时刻在本地是中午，前后各留 12 小时缓冲 ——
    //   任何"±几小时"的样本都不会跨日，分组断言才稳定。
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Instant = Instant.parse("2026-10-26T04:00:00Z") // 本地 12:00
    private val clock: Clock = Clock.fixed(now, zone)

    /** 今天 08:30 起飞 */
    private val todayMs: Long = now.toEpochMilli() - 3.hours
    /** 昨天 09:00 起飞 */
    private val yesterdayMs: Long = todayMs - 24.hours

    // ═══════════════════════════════════════════════════════════
    // 样本
    // ═══════════════════════════════════════════════════════════

    private fun entity(
        id: String,
        from: String = "PEK",
        fromCity: String = "北京",
        to: String = "KIX",
        toCity: String = "大阪",
        startMs: Long = todayMs,
        planned: Int = 135,
        actual: Int = 120,
        status: String = "COMPLETED",
        km: Double = 1800.0,
        country: String = "日本",
    ) = FlightHistoryEntity(
        id = id,
        roomId = null,
        focusType = "SOLO",
        roomParticipantCount = null,
        departureAirportCode = from,
        departureAirportName = "北京首都国际机场",
        departureCity = fromCity,
        departureCountry = "中国",
        departureLatitude = 40.08,
        departureLongitude = 116.58,
        destinationAirportCode = to,
        destinationAirportName = "关西国际机场",
        destinationCity = toCity,
        destinationCountry = country,
        destinationLatitude = 34.43,
        destinationLongitude = 135.24,
        startTimeMillis = startMs,
        endTimeMillis = startMs + planned * 60_000L,
        durationMinutes = planned,
        actualFocusMinutes = actual,
        distanceKm = km,
        status = status,
        categoryId = null,
        seat = null,
        hasAnnouncedDeparture = true,
        hasAnnouncedDescent = true,
        hasAnnouncedArrival = true,
        logJson = null,
        isSynced = true,
        createdAt = startMs,
        updatedAt = startMs,
    )

    private fun twoRows() = listOf(
        entity(id = "a", startMs = todayMs),
        entity(id = "b", to = "PVG", toCity = "上海", startMs = yesterdayMs, km = 1200.0),
    )

    // ═══════════════════════════════════════════════════════════
    // 启动
    // ═══════════════════════════════════════════════════════════

    private fun launch(rows: List<FlightHistoryEntity>): TagProbeFragment {
        val dao = FakeFlightHistoryDao(rows)
        scenario = ActivityScenario.launch(RecordHostActivity::class.java)
        lateinit var frag: TagProbeFragment
        scenario!!.onActivity { act ->
            frag = TagProbeFragment(dao, clock, zone)
            act.install(frag)
        }
        settle()
        return frag
    }

    private fun fragment(): TagProbeFragment {
        lateinit var frag: TagProbeFragment
        scenario!!.onActivity { act ->
            frag = act.supportFragmentManager.fragments.first() as TagProbeFragment
        }
        return frag
    }

    private fun activity(): RecordHostActivity {
        lateinit var act: RecordHostActivity
        scenario!!.onActivity { act = it }
        return act
    }








    private fun settle() {
        val main = Looper.getMainLooper()
        val shadow = shadowOf(main)
        repeat(6) {
            shadow.idle()

            //
            //   `ListAdapter.submitList` 的 diff 在**真实后台线程**上算
            //   （`DiffUtil.DEFAULT_THREAD`，共享的 2 线程池），
            //   算完才 post 回主线程。只拨虚拟时钟是拨不动它的。
            //   5ms × 6 轮 = 30ms，对 5 条以内的 diff 足够（实测 < 1ms），
            //   而又不会把测试拖慢到秒级。
            Thread.sleep(5)
            shadow.idle()
            shadow.idleFor(java.time.Duration.ofMillis(24))
        }
    }























    private fun idle() {
        settle()
    }

    private fun type(text: String) {
        scenario!!.onActivity { act ->
            val f = act.supportFragmentManager.fragments.first() as TagProbeFragment
            f.fragBinding.searchInput.setText(text)
        }
        settle()
    }

    private fun stateTitleText(f: TagProbeFragment): String =
        f.fragBinding.stateTitle.text.toString()

    private fun list(): RecyclerView = fragment().fragBinding.recordList

    private fun statePanelVisible(f: TagProbeFragment): Boolean =
        f.fragBinding.statePanel.visibility == View.VISIBLE

    // ═══════════════════════════════════════════════════════════
    // 1~4 加载态
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `有记录时列表可见而状态面板不可见`() {
        val f = launch(twoRows())
        assertThat(list().visibility).isEqualTo(View.VISIBLE)
        assertThat(statePanelVisible(f)).isFalse()
    }

    @Test
    fun `有记录时列表条目数等于汇总加分组标题加卡片`() {
        // 2 条记录，分属今天/昨天 → 2 个分组，每组 1 条
        // → 汇总(1) + 标题(2) + 卡片(2) = 5
        val f = launch(twoRows())

        //   ListAdapter 是异步 diff，但 Robolectric 的 main looper
        //   一旦 idle 完，AsyncListDiffer 的后台任务也早完成了。
        idle()
        assertThat(f.fragBinding.recordList.adapter!!.itemCount).isEqualTo(5)
    }

    @Test
    fun `首帧不会闪出空态面板`() {

        //   默认 visible 的话，第一帧会闪一下"还没飞过"再变成列表 ——
        //   用户看到的是"App 卡了一下"。
        val f = launch(twoRows())
        assertThat(statePanelVisible(f)).isFalse()
    }

    @Test
    fun `有记录时不显示空态文案`() {
        val f = launch(twoRows())

        //   万一将来加了别的地方设文案，isEqualTo 会误报；

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(stateTitleText(f)).isNotEqualTo(ctx.getString(R.string.record_empty_title))
        assertThat(stateTitleText(f)).doesNotContain("还没飞过")
    }

    // ═══════════════════════════════════════════════════════════
    // 5~8 空态
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `没有记录时显示还没飞过而不是空白`() {
        val f = launch(emptyList())
        assertThat(statePanelVisible(f)).isTrue()
        assertThat(stateTitleText(f)).isEqualTo("还没飞过")
    }

    @Test
    fun `没有记录时给出下一步引导而不是只说没有`() {
        val f = launch(emptyList())

        assertThat(f.fragBinding.stateBody.text.toString()).contains("第一趟")
    }

    @Test
    fun `没有记录时列表隐藏`() {
        launch(emptyList())
        assertThat(list().visibility).isNotEqualTo(View.VISIBLE)
    }

    @Test
    fun `空态不显示清除搜索按钮`() {
        val f = launch(emptyList())

        assertThat(f.fragBinding.btnClearSearch.visibility).isEqualTo(View.GONE)
    }

    // ═══════════════════════════════════════════════════════════
    // 9~12 搜索
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `搜不到时回显关键词`() {
        val f = launch(twoRows())
        type("广州")
        assertThat(stateTitleText(f)).contains("广州")
    }

    @Test
    fun `搜不到时提示是他搜的词造成的而不是库空了`() {
        val f = launch(twoRows())
        type("广州")

        //   两者界面都是空白，但用户该做的事完全不同。
        assertThat(stateTitleText(f)).contains("没有匹配")
        assertThat(stateTitleText(f)).doesNotContain("还没飞过")
    }

    @Test
    fun `搜不到时给出清除搜索按钮`() {
        val f = launch(twoRows())
        type("广州")
        assertThat(f.fragBinding.btnClearSearch.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `搜城市名能筛出对应记录`() {
        val f = launch(twoRows())
        type("上海")
        idle()
        // 1 条 → 汇总 + 卡片（单组单条不显示标题）= 2
        assertThat(f.fragBinding.recordList.adapter!!.itemCount).isEqualTo(2)
    }

    @Test
    fun `搜三字码能筛出对应记录`() {
        val f = launch(twoRows())
        type("PVG")
        idle()
        assertThat(f.fragBinding.recordList.adapter!!.itemCount).isEqualTo(2)
    }

    @Test
    fun `搜索大小写不敏感`() {
        val f = launch(twoRows())
        type("pvg")
        idle()
        assertThat(f.fragBinding.recordList.adapter!!.itemCount).isEqualTo(2)
    }

    @Test
    fun `长关键词会被截断而不是把标题撑成三行`() {
        val f = launch(twoRows())

        type("一二三四五六七八九十一二三四五六七八九十")
        assertThat(stateTitleText(f).length).isLessThan(40)
    }

    @Test
    fun `有内容时清除按钮出现`() {
        val f = launch(twoRows())
        assertThat(f.fragBinding.btnClear.visibility).isEqualTo(View.GONE)
        type("北京")
        assertThat(f.fragBinding.btnClear.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `点清除后输入框清空且回到完整列表`() {
        val f = launch(twoRows())
        type("上海")
        idle()
        scenario!!.onActivity { f.fragBinding.btnClear.performClick() }
        settle()

        assertThat(f.fragBinding.searchInput.text.toString()).isEmpty()
        assertThat(f.fragBinding.btnClear.visibility).isEqualTo(View.GONE)
        assertThat(f.fragBinding.recordList.adapter!!.itemCount).isEqualTo(5)
    }

    // ═══════════════════════════════════════════════════════════
    // 13~15 「隐藏」不等于「清空」
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `回到有内容后状态面板的文案被清空而不是残留上一态`() {

        //   `renderIdle` 只把 stateTitle 设 GONE 没清 text，
        //   屏幕上看不出来，但 TalkBack 会把旧文案拼进朗读。
        //   本页用同一个 renderMessage 入口，理论上不会残留；
        //   这条测试是那个"理论上"的证明。
        val f = launch(twoRows())
        type("广州")            // → NoResult，标题 = 没有匹配「广州」的航班
        settle()
        assertThat(stateTitleText(f)).contains("广州")

        scenario!!.onActivity { f.fragBinding.btnClear.performClick() }
        settle()

        // 面板整体 GONE，且文案**必须**已经换了 —— 不再是「广州」
        assertThat(statePanelVisible(f)).isFalse()
        assertThat(stateTitleText(f)).doesNotContain("广州")
    }

    @Test
    fun `空态不会残留搜索态文案`() {
        val f = launch(twoRows())
        type("广州")
        settle()
        System.err.println("XPROBE A vm=" + f.stateForTest())
        System.err.println("XPROBE A input=" + f.fragBinding.searchInput.text)
        type("")
        settle()
        System.err.println("XPROBE B vm=" + f.stateForTest())
        System.err.println("XPROBE B input=" + f.fragBinding.searchInput.text)
        System.err.println("XPROBE B title=" + stateTitleText(f))
        assertThat(stateTitleText(f)).doesNotContain("没有匹配")
    }

    @Test
    fun `没有正文的状态下正文控件被 GONE 而不是留一个空白 TextView`() {

        //   标题看起来会偏上 4dp —— 肉眼看不出，但对齐就是不对。
        //


        //   但**搜不到时 body 本来就不该有内容**（NoResult 只有标题 + 按钮），

        //

        //   ```
        //   XPROBE renderMessage body=[] len=0              ← NoResult，空正文
        //   XPROBE after isVisible vis=8                    ← GONE，正确
        //   XPROBE renderMessage body=[完成第一趟…] len=20  ← Empty，有正文
        //   XPROBE after isVisible vis=0                    ← VISIBLE，正确
        //   ```
        //   `isVisible = body.isNotEmpty()` 一直是对的，错的是断言。
        //

        //     正确顺序是先跑一遍看真实值 —— 但前提是"真实值确实符合设计"，
        //     而这里它确实符合（NoResult 不该有第二行字）。

        //   `launch(emptyList())` 是「库里没有记录」→ 状态永久是 `Empty`，
        //   输入「广州」也**不会**变成 `NoResult`（`RecordListBuilder.build`
        //   第一行就是 `if (entities.isEmpty()) return Empty`）。
        //   要在 `NoResult` 上断言，前提是**库里有记录**。
        //

        //     一条记录都没有时，说「没有匹配广州」是误导 ——
        //     用户会以为搜别的就能搜到，其实搜什么都搜不到。
        //     `Empty`（"还没飞过"）才是诚实的。
        val f = launch(twoRows())
        // 有记录 → 列表态，面板隐藏，但 Empty 的正文**必须已经被清掉**
        assertThat(f.stateForTest()).isInstanceOf(com.projectfocus.feature.record.list.RecordListState.Content::class.java)
        assertThat(f.fragBinding.stateBody.visibility).isEqualTo(View.GONE)

        type("广州")
        settle()
        // NoResult：没有正文，控件必须 GONE，否则会白占 8dp
        assertThat(f.stateForTest()).isInstanceOf(com.projectfocus.feature.record.list.RecordListState.NoResult::class.java)
        assertThat(f.fragBinding.stateBody.visibility).isEqualTo(View.GONE)
        assertThat(f.fragBinding.stateBody.text.toString()).isEmpty()
    }

    // ═══════════════════════════════════════════════════════════
    // 16~17 返回键
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `搜索框空时返回键交给宿主`() {
        val f = launch(twoRows())
        var backCalls = 0
        f.onBack = { backCalls++ }



        activity().dispatcher.onBackPressed()
        idle()
        assertThat(backCalls).isEqualTo(0)
    }

    @Test
    fun `搜索框有内容时返回键先清搜索而不是退出页面`() {
        val f = launch(twoRows())
        type("上海")
        var backCalls = 0
        f.onBack = { backCalls++ }
        settle()

        activity().dispatcher.onBackPressed()
        settle()


        //   （Gmail / 通讯录 / Play 都是这个行为）。
        assertThat(f.fragBinding.searchInput.text.toString()).isEmpty()
        assertThat(backCalls).isEqualTo(0)
    }

    // ═══════════════════════════════════════════════════════════
    // 18~19 删除
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `删除后列表真的少一条`() {
        val f = launch(twoRows())
        System.err.println("XPROBE before delete: vmState=" + f.stateForTest())
        idle()
        assertThat(f.fragBinding.recordList.adapter!!.itemCount).isEqualTo(5)

        var deleted = 0
        f.onDeleted = { deleted++ }
        f.onConfirmDelete = { _, confirm -> confirm() }

        scenario!!.onActivity {
            f.requestDelete(
                com.projectfocus.feature.record.list.RecordItem(
                    id = "a", fromCode = "PEK", toCode = "KIX", fromCity = "北京", toCity = "大阪",
                    startTime = "08:30", durationText = "2 小时 15 分", distanceText = "1800.0 km",
                    focusText = "2 小时", status = com.projectfocus.feature.record.list.FlightStatus.COMPLETED,
                    completionRatio = 0.9f,
                ),
            )
        }
        settle()
        idle()

        System.err.println("XPROBE after delete: vmState=" + f.stateForTest())
        System.err.println("XPROBE adapter items=" + f.fragBinding.recordList.adapter!!.itemCount + " currentList=" + (f.adapterForTest().currentList.size))
        System.err.println("XPROBE sameAdapter=" + (f.fragBinding.recordList.adapter === f.adapterForTest()) + " state=" + f.stateForTest())
        System.err.println("XPROBE lam=" + f.fragBinding.recordList.layoutManager + " hasAdapterFlag=" + (f.fragBinding.recordList.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).itemCount)
        System.err.println("XPROBE dao rows=" + f.daoRowsForTest().size)
        assertThat(deleted).isEqualTo(1)
        // 剩 1 条：汇总 + 卡片 = 2
        assertThat(f.fragBinding.recordList.adapter!!.itemCount).isEqualTo(2)
    }

    @Test
    fun `删除走二次确认而不是点一下就删`() {
        val f = launch(twoRows())
        idle()
        var asked = 0
        f.onConfirmDelete = { _, _ -> asked++ }
        f.onDeleted = { }

        f.requestDelete(
            com.projectfocus.feature.record.list.RecordItem(
                id = "a", fromCode = "PEK", toCode = "KIX", fromCity = "北京", toCity = "大阪",
                startTime = "08:30", durationText = "2 小时 15 分", distanceText = "1800.0 km",
                focusText = "2 小时", status = com.projectfocus.feature.record.list.FlightStatus.COMPLETED,
                completionRatio = 0.9f,
            ),
        )
        idle()

        assertThat(asked).isEqualTo(1)
        assertThat(f.fragBinding.recordList.adapter!!.itemCount).isEqualTo(5)
    }

    // ═══════════════════════════════════════════════════════════
    // 20 生命周期
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `销毁视图后访问 binding 立刻抛错而不是静默写入孤儿视图`() {
        launch(twoRows())
        val f = fragment()



        //    onPause/onStop，view 还在）。必须 detach。
        scenario!!.onActivity { act ->
            act.supportFragmentManager.beginTransaction().detach(f).commitNow()
        }
        idle()

        assertThat(f.view).isNull()
        val thrown = runCatching { f.requireViewForTest() }.exceptionOrNull()

        //   不是 IllegalStateException（require=参数错=IAE，check=状态错=ISE）。
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown).hasMessageThat().contains("onDestroyView")
    }

    // ═══════════════════════════════════════════════════════════
    // 测试替身
    // ═══════════════════════════════════════════════════════════









    class TagProbeFragment(
        private val dao: FakeFlightHistoryDao,
        private val clock: Clock,
        private val zone: ZoneId,
    ) : RecordListFragment() {








        val fragBinding get() = binding

        fun daoRowsForTest() = dao.rows.value

        override fun createViewModel(): RecordListViewModel =

            //   注意这里**不需要**注入 dispatcher —— 本 ViewModel 的
            //   链路里没有 `flowOn` / `withContext`，全在 viewModelScope 上，
            //   而 viewModelScope 已由 Robolectric 的 main looper 驱动。

            RecordListViewModel(dao = dao, clock = clock, zone = zone, todayNotifier = TodayStatsNotifier())
    }
}

/** `3.5.hours` 这种写法只为可读 —— 避免在样本里写 12600000 */
private val Int.hours: Long get() = this.toLong() * 3_600_000L
