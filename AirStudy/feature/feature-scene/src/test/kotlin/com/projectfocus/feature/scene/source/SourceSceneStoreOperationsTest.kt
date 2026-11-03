package com.projectfocus.feature.scene.source

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceSceneStoreOperationsTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var store: SharedPreferencesSceneCatalogStore
    private val scope = SourceSceneScope("operations")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = context.getSharedPreferences(SharedPreferencesSceneCatalogStore.FILE_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = SharedPreferencesSceneCatalogStore.from(context)
    }

    @Test
    fun `builtin key and nonempty stored name replace only the first matching builtin`() {
        val named = SourceSceneRecord("alias", "reading", 1, false, 3L, "named")
        val duplicate = named.copy(color = 2)
        val custom = named.copy(isCustom = true, color = 3)
        store.writeEnabled(scope, listOf(named, duplicate, custom))
        val byKey = SourceSceneRecord("new label", "reading", 4, false, 8L, null)
        store.addOrUpdateEnabled(scope, byKey)
        assertEquals(listOf(byKey, duplicate, custom), store.readEnabled(scope).records)
        val byName = SourceSceneRecord("new label", "meditation", 5, false, 12L, "replacement")
        store.addOrUpdateEnabled(scope, byName)
        assertEquals(listOf(byName, duplicate, custom), store.readEnabled(scope).records)
        assertTrue(store.readAllCreatedCustom(scope).records.isEmpty())
    }

    @Test
    fun `same icon different custom names stay distinct and same name replaces first in both stores`() {
        val first = SourceSceneRecord("甲", "work", 11, true, 31L, "a")
        val second = SourceSceneRecord("乙", "work", 12, true, 32L, "b")
        val duplicate = first.copy(color = 13, clientUuid = "duplicate")
        store.writeEnabled(scope, listOf(first, second, duplicate))
        store.writeAllCreatedCustom(scope, listOf(second, first.copy(isCustom = false), duplicate))
        val replacement = first.copy(iconKey = "reading", color = 14, cloudId = 99L, clientUuid = null)
        store.addOrUpdateEnabled(scope, replacement)
        assertEquals(listOf(replacement, second, duplicate), store.readEnabled(scope).records)
        assertEquals(listOf(second, replacement, duplicate), store.readAllCreatedCustom(scope).records)
        val encoded = JSONArray(requireNotNull(preferences.getString(SharedPreferencesSceneCatalogStore.enabledKey(scope), null)))
            .getJSONObject(0)
        assertEquals(setOf("name", "iconKey", "color", "isCustom", "cloudId", "clientUuid"), encoded.keys().asSequence().toSet())
        assertTrue(encoded.isNull("clientUuid"))
    }

    @Test
    fun `builtin and custom sharing both name and icon never overwrite each other`() {
        val builtin = SourceSceneRecord("same", "work", 11)
        val custom = builtin.copy(isCustom = true, color = 12, cloudId = 44L)
        store.writeEnabled(scope, listOf(builtin))
        store.addOrUpdateEnabled(scope, custom)
        assertEquals(listOf(builtin, custom), store.readEnabled(scope).records)
        assertEquals(listOf(custom), store.readAllCreatedCustom(scope).records)
    }

    @Test
    fun `remove enabled custom preserves byte exact history other scope and unrelated preference`() {
        val custom = SourceSceneRecord("keep history", "reading", 7, true, 8L, "uuid")
        store.addOrUpdateEnabled(scope, custom)
        val other = SourceSceneScope("other")
        store.writeEnabled(other, listOf(custom))
        val historyBefore = preferences.getString(SharedPreferencesSceneCatalogStore.allCreatedCustomKey(scope), null)
        val otherBefore = preferences.getString(SharedPreferencesSceneCatalogStore.enabledKey(other), null)
        preferences.edit().putString("user_scenes", "legacy enabled")
            .putString("all_created_custom_scenes", "legacy history")
            .putString("flight_snapshot", "frozen untouched").commit()
        val target = store.readEnabled(scope).options.single { it.record == custom }
        assertTrue(store.removeEnabled(scope, target.optionId))
        assertEquals(listOf("study", "work"), store.readEnabled(scope).records.map { it.iconKey })
        assertEquals(historyBefore, preferences.getString(SharedPreferencesSceneCatalogStore.allCreatedCustomKey(scope), null))
        assertEquals(otherBefore, preferences.getString(SharedPreferencesSceneCatalogStore.enabledKey(other), null))
        assertFalse(preferences.contains("user_scenes"))
        assertEquals("legacy history", preferences.getString("all_created_custom_scenes", null))
        assertEquals("frozen untouched", preferences.getString("flight_snapshot", null))
        assertTrue(store.availableToEnable(scope).customHistory.single().alreadyEnabled.not())
    }

    @Test
    fun `stale scene id after edit cannot delete the new record at the same position`() {
        val old = SourceSceneRecord("A", "work", 1, true)
        val next = SourceSceneRecord("B", "reading", 2, true)
        store.writeEnabled(scope, listOf(old, next))
        val stale = store.readEnabled(scope).options.first().optionId
        val edited = old.copy(color = 3, cloudId = 7L)
        store.addOrUpdateEnabled(scope, edited)
        val before = preferences.getString(SharedPreferencesSceneCatalogStore.enabledKey(scope), null)
        assertFalse(store.removeEnabled(scope, stale))
        assertEquals(before, preferences.getString(SharedPreferencesSceneCatalogStore.enabledKey(scope), null))
        assertEquals(listOf(edited, next), store.readEnabled(scope).records)
        val current = store.readEnabled(scope).options.first().optionId
        assertTrue(store.removeEnabled(scope, current))
        assertEquals(listOf(next), store.readEnabled(scope).records)
    }

    @Test
    fun `fresh ids survive reorder but another scope or collection cannot remove a record`() {
        val first = SourceSceneRecord("A", "work", 1, true)
        val second = SourceSceneRecord("B", "work", 2, true)
        store.writeEnabled(scope, listOf(first, second))
        store.writeAllCreatedCustom(scope, listOf(first))
        val id = store.readEnabled(scope).options.first().optionId
        store.writeEnabled(SourceSceneScope("other"), listOf(first))
        val foreign = store.readEnabled(SourceSceneScope("other")).options.single().optionId
        assertFalse(store.removeEnabled(scope, foreign))
        assertFalse(store.removeEnabled(scope, store.readAllCreatedCustom(scope).options.single().optionId))
        store.writeEnabled(scope, listOf(second, first))
        val reopened = SharedPreferencesSceneCatalogStore.from(context)
        assertTrue(reopened.removeEnabled(scope, id))
        assertEquals(listOf(second), reopened.readEnabled(scope).records)
    }

    @Test
    fun `available choices omit enabled builtin key and keep all same icon custom names with source flags`() {
        val first = SourceSceneRecord("A", "work", 1, true, 4L, "")
        val second = SourceSceneRecord("B", "work", 2, false, 5L, null)
        val emptyNameCustom = SourceSceneRecord("", "work", 3, true)
        store.writeEnabled(scope, listOf(SourceSceneCatalog.builtins.first().record(), first, emptyNameCustom))
        store.writeAllCreatedCustom(scope, listOf(first, second))
        val rawBefore = preferences.getString(SharedPreferencesSceneCatalogStore.allCreatedCustomKey(scope), null)
        val choices = store.availableToEnable(scope)
        assertEquals(SourceSceneCatalog.builtins.drop(1).map { it.iconKey }, choices.builtins.map { it.record.iconKey })
        assertEquals(listOf(first, second.copy(isCustom = true)), choices.customHistory.map { it.option.record })
        assertEquals(listOf(true, false), choices.customHistory.map { it.alreadyEnabled })
        assertEquals(2, choices.customHistory.map { it.option.optionId }.distinct().size)
        assertEquals(rawBefore, preferences.getString(SharedPreferencesSceneCatalogStore.allCreatedCustomKey(scope), null))
    }

    @Test
    fun `history already enabled follows source name even when enabled item is not custom`() {
        val history = SourceSceneRecord("same name", "work", 1, true)
        store.writeEnabled(scope, listOf(SourceSceneRecord("same name", "reading", 2)))
        store.writeAllCreatedCustom(scope, listOf(history))
        assertTrue(store.availableToEnable(scope).customHistory.single().alreadyEnabled)
    }

    @Test
    fun `both writers use scoped put remove exact legacy key and apply rather than commit`() {
        preferences.edit().putString("user_scenes", "old enabled")
            .putString("all_created_custom_scenes", "old custom")
            .putString("unrelated", "preserved").commit()
        val recorder = RecordingPreferences(preferences)
        val recordedStore = SharedPreferencesSceneCatalogStore(recorder)
        val custom = SourceSceneRecord("record", "work", 9, true)
        recordedStore.writeEnabled(scope, listOf(custom))
        recordedStore.writeAllCreatedCustom(scope, listOf(custom))
        assertEquals(listOf(
            listOf("put:user_scenes_operations", "remove:user_scenes", "apply"),
            listOf("put:all_created_custom_scenes_operations", "remove:all_created_custom_scenes", "apply"),
        ), recorder.events)
        assertFalse(preferences.contains("user_scenes"))
        assertFalse(preferences.contains("all_created_custom_scenes"))
        assertEquals("preserved", preferences.getString("unrelated", null))
        assertEquals(listOf(custom), SharedPreferencesSceneCatalogStore.from(context).readAllCreatedCustom(scope).records)
    }

    @Test
    fun `second step failure exposes already updated enabled and a retry repairs only custom history`() {
        val initial = SourceSceneRecord("initial", "reading", 1)
        val custom = SourceSceneRecord("retry", "work", 2, true, 33L, "same-uuid")
        store.writeEnabled(scope, listOf(initial))
        preferences.edit().putInt(SharedPreferencesSceneCatalogStore.allCreatedCustomKey(scope), 7).commit()
        try {
            store.addOrUpdateEnabled(scope, custom)
            fail("wrong typed all-created value must surface after enabled was applied")
        } catch (_: ClassCastException) {
            // Explicit product error propagation; original u.q/u.p swallow writer exceptions.
        }
        assertEquals(listOf(initial, custom), store.readEnabled(scope).records)
        assertEquals(7, preferences.getInt(SharedPreferencesSceneCatalogStore.allCreatedCustomKey(scope), 0))
        val enabledBeforeRetry = preferences.getString(SharedPreferencesSceneCatalogStore.enabledKey(scope), null)
        preferences.edit().remove(SharedPreferencesSceneCatalogStore.allCreatedCustomKey(scope)).commit()
        SharedPreferencesSceneCatalogStore.from(context).addOrUpdateEnabled(scope, custom)
        assertEquals(enabledBeforeRetry, preferences.getString(SharedPreferencesSceneCatalogStore.enabledKey(scope), null))
        assertEquals(listOf(custom), store.readAllCreatedCustom(scope).records)
    }

    @Test
    fun `store does not apply UI membership counts or unproven name validation`() {
        val records = (1..127).map { SourceSceneRecord("scene $it", "work", it, true) }
        store.writeEnabled(scope, records)
        val record = SourceSceneRecord("", "unknown-source-key", 9, true, 128L, "untouched")
        store.addOrUpdateEnabled(scope, record)
        assertEquals(records + record, store.readEnabled(scope).records)
        assertEquals(listOf(record), store.readAllCreatedCustom(scope).records)
    }

    /** Records editor calls while the real Robolectric SharedPreferences stores actual JSON. */
    private class RecordingPreferences(private val actual: SharedPreferences) : SharedPreferences by actual {
        val events = mutableListOf<List<String>>()

        override fun edit(): SharedPreferences.Editor {
            val actualEditor = actual.edit()
            val operations = mutableListOf<String>()
            return object : SharedPreferences.Editor by actualEditor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    operations += "put:$key"
                    actualEditor.putString(key, value)
                    return this
                }

                override fun remove(key: String?): SharedPreferences.Editor {
                    operations += "remove:$key"
                    actualEditor.remove(key)
                    return this
                }

                override fun apply() {
                    operations += "apply"
                    events += operations.toList()
                    actualEditor.apply()
                }

                override fun commit(): Boolean {
                    operations += "commit"
                    events += operations.toList()
                    return actualEditor.commit()
                }
            }
        }
    }
}
