package com.projectfocus.feature.scene.source

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject


class SharedPreferencesSceneCatalogStore(private val preferences: SharedPreferences) {
    @Synchronized
    fun readEnabled(scope: SourceSceneScope): SourceSceneSnapshot {
        val raw = preferences.getString(enabledKey(scope), null)
        val records = if (raw == null) {
            SourceSceneCatalog.initialEnabledRecords().also { writeEnabled(scope, it) }
        } else {
            decode(raw, appendEnabledDefaultsOnException = true)
        }
        return SourceSceneOptionIds.snapshot(scope, SourceSceneCollection.ENABLED, records)
    }

    @Synchronized
    fun writeEnabled(scope: SourceSceneScope, records: List<SourceSceneRecord>) {
        preferences.edit().putString(enabledKey(scope), encode(records)).remove("user_scenes").apply()
    }

    @Synchronized
    fun readAllCreatedCustom(scope: SourceSceneScope): SourceSceneSnapshot {
        val raw = preferences.getString(allCreatedCustomKey(scope), null)
        val records = if (raw == null) emptyList() else decode(raw, appendEnabledDefaultsOnException = false)
        return SourceSceneOptionIds.snapshot(scope, SourceSceneCollection.ALL_CREATED_CUSTOM, records)
    }

    @Synchronized
    fun writeAllCreatedCustom(scope: SourceSceneScope, records: List<SourceSceneRecord>) {
        preferences.edit().putString(allCreatedCustomKey(scope), encode(records)).remove("all_created_custom_scenes").apply()
    }

    /**
     * Source u.a: replace the first builtin key/nonempty-name or custom-name match.
     * Writes enabled first, then custom history. These are two independent apply()
     * calls: a second-step failure leaves enabled updated and can be retried.
     *
     * Product adapter: unlike source u.q/u.p's swallowed writer exceptions, errors
     * propagate to the caller. Returning does not establish disk durability. Name
     * validation, membership limits, cloud UUID creation/sync belong to the caller.
     */
    @Synchronized
    fun addOrUpdateEnabled(scope: SourceSceneScope, record: SourceSceneRecord) {
        val enabled = readEnabled(scope).records.toMutableList()
        val index = enabled.indexOfFirst { existing ->
            if (existing.isCustom && record.isCustom) {
                existing.name == record.name
            } else {
                !existing.isCustom && !record.isCustom &&
                    (existing.iconKey == record.iconKey ||
                        (existing.name.isNotEmpty() && existing.name == record.name))
            }
        }
        if (index < 0) enabled.add(record) else enabled[index] = record
        writeEnabled(scope, enabled)
        if (record.isCustom) {
            val history = readAllCreatedCustom(scope).records.toMutableList()
            val historyIndex = history.indexOfFirst { it.name == record.name }
            if (historyIndex < 0) history.add(record) else history[historyIndex] = record
            writeAllCreatedCustom(scope, history)
        }
    }

    /**
     * Product-safe alternative to the source delete callback's captured row index.
     * Resolve against a fresh enabled snapshot; changed/foreign IDs cannot remove
     * another row. No deletion write occurs for a missing ID. readEnabled retains
     * its existing source default-initialization side effect if the key is absent.
     * Custom history and independent flight/category snapshots are not modified.
     */
    @Synchronized
    fun removeEnabled(scope: SourceSceneScope, optionId: String): Boolean {
        val current = readEnabled(scope)
        val index = current.options.indexOfFirst { it.optionId == optionId }
        if (index < 0) return false
        val records = current.records.toMutableList()
        records.removeAt(index)
        writeEnabled(scope, records)
        return true
    }

    /**
     * Source x4.h.onCreate: omit enabled builtins; retain all custom history with
     * its already-enabled name flag. The display-only custom flag is not persisted.
     * Scope comes from the caller; this does not infer an original account/device.
     */
    @Synchronized
    fun availableToEnable(scope: SourceSceneScope): SourceSceneEnableChoices {
        val enabled = readEnabled(scope).records
        val builtins = SourceSceneCatalog.builtinSnapshot(scope).options.filter { option ->
            val candidate = option.record
            enabled.none { existing ->
                (!existing.isCustom && !candidate.isCustom && existing.iconKey == candidate.iconKey) ||
                    (existing.name.isNotEmpty() && existing.name == candidate.name)
            }
        }
        val historyRecords = readAllCreatedCustom(scope).records.map { it.copy(isCustom = true) }
        val history = SourceSceneOptionIds.snapshot(scope, SourceSceneCollection.ALL_CREATED_CUSTOM, historyRecords)
            .options.map { option ->
                SourceSceneEnableCandidate(option, enabled.any { it.name == option.record.name })
            }
        return SourceSceneEnableChoices(builtins, history)
    }

    private fun decode(raw: String, appendEnabledDefaultsOnException: Boolean): List<SourceSceneRecord> {
        val records = mutableListOf<SourceSceneRecord>()
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                // A non-object entry throws outside decodeObject; retain prefix then source fallback.
                decodeObject(array.getJSONObject(index))?.let(records::add)
            }
        } catch (_: Exception) {
            if (appendEnabledDefaultsOnException) records.addAll(SourceSceneCatalog.initialEnabledRecords())
        }
        return records
    }

    private fun decodeObject(value: JSONObject): SourceSceneRecord? = try {
        SourceSceneRecord(
            name = value.getString("name"),
            iconKey = value.getString("iconKey"),
            color = value.getInt("color"),
            isCustom = value.optBoolean("isCustom", false),
            cloudId = value.optLong("cloudId", -1L),
            clientUuid = if (value.isNull("clientUuid")) null else value.optString("clientUuid", ""),
        )
    } catch (_: Exception) {
        null
    }

    private fun encode(records: List<SourceSceneRecord>): String = JSONArray().apply {
        records.forEach { record ->
            put(JSONObject().apply {
                put("name", record.name)
                put("iconKey", record.iconKey)
                put("color", record.color)
                put("isCustom", record.isCustom)
                put("cloudId", record.cloudId)
                put("clientUuid", record.clientUuid ?: JSONObject.NULL)
            })
        }
    }.toString()

    companion object {
        const val FILE_NAME: String = "focus_scene_prefs"

        fun from(context: Context): SharedPreferencesSceneCatalogStore = SharedPreferencesSceneCatalogStore(
            context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
        )

        fun enabledKey(scope: SourceSceneScope): String = "user_scenes_${scope.suffix}"
        fun allCreatedCustomKey(scope: SourceSceneScope): String = "all_created_custom_scenes_${scope.suffix}"
    }
}
