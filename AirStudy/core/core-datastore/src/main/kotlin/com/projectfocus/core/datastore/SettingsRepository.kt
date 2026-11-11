package com.projectfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber









































class SettingsRepository(private val store: DataStore<Preferences>) {

    // ==================================================================
    // 读
    // ==================================================================

    /** 一次性读取。不存在时返回 [PrefKey.default]。 */
    suspend fun <T> read(key: PrefKey<T>): T = readFrom(store.data.first(), key)








    fun <T> observe(key: PrefKey<T>): Flow<T> =
        store.data.map { readFrom(it, key) }.distinctUntilChanged()

    /** 一次性读多个键（避免 N 次订阅） */
    suspend fun <T> readAll(keys: List<PrefKey<T>>): List<T> {
        val prefs = store.data.first()
        return keys.map { readFrom(prefs, it) }
    }































    fun observeAllKeys(keys: List<PrefKey<*>>): Flow<Map<String, Any?>> =
        store.data
            .map { prefs -> keys.associate { it.name to snapshotOf(prefs, it) } }
            .distinctUntilChanged()









    @Suppress("UNCHECKED_CAST")
    fun <T> snapshotOf(prefs: Preferences, key: PrefKey<T>): T = readFrom(prefs, key)

    // ==================================================================
    // 写
    // ==================================================================

    suspend fun <T> write(key: PrefKey<T>, value: T) {
        writeOnStorageContext {
            store.edit { prefs -> put(prefs, key, value) }
        }
    }








    suspend fun writeAll(pairs: List<Pair<PrefKey<*>, Any?>>) {
        if (pairs.isEmpty()) return
        writeOnStorageContext {
            store.edit { prefs ->
                pairs.forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    put(prefs, key as PrefKey<Any?>, value)
                }
            }
        }
    }


    suspend fun resetToDefaults() {
        writeOnStorageContext {
            store.edit { prefs ->

                //   object 单例在静态初始化未完成时读到 null。
                PrefKey.ALL.filterNotNull().forEach { prefs.remove(prefKeyOf(it)) }
            }
        }
    }

    /** 清空全部（含第三方键，慎用） */
    suspend fun clear() {
        writeOnStorageContext {
            store.edit { it.clear() }
        }
    }

    private suspend fun writeOnStorageContext(block: suspend () -> Unit) = coroutineScope {
        // Submit before yielding so rapid edits retain their invocation order.
        async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { block() }.await()
    }

    // ==================================================================
    // 内部：类型桥接
    // ==================================================================








    @Suppress("UNCHECKED_CAST")
    private fun <T> readFrom(prefs: Preferences, key: PrefKey<T>): T {
        val primary = prefKeyOf(key)
        val stored = prefs[primary]
        if (stored != null) return stored as T

        // ① 新键不存在 → 尝试从旧键迁移
        for (legacy in key.legacyNames) {
            val legacyKey = prefKeyOfName(key, legacy)
            val legacyValue = prefs[legacyKey]
            if (legacyValue != null) {
                Timber.i("偏好迁移：%s → %s", legacy, key.name)
                return legacyValue as T
            }
        }

        // ② 都没有 → 默认值
        return key.default
    }

    private fun <T> put(prefs: androidx.datastore.preferences.core.MutablePreferences, key: PrefKey<T>, value: T) {
        val k = prefKeyOf(key)
        if (value == null) {
            prefs.remove(k)
            return
        }
        when (key) {
            is PrefKey.BlacklistEnabled -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.SelectedCategoryId -> prefs[k as Preferences.Key<Long>] = value as Long
            is PrefKey.ThemeMode -> prefs[k as Preferences.Key<String>] = value as String
            is PrefKey.MaterialAlpha -> prefs[k as Preferences.Key<Float>] = value as Float
            is PrefKey.CountUpFlight -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.CruiseZoom -> prefs[k as Preferences.Key<Float>] = value as Float
            is PrefKey.RouteColor -> prefs[k as Preferences.Key<Int>] = value as Int
            is PrefKey.ScreenOrientation -> prefs[k as Preferences.Key<String>] = value as String
            is PrefKey.GlassEnabled -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.GlassBlurRadius -> prefs[k as Preferences.Key<Float>] = value as Float
            is PrefKey.GlassRefractionHeight -> prefs[k as Preferences.Key<Float>] = value as Float
            is PrefKey.GlassRefractionOffset -> prefs[k as Preferences.Key<Float>] = value as Float
            is PrefKey.GlassDispersion -> prefs[k as Preferences.Key<Float>] = value as Float
            is PrefKey.GlassLiquid -> prefs[k as Preferences.Key<Float>] = value as Float
            is PrefKey.GlassTintAlphaDay -> prefs[k as Preferences.Key<Float>] = value as Float
            is PrefKey.GlassTintAlphaNight -> prefs[k as Preferences.Key<Float>] = value as Float
            is PrefKey.HapticMaster -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.HapticArrival -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.HapticTear -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.HapticTimeline -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.HapticPauseHold -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.HapticBoardingPass -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.WhiteNoiseEnabled -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.WhiteNoiseEnvVolume -> prefs[k as Preferences.Key<Float>] = value as Float
            is PrefKey.WhiteNoisePlaneVolume -> prefs[k as Preferences.Key<Float>] = value as Float
            is PrefKey.TtsEnabled -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.TtsGender -> prefs[k as Preferences.Key<String>] = value as String
            is PrefKey.TtsLang1 -> prefs[k as Preferences.Key<String>] = value as String
            is PrefKey.TtsLang2 -> prefs[k as Preferences.Key<String>] = value as String
            is PrefKey.ActiveSessionCategoryId -> prefs[k as Preferences.Key<Long>] = value as Long
            is PrefKey.SessionPaused -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.PushMaster -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.PushDailyReport -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.PushWeeklyReport -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.PushMonthlyReport -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.PushPlanReminder -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.DeveloperMode -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.ShowWsLatency -> prefs[k as Preferences.Key<Boolean>] = value as Boolean
            is PrefKey.RecentAirportIds -> prefs[k as Preferences.Key<Set<String>>] = value as Set<String>
        }
    }

    private fun prefKeyOf(key: PrefKey<*>): Preferences.Key<*> = prefKeyOfName(key, key.name)



























    private fun prefKeyOfName(key: PrefKey<*>, name: String): Preferences.Key<*> = when (key.default) {

        //
        //   为什么不用「逗号分隔的 String」存一组 id：
        //   · 拼/拆各要一次全量遍历，且要保证 id 里没有逗号（现在是数字，将来呢）
        //   · 空集合会变成空串，读回来要特殊判断（"" → 空集合 vs 一个空 id）
        //   · 顺序信息会丢（见 `PrefKey.RecentAirportIds` 里关于顺序的说明）
        //

        //     而且它是**不可变副本**（改它不会写回）。两件事都要在消费侧处理。
        is Set<*> -> stringSetPreferencesKey(name)
        is Boolean -> booleanPreferencesKey(name)
        is Int -> intPreferencesKey(name)
        is Long -> longPreferencesKey(name)
        is Float -> floatPreferencesKey(name)
        is String -> stringPreferencesKey(name)
        else -> error("不支持的偏好类型：${key.default?.javaClass}（键 ${key.name}）")
    }
}
