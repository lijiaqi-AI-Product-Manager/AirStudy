package com.projectfocus.feature.profile.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.feature.profile.spec.PrefSpecs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File































@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()




















    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val vmDispatcher: CoroutineDispatcher get() = mainDispatcherRule.dispatcher

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var repo: SettingsRepository
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(tempFolder.root, "vm_settings.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { file })
        repo = SettingsRepository(store)
        vm = SettingsViewModel(repo)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    // ==================================================================
    // 读
    // ==================================================================

    @Test
    fun `初始值就是各键的默认值（订阅前）`() {

        //   设置页在 onCreateView 才第一次读，那时订阅已开始 —— 这个语义是安全的。
        assertThat(vm.valueOf(PrefKey.BlacklistEnabled)).isTrue()
        assertThat(vm.valueOf(PrefKey.GlassBlurRadius)).isEqualTo(18f)
        assertThat(vm.valueOf(PrefKey.ThemeMode)).isEqualTo("auto")
        assertThat(vm.valueOf(PrefKey.RouteColor)).isEqualTo(0xFF0A59F7.toInt())
    }

    @Test
    fun `订阅后读到落盘值`() = runTest {
        repo.write(PrefKey.BlacklistEnabled, false)
        repo.write(PrefKey.CruiseZoom, 9.5f)
        waitForValuesLoaded()

        assertThat(vm.valueOf(PrefKey.BlacklistEnabled)).isFalse()
        assertThat(vm.valueOf(PrefKey.CruiseZoom)).isEqualTo(9.5f)
    }

    @Test
    fun `每条规格表里的键都能读出且类型正确`() = runTest {
        waitForValuesLoaded()

        //   这里就会拿到 null → valueOf 回退 default → 断言仍然通过？
        //   不。下面显式断言"读到的值与 default 同类型"，才能抓住类型错配。
        PrefSpecs.allKeys().forEach { key ->
            val raw = vm.values.value[key.name]
            assertThat(raw).isNotNull()
            assertThat(raw!!::class).isEqualTo(key.default!!::class)
        }
    }

    @Test
    fun `isOn 与 valueOf 对布尔键一致`() = runTest {
        waitForValuesLoaded()
        PrefSpecs.allKeys().filter { it.default is Boolean }.forEach { key ->
            @Suppress("UNCHECKED_CAST")
            val k = key as PrefKey<Boolean>
            assertThat(vm.isOn(k)).isEqualTo(vm.valueOf(k))
        }
    }

    // ==================================================================
    // 写：乐观更新
    // ==================================================================

    @Test
    fun `写入立刻反映到 values 而不是等落盘`() = runTest {
        waitForValuesLoaded()

        //   所以 set 一返回，`pending` 里就已经有新值 —— 完全不需要等落盘。
        //   若哪天有人把 pending 的赋值挪进 launch 里，这条立刻红。
        vm.set(PrefKey.BlacklistEnabled, false)


        //
        //   `pending` 确实是同步写的，但 `values` 不是 `pending` 本身 ——
        //   它是 `combine(stored, pending).stateIn(...)`。
        //   `combine` 的合并**必须经过一次协程调度**才把新值推给 StateFlow。
        //
        //   · 生产环境：`Dispatchers.Main.immediate` → 同一帧内完成，用户看不到延迟，
        //     "乐观更新"的体感成立。
        //   · `runTest` + `StandardTestDispatcher`：任务是**排队**的，
        //     不推进调度器就永远是旧值。
        //
        //   ⇒ 这条测试要钉住的是「**同步的 pending** + 一次调度即可见」，
        //     而不是「零调度可见」。后者在生产上也不成立，硬要求它等于要求

        //

        //     真正要防的回归是"把 pending 赋值挪进 launch" —— 那会让它变成
        //     "一次调度 + 一次真实 IO 调度"，本断言依然会红（因为 IO 那步在另一个线程）。
        runCurrent()

        assertThat(vm.values.value[PrefKey.BlacklistEnabled.name]).isEqualTo(false)
        assertThat(vm.valueOf(PrefKey.BlacklistEnabled)).isFalse()


        //   此刻 DataStore 的写盘**可能还没完成**（真实 IO），但界面必须已经变了。
        //   这就是乐观更新的定义。
        runCurrent()
        assertThat(vm.values.value[PrefKey.BlacklistEnabled.name])
            .isEqualTo(false)
    }

    @Test
    fun `写入最终落盘`() = runTest {
        waitForValuesLoaded()
        vm.set(PrefKey.ThemeMode, "night")
        awaitSaved(PrefKey.ThemeMode, "night")


        assertThat(repo.read(PrefKey.ThemeMode)).isEqualTo("night")
    }

    @Test
    fun `连续写同一个键 最后一次生效`() = runTest {
        waitForValuesLoaded()
        vm.set(PrefKey.CruiseZoom, 5f)
        vm.set(PrefKey.CruiseZoom, 8f)
        vm.set(PrefKey.CruiseZoom, 12f)
        awaitSaved(PrefKey.CruiseZoom, 12f)

        assertThat(repo.read(PrefKey.CruiseZoom)).isEqualTo(12f)
        assertThat(vm.valueOf(PrefKey.CruiseZoom)).isEqualTo(12f)
    }

    @Test
    fun `写不同键互不覆盖`() = runTest {
        waitForValuesLoaded()
        vm.set(PrefKey.BlacklistEnabled, false)
        vm.set(PrefKey.TtsEnabled, false)
        vm.set(PrefKey.GlassEnabled, false)
        awaitSaved(PrefKey.GlassEnabled, false)

        assertThat(vm.valueOf(PrefKey.BlacklistEnabled)).isFalse()
        assertThat(vm.valueOf(PrefKey.TtsEnabled)).isFalse()
        assertThat(vm.valueOf(PrefKey.GlassEnabled)).isFalse()
    }

    @Test
    fun `pending 在写完之后被清空`() = runTest {
        waitForValuesLoaded()
        vm.set(PrefKey.HapticMaster, false)
        awaitSaved(PrefKey.HapticMaster, false)


        //   若 pending 还留着 false，repo 改成 true 后 VM 仍会读 false。
        repo.write(PrefKey.HapticMaster, true)
        waitForValuesLoaded()
        assertThat(vm.valueOf(PrefKey.HapticMaster)).isTrue()
    }

    @Test
    fun `每一项都能写进去再读回来`() = runTest {
        waitForValuesLoaded()

        //   `filterIsInstance<PrefKey<Boolean>>()` 只按**原始类型** `PrefKey` 过滤
        //   （泛型在 JVM 上被擦除），所以 `PrefKey<Float>` 也会通过，
        //   `key.default` 的类型是 `Any?`（声明类型是 PrefKey<*> 的 default）。
        //   `!key.default` 于是调到了 `String.not()` 之外的扩展 —— 编译期报
        //   `ClassCastException: String cannot be cast to Boolean`，**运行时**才炸。
        //
        //   → 用 `default is Boolean` 做真正的类型过滤，然后才强转。
        //     这也说明：**`filterIsInstance` 对泛型参数无效**，它不是运行时类型检查。
        val booleans = PrefSpecs.allKeys()
            .filter { it.default is Boolean }
            .map { @Suppress("UNCHECKED_CAST") (it as PrefKey<Boolean>) }
        assertThat(booleans).isNotEmpty()

        booleans.forEach { key ->
            vm.set(key, !key.default)
        }
        // Independent writes may reach DataStore in a different order.
        // Require every persisted value and its actual ViewModel value to settle.
        awaitReal {
            booleans.all { repo.read(it) == !it.default } &&
                booleans.all { vm.valueOf(it) == !it.default }
        }

        booleans.forEach { key ->
            assertWithMessage("persisted %s", key.id).that(repo.read(key)).isEqualTo(!key.default)
            assertWithMessage("rendered %s", key.id).that(vm.valueOf(key)).isEqualTo(!key.default)
        }
    }

    // ==================================================================
    // 恢复默认
    // ==================================================================

    @Test
    fun `恢复默认把改过的值还原`() = runTest {
        waitForValuesLoaded()
        vm.set(PrefKey.ThemeMode, "night")
        vm.set(PrefKey.GlassBlurRadius, 0f)
        awaitSaved(PrefKey.ThemeMode, "night")
        assertThat(vm.valueOf(PrefKey.ThemeMode)).isEqualTo("night")

        vm.resetToDefaults()
        awaitMessage(SettingsMessage.ResetDone)
        waitForValuesLoaded()

        assertThat(vm.valueOf(PrefKey.ThemeMode)).isEqualTo(PrefKey.ThemeMode.default)
        assertThat(vm.valueOf(PrefKey.GlassBlurRadius)).isEqualTo(PrefKey.GlassBlurRadius.default)
    }

    @Test
    fun `恢复默认会发 ResetDone 提示`() = runTest {
        waitForValuesLoaded()
        vm.resetToDefaults()
        awaitMessage(SettingsMessage.ResetDone)
        assertThat(vm.message.value).isEqualTo(SettingsMessage.ResetDone)
    }

    @Test
    fun `消费提示后置空`() = runTest {
        waitForValuesLoaded()
        vm.resetToDefaults()
        awaitMessage(SettingsMessage.ResetDone)
        vm.consumeMessage()
        assertThat(vm.message.value).isNull()
    }

    @Test
    fun `恢复默认会清掉未落盘的 pending`() = runTest {

        //   若不清 pending，恢复完之后界面还显示刚才拖的值 —— 用户以为没生效。
        waitForValuesLoaded()
        vm.set(PrefKey.MaterialAlpha, 0.9f)

        vm.resetToDefaults()
        awaitMessage(SettingsMessage.ResetDone)
        waitForValuesLoaded()

        assertThat(vm.valueOf(PrefKey.MaterialAlpha)).isEqualTo(PrefKey.MaterialAlpha.default)
    }

    // ==================================================================
    // 依赖（enabledWhen）
    // ==================================================================

    @Test
    fun `母项关掉后子项不可交互`() = runTest {
        waitForValuesLoaded()
        val arrival = PrefSpecs.specOf(PrefKey.HapticArrival)!!
        val values = vm.values.value + (PrefKey.HapticMaster.name to false)
        assertThat(vm.isEnabled(arrival, values)).isFalse()
    }

    @Test
    fun `母项开着时子项可交互`() = runTest {
        waitForValuesLoaded()
        val arrival = PrefSpecs.specOf(PrefKey.HapticArrival)!!
        val values = vm.values.value + (PrefKey.HapticMaster.name to true)
        assertThat(vm.isEnabled(arrival, values)).isTrue()
    }

    @Test
    fun `非交互项即使母项开着也不可交互`() {
        // 构造一个"产品决定不可交互 + 有母项"的假 spec 来验证短路顺序
        val fake = PrefSpecs.specOf(PrefKey.HapticArrival)!!.copy(interactive = false)
        assertThat(vm.isEnabled(fake, mapOf(PrefKey.HapticMaster.name to true))).isFalse()
    }

    @Test
    fun `没有母项的项永远可交互`() {
        val theme = PrefSpecs.specOf(PrefKey.ThemeMode)!!
        assertThat(vm.isEnabled(theme, emptyMap())).isTrue()
    }

    @Test
    fun `母项值缺失时按不可交互处理`() {

        //   也不要让他在"看起来能用"的控件上改一个会被静默丢弃的值。
        val arrival = PrefSpecs.specOf(PrefKey.HapticArrival)!!
        assertThat(vm.isEnabled(arrival, emptyMap())).isFalse()
    }

    // ==================================================================
    // 辅助
    // ==================================================================














    private fun TestScope.subscribeForever() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.values.collect { /* 订阅住 */ }
        }
    }









    private suspend fun <T> awaitSaved(key: PrefKey<T>, expected: T) {
        awaitReal { repo.read(key) == expected }
    }








    private suspend fun TestScope.awaitMessage(expected: SettingsMessage) {
        awaitReal { vm.message.value == expected }
    }





























    private suspend fun TestScope.waitForValuesLoaded() {
        subscribeForever()

        //   因为初始值恰好可能等于落盘值（比如全默认），"值变了"会误判。
        //   这里退一步：等 DataStore 的第一次 emit 完成 —— 用真实时间读一次 repo，
        //   再让虚拟时间把 combine/stateIn 的管线跑完。
        awaitReal { vm.values.value.isNotEmpty() }
        runCurrent()
        // 再给它一次机会：真实 IO 完成 + 调度器推进
        awaitReal(timeoutMs = 1_000) {
            val stored = repo.read(PrefKey.ThemeMode)
            vm.values.value[PrefKey.ThemeMode.name] == stored
        }
        runCurrent()
    }
}
