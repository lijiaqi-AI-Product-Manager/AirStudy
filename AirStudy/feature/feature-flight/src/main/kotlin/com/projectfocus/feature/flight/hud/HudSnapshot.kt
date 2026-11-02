package com.projectfocus.feature.flight.hud

import com.projectfocus.core.common.flight.FlightMath




data class HudSnapshot(
    /** 已行里程，保留 1 位小数的文本（`123.4`） */
    val traveledKmText: String,
    /** 剩余里程，同上（`56.6`） */
    val remainingKmText: String,
    /** 已用时间 `01:23:45` */
    val elapsedText: String,
    /** 剩余时间 `00:21:15` */
    val remainingText: String,
    /** 进度 0f~1f，用于进度条/航线上的飞机图标 */
    val progress: Float,
    /** 剩余秒数，供"最后 10 秒"等交互判断 */
    val remainingSeconds: Long,
) {
    companion object {

        /**
         * @param elapsedMs 已接受的计时秒，以毫秒容器传入。
         * @param plannedMinutes 计划飞行分钟数。
         */
        // 无路线计时器的兼容入口；有航线的调用方必须传冻结的总里程。
        fun of(elapsedMs: Long, plannedMinutes: Int): HudSnapshot =
            of(elapsedMs, plannedMinutes, FlightMath.distanceKmForMinutes(plannedMinutes))

        /** 已冻结路线的HUD；时间推进只改变进度，不改写路线总里程。 */
        fun of(elapsedMs: Long, plannedMinutes: Int, totalDistanceKm: Double): HudSnapshot {
            require(totalDistanceKm.isFinite() && totalDistanceKm >= 0.0) {
                "totalDistanceKm must be finite and non-negative"
            }
            require(plannedMinutes >= 0) { "plannedMinutes must be non-negative" }
            val safeElapsed = elapsedMs.coerceAtLeast(0L)
            val elapsedSec = safeElapsed / 1000L
            val totalKm = totalDistanceKm
            val progress = FlightMath.progress(elapsedSec, plannedMinutes)

            // 保留原包Float剩余字段精度，再格式化；已行距离由总距减该字段得到。
            val traveledKm = FlightMath.traveledKm(totalKm, progress)
            val remainingKm = FlightMath.remainingKm(totalKm, progress)

            return HudSnapshot(
                traveledKmText = formatKm(traveledKm),
                remainingKmText = formatKm(remainingKm),
                elapsedText = FlightMath.formatDuration(elapsedSec),
                remainingText = FlightMath.formatDuration(
                    FlightMath.remainingSeconds(elapsedSec, plannedMinutes),
                ),
                progress = progress,
                remainingSeconds = FlightMath.remainingSeconds(elapsedSec, plannedMinutes),
            )
        }

        /** 本品文本保留一位小数、使用Locale.ROOT，并归一化接近零的数值。 */
        fun formatKm(km: Double): String {
            val v = if (km > -1e-9 && km < 1e-9) 0.0 else km
            return String.format(java.util.Locale.ROOT, "%.1f", v)
        }
    }
}









@JvmInline
value class HudChanged(private val bits: Int) {

    operator fun contains(field: Field): Boolean = (bits and field.mask) != 0

    val any: Boolean get() = bits != 0

    /** 需要重新 layout（而非仅 setText）的字段 —— 见类注释 ② */
    val needsLayout: Boolean get() = any

    enum class Field(val mask: Int) {
        TRAVELED(1 shl 0),
        REMAINING(1 shl 1),
        ELAPSED(1 shl 2),
        REMAINING_TIME(1 shl 3),
        PROGRESS(1 shl 4),
        ;
    }

    companion object {
        val NONE = HudChanged(0)









        fun between(old: HudSnapshot?, new: HudSnapshot): HudChanged {
            if (old == null) {
                // 首帧：全部字段都算变化，否则界面会是空的
                return HudChanged(Field.entries.fold(0) { acc, f -> acc or f.mask })
            }
            var bits = 0
            if (old.traveledKmText != new.traveledKmText) bits = bits or Field.TRAVELED.mask
            if (old.remainingKmText != new.remainingKmText) bits = bits or Field.REMAINING.mask
            if (old.elapsedText != new.elapsedText) bits = bits or Field.ELAPSED.mask
            if (old.remainingText != new.remainingText) bits = bits or Field.REMAINING_TIME.mask

            //   精确比较等于"永远变化"。0.001 对应进度条上不到 1 像素，
            //   用户看不出差别，但省下每秒一次重绘。
            if (kotlin.math.abs(old.progress - new.progress) > 0.001f) {
                bits = bits or Field.PROGRESS.mask
            }
            return HudChanged(bits)
        }
    }
}

/**
 * 舷窗循环视频的时段选择。
 *
 * 规格：《03_功能规格书》§07
 * ```
 * 06:00-11:00  morning_loop
 * 11:00-16:00  noon_loop
 * 16:00-19:00  sunset_loop
 * 19:00-06:00  night_loop
 * ```
 */
enum class WindowVideo(val asset: String) {
    MORNING("morning_loop"),
    NOON("noon_loop"),
    SUNSET("sunset_loop"),
    NIGHT("night_loop"),
    ;

    companion object {



        fun forHour(hour24: Int): WindowVideo {
            val h = ((hour24 % 24) + 24) % 24
            return when {
                h in 6..10 -> MORNING
                h in 11..15 -> NOON
                h in 16..18 -> SUNSET
                else -> NIGHT
            }
        }
    }
}
