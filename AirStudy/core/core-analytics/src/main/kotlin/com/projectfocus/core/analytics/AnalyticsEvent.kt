package com.projectfocus.core.analytics


sealed interface AnalyticsEvent {

    /** 事件名。与数据看板的表名一一对应，改名等于断数据，必须同步改看板。 */
    val name: String









    fun params(): Map<String, Any?>

    // ==================================================================
    // 应用生命周期
    // ==================================================================

    /** App 启动 */
    data class AppOpen(
        /** 冷启动还是热启动 */
        val coldStart: Boolean,
        /** 从启动到首帧的毫秒数。用于监控启动性能回归 */
        val firstFrameMs: Long?,
    ) : AnalyticsEvent {
        override val name: String get() = "app_open"

        override fun params(): Map<String, Any?> = mapOf(
            "cold_start" to coldStart,
            "first_frame_ms" to firstFrameMs,
        )
    }

    // ==================================================================
    // 专注会话 —— 这是产品的核心漏斗
    // ==================================================================








    data class SessionStart(
        val plannedDurationMin: Int,
        /** 是否选择了自定义分类（不是 9 个内置场景之一） */
        val customCategory: Boolean,

        val blockedAppCount: Int,
    ) : AnalyticsEvent {
        override val name: String get() = "session_start"

        override fun params(): Map<String, Any?> = mapOf(
            "planned_duration_min" to plannedDurationMin,
            "custom_category" to customCategory,
            "blocked_app_count" to blockedAppCount,
        )
    }







    data class SessionPause(
        /** 已经飞了多久才暂停（分钟） */
        val elapsedMin: Int,

        val pauseIndex: Int,
    ) : AnalyticsEvent {
        override val name: String get() = "session_pause"

        override fun params(): Map<String, Any?> = mapOf(
            "elapsed_min" to elapsedMin,
            "pause_index" to pauseIndex,
        )
    }


    data class SessionEnd(
        val plannedDurationMin: Int,
        /** 实际专注时长（分钟，不含暂停） */
        val actualDurationMin: Int,
        val abandoned: Boolean,
        /** 总里程（公里，取整） */
        val distanceKm: Int,
        /** 暂停了几次 */
        val pauseCount: Int,
        /** 期间被拦截了几次 */
        val blockedCount: Int,
    ) : AnalyticsEvent {
        override val name: String get() = "session_end"

        override fun params(): Map<String, Any?> = mapOf(
            "planned_duration_min" to plannedDurationMin,
            "actual_duration_min" to actualDurationMin,
            "abandoned" to abandoned,
            "distance_km" to distanceKm,
            "pause_count" to pauseCount,
            "blocked_count" to blockedCount,
        )
    }










    data class BlockTriggered(
        /** 被拦时已飞多久（分钟） */
        val elapsedMin: Int,
        /** 是包名规则、分类规则还是域名规则命中 */
        val ruleType: String,

        val blockIndex: Int,
    ) : AnalyticsEvent {
        override val name: String get() = "block_triggered"

        override fun params(): Map<String, Any?> = mapOf(
            "elapsed_min" to elapsedMin,
            "rule_type" to ruleType,
            "block_index" to blockIndex,
        )
    }

    // ==================================================================
    // 权限 —— 这是「能不能用」的前置漏斗
    // ==================================================================


    data class PermissionStep(
        /** usage_stats / overlay / battery / notification */
        val permission: String,
        /** shown（进了引导页）/ granted（已授予）/ denied（点了去开启但没给） */
        val result: String,
        /** 第几次进来（用户可能反复进出） */
        val attempt: Int,
    ) : AnalyticsEvent {
        override val name: String get() = "permission_step"

        override fun params(): Map<String, Any?> = mapOf(
            "permission" to permission,
            "result" to result,
            "attempt" to attempt,
        )
    }

    // ==================================================================
    // 性能 —— 只用于自己看，不上报第三方
    // ==================================================================









    data class GlassLevelReported(
        /** FULL / BLUR / SOLID */
        val level: String,

        val deviceHash: String,
    ) : AnalyticsEvent {
        override val name: String get() = "glass_level"

        override fun params(): Map<String, Any?> = mapOf(
            "level" to level,
            "device_hash" to deviceHash,
        )
    }

    companion object {







        val ALL_NAMES: List<String> = listOf(
            "app_open",
            "session_start",
            "session_pause",
            "session_end",
            "block_triggered",
            "permission_step",
            "glass_level",
        )







        fun validateRegistry(): List<String> {
            val problems = mutableListOf<String>()
            if (ALL_NAMES.isEmpty()) problems += "ALL_NAMES 为空"
            val duplicates = ALL_NAMES.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) problems += "重复的事件名：$duplicates"
            ALL_NAMES.filter { it.isBlank() }.forEach { problems += "空事件名" }
            ALL_NAMES.filter { !it.matches(Regex("^[a-z][a-z0-9_]*$")) }
                .forEach { problems += "事件名不合规（应小写下划线）：$it" }
            return problems
        }
    }
}
