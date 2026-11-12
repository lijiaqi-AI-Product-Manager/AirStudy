package com.projectfocus.feature.home.state

import com.projectfocus.core.common.flight.FlightMath
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.core.model.airport.AirportWithFlightTime
import com.projectfocus.service.focus.session.SessionState












































sealed interface HomeUiState {

    /** 还没选目的地。屏幕上显示欢迎语 + 热度推荐时间轴 */
    data object Idle : HomeUiState

    /** 已选目的地，等用户点「准备起飞」。显示航线摘要 + 时长 */
    data class AirportSelected(
        val destination: AirportWithFlightTime,

        val departure: Airport? = null,
    ) : HomeUiState {
        val routeLabel: String
            get() = "${departure?.displayCode ?: "---"} → ${destination.airport.displayCode}"
    }

    /** 起飞前检查完毕，可以点「起飞」了 */
    data class Ready(
        val destination: AirportWithFlightTime,
        val departure: Airport? = null,
        val categoryId: Long,
        val plannedMinutes: Int,
    ) : HomeUiState

    /** 正在飞。面板收起，只剩舷窗 + 状态图标 + 底部进度胶囊 */
    data class Flying(
        val destinationCode: String,
        val totalMinutes: Int,
        /** 已用毫秒。**单调时钟**口径（见 `SessionController` 类注释 ①） */
        val elapsedMs: Long,
    ) : HomeUiState {
        val progress: Float get() = FlightMath.progress(elapsedMs / 1000, totalMinutes)
        val remainingMs: Long get() = (totalMinutes * 60_000L - elapsedMs).coerceAtLeast(0L)
    }

    /** 暂停中。与 [Flying] 的区别是进度条静止 + 显示「已暂停」 */
    data class Paused(
        val destinationCode: String,
        val totalMinutes: Int,
        val elapsedMs: Long,
    ) : HomeUiState

    /** 取消确认中。**遮罩层**，期间不响应其它交互 */
    data class Cancelling(
        val destinationCode: String,

        val holdProgress: Float = 0f,
    ) : HomeUiState

    /** 到站。跳转到完成页前的中间态（避免闪一下 Idle） */
    data class Arrived(
        val destinationCode: String,
        val abandoned: Boolean,
    ) : HomeUiState

    // ─────────────────────────────────────────────────────
    // 派生属性 —— 供 UI 决定「哪些控件可见」
    // ─────────────────────────────────────────────────────

    /** 是否要显示「选择目的地」入口 */
    val canSelectAirport: Boolean
        get() = this is Idle || this is AirportSelected

    /** 是否要显示「准备起飞 / 起飞」主按钮 */
    val showTakeoffButton: Boolean
        get() = this is AirportSelected || this is Ready

    /** 时间轴面板是否可见（选机场阶段才显示，飞行中必须收起） */
    val showTimeline: Boolean
        get() = this is Idle || this is AirportSelected

    /** 是否锁定其它交互（取消确认中、到站跳转中都必须锁） */
    val isModal: Boolean
        get() = this is Cancelling
}










object HomeStateProjector {



















    fun project(
        session: SessionState,
        picked: AirportWithFlightTime?,
        categoryId: Long,
        nowMonoMs: Long,
    ): HomeUiState = when (session) {
        is SessionState.Running -> HomeUiState.Flying(
            destinationCode = picked?.airport?.displayCode ?: "---",
            totalMinutes = (session.plannedDurationMs / 60_000L).toInt(),

            // elapsedMs保留兼容参数并限幅，推进只发生在会话tick。
            elapsedMs = session.elapsedMs(nowMonoMs),
        )

        is SessionState.Paused -> HomeUiState.Paused(
            destinationCode = picked?.airport?.displayCode ?: "---",
            totalMinutes = (session.plannedDurationMs / 60_000L).toInt(),
            elapsedMs = session.accumulatedMs,
        )

        is SessionState.Arrived -> HomeUiState.Arrived(
            destinationCode = picked?.airport?.displayCode ?: "---",
            abandoned = session.abandoned,
        )

        SessionState.Idle -> {

            //   判断标准只看数据齐不齐，不看用户点过什么 —— 因为
            //   "点过什么"在进程重启后会丢，"数据齐不齐"不会。
            when {
                picked == null -> HomeUiState.Idle
                categoryId == NO_CATEGORY || categoryId == NO_SESSION ->
                    HomeUiState.AirportSelected(picked)
                else -> HomeUiState.Ready(
                    destination = picked,
                    departure = null,
                    categoryId = categoryId,
                    plannedMinutes = picked.flightTimeMinutes,
                )
            }
        }
    }

    /** 与 `SessionRepository.NO_CATEGORY` 同值。见那里的注释：不用 0，因为 AUTOINCREMENT 可能真的产生 0 */
    const val NO_CATEGORY: Long = -2L

    /** 与 `SessionRepository.NO_SESSION` 同值 */
    const val NO_SESSION: Long = -1L
}
