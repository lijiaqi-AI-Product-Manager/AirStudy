package com.projectfocus.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey


@Entity(
    tableName = "flight_history",
    foreignKeys = [
        ForeignKey(
            entity = FocusCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],

            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [

        Index(value = ["categoryId"], name = "index_flight_history_categoryId"),
        Index(value = ["startTimeMillis"], name = "index_flight_history_startTimeMillis"),

        //   迁移上来的库会因"多出索引"过不了 Room 校验。名字与迁移逐字一致。
        Index(value = ["isSynced", "status"], name = "index_flight_history_isSynced_status"),
        Index(value = ["endTimeMillis"], name = "index_flight_history_endTimeMillis"),
        Index(value = ["status", "createdAt"], name = "index_flight_history_status_createdAt"),
    ],
)
data class FlightHistoryEntity(
    // ── 1~4 主键与关联 ──────────────────────────────────────
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "roomId")
    val roomId: String?,


    @ColumnInfo(name = "focusType")
    val focusType: String,

    @ColumnInfo(name = "roomParticipantCount")
    val roomParticipantCount: Int?,

    // ── 5~10 出发地 ────────────────────────────────────────
    @ColumnInfo(name = "departureAirportCode")
    val departureAirportCode: String,

    @ColumnInfo(name = "departureAirportName")
    val departureAirportName: String,

    @ColumnInfo(name = "departureCity")
    val departureCity: String,

    @ColumnInfo(name = "departureCountry")
    val departureCountry: String,

    @ColumnInfo(name = "departureLatitude")
    val departureLatitude: Double,

    @ColumnInfo(name = "departureLongitude")
    val departureLongitude: Double,

    // ── 11~16 目的地 ───────────────────────────────────────
    @ColumnInfo(name = "destinationAirportCode")
    val destinationAirportCode: String,

    @ColumnInfo(name = "destinationAirportName")
    val destinationAirportName: String,

    @ColumnInfo(name = "destinationCity")
    val destinationCity: String,

    @ColumnInfo(name = "destinationCountry")
    val destinationCountry: String,

    @ColumnInfo(name = "destinationLatitude")
    val destinationLatitude: Double,

    @ColumnInfo(name = "destinationLongitude")
    val destinationLongitude: Double,

    // ── 17~20 时间 ─────────────────────────────────────────
    /** 起飞时刻，Unix 毫秒 */
    @ColumnInfo(name = "startTimeMillis")
    val startTimeMillis: Long,

    /** 落地时刻，Unix 毫秒；进行中为 null */
    @ColumnInfo(name = "endTimeMillis")
    val endTimeMillis: Long?,

    /** **计划**时长（分钟） */
    @ColumnInfo(name = "durationMinutes")
    val durationMinutes: Int,

    /** **实际**专注分钟。发 XP / 算徽章用它 */
    @ColumnInfo(name = "actualFocusMinutes")
    val actualFocusMinutes: Int,

    // ── 21 地理结果 ────────────────────────────────────────
    /** = durationMinutes / 60 * 800，与机场真实距离无关 */
    @ColumnInfo(name = "distanceKm")
    val distanceKm: Double,

    // ── 22 状态 ───────────────────────────────────────────
    /** ONGOING / COMPLETED / CANCELLED */
    @ColumnInfo(name = "status")
    val status: String,

    // ── 23 分类外键 ────────────────────────────────────────
    @ColumnInfo(name = "categoryId")
    val categoryId: Long?,

    // ── 24 座位 ───────────────────────────────────────────
    @ColumnInfo(name = "seat")
    val seat: String?,

    // ── 25~27 语音播报幂等标记 ──────────────────────────────



    @ColumnInfo(name = "hasAnnouncedDeparture")
    val hasAnnouncedDeparture: Boolean,

    @ColumnInfo(name = "hasAnnouncedDescent")
    val hasAnnouncedDescent: Boolean,

    @ColumnInfo(name = "hasAnnouncedArrival")
    val hasAnnouncedArrival: Boolean,

    // ── 28 日志 ───────────────────────────────────────────
    /** 逃生舱：整段 List<LogEntry> 的 JSON 塞一个 TEXT 列。
     *  好处：后续加维度不需要迁移。代价：查询能力。 */
    @ColumnInfo(name = "logJson")
    val logJson: String?,

    // ── 29 同步 ───────────────────────────────────────────
    /** 同步只捞 isSynced=0 AND status='COMPLETED'，避免同步进行中的飞行 */
    @ColumnInfo(name = "isSynced")
    val isSynced: Boolean,

    // ── 30~31 审计 ─────────────────────────────────────────
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,




    @ColumnInfo(name = "quote")
    val quote: String? = null,

    @ColumnInfo(name = "quoteAuthor")
    val quoteAuthor: String? = null,


    /**
     * 最近一次同步成功的时刻（毫秒）。只有 isSynced 布尔不够 ——
     * 出问题时分不清"同步失败"还是"从没同步"。
     *
     * 迁移 20→21 早就加了这一列，但实体一直没有，迁移上来的库会因
     * "多出一列"过不了 Room 校验（审计 P0-1）。同步功能尚未接入，目前恒为 null。
     */
    @ColumnInfo(name = "syncedAt")
    val syncedAt: Long? = null,

    /** 本品独立场景快照；原样保存 JSON，不占用 categoryId。 */
    @ColumnInfo(name = "sceneSnapshotJson", defaultValue = "NULL")
    val sceneSnapshotJson: String? = null,

    /** 本品计划起飞日/时区/HH:mm 快照；与实际飞行时长独立。 */
    @ColumnInfo(name = "plannedDepartureJson", defaultValue = "NULL")
    val plannedDepartureJson: String? = null,
)
