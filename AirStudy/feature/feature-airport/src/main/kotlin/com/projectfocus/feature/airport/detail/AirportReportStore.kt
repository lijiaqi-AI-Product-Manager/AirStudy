package com.projectfocus.feature.airport.detail

import android.content.Context
import org.json.JSONObject
import java.util.UUID


object AirportReportStore {

    private const val PREFS = "airport_reports"
    private const val PREFIX = "report_"

    data class Report(
        val id: String,
        val code: String,
        val name: String,
        val reason: String,
        val createdAt: Long,
    )

    fun save(context: Context, code: String, name: String, reason: String): Boolean = runCatching {
        val id = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("code", code)
            .put("name", name)
            .put("reason", reason)
            .put("createdAt", System.currentTimeMillis())
            .put("status", "pending")
            .toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFIX + id, payload)
            .apply()
    }.isSuccess

    /** code 为空时返回全部；否则只返回该机场。按时间倒序。 */
    fun list(context: Context, code: String? = null): List<Report> {
        val wanted = code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .all
            .asSequence()
            .filter { (key, _) -> key.startsWith(PREFIX) }
            .mapNotNull { (key, raw) -> parse(key.removePrefix(PREFIX), raw as? String) }
            .filter { wanted == null || it.code.equals(wanted, ignoreCase = true) }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    fun delete(context: Context, id: String): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PREFIX + id)
            .commit()
    }.getOrDefault(false)

    private fun parse(id: String, raw: String?): Report? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            Report(
                id = id,
                code = json.optString("code"),
                name = json.optString("name"),
                reason = json.optString("reason"),
                createdAt = json.optLong("createdAt"),
            )
        }.getOrNull()
    }
}
