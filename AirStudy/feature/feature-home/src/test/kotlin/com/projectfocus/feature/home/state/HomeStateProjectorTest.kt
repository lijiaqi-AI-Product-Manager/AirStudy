package com.projectfocus.feature.home.state

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.core.model.airport.AirportType
import com.projectfocus.core.model.airport.AirportWithFlightTime
import com.projectfocus.service.focus.session.SessionState
import org.junit.Test

/**
 * 首页 UI 状态投影的验收测试。
 *
 * ─────────────────────────────────────────────────────────────
 * 核心命题：**「会话在哪一步」≠「用户此刻能做什么」。**
 *
 * 每个用例都在问同一个问题：给定这个 (会话状态, 已选目的地, 已选分类) 组合，
 * 用户屏幕上应该看到什么、能点什么。答案错了不会崩，只会让用户
 * 「点了没反应」或者「正在飞的时候还能再点起飞」——
 * 后者是真正的事故：会同时开两个会话，两个 service 抢同一个通知 ID。
 * ─────────────────────────────────────────────────────────────
 */
class HomeStateProjectorTest {

    private fun airport(code: String) = Airport(
        id = 1, iataCode = code, icaoCode = "Z$code",
        name = "$code Airport", city = "City", country = "CN",
        latitude = 40.0, longitude = 116.0, elevation = 100,
        continent = "AS", type = AirportType.LARGE_AIRPORT,
        popularity = 100, isPremiumOnly = false,
    )

    private val nrt = AirportWithFlightTime(
        airport = airport("NRT"),
        distanceKm = 2100.0,
        flightTimeMinutes = 45,
    )

    private val CATEGORY = 7L
    private val NOW = 1_000_000L

    private fun running(
        plannedMs: Long = 45 * 60_000L,
        accumulated: Long = 0L,
        segmentStart: Long = NOW,
    ) = SessionState.Running(
        sessionId = "s1",
        categoryId = CATEGORY,
        plannedDurationMs = plannedMs,
        startedAtWallMs = 1_700_000_000_000L,
        accumulatedMs = accumulated,
        segmentStartMs = segmentStart,
    )

    // ── 1. Idle 分支：本类存在的第一个理由 ─────────────────

    @Test
    fun `没选目的地时是 Idle`() {
        val s = HomeStateProjector.project(SessionState.Idle, null, HomeStateProjector.NO_CATEGORY, NOW)
        assertThat(s).isEqualTo(HomeUiState.Idle)
    }

    @Test
    fun `选了目的地但没选分类时是 AirportSelected 而不是 Idle`() {

        //   如果直接拿 SessionState 当首页状态，这里会是 Idle ——
        //   用户刚选好大阪，屏幕上却显示「请选择目的地」，他会以为没选上。
        //   反复点、反复「没反应」，最后卸载。
        val s = HomeStateProjector.project(SessionState.Idle, nrt, HomeStateProjector.NO_CATEGORY, NOW)
        assertThat(s).isInstanceOf(HomeUiState.AirportSelected::class.java)
        assertThat((s as HomeUiState.AirportSelected).destination.airport.displayCode).isEqualTo("NRT")
    }

    @Test
    fun `选了目的地且选了分类时直接是 Ready`() {
        val s = HomeStateProjector.project(SessionState.Idle, nrt, CATEGORY, NOW)
        assertThat(s).isInstanceOf(HomeUiState.Ready::class.java)
        val r = s as HomeUiState.Ready
        assertThat(r.categoryId).isEqualTo(CATEGORY)
        // 时长取自机场的飞行时长（距离/800km/h），不是用户手输的
        assertThat(r.plannedMinutes).isEqualTo(45)
    }

    @Test
    fun `分类 id 是 NO_SESSION 时也当作没选`() {

        //   NO_CATEGORY(-2) 表示"从未选过"，NO_SESSION(-1) 是 DataStore 的
        //   "没有活跃会话"。任何一个漏判，用户都会看到一个能点但点了没用的
        //   「起飞」按钮 —— 因为 start() 会因分类不合法而返回 false。
        val s = HomeStateProjector.project(SessionState.Idle, nrt, HomeStateProjector.NO_SESSION, NOW)
        assertThat(s).isInstanceOf(HomeUiState.AirportSelected::class.java)
    }

    // ── 2. 会话状态压过 picked ─────────────────────────────

    @Test
    fun `正在飞的时候即使还有已选目的地也只显示 Flying`() {

        //   若投影逻辑先看 picked 再看 session，用户在飞的时候
        //   仍能看到「准备起飞」按钮 → 点 → SessionController.start()
        //   若守卫不严会覆盖掉正在飞的会话 → 两个 2001 通知互相抢。
        val s = HomeStateProjector.project(running(), nrt, CATEGORY, NOW)
        assertThat(s).isInstanceOf(HomeUiState.Flying::class.java)
        assertThat(s.showTakeoffButton).isFalse()
    }

    @Test
    fun `暂停中也覆盖已选目的地`() {
        val paused = SessionState.Paused(
            sessionId = "s1", categoryId = CATEGORY,
            plannedDurationMs = 45 * 60_000L,
            startedAtWallMs = 1_700_000_000_000L,
            accumulatedMs = 10 * 60_000L,
            pausedAtMonoMs = NOW,
        )
        val s = HomeStateProjector.project(paused, nrt, CATEGORY, NOW)
        assertThat(s).isInstanceOf(HomeUiState.Paused::class.java)
        assertThat(s.showTimeline).isFalse()
    }

    @Test
    fun `到站状态压过一切`() {
        val arrived = SessionState.Arrived(
            sessionId = "s1", categoryId = CATEGORY,
            plannedDurationMs = 45 * 60_000L,
            startedAtWallMs = 1_700_000_000_000L,
            totalElapsedMs = 45 * 60_000L,
            endedAtWallMs = 1_700_002_700_000L,
            abandoned = false,
        )
        val s = HomeStateProjector.project(arrived, nrt, CATEGORY, NOW)
        assertThat(s).isInstanceOf(HomeUiState.Arrived::class.java)
        assertThat((s as HomeUiState.Arrived).abandoned).isFalse()
    }

    // ── 3. 飞行中的数值口径 ────────────────────────────────

    @Test
    fun `飞行中只投影已接受时长而不从单调时钟差补齐`() {

        val snapshot = running(accumulated = 10 * 60_000L, segmentStart = NOW - 5 * 60_000L)
        for (readAt in listOf(NOW, NOW + 3_600_000L, NOW - 3_600_000L)) {
            val s = HomeStateProjector.project(snapshot, nrt, CATEGORY, readAt) as HomeUiState.Flying
            assertThat(s.elapsedMs).isEqualTo(10 * 60_000L)
            assertThat(s.remainingMs).isEqualTo(35 * 60_000L)
            assertThat(s.progress).isEqualTo(10f / 45f)
        }
        val acceptedLater = HomeStateProjector.project(
            snapshot.copy(accumulatedMs = 15 * 60_000L, segmentStartMs = NOW),
            nrt, CATEGORY, NOW,
        ) as HomeUiState.Flying
        assertThat(acceptedLater.elapsedMs).isEqualTo(15 * 60_000L)
        assertThat(acceptedLater.remainingMs).isEqualTo(30 * 60_000L)
        assertThat(acceptedLater.progress).isEqualTo(15f / 45f)
    }

    @Test
    fun `时钟回拨不会让已用时长变成负数`() {
        // session 的 segmentStart 在未来（比如测试构造错了，或恢复时库里的值离谱）
        val future = NOW + 60_000L
        val s = HomeStateProjector.project(
            running(accumulated = 0L, segmentStart = future), nrt, CATEGORY, NOW,
        ) as HomeUiState.Flying
        assertThat(s.elapsedMs).isEqualTo(0L)
    }

    @Test
    fun `已用时长不会超过计划时长`() {
        // 超界的是已接受量；段起点再早也不能充当已接受时长。
        val s = HomeStateProjector.project(
            running(accumulated = 10 * 60 * 60_000L, segmentStart = NOW),
            nrt, CATEGORY, NOW,
        ) as HomeUiState.Flying
        assertThat(s.elapsedMs).isEqualTo(45 * 60_000L)
        assertThat(s.remainingMs).isEqualTo(0L)
        assertThat(s.progress).isEqualTo(1f)
    }

    // ── 4. 可见性派生属性 ──────────────────────────────────

    @Test
    fun `只有 Idle 与 AirportSelected 能选机场`() {
        assertThat(HomeUiState.Idle.canSelectAirport).isTrue()
        assertThat(HomeUiState.AirportSelected(nrt).canSelectAirport).isTrue()
        val flying = HomeUiState.Flying("NRT", 45, 0L)
        assertThat(flying.canSelectAirport).isFalse()
    }

    @Test
    fun `时间轴只在选机场阶段显示`() {

        //   飞行中还显示会让用户以为可以改目的地，而实际改了不生效。
        assertThat(HomeUiState.Idle.showTimeline).isTrue()
        assertThat(HomeUiState.AirportSelected(nrt).showTimeline).isTrue()
        assertThat(HomeUiState.Flying("NRT", 45, 0L).showTimeline).isFalse()
        assertThat(HomeUiState.Paused("NRT", 45, 0L).showTimeline).isFalse()
    }

    @Test
    fun `取消确认是模态，期间锁定其它交互`() {
        assertThat(HomeUiState.Cancelling("NRT").isModal).isTrue()
        assertThat(HomeUiState.Flying("NRT", 45, 0L).isModal).isFalse()
    }

    @Test
    fun `Ready 状态显示起飞按钮但 AirportSelected 也显示`() {
        // 两者的区别是按钮文案（「准备起飞」vs「起飞」），
        // 但都需要显示按钮 —— 如果 AirportSelected 不显示，
        // 用户选完机场就找不到下一步了。
        val selected = HomeUiState.AirportSelected(nrt)
        val ready = HomeUiState.Ready(nrt, null, CATEGORY, 45)
        assertThat(selected.showTakeoffButton).isTrue()
        assertThat(ready.showTakeoffButton).isTrue()
        assertThat(HomeUiState.Idle.showTakeoffButton).isFalse()
    }

    // ── 5. 路由文本 ────────────────────────────────────────

    @Test
    fun `航线文本在缺出发地时用占位符而不是拼出箭头乱码`() {
        val s = HomeUiState.AirportSelected(nrt, departure = null)
        assertThat(s.routeLabel).isEqualTo("--- → NRT")
    }

    @Test
    fun `有出发地时航线文本是出发到达`() {
        val s = HomeUiState.AirportSelected(nrt, departure = airport("PEK"))
        assertThat(s.routeLabel).isEqualTo("PEK → NRT")
    }
}
