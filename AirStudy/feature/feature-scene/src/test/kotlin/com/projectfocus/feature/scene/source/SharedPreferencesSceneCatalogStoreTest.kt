package com.projectfocus.feature.scene.source

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPreferencesSceneCatalogStoreTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var store: SharedPreferencesSceneCatalogStore
    private val scope = SourceSceneScope("store-test")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = context.getSharedPreferences(SharedPreferencesSceneCatalogStore.FILE_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = SharedPreferencesSceneCatalogStore.from(context)
    }

    @Test
    fun `missing enabled key initializes only study work and writes actual JSON`() {
        val result = store.readEnabled(scope)
        assertEquals(listOf("study", "work"), result.records.map { it.iconKey })
        val array = JSONArray(requireNotNull(preferences.getString(SharedPreferencesSceneCatalogStore.enabledKey(scope), null)))
        assertEquals(2, array.length())
        assertEquals("", array.getJSONObject(0).getString("name"))
        assertFalse(array.getJSONObject(0).getBoolean("isCustom"))
        assertFalse(array.getJSONObject(0).has("optionId"))
        assertEquals(result.options.map { it.optionId }, SharedPreferencesSceneCatalogStore.from(context).readEnabled(scope).options.map { it.optionId })
    }

    @Test
    fun `legal empty enabled list is retained and never replaced with builtins`() {
        store.writeEnabled(scope, emptyList())
        repeat(2) { assertTrue(store.readEnabled(scope).options.isEmpty()) }
        assertEquals("[]", preferences.getString(SharedPreferencesSceneCatalogStore.enabledKey(scope), null))
    }

    @Test
    fun `all created custom and enabled are independent collections`() {
        val custom = SourceSceneRecord("自定义", "work", 0xFF123456.toInt(), true, 41L, "custom-41")
        store.writeAllCreatedCustom(scope, listOf(custom))
        assertEquals(listOf("study", "work"), store.readEnabled(scope).records.map { it.iconKey })
        assertEquals(listOf(custom), store.readAllCreatedCustom(scope).records)
        store.writeEnabled(scope, emptyList())
        assertEquals(listOf(custom), store.readAllCreatedCustom(scope).records)
        assertNotEquals(
            SourceSceneOptionIds.snapshot(scope, SourceSceneCollection.ENABLED, listOf(custom)).options.single().optionId,
            store.readAllCreatedCustom(scope).options.single().optionId,
        )
    }

    @Test
    fun `same icon custom records and exact duplicates keep all fields and unique UI ids`() {
        val first = SourceSceneRecord("  自定义甲  ", "shared-icon", 0xFFABCDEF.toInt(), true, 13L, "")
        val second = SourceSceneRecord("自定义乙", "shared-icon", 0xFF123456.toInt(), true, -1L, null)
        store.writeEnabled(scope, listOf(first, second, first))
        val result = store.readEnabled(scope)
        assertEquals(listOf(first, second, first), result.records)
        assertEquals(3, result.options.map { it.optionId }.distinct().size)
        assertTrue(result.options.none { it.optionId == it.record.iconKey })
        assertEquals("", result.records[0].clientUuid)
        assertEquals(null, result.records[1].clientUuid)
    }

    @Test
    fun `unchanged distinct records keep UI ids across reorder and store recreation`() {
        val first = SourceSceneRecord("甲", "work", 1, true, 1L, "a")
        val second = SourceSceneRecord("乙", "work", 2, true, 2L, "b")
        store.writeEnabled(scope, listOf(first, second))
        val ids = store.readEnabled(scope).options.associate { it.record.name to it.optionId }
        store.writeEnabled(scope, listOf(second, first))
        val fresh = SharedPreferencesSceneCatalogStore.from(context).readEnabled(scope)
        assertEquals(ids, fresh.options.associate { it.record.name to it.optionId })
    }

    @Test
    fun `non object array member retains prefix then appends enabled defaults only`() {
        val prefix = JSONObject().put("name", "prefix").put("iconKey", "study").put("color", 123)
        val raw = JSONArray().put(prefix).put(9).toString()
        preferences.edit()
            .putString(SharedPreferencesSceneCatalogStore.enabledKey(scope), raw)
            .putString(SharedPreferencesSceneCatalogStore.allCreatedCustomKey(scope), raw)
            .commit()
        val enabled = store.readEnabled(scope)
        assertEquals(listOf("study", "study", "work"), enabled.records.map { it.iconKey })
        assertEquals(3, enabled.options.map { it.optionId }.distinct().size)
        assertEquals(listOf("prefix"), store.readAllCreatedCustom(scope).records.map { it.name })
        assertEquals(raw, preferences.getString(SharedPreferencesSceneCatalogStore.enabledKey(scope), null))
    }

    @Test
    fun `bad object is skipped without discarding next valid object or forcing custom flag`() {
        val raw = JSONArray().put(JSONObject().put("name", "missing required fields"))
            .put(JSONObject().put("name", "raw alias").put("iconKey", "work").put("color", 42)).toString()
        preferences.edit().putString(SharedPreferencesSceneCatalogStore.allCreatedCustomKey(scope), raw).commit()
        val record = store.readAllCreatedCustom(scope).records.single()
        assertEquals(SourceSceneRecord("raw alias", "work", 42), record)
        assertFalse(record.isCustom)
    }

    @Test
    fun `caller supplied scope including empty suffix is used exactly and isolated`() {
        val other = SourceSceneScope("")
        val custom = SourceSceneRecord("scope record", "work", 2, true)
        store.writeEnabled(other, listOf(custom))
        assertEquals("user_scenes_", SharedPreferencesSceneCatalogStore.enabledKey(other))
        assertEquals(listOf(custom), store.readEnabled(other).records)
        assertEquals(listOf("study", "work"), store.readEnabled(scope).records.map { it.iconKey })
    }

    @Test(expected = ClassCastException::class)
    fun `wrong preference value type remains a real store failure for Host to report`() {
        preferences.edit().putInt(SharedPreferencesSceneCatalogStore.enabledKey(scope), 7).commit()
        store.readEnabled(scope)
    }
}
