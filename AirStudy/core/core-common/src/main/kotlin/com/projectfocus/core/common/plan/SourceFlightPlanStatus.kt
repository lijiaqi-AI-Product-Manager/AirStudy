package com.projectfocus.core.common.plan

import java.util.Locale


enum class SourceFlightPlanStatus {
    UNKNOWN, WAITING, IN_PROGRESS, COMPLETED, CANCELED;

    companion object {
        fun parse(value: String?): SourceFlightPlanStatus = when (
            value?.trim { it <= '\u0020' }?.lowercase(Locale.getDefault())
        ) {
            "waiting_takeoff", "等待起飞" -> WAITING
            "in_progress", "进行中", "飞行中" -> IN_PROGRESS
            "completed", "已完成" -> COMPLETED
            "canceled", "cancelled", "已取消" -> CANCELED
            else -> UNKNOWN
        }
    }
}
