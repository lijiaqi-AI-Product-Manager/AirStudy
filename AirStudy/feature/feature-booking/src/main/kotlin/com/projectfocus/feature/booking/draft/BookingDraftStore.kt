package com.projectfocus.feature.booking.draft

import android.content.Context
import com.projectfocus.feature.booking.seat.SeatSelectionStore
import org.json.JSONException
import org.json.JSONObject


class BookingDraftStore(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val seatStore = SeatSelectionStore(context)

    fun save(draft: BookingDraft, draftKey: String = draft.flightNo): Boolean {
        if (draftKey.isBlank() || draft.seat.isBlank()) return false
        val json = JSONObject()
            .put(KEY_FLIGHT, draft.flightNo)
            .put(KEY_FROM, draft.fromCode)
            .put(KEY_TO, draft.toCode)
            .put(KEY_SEAT, draft.seat)
            .put(KEY_SAVED_AT, draft.savedAtMillis)
            .put(KEY_RECORD_ID, draft.recordId)
        return prefs.edit().putString(key(draftKey), json.toString()).commit()
    }

    fun load(draftKey: String): BookingDraft? {
        val raw = prefs.getString(key(draftKey), null) ?: return null
        return try {
            val json = JSONObject(raw)
            val storedFlight = json.optString(KEY_FLIGHT)
            val seat = json.optString(KEY_SEAT)
            val savedAt = json.optLong(KEY_SAVED_AT)
            val storedRecordId = json.optString(KEY_RECORD_ID)
            if (!seatStore.isValid(seat)) {
                discard(draftKey)
                return null
            }
            // 记录草稿的键必须和内容里的 recordId 一致；不一致视为损坏，不回到 UI。
            if (storedRecordId.isNotBlank() && storedRecordId != draftKey) {
                discard(draftKey)
                return null
            }
            if (savedAt <= 0L) {
                discard(draftKey)
                return null
            }
            // 过期草稿不再回到 UI；同时清掉，避免每次启动都读一遍坏值。
            if (now() - savedAt > MAX_AGE_MILLIS) {
                discard(draftKey)
                return null
            }
            BookingDraft(
                flightNo = storedFlight,
                fromCode = json.optString(KEY_FROM),
                toCode = json.optString(KEY_TO),
                seat = seat,
                savedAtMillis = savedAt,
                recordId = storedRecordId,
            )
        } catch (_: JSONException) {
            discard(draftKey)
            null
        }
    }

    /** 所有未过期草稿，按保存时间倒序；损坏/过期项会被 load 顺手清掉。 */
    fun all(): List<BookingDraft> =
        prefs.all.keys
            .asSequence()
            .filter { it.startsWith(PREFIX) }
            .mapNotNull { load(it.removePrefix(PREFIX)) }
            .sortedByDescending { it.savedAtMillis }
            .toList()

    fun clear(draftKey: String) {
        prefs.edit().remove(key(draftKey)).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** 只清理损坏/过期的键；调用方已经决定不再使用该草稿。 */
    private fun discard(draftKey: String) {
        prefs.edit().remove(key(draftKey)).apply()
    }

    private fun key(draftKey: String): String =
        PREFIX + draftKey.ifBlank { DEFAULT_KEY }

    private companion object {
        const val PREFS: String = "booking_drafts"
        const val PREFIX: String = "draft_"
        const val DEFAULT_KEY: String = "default"
        const val KEY_FLIGHT: String = "flightNo"
        const val KEY_FROM: String = "fromCode"
        const val KEY_TO: String = "toCode"
        const val KEY_SEAT: String = "seat"
        const val KEY_SAVED_AT: String = "savedAtMillis"
        const val KEY_RECORD_ID: String = "recordId"
        const val MAX_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
    }
}
