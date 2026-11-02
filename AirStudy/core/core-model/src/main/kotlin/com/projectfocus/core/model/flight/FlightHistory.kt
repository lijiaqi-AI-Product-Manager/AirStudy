package com.projectfocus.core.model.flight


data class FlightHistory(
    // ── 主键与关联 ────────────────────────────────────────────
    /** UUID 字符串主键 */
    val id: String,
    /** 组队飞行的房间 ID；独飞为 null */
    val roomId: String? = null,
    /** 专注类型，见 [FocusType] */
    val focusType: FocusType = FocusType.SOLO,
    /** 房间参与人数快照；独飞为 null。
     *  冗余存快照的理由：房间解散后人数仍可追溯。 */
    val roomParticipantCount: Int? = null,

    // ── 出发地（6 字段扁平快照，不 JOIN airports）───────────
    val departureAirportCode: String,
    val departureAirportName: String,
    val departureCity: String,
    val departureCountry: String,
    val departureLatitude: Double,
    val departureLongitude: Double,

    // ── 目的地（6 字段扁平快照）─────────────────────────────
    val destinationAirportCode: String,
    val destinationAirportName: String,
    val destinationCity: String,
    val destinationCountry: String,
    val destinationLatitude: Double,
    val destinationLongitude: Double,

    // ── 时间（3 个 + 1 个实际）──────────────────────────────

    val startTimeMillis: Long,

    val endTimeMillis: Long?,
    /** **计划**时长（分钟），与冻结的路线里程独立保存。 */
    val durationMinutes: Int,
    /** **实际**专注分钟。发 XP / 算徽章用的是这个，不是 durationMinutes */
    val actualFocusMinutes: Int,

    // ── 地理结果 ──────────────────────────────────────────
    /**
     * 记录的航程公里数。
     *
     * 路线在开始时冻结并持久化，详情、登机牌、分享和汇总共用这个值。
     * R156原包证据区分Float路线里程和Int分钟，不能再按分钟重算并判定记录漂移。
     * 无路线计时器或旧记录的里程口径由写入方决定，读取方不改写历史快照。
     */
    val distanceKm: Double,

    // ── 状态机 ────────────────────────────────────────────
    val status: FlightStatus,

    // ── 分类与座位 ─────────────────────────────────────────


    val categoryId: Long? = null,
    /** 座位号，如 "12A"；v1 数据没有这一列，迁移后为 null */
    val seat: String? = null,

    // ── 语音播报幂等标记（3 布尔）───────────────────────────
    /** 「Cabin doors closed, Ready for takeoff」已播 */
    val hasAnnouncedDeparture: Boolean = false,
    /** 「Landing soon…」已播 */
    val hasAnnouncedDescent: Boolean = false,
    /** 「Arrived, Welcome to %1$s」已播 */
    val hasAnnouncedArrival: Boolean = false,

    // ── 日志与同步 ─────────────────────────────────────────


    val log: List<FlightLogEntry> = emptyList(),

    /** 已上云标记。同步只捞 `isSynced=false AND status=COMPLETED` */
    val isSynced: Boolean = false,

    // ── 审计 ─────────────────────────────────────────────
    val createdAt: Long,
    val updatedAt: Long,


    val quote: String? = null,
    val quoteAuthor: String? = null,
) {
    val isOngoing: Boolean get() = status == FlightStatus.ONGOING
    val isCompleted: Boolean get() = status == FlightStatus.COMPLETED

    /** 实际专注秒数；进行中返回 null */
    val actualFocusSeconds: Long?
        get() = endTimeMillis?.let { maxOf(0L, (it - startTimeMillis) / 1000L) }

}


data class FlightLogEntry(
    val id: String,
    val content: String,
    /** 事件发生时刻，Unix 毫秒 */
    val timestampMillis: Long,
    /** 十六进制色值，如 "#FFB74D"；null 表示用默认色 */
    val color: String? = null,
)


enum class FlightStatus {
    ONGOING,
    COMPLETED,
    CANCELLED,
    ;

    companion object {
        fun fromRaw(raw: String?): FlightStatus? = when (raw?.uppercase()) {
            "ONGOING", "IN_PROGRESS" -> ONGOING
            "COMPLETED" -> COMPLETED
            "CANCELLED", "ABANDONED" -> CANCELLED
            else -> null
        }
    }
}


enum class FocusType {
    SOLO,
    GROUP,
    ;

    companion object {
        fun fromRaw(raw: String?): FocusType = when (raw?.uppercase()) {
            "GROUP", "ROOM" -> GROUP
            else -> SOLO
        }
    }
}
