package com.projectfocus.feature.record.share

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.feature.completion.pass.BoardingPassData
import com.projectfocus.feature.record.detail.RecordDetail
import com.projectfocus.feature.record.list.RecordListBuilder
import com.projectfocus.feature.record.list.FlightStatus
import java.time.Instant
import java.time.ZoneId






































object ShareCardMapper {






    fun build(
        entity: FlightHistoryEntity,
        detail: RecordDetail,
        zone: ZoneId,
    ): BoardingPassData? {

        //   调用方（Fragment）在按钮上已经 disable 了，但无障碍服务 /
        //   连点 / 未来的快捷方式都能走到这里。给 null 让调用方**必须**处理。
        if (!detail.canShare || entity.status != "COMPLETED" ||
            !entity.distanceKm.isFinite() || entity.distanceKm < 0.0
        ) return null

        val startMs = entity.startTimeMillis

        //   零长度会让 `BoardingPassData.from` 得到相同的起降时间，
        //   登机牌上显示 "08:30 → 08:30" —— 比显示 "08:30 → 09:15（推算）" 差，
        //   但比抛异常好。这条路径只在数据严重损坏时走到。
        val endMs = entity.endTimeMillis
            ?: if (entity.durationMinutes > 0) {
                startMs + entity.durationMinutes * 60_000L
            } else {
                startMs
            }

        return BoardingPassData.from(
            departureCode = detail.fromCode,
            departureCity = detail.fromCity,
            arrivalCode = detail.toCode,
            arrivalCity = detail.toCity,
            startWallMs = startMs,
            endWallMs = endMs,
            zone = zone,

            plannedMinutes = entity.durationMinutes,
            actualFocusMinutes = entity.actualFocusMinutes,
            // 同一条记录的冻结路线值；不从分钟重算，也不读取可能过期的详情文本。
            distanceKm = entity.distanceKm,
            // entity 是唯一权威值；detail 可能是进入页面时的快照。
            // entity 明确没有座位时，不能回退到旧快照，否则会印出已清空的座位。
            seat = entity.seat,

            //   ⚠️ `FlightStatus.fromDb` 对未知值也兜成 CANCELLED，
            //      所以状态异常的记录会盖上 CANCELLED 章 —— 但这类记录
            //      的 `canShare` 为 false（`statusText` 含"异常"），
            //      根本走不到这里。这个结论由 `ShareCardMapperTest` 钉住。
            abandoned = detail.status == FlightStatus.CANCELLED,
        )
    }








    fun spec(
        data: BoardingPassData,
        detail: RecordDetail,
        entity: FlightHistoryEntity,
        zone: ZoneId,
        templates: ShareCardSpec.Templates,
    ): ShareCardSpec = ShareCardSpec(
        detail = ShareDetail(
            fromCity = detail.fromCity,
            toCity = detail.toCity,
            fromCode = detail.fromCode,
            toCode = detail.toCode,

            //   去掉单位后缀，避免出现 "600.0 km km"。
            distanceText = data.distanceText.removeSuffix(" km").trim(),

            //   而不是登机牌上的紧凑写法（"45m"）—— 因为这段文字是
            //   给聊天窗口里的读者看的，不是给登机牌版式看的。
            actualText = RecordListBuilder.formatMinutes(entity.actualFocusMinutes),
        ),
        flightNo = data.flightNo,
        startWallMs = Instant.ofEpochMilli(entity.startTimeMillis).toEpochMilli(),
        zone = zone,
        templates = templates,
    )
}
