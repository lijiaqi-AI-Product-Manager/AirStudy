package com.projectfocus.widget.snapshot


sealed interface WidgetSnapshot {

    /** 没有进行中的飞行 —— 小组件应该显示「开始」 */
    data object Idle : WidgetSnapshot

    /** 正在飞 */
    data class Flying(
        val remainingMs: Long,
        val totalMs: Long,
        /** 目的地三字码，如 "NRT"。没有机场数据时为 null，显示航线名 */
        val destinationCode: String?,
        /** 场景名（工作 / 阅读…） */
        val scenarioLabel: String,
    ) : WidgetSnapshot {
        /** 0f~1f */
        val progress: Float
            get() = if (totalMs <= 0L) 1f
            else ((totalMs - remainingMs).toDouble() / totalMs).toFloat().coerceIn(0f, 1f)

        val elapsedMs: Long get() = (totalMs - remainingMs).coerceAtLeast(0L)
    }

    /** 暂停中 */
    data class Paused(
        val elapsedMs: Long,
        val totalMs: Long,
        val scenarioLabel: String,
    ) : WidgetSnapshot {
        val progress: Float
            get() = if (totalMs <= 0L) 0f
            else (elapsedMs.toDouble() / totalMs).toFloat().coerceIn(0f, 1f)

        val remainingMs: Long get() = (totalMs - elapsedMs).coerceAtLeast(0L)
    }

    /** 今日概览 */
    data class Today(
        val sessionCount: Int,
        val focusMinutes: Int,
        val distanceKm: Int,
        /** 连续专注天数 */
        val streakDays: Int,
    ) : WidgetSnapshot
}


object WidgetText {

    /**
     * 格式化倒计时。
     *
     * @param remainingMs 剩余毫秒（负数按 0 处理）
     * @return `"45:00"`（不足 1 小时）或 `"1:59:59"`（超过 1 小时）
     */
    fun countdown(remainingMs: Long): String {
        val totalSeconds = if (remainingMs <= 0L) 0L
        else (remainingMs + 999L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /** 已专注时长的可读文案，如 "已飞 32 分" */
    fun elapsedLabel(elapsedMs: Long): String {
        val minutes = (elapsedMs.coerceAtLeast(0L) / 60_000L).toInt()
        return if (minutes < 1) "刚刚起飞" else "已飞 $minutes 分"
    }

    /** 进度百分比，整数。用于无障碍描述 */
    fun progressPercent(progress: Float): String =
        "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"

    /** 今日概览的副标题 */
    fun todaySubtitle(minutes: Int, distanceKm: Int): String =
        "$minutes 分钟 · ${formatDistance(distanceKm)}"

    /** 里程：< 1000 显示 km，否则显示 "x.x 千公里" */
    fun formatDistance(km: Int): String = when {
        km < 0 -> "0 km"
        km < 1000 -> "$km km"
        else -> "%.1f 千公里".format(km / 1000.0)
    }

    /** 连续天数 */
    fun streakLabel(days: Int): String = when {
        days <= 0 -> "今天还没飞"
        days == 1 -> "连续 1 天"
        else -> "连续 $days 天"
    }








    fun routeLabel(originCode: String?, destinationCode: String?): String {
        val dest = destinationCode?.takeIf { it.isNotBlank() } ?: return "专注航线"
        val origin = originCode?.takeIf { it.isNotBlank() }
        return if (origin != null) "$origin → $dest" else "→ $dest"
    }
}
