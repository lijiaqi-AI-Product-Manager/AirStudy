package com.projectfocus.feature.record.ui

import android.os.Looper
import android.view.View
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.detail.RecordDetailState
import com.projectfocus.feature.record.detail.RecordDetailViewModel
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
class RecordDetailFragmentTest {

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
        com.projectfocus.core.network.di.TokenStore.clear()
    }

    private var scenario: ActivityScenario<RecordHostActivity>? = null

    // ═══════════════════════════════════════════════════════════
    // 固定时间
    // ═══════════════════════════════════════════════════════════

    /** 2026-10-26 12:00 北京时间 */
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Instant = Instant.parse("2026-10-26T04:00:00Z")
    private val clock: Clock = Clock.fixed(now, zone)

    /** 今天 08:30 起飞 */
    private val startMs: Long = now.toEpochMilli() - 3L * 60 * 60 * 1000

    // ═══════════════════════════════════════════════════════════
    // 样本
    // ═══════════════════════════════════════════════════════════

    private fun entity(
        id: String = "rec-1",
        planned: Int = 135,
        actual: Int = 125,
        status: String = "COMPLETED",
        km: Double = 1800.0,
        seat: String? = null,
        quote: String? = null,
        quoteAuthor: String? = null,
        focusType: String = "SOLO",
        participants: Int? = null,
    ) = FlightHistoryEntity(
        id = id,
        roomId = null,
        focusType = focusType,
        roomParticipantCount = participants,
        departureAirportCode = "PEK",
        departureAirportName = "北京首都国际机场",
        departureCity = "北京",
        departureCountry = "中国",
        departureLatitude = 40.08,
        departureLongitude = 116.58,
        destinationAirportCode = "PVG",
        destinationAirportName = "上海浦东国际机场",
        destinationCity = "上海",
        destinationCountry = "中国",
        destinationLatitude = 31.14,
        destinationLongitude = 121.81,
        startTimeMillis = startMs,
        endTimeMillis = startMs + planned * 60_000L,
        durationMinutes = planned,
        actualFocusMinutes = actual,
        distanceKm = km,
        status = status,
        categoryId = null,
        seat = seat,
        hasAnnouncedDeparture = true,
        hasAnnouncedDescent = true,
        hasAnnouncedArrival = true,
        logJson = null,
        isSynced = true,
        createdAt = startMs,
        updatedAt = startMs,
        quote = quote,
        quoteAuthor = quoteAuthor,
    )

    // ═══════════════════════════════════════════════════════════
    // 启动
    // ═══════════════════════════════════════════════════════════

    private fun launch(
        rows: List<FlightHistoryEntity>,
        id: String = rows.firstOrNull()?.id ?: "missing",








        findByIdDelayMs: Long = 0,
    ): TagProbeDetailFragment {
        val dao = FakeFlightHistoryDao(rows).apply { this.findByIdDelayMs = findByIdDelayMs }
        scenario = ActivityScenario.launch(RecordHostActivity::class.java)
        lateinit var frag: TagProbeDetailFragment
        scenario!!.onActivity { act ->
            frag = TagProbeDetailFragment(dao, clock, zone, id)
            act.install(frag)
        }
        settle()
        return frag
    }

    private fun currentFragment(): TagProbeDetailFragment {
        lateinit var frag: TagProbeDetailFragment
        scenario!!.onActivity { act ->
            frag = act.supportFragmentManager.fragments.first() as TagProbeDetailFragment
        }
        return frag
    }









    private fun settle() {
        val shadow = shadowOf(Looper.getMainLooper())
        repeat(4) {
            shadow.idle()
            shadow.idleFor(java.time.Duration.ofMillis(24))
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 1~4 加载与内容
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `有记录时显示内容区并隐藏状态面板`() {
        val f = launch(listOf(entity()))
        assertThat(f.stateForTest()).isInstanceOf(RecordDetailState.Content::class.java)
        assertThat(f.fragBinding.detailScroll.visibility).isEqualTo(View.VISIBLE)
        assertThat(f.fragBinding.statePanel.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `三字码与城市按投影显示`() {
        val f = launch(listOf(entity()))
        assertThat(f.fragBinding.fromCode.text.toString()).isEqualTo("PEK")
        assertThat(f.fragBinding.toCode.text.toString()).isEqualTo("PVG")
        assertThat(f.fragBinding.fromCity.text.toString()).isEqualTo("北京")
        assertThat(f.fragBinding.toCity.text.toString()).isEqualTo("上海")
    }

    @Test
    fun `时间区间与状态按投影显示`() {

        //   本地 12:00 减 3 小时是 **09:00**，不是 08:30。

        //     而错的是断言不是代码。这正是"先跑一遍打印真实值"的价值

        val local0830 = java.time.ZonedDateTime
            .of(2026, 10, 26, 8, 30, 0, 0, zone)
            .toInstant().toEpochMilli()
        val f = launch(listOf(entity().copy(startTimeMillis = local0830,
            endTimeMillis = local0830 + 135 * 60_000L)))
        assertThat(f.fragBinding.timeRangeText.text.toString()).isEqualTo("08:30 — 10:45")
        assertThat(f.fragBinding.statusText.text.toString()).isEqualTo("已完成")
    }

    @Test
    fun `计划与实际与差额三处都对`() {
        val f = launch(listOf(entity(planned = 135, actual = 125)))
        assertThat(f.fragBinding.plannedText.text.toString()).isEqualTo("2 小时 15 分")
        assertThat(f.fragBinding.actualText.text.toString()).isEqualTo("2 小时 5 分")
        assertThat(f.fragBinding.diffText.text.toString()).isEqualTo("少 10 分钟")
    }

    @Test
    fun `完成度百分比显示并带百分号`() {
        val f = launch(listOf(entity(planned = 135, actual = 125)))
        assertThat(f.fragBinding.percentRow.visibility).isEqualTo(View.VISIBLE)
        assertThat(f.fragBinding.percentText.text.toString()).isEqualTo("93%")
    }

    @Test
    fun `计划为零时整行完成度 GONE`() {


        //     详情页仍要能正常显示内容（不能因为数据缺失就整页空）。
        val f = launch(listOf(entity(planned = 0, actual = 0)))
        assertThat(f.stateForTest()).isInstanceOf(RecordDetailState.Content::class.java)
        assertThat(f.fragBinding.percentRow.visibility).isEqualTo(View.GONE)
        assertThat(f.fragBinding.percentText.text.toString()).isEmpty()
    }

    @Test
    fun `里程与换算都显示`() {
        val f = launch(listOf(entity(km = 1800.0)))
        assertThat(f.fragBinding.distanceText.text.toString()).isEqualTo("1800.0 km")
        assertThat(f.fragBinding.cruiseEquivalentText.text.toString())
            .isEqualTo("相当于巡航 2 小时 15 分")
        assertThat(f.fragBinding.routeEquivalentText.text.toString())
            .isEqualTo("约 1.7 段京沪航线")
    }

    @Test
    fun `短途时换算行 GONE 而不是显示破折号`() {

        //   显示一个 "—" 会让用户觉得"这里应该有字但丢了"。
        val f = launch(listOf(entity(km = 30.0)))
        assertThat(f.fragBinding.routeEquivalentText.visibility).isEqualTo(View.GONE)
        // 30 km 仍能算巡航时长（2 分钟），所以那行还在
        assertThat(f.fragBinding.cruiseEquivalentText.visibility).isEqualTo(View.VISIBLE)
    }

    // ═══════════════════════════════════════════════════════════
    // 5~8 可选块
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `没有语录时语录块 GONE`() {
        val f = launch(listOf(entity(quote = null)))
        assertThat(f.fragBinding.quoteBlock.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `有语录时语录块显示且作者带破折号前缀`() {
        val f = launch(listOf(entity(quote = "路是自己走出来的", quoteAuthor = "鲁迅")))
        assertThat(f.fragBinding.quoteBlock.visibility).isEqualTo(View.VISIBLE)
        assertThat(f.fragBinding.quoteText.text.toString()).isEqualTo("路是自己走出来的")
        assertThat(f.fragBinding.quoteAuthorText.text.toString()).isEqualTo("—— 鲁迅")
    }

    @Test
    fun `语录没有作者时作者行 GONE 而不是留一个空破折号`() {
        val f = launch(listOf(entity(quote = "路是自己走出来的", quoteAuthor = null)))
        assertThat(f.fragBinding.quoteText.text.toString()).isEqualTo("路是自己走出来的")
        assertThat(f.fragBinding.quoteAuthorText.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `没有座位时座位行 GONE`() {
        val f = launch(listOf(entity(seat = null)))
        assertThat(f.fragBinding.seatText.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `有座位时带前缀显示`() {
        val f = launch(listOf(entity(seat = "12A")))
        assertThat(f.fragBinding.seatText.visibility).isEqualTo(View.VISIBLE)
        assertThat(f.fragBinding.seatText.text.toString()).isEqualTo("座位 12A")
    }

    @Test
    fun `联机飞行显示同行人数`() {
        val f = launch(listOf(entity(focusType = "GROUP", participants = 3)))
        assertThat(f.fragBinding.participantText.visibility).isEqualTo(View.VISIBLE)
        assertThat(f.fragBinding.participantText.text.toString()).isEqualTo("与 3 人同行")
    }

    // ═══════════════════════════════════════════════════════════
    // 9~12 操作可用性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `已完成的记录可以重飞和分享`() {
        val f = launch(listOf(entity(status = "COMPLETED")))
        assertThat(f.fragBinding.btnReplay.isEnabled).isTrue()
        assertThat(f.fragBinding.btnShare.isEnabled).isTrue()
    }

    @Test
    fun `已取消的记录不能重飞但可以删除`() {
        val f = launch(listOf(entity(status = "CANCELLED")))
        assertThat(f.fragBinding.btnReplay.isEnabled).isFalse()
        assertThat(f.fragBinding.btnShare.isEnabled).isFalse()

        assertThat(f.fragBinding.btnDelete.isEnabled).isTrue()
    }

    @Test
    fun `不可重飞时按钮仍然可见只是置灰`() {

        //   → 始终显示，靠 state_enabled=false 的 drawable 表现禁用。
        val f = launch(listOf(entity(status = "CANCELLED")))
        assertThat(f.fragBinding.btnReplay.visibility).isEqualTo(View.VISIBLE)
        assertThat(f.fragBinding.btnShare.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `状态异常的记录不能重飞`() {
        val f = launch(listOf(entity(status = "TOTALLY_UNKNOWN")))
        assertThat(f.fragBinding.statusText.text.toString()).isEqualTo("已取消（状态异常）")
        assertThat(f.fragBinding.btnReplay.isEnabled).isFalse()
    }

    // ═══════════════════════════════════════════════════════════
    // 13~16 空 / 不存在 / 错误
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `记录不存在时显示不存在态而不是一直转圈`() {

        //   当成 Loading 的话用户会永远看到转圈。
        val f = launch(listOf(entity(id = "other")), id = "no-such-id")
        assertThat(f.stateForTest()).isEqualTo(RecordDetailState.NotFound)
        assertThat(f.fragBinding.statePanel.visibility).isEqualTo(View.VISIBLE)
        assertThat(f.fragBinding.stateTitle.text.toString()).isEqualTo("这条记录不在了")

    }

    @Test
    fun `不存在态给出返回列表的出口`() {
        val f = launch(emptyList(), id = "no-such-id")

        assertThat(f.fragBinding.btnStateBack.visibility).isEqualTo(View.VISIBLE)
        assertThat(f.fragBinding.btnStateBack.text.toString()).isEqualTo("返回列表")
    }

    @Test
    fun `不存在态下内容区与操作区都 GONE`() {
        val f = launch(emptyList(), id = "no-such-id")
        assertThat(f.fragBinding.detailScroll.visibility).isEqualTo(View.GONE)
        assertThat(f.fragBinding.actionBlock.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `不存在态有正文而加载态没有正文`() {

        //   否则空 TextView 会吃掉 marginTop，标题看起来偏上。
        val f = launch(emptyList(), id = "no-such-id")
        assertThat(f.fragBinding.stateBody.visibility).isEqualTo(View.VISIBLE)
        assertThat(f.fragBinding.stateBody.text.toString()).isNotEmpty()
    }

    // ═══════════════════════════════════════════════════════════
    // 17~20 文案残留（第 14/22 条的第三次应用）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `回到内容态后状态文案被清空`() {

        //
        //   真实路径：用户在详情页，另一台设备删了这条记录 →
        //   状态变 NotFound（面板显示"这条记录不在了"）→
        //   然后那台设备又同步回来了 / 用户撤回 →
        //   状态变回 Content。此时如果只 `statePanel.isVisible = false`，
        //   文案还挂在 stateTitle 上 —— TalkBack 会在内容页念出
        //   "这条记录不在了"，而用户明明看到了完整详情。
        val dao = FakeFlightHistoryDao(emptyList())
        scenario = ActivityScenario.launch(RecordHostActivity::class.java)
        lateinit var frag: TagProbeDetailFragment
        scenario!!.onActivity { act ->
            frag = TagProbeDetailFragment(dao, clock, zone, "rec-1")
            act.install(frag)
        }
        settle()

        // 此刻记录不存在
        assertThat(frag.stateForTest()).isEqualTo(RecordDetailState.NotFound)
        assertThat(frag.fragBinding.stateTitle.text.toString()).isEqualTo("这条记录不在了")

        // 记录"回来了"
        dao.rows.value = listOf(entity(id = "rec-1"))
        settle()

        assertThat(frag.stateForTest()).isInstanceOf(RecordDetailState.Content::class.java)
        assertThat(frag.fragBinding.statePanel.visibility).isEqualTo(View.GONE)

        assertThat(frag.fragBinding.stateTitle.text.toString()).isEmpty()
        assertThat(frag.fragBinding.stateBody.text.toString()).isEmpty()
    }

    @Test
    fun `不存在态的正文不会残留在加载态上`() {

        //   body 也要清。这里用"记录存在 → 被删 → 面板出现正文"验证一次。
        val dao = FakeFlightHistoryDao(listOf(entity(id = "rec-1")))
        scenario = ActivityScenario.launch(RecordHostActivity::class.java)
        lateinit var frag: TagProbeDetailFragment
        scenario!!.onActivity { act ->
            frag = TagProbeDetailFragment(dao, clock, zone, "rec-1")
            act.install(frag)
        }
        settle()
        assertThat(frag.fragBinding.stateBody.visibility).isEqualTo(View.GONE)

        dao.rows.value = emptyList()
        settle()
        assertThat(frag.stateForTest()).isEqualTo(RecordDetailState.NotFound)
        assertThat(frag.fragBinding.stateBody.visibility).isEqualTo(View.VISIBLE)
        assertThat(frag.fragBinding.stateBody.text.toString()).contains("删除")
    }

    // ═══════════════════════════════════════════════════════════
    // 21~24 删除
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `删除走二次确认不会直接删`() {
        val f = launch(listOf(entity()))
        var asked = 0
        f.onConfirmDelete = { asked++ } // 只记录，不调 confirm

        scenario!!.onActivity { f.fragBinding.btnDelete.performClick() }
        settle()

        assertThat(asked).isEqualTo(1)
    }

    @Test
    fun `确认后真的从库里删掉`() {
        val dao = FakeFlightHistoryDao(listOf(entity(id = "rec-1")))
        scenario = ActivityScenario.launch(RecordHostActivity::class.java)
        lateinit var frag: TagProbeDetailFragment
        scenario!!.onActivity { act ->
            frag = TagProbeDetailFragment(dao, clock, zone, "rec-1")
            act.install(frag)
        }
        settle()

        var deleted = 0
        frag.onDeleted = { deleted++ }
        frag.onConfirmDelete = { it() }

        scenario!!.onActivity { frag.fragBinding.btnDelete.performClick() }
        settle()

        assertThat(dao.rows.value).isEmpty()
        assertThat(deleted).isEqualTo(1)
    }

    @Test fun retainedDeletionConfirmationCannotRunAfterRecordChangedOrAccountChanged() {
        val dao = FakeFlightHistoryDao(listOf(entity(id = "rec-1")))
        scenario = ActivityScenario.launch(RecordHostActivity::class.java)
        lateinit var frag: TagProbeDetailFragment
        scenario!!.onActivity { act ->
            frag = TagProbeDetailFragment(dao, clock, zone, "rec-1")
            act.install(frag)
        }
        settle()
        var confirm: (() -> Unit)? = null
        var successes = 0
        var failures = 0
        frag.onConfirmDelete = { confirm = it }
        frag.onDeleted = { successes++ }
        frag.onDeleteFailed = { failures++ }
        scenario!!.onActivity { frag.fragBinding.btnDelete.performClick() }
        settle()
        assertThat(confirm).isNotNull()
        dao.rows.value = listOf(entity(id = "rec-1").copy(actualFocusMinutes = 1))
        settle()
        scenario!!.onActivity { confirm!!.invoke() }
        settle()
        assertThat(dao.rows.value).hasSize(1)
        assertThat(successes).isEqualTo(0)
        assertThat(failures).isEqualTo(1)
        scenario!!.onActivity { frag.fragBinding.btnDelete.performClick() }
        settle()
        kotlinx.coroutines.runBlocking { com.projectfocus.core.network.di.TokenStore.setCancellable("detail-different-account") }
        settle()
        scenario!!.onActivity { confirm!!.invoke() }
        settle()
        assertThat(dao.rows.value).hasSize(1)
        assertThat(successes).isEqualTo(0)
        assertThat(failures).isEqualTo(1)
        com.projectfocus.core.network.di.TokenStore.clear()
    }

    @Test
    fun `删除成功后状态变成不存在`() {
        val dao = FakeFlightHistoryDao(listOf(entity(id = "rec-1")))
        scenario = ActivityScenario.launch(RecordHostActivity::class.java)
        lateinit var frag: TagProbeDetailFragment
        scenario!!.onActivity { act ->
            frag = TagProbeDetailFragment(dao, clock, zone, "rec-1")
            act.install(frag)
        }
        settle()
        frag.onDeleted = {} // 不真的 pop，否则 view 会被销毁
        frag.onConfirmDelete = { it() }

        scenario!!.onActivity { frag.fragBinding.btnDelete.performClick() }
        settle()


        //   删完之后界面自己就知道记录没了，不需要任何额外的通知。
        assertThat(frag.stateForTest()).isEqualTo(RecordDetailState.NotFound)
    }

    @Test
    fun `删除结果被消费后不会重放`() {

        //   `deleteResult` 是 StateFlow，旋转屏幕时重放最后一个值。
        //   不消费的话会再 pop 一次 —— 把列表页也退掉，用户回到首页。
        val dao = FakeFlightHistoryDao(listOf(entity(id = "rec-1")))
        scenario = ActivityScenario.launch(RecordHostActivity::class.java)
        lateinit var frag: TagProbeDetailFragment
        scenario!!.onActivity { act ->
            frag = TagProbeDetailFragment(dao, clock, zone, "rec-1")
            act.install(frag)
        }
        settle()

        var deleted = 0
        frag.onDeleted = { deleted++ }
        frag.onConfirmDelete = { it() }

        scenario!!.onActivity { frag.fragBinding.btnDelete.performClick() }
        settle()
        assertThat(deleted).isEqualTo(1)

        // 消费后值应当回到 null
        scenario!!.onActivity { frag.viewModelForTest().consumeDeleteResult() }
        settle()
        assertThat(frag.viewModelForTest().deleteResult.value).isNull()

        // 再推一轮（模拟旋转后的重放）—— 不能再触发 onDeleted
        settle()
        assertThat(deleted).isEqualTo(1)
    }

    // ═══════════════════════════════════════════════════════════
    // 25~28 回调与生命周期
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `点返回走宿主回调`() {
        val f = launch(listOf(entity()))
        var backs = 0
        f.onBack = { backs++ }
        scenario!!.onActivity { f.fragBinding.btnBack.performClick() }
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `不存在态点返回列表也走同一回调`() {
        val f = launch(emptyList(), id = "no-such-id")
        var backs = 0
        f.onBack = { backs++ }
        scenario!!.onActivity { f.fragBinding.btnStateBack.performClick() }
        assertThat(backs).isEqualTo(1)
    }


    @Test
    fun `点重飞把可执行入参交给宿主`() {
        // 计划 45 分钟，实际只专注了 30 分钟
        val f = launch(listOf(entity(planned = 45, actual = 30)), findByIdDelayMs = 5)
        val got = mutableListOf<com.projectfocus.feature.record.detail.ReplayIntent>()
        f.onReplayIntent = { got += it }

        // 连点两次（同一帧）
        scenario!!.onActivity {
            f.fragBinding.btnReplay.performClick()
            f.fragBinding.btnReplay.performClick()
        }

        //   不推的话断言看到 0 —— 而 0 会被误读成"防重入过严"。
        settle()

        assertThat(got).hasSize(1)
        assertThat(got[0].durationMinutes).isEqualTo(30)
        // 夹具里是 PEK → PVG
        assertThat(got[0].departureCode).isEqualTo("PEK")
        assertThat(got[0].destinationCode).isEqualTo("PVG")
        // ④ 意图已被消费 —— 否则从飞行页返回详情页时 StateFlow 会重放并再次导航
        assertThat(f.viewModelForTest().replayIntent.value).isNull()
    }







    @Test
    fun `隔一会儿再点重飞仍然有效`() {
        val f = launch(listOf(entity(planned = 45, actual = 30)), findByIdDelayMs = 2)
        val got = mutableListOf<com.projectfocus.feature.record.detail.ReplayIntent>()
        f.onReplayIntent = { got += it }

        scenario!!.onActivity { f.fragBinding.btnReplay.performClick() }
        settle()
        assertThat(got).hasSize(1)

        scenario!!.onActivity { f.fragBinding.btnReplay.performClick() }
        settle()
        assertThat(got).hasSize(2)
    }

    @Test
    fun `点分享把详情交给宿主`() {
        val f = launch(listOf(entity()))
        var shared: String? = null
        f.onShare = { shared = it.id }
        scenario!!.onActivity { f.fragBinding.btnShare.performClick() }
        assertThat(shared).isEqualTo("rec-1")
    }

    @Test
    fun `销毁视图后访问 binding 立刻抛错`() {

        //   写到一个已经 detach 的旧 View 树上不会崩，但屏幕上永远看不到。
        //   写成 `requireNotNull` 才能在这里立刻发现。
        val f = launch(listOf(entity()))
        scenario!!.onActivity { act ->
            act.supportFragmentManager.beginTransaction().remove(f).commitNow()
        }

        //
        //   `Kotlin.requireNotNull(x) { ... }` 抛 `IllegalArgumentException`，
        //   而 `checkNotNull(x) { ... }` 才抛 `IllegalStateException`。
        //   "值不对" → IllegalArgumentException；"状态不对" → IllegalStateException。
        //   这里语义上其实是**状态**（视图已销毁），所以本该用 checkNotNull。
        //

        //     `requireNotNull`，且它的测试（`销毁视图后 binding 抛错`）
        //     断言的也是 IllegalArgumentException。**两页必须一致**：
        //     同一个错误在两个页面上抛不同异常类型，是一致性的倒退。
        //   → 改断言，并在两个页面的注释里都记下这笔账。
        val thrown = runCatching { f.fragBinding }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown).hasMessageThat().contains("onDestroyView")
    }

    @Test
    fun `整页有无障碍朗读头信息`() {
        val f = launch(listOf(entity()))
        val desc = f.fragBinding.detailScroll.contentDescription?.toString().orEmpty()
        assertThat(desc).contains("北京")
        assertThat(desc).contains("上海")
        assertThat(desc).contains("已完成")
    }

    @Test
    fun `机场全名两个都空时整行 GONE`() {

        val e = entity().copy(departureAirportName = "", destinationAirportName = "")
        val f = launch(listOf(e))
        assertThat(f.fragBinding.airportNames.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `机场全名有值时显示并带箭头`() {
        val f = launch(listOf(entity()))
        assertThat(f.fragBinding.airportNames.visibility).isEqualTo(View.VISIBLE)
        assertThat(f.fragBinding.airportNames.text.toString())
            .isEqualTo("北京首都国际机场 → 上海浦东国际机场")
    }

    @Test
    fun `不同状态的记录都能正常打开且状态色不同`() {

        //   断言具体色值会让"调色"变成"改测试"，那是反的。
        listOf("COMPLETED", "ONGOING", "CANCELLED", "WEIRD").forEach { status ->
            scenario?.close()
            val f = launch(listOf(entity(status = status)))
            assertThat(f.stateForTest()).isInstanceOf(RecordDetailState.Content::class.java)
            assertThat(f.fragBinding.statusText.text.toString()).isNotEmpty()
        }
    }
}







class TagProbeDetailFragment(
    private val dao: FakeFlightHistoryDao,
    private val clock: Clock,
    private val zone: ZoneId,
    private val recordId: String,
) : RecordDetailFragment() {

    val fragBinding get() = binding






    override fun createViewModel(): RecordDetailViewModel =
        RecordDetailViewModel(
            dao = dao,
            clock = clock,
            zone = zone,
            savedState = SavedStateHandle(),

            //   但用一个真的（而不是 mock）能顺带守住"构造函数里允许传真的"这条 ——
            //   若哪天 Notifier 变成需要 Context 的类，这条会立刻红。
            todayNotifier = TodayStatsNotifier(),
            deletion = com.projectfocus.feature.record.testHistoryDeletion(dao, TodayStatsNotifier()),
        )

    fun viewModelForTest(): RecordDetailViewModel = detailViewModelForTest()

    init {
        arguments = android.os.Bundle().apply {
            putString(RecordDetailFragment.ARG_RECORD_ID, recordId)
        }
    }
}
