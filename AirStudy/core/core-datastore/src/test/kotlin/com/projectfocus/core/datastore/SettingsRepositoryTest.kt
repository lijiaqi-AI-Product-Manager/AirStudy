package com.projectfocus.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File











// Android 版 DataStore 按 SDK 选择文件替换实现；纯 JVM 的 SDK 桩值会走到
// minSdk 26 之外的 renameTo 分支，无法在 Windows 覆盖已有文件。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var repo: SettingsRepository
    private lateinit var store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File(tempFolder.root, "test_settings.preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        repo = SettingsRepository(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ==================================================================
    // 基础读写
    // ==================================================================

    @Test
    fun `未写入时读到默认值`() = runBlocking {
        assertThat(repo.read(PrefKey.BlacklistEnabled)).isTrue()
        assertThat(repo.read(PrefKey.GlassBlurRadius)).isEqualTo(18f)
        assertThat(repo.read(PrefKey.ThemeMode)).isEqualTo("auto")
        assertThat(repo.read(PrefKey.SelectedCategoryId)).isEqualTo(-1L)
    }

    @Test
    fun `写入后能读回`() = runBlocking {
        repo.write(PrefKey.BlacklistEnabled, false)
        repo.write(PrefKey.GlassBlurRadius, 24f)
        repo.write(PrefKey.ThemeMode, "night")
        repo.write(PrefKey.SelectedCategoryId, 42L)

        assertThat(repo.read(PrefKey.BlacklistEnabled)).isFalse()
        assertThat(repo.read(PrefKey.GlassBlurRadius)).isEqualTo(24f)
        assertThat(repo.read(PrefKey.ThemeMode)).isEqualTo("night")
        assertThat(repo.read(PrefKey.SelectedCategoryId)).isEqualTo(42L)
    }






    @Test
    fun `不同值类型互不干扰`() = runBlocking {
        repo.write(PrefKey.SelectedCategoryId, 123456789012L) // Long，超过 Int 范围
        repo.write(PrefKey.RouteColor, 0xFF00FF00.toInt()) // Int，负数
        assertThat(repo.read(PrefKey.SelectedCategoryId)).isEqualTo(123456789012L)
        assertThat(repo.read(PrefKey.RouteColor)).isEqualTo(0xFF00FF00.toInt())
    }

    // ==================================================================
    // 旧键迁移
    // ==================================================================


    @Test
    fun `旧键名被识别并迁移`() = runBlocking {
        // 模拟「只有旧键」的存量数据
        store.edit { prefs ->
            prefs[androidx.datastore.preferences.core.booleanPreferencesKey("isBlacklistEnabled")] = false
            prefs[androidx.datastore.preferences.core.floatPreferencesKey("flight_settings_liquid_glass_slider_blur_frost")] = 30f
        }

        assertThat(repo.read(PrefKey.BlacklistEnabled)).isFalse()
        assertThat(repo.read(PrefKey.GlassBlurRadius)).isEqualTo(30f)
    }

    @Test
    fun `新旧键同时存在时优先新键`() = runBlocking {
        store.edit { prefs ->
            prefs[androidx.datastore.preferences.core.booleanPreferencesKey("isBlacklistEnabled")] = false
            prefs[androidx.datastore.preferences.core.booleanPreferencesKey("pf_blacklist_enabled")] = true
        }
        // 新键存在 → 用新键。旧键不能「反过来覆盖」用户最新的选择。
        assertThat(repo.read(PrefKey.BlacklistEnabled)).isTrue()
    }

    @Test
    fun `旧键被读到时不影响其它键的默认值`() = runBlocking {
        store.edit { prefs ->
            prefs[androidx.datastore.preferences.core.booleanPreferencesKey("flight_settings_liquid_glass")] = false
        }
        assertThat(repo.read(PrefKey.GlassEnabled)).isFalse()
        // 同一个 PrefKey 的其它键不能受污染
        assertThat(repo.read(PrefKey.GlassDispersion)).isEqualTo(0.35f)
    }

    // ==================================================================
    // Flow 行为
    // ==================================================================






    @Test
    fun `改其它键不触发本键的发射`() = runBlocking {
        val observed = mutableListOf<Boolean>()
        val job = launch {
            repo.observe(PrefKey.BlacklistEnabled).take(2).toList(observed)
        }
        // 等第一个值（默认 true）
        withTimeout(5_000) {
            while (observed.isEmpty()) kotlinx.coroutines.delay(10)
        }

        repo.write(PrefKey.GlassBlurRadius, 12f) // 不相关
        repo.write(PrefKey.BlacklistEnabled, false) // 相关

        withTimeout(5_000) {
            while (observed.size < 2) kotlinx.coroutines.delay(10)
        }
        job.cancel()
        assertThat(observed).containsExactly(true, false).inOrder()
    }

    @Test
    fun `写相同值不重复发射`() = runBlocking {
        val observed = mutableListOf<Float>()
        val job = launch { repo.observe(PrefKey.CruiseZoom).take(2).toList(observed) }
        withTimeout(5_000) {
            while (observed.isEmpty()) kotlinx.coroutines.delay(10)
        }
        repo.write(PrefKey.CruiseZoom, 12.0f) // 原 q0.g 的新装默认值；相同值不重复发射
        repo.write(PrefKey.CruiseZoom, 15f)
        withTimeout(5_000) {
            while (observed.size < 2) kotlinx.coroutines.delay(10)
        }
        job.cancel()
        assertThat(observed).containsExactly(12.0f, 15f).inOrder()
    }

    @Test
    fun `observe 首次发射即为当前值`() = runBlocking {
        repo.write(PrefKey.TtsGender, "male")
        val v = withTimeout(5_000) { repo.observe(PrefKey.TtsGender).first() }
        assertThat(v).isEqualTo("male")
    }

    // ==================================================================
    // 批量与重置
    // ==================================================================

    @Test
    fun `一次写多个键`() = runBlocking {
        repo.writeAll(
            listOf(
                PrefKey.BlacklistEnabled to false,
                PrefKey.GlassBlurRadius to 6f,
                PrefKey.ThemeMode to "night",
            ),
        )
        assertThat(repo.read(PrefKey.BlacklistEnabled)).isFalse()
        assertThat(repo.read(PrefKey.GlassBlurRadius)).isEqualTo(6f)
        assertThat(repo.read(PrefKey.ThemeMode)).isEqualTo("night")
    }

    @Test
    fun `恢复默认后所有键回到默认值`() = runBlocking {
        repo.writeAll(
            listOf(
                PrefKey.BlacklistEnabled to false,
                PrefKey.GlassBlurRadius to 6f,
            ),
        )
        repo.resetToDefaults()
        assertThat(repo.read(PrefKey.BlacklistEnabled)).isTrue()
        assertThat(repo.read(PrefKey.GlassBlurRadius)).isEqualTo(18f)
    }

    @Test
    fun `恢复默认不清除第三方写入的键`() = runBlocking {
        store.edit { it[androidx.datastore.preferences.core.stringPreferencesKey("sdk_install_id")] = "abc" }
        repo.write(PrefKey.BlacklistEnabled, false)
        repo.resetToDefaults()

        val leftover = store.data.first()[androidx.datastore.preferences.core.stringPreferencesKey("sdk_install_id")]
        assertThat(leftover).isEqualTo("abc")
    }

    // ==================================================================
    // readAll
    // ==================================================================

    @Test
    fun `readAll 顺序与入参一致`() = runBlocking {
        repo.write(PrefKey.TtsGender, "male")
        val values = repo.readAll(listOf(PrefKey.TtsGender, PrefKey.ThemeMode))
        assertThat(values).containsExactly("male", "auto").inOrder()
    }






















    @Test
    fun `键清单不是空的且每项都可用`() {

        //   如果 ALL 被误改成 emptyList()，所有遍历型用例都会"零次循环 → 通过"。
        //   → 留一条最低限度的哨兵。
        assertThat(PrefKey.ALL).isNotEmpty()

        //   `default` 为 null 是**合法**的（Set 类型暂不支持可空，但将来可能），
        //   所以这里只断言 id/name，不断言 default 非空。

        //     是 synthesized 嵌套类，simpleName 返回 null（见 PrefKey.id 的注释），
        //     拿它拼期望值等于用一个坏掉的东西去验另一个东西。
        PrefKey.ALL.forEach { key ->
            assertThat(key.name.startsWith("pf_")).isTrue()
            assertThat(key.id.startsWith("PrefKey.")).isTrue()
            assertThat(key.id.length).isGreaterThan("PrefKey.".length)
        }
    }







    @Test
    fun `每个定义出来的键都登记在 ALL 里`() {
        assertThat(PrefKey.missingFromAll()).isEmpty()
    }

    // ==================================================================
    // 全量往返：every key × 一个非默认值
    // ==================================================================
    //

    //
    //   `PrefKey.ALL` 现在有 40+ 个键。`SettingsRepository.put()` 是一串
    //   `when (key)` 分支，每加一个键都要在**三处**同步：
    //     ① SettingsKeys.kt 里定义 data object
    //     ② SettingsRepository.prefKeyOfName 里加类型分支
    //     ③ SettingsRepository.put 里加写入分支
    //   漏了 ③ 的表现：**写不进去，但也不报错** ——
    //   `put` 的 when 没有命中时什么都不做（或走进 else 的 error）。
    //
    //   一个键一个键手写测试，写的人一定会偷懒只测几个"有代表性的"。
    //   → 改成遍历 ALL：新增键自动被覆盖，漏了 ③ 立刻红。
    //

    //   · `RecentAirportIds`（Set<String>）在 put 里没分支 → 写入静默丢失
    //   · `ActiveSessionCategoryId` 的默认值是 -1L，被误判成"未设置"
    //     （这里记录的是第二类的**可能性**，实际验证在下面 nonDefault() 里显式绕开）








    private fun nonDefault(key: PrefKey<*>): Any {
        val v: Any = when (val d = key.default) {
            is Boolean -> !d
            is Int -> d + 7
            is Long -> d + 7_000L
            is Float -> d + 3.5f
            is String -> "${d}_x"
            is Set<*> -> setOf("0000#1", "0001#2")
            else -> error("未知默认值类型：${d?.javaClass}（键 ${key.name}）")
        }
        return v
    }

    @Test
    fun `每个键都能写入并读回`() = runBlocking {
        // 用一个「新构造的同形状列表」做对照，看是不是 vararg 传参本身丢元素
        val control = arrayListOf(
            PrefKey.BlacklistEnabled, PrefKey.SelectedCategoryId, PrefKey.ThemeMode, PrefKey.MaterialAlpha,
            PrefKey.CountUpFlight, PrefKey.CruiseZoom, PrefKey.RouteColor, PrefKey.ScreenOrientation,
            PrefKey.GlassEnabled, PrefKey.GlassBlurRadius, PrefKey.GlassRefractionHeight,
            PrefKey.GlassRefractionOffset, PrefKey.GlassDispersion, PrefKey.GlassLiquid,
            PrefKey.GlassTintAlphaDay, PrefKey.GlassTintAlphaNight, PrefKey.HapticMaster,
            PrefKey.HapticArrival, PrefKey.HapticTear, PrefKey.HapticTimeline, PrefKey.HapticPauseHold,
            PrefKey.HapticBoardingPass, PrefKey.WhiteNoiseEnabled, PrefKey.WhiteNoiseEnvVolume,
            PrefKey.WhiteNoisePlaneVolume, PrefKey.TtsEnabled, PrefKey.TtsGender, PrefKey.TtsLang1,
            PrefKey.TtsLang2, PrefKey.ActiveSessionCategoryId, PrefKey.SessionPaused, PrefKey.PushMaster,
            PrefKey.PushDailyReport, PrefKey.PushWeeklyReport, PrefKey.PushMonthlyReport,
            PrefKey.PushPlanReminder, PrefKey.DeveloperMode, PrefKey.ShowWsLatency, PrefKey.RecentAirportIds,
        )

        PrefKey.ALL.forEach { key ->

            //   测试 JVM 被 OOM kill，报错落在这一行上（见类上方那条长注释）。
            @Suppress("UNCHECKED_CAST")
            val k = key as PrefKey<Any?>
            val v = nonDefault(k)
            assertThat(v).isNotEqualTo(k.default)
        }

        // 写入
        PrefKey.ALL.forEach { key ->

            //   测试 JVM 被 OOM kill，报错落在这一行上（见类上方那条长注释）。
            @Suppress("UNCHECKED_CAST")
            val k = key as PrefKey<Any?>
            repo.write(k, nonDefault(k))
        }

        // 读回
        PrefKey.ALL.forEach { key ->

            //   测试 JVM 被 OOM kill，报错落在这一行上（见类上方那条长注释）。
            @Suppress("UNCHECKED_CAST")
            val k = key as PrefKey<Any?>
            val expected = nonDefault(k)
            val actual = repo.read(k)
            assertThat(actual).isEqualTo(expected)
        }
    }








    @Test
    fun `批量写每个键都能读回`() = runBlocking {
        val pairs = PrefKey.ALL.map { key ->
            @Suppress("UNCHECKED_CAST")
            (key as PrefKey<Any?>) to nonDefault(key)
        }
        repo.writeAll(pairs)
        PrefKey.ALL.forEach { key ->

            //   测试 JVM 被 OOM kill，报错落在这一行上（见类上方那条长注释）。
            @Suppress("UNCHECKED_CAST")
            val k = key as PrefKey<Any?>
            assertThat(repo.read(k)).isEqualTo(nonDefault(k))
        }
    }







    @Test
    fun `恢复默认后每个键都回到默认值`() = runBlocking {
        PrefKey.ALL.forEach { key ->

            //   测试 JVM 被 OOM kill，报错落在这一行上（见类上方那条长注释）。
            @Suppress("UNCHECKED_CAST")
            val k = key as PrefKey<Any?>
            repo.write(k, nonDefault(k))
        }
        repo.resetToDefaults()
        PrefKey.ALL.forEach { key ->
            assertThat(repo.read(key)).isEqualTo(key.default)
        }
    }








    @Test
    fun `每个键的存储名唯一`() {
        val names = PrefKey.ALL.map { it.name }
        assertThat(names).hasSize(PrefKey.ALL.size)
        assertThat(names.toSet()).hasSize(PrefKey.ALL.size)
    }




    @Test
    fun `每个键的 id 唯一且带前缀`() {
        val ids = PrefKey.ALL.map { it.id }
        assertThat(ids.toSet()).hasSize(PrefKey.ALL.size)
        ids.forEach { id -> assertThat(id.startsWith("PrefKey.")).isTrue() }
    }
}
