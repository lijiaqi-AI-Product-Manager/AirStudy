package com.projectfocus.feature.booking.seat

import android.content.Context

/**
 * 选座草稿的本地持久化。
 *
 * 只存“用户在这趟航班上点过哪个座位”，不存占用/已预订/已支付；
 * 服务端座位状态仍缺失，所以这不是 booking 成功。
 */
class SeatSelectionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isValid(seat: String): Boolean = canonicalSeat(seat) != null

    fun load(flightNo: String): String? {
        val raw = prefs.getString(key(flightNo), null) ?: return null
        val seat = canonicalSeat(raw)
        if (seat == null) {
            prefs.edit().remove(key(flightNo)).apply()
            return null
        }
        return seat
    }

    /**
     * @return true 表示已同步写入；false 表示座位值非法或写入失败。
     *
     * 用 `commit()` 而不是 `apply()`：调用方要在 Toast 里区分“已确认”和
     * “没保存”，异步 apply 给不了这个答案。草稿只有一条字符串，阻塞成本可忽略。
     */
    fun save(flightNo: String, seat: String): Boolean {
        // 座位合法性只信 SeatMapGeometry，避免 store / View 两套规则漂移。
        val canonical = canonicalSeat(seat) ?: return false
        return prefs.edit().putString(key(flightNo), canonical).commit()
    }

    fun clear(flightNo: String) {
        prefs.edit().remove(key(flightNo)).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun key(flightNo: String): String =
        if (flightNo.isBlank()) DEFAULT_KEY else "seat_$flightNo"

    private companion object {
        const val PREFS: String = "seat_selection"
        const val DEFAULT_KEY: String = "seat_default"
    }
}
