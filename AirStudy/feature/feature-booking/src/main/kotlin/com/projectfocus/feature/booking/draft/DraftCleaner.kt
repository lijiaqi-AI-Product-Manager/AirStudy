package com.projectfocus.feature.booking.draft

import android.content.Context
import com.projectfocus.feature.booking.seat.SeatSelectionStore


object DraftCleaner {

    fun clear(context: Context, draftKey: String) {
        BookingDraftStore(context).clear(draftKey)
        SeatSelectionStore(context).clear(draftKey)
    }

    /**
     * 清空全部本地草稿。
     *
     * 只清这两份草稿存储，不碰 `flight_history`、不碰设置、不碰机场库。
     * 目前没有 UI 入口；等设置页需要“清除草稿”时直接调用，避免再写两遍。
     */
    fun clearAll(context: Context) {
        BookingDraftStore(context).clearAll()
        SeatSelectionStore(context).clearAll()
    }
}
