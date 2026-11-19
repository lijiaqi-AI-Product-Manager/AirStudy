package com.projectfocus.feature.onboarding.permission

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 权限引导 ViewModel 的行为测试。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 核心命题：**「从设置页返回」必须被当成一次需要判断的事件**
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   这里测的不是「读到的状态对不对」（那是 reducer 的 14 条测试），
 *   而是**五种返回路径的行为差异**：
 *
 *   | # | 用户动作 | 期望 |
 *   |---|---|---|
 *   | 1 | 首次进入 | 全 DENIED，`returnedWithoutChange = false` |
 *   | 2 | 跳设置 → 开了 → 返回 | 自动推进，**不显示**提示 |
 *   | 3 | 跳设置 → 没找到 → 返回 | 停在原地，**显示**提示 |
 *   | 4 | 只是切了下 App 回来（没点任何权限） | 安静刷新，**不显示**提示 |
 *   | 5 | 通知被拒过一次 → 返回 | 文案改「前往设置」 |
 *
 *   第 3 与第 4 的差别是本文件最重要的两条：它们的**状态完全一样**
 *   （核心权限都没开），唯一区别是「用户是不是刚从设置页回来」。
 *   如果不用 pendingItem 记录来源，这两条无法区分 ——
 *   于是要么该提示时不提示（用户找不到开关），
 *   要么不该提示时乱提示（用户被指责）。
 */
class PermissionGuideViewModelTest {







    private class FakePlatform : PermissionPlatform {
        /** 当前各权限状态。测试直接改它来模拟「用户在设置页里开了」 */
        val statuses = PermissionItem.entries.associateWith { PermissionStatus.DENIED }.toMutableMap()

        /** 记录每次点「开启」跳了哪一项 */
        val opened = mutableListOf<PermissionItem>()

        /** 通知是否需要运行时请求（模拟 Android 13+） */
        var runtimeForNotification = true

        /** 通知是否算永久拒绝 */
        var permanentlyDeniedNotification = false

        override fun status(item: PermissionItem) = statuses.getValue(item)

        override fun settingsIntent(item: PermissionItem): android.content.Intent? {
            opened += item
            return if (needsRuntimeRequest(item)) null else android.content.Intent("test.${item.name}")
        }

        override fun needsRuntimeRequest(item: PermissionItem) =
            item == PermissionItem.NOTIFICATION && runtimeForNotification

        override fun isPermanentlyDenied(item: PermissionItem) =
            item == PermissionItem.NOTIFICATION && permanentlyDeniedNotification

        fun grantAll() {
            PermissionItem.entries.forEach { statuses[it] = PermissionStatus.GRANTED }
        }
    }

    private fun vm(platform: FakePlatform) = PermissionGuideViewModel(platform)

    // ── 1. 首次进入 ─────────────────────────────────────────

    @Test
    fun `首次进入时全部未开启且不显示没找到提示`() {
        val p = FakePlatform()
        val v = vm(p)
        v.onEnter()
        assertThat(v.state.value.coreGrantedCount).isEqualTo(0)
        assertThat(v.state.value.returnedWithoutChange).isFalse()
        assertThat(v.state.value.nextRequired).isEqualTo(PermissionItem.CORE.first())
    }

    @Test
    fun `首次进入时若有状态已开启则如实反映`() {
        val p = FakePlatform()
        p.statuses[PermissionItem.USAGE_STATS] = PermissionStatus.GRANTED
        val v = vm(p)
        v.onEnter()
        assertThat(v.state.value.coreGrantedCount).isEqualTo(1)
        // 下一个待办应跳过已开的
        assertThat(v.state.value.nextRequired).isNotEqualTo(PermissionItem.USAGE_STATS)
    }

    @Test
    fun `核心权限全开时 readyToFly 为真`() {
        val p = FakePlatform()
        p.grantAll()
        val v = vm(p)
        v.onEnter()
        assertThat(v.state.value.isReadyToFly).isTrue()
        assertThat(v.state.value.shouldShowGuide).isFalse()
        assertThat(v.state.value.nextRequired).isNull()
    }

    // ── 2. 从设置页返回且状态变了 ───────────────────────────

    @Test
    fun `从设置页返回且已开启时自动推进到下一步`() {
        val p = FakePlatform()
        val v = vm(p)
        v.onEnter()

        // 用户点使用情况访问的「开启」
        val intent = v.onItemClicked(PermissionItem.USAGE_STATS)
        assertThat(intent).isNotNull()
        assertThat(p.opened).containsExactly(PermissionItem.USAGE_STATS)

        // 用户在设置页里把它开了，然后按返回
        p.statuses[PermissionItem.USAGE_STATS] = PermissionStatus.GRANTED
        v.onResume()

        assertThat(v.state.value.coreGrantedCount).isEqualTo(1)

        assertThat(v.state.value.returnedWithoutChange).isFalse()
    }

    // ── 3. 从设置页返回但状态没变（最关键） ─────────────────

    @Test
    fun `从设置页返回但没变化时显示没找到提示`() {

        //   MIUI/EMUI 把「使用情况访问」藏在「隐私保护 → 特殊权限」两级菜单下，
        //   用户点了「开启」，跳到设置页，找不到开关，返回 —— 状态没变。
        //   此时必须给一条兜底提示，否则用户完全卡死。
        val p = FakePlatform()
        val v = vm(p)
        v.onEnter()

        v.onItemClicked(PermissionItem.USAGE_STATS)
        // 用户在设置页里什么都没改
        v.onResume()

        assertThat(v.state.value.returnedWithoutChange).isTrue()
        assertThat(v.state.value.coreGrantedCount).isEqualTo(0)
    }

    @Test
    fun `没找到提示在用户下一次成功后自动消失`() {
        val p = FakePlatform()
        val v = vm(p)
        v.onEnter()

        v.onItemClicked(PermissionItem.USAGE_STATS)
        v.onResume()
        assertThat(v.state.value.returnedWithoutChange).isTrue()

        // 用户第二次终于找到了开关
        p.statuses[PermissionItem.USAGE_STATS] = PermissionStatus.GRANTED
        v.onResume()   // 注意：这次没有点任何权限，pendingItem 是 null

        assertThat(v.state.value.returnedWithoutChange).isFalse()
        assertThat(v.state.value.coreGrantedCount).isEqualTo(1)
    }

    @Test
    fun `提示可以被用户主动关掉`() {
        val p = FakePlatform()
        val v = vm(p)
        v.onEnter()
        v.onItemClicked(PermissionItem.USAGE_STATS)
        v.onResume()
        assertThat(v.state.value.returnedWithoutChange).isTrue()

        v.onDismissHint()
        assertThat(v.state.value.returnedWithoutChange).isFalse()
    }

    // ── 4. 普通切回（没点任何权限） ─────────────────────────

    @Test
    fun `只是切了下 App 回来不显示没找到提示`() {

        //   如果没有 pendingItem 机制，这两条无法区分。
        val p = FakePlatform()
        val v = vm(p)
        v.onEnter()
        // 用户按 Home 键，再切回来
        v.onResume()

        assertThat(v.state.value.returnedWithoutChange).isFalse()
    }

    @Test
    fun `连续两次普通切回都不会累积出提示`() {
        val p = FakePlatform()
        val v = vm(p)
        v.onEnter()
        repeat(3) { v.onResume() }
        assertThat(v.state.value.returnedWithoutChange).isFalse()
    }

    // ── 5. 运行时权限（Android 13+ 通知） ───────────────────

    @Test
    fun `通知在 Android 十三以上走运行时请求而不跳设置页`() {
        val p = FakePlatform()
        p.runtimeForNotification = true
        val v = vm(p)
        v.onEnter()

        val intent = v.onItemClicked(PermissionItem.NOTIFICATION)
        assertThat(intent).isNull()               // 由 Fragment 用 launcher 发起
        assertThat(p.opened).isEmpty()            // 没有跳设置页
    }

    @Test
    fun `运行时请求被拒后文案改成前往设置`() {

        //   继续显示「开启」会让用户点了没反应 → 认为 App 坏了。
        val p = FakePlatform()
        p.runtimeForNotification = true
        val v = vm(p)
        v.onEnter()

        v.onItemClicked(PermissionItem.NOTIFICATION)
        v.onRuntimeResult(PermissionItem.NOTIFICATION, granted = false)

        assertThat(v.state.value.statuses[PermissionItem.NOTIFICATION])
            .isEqualTo(PermissionStatus.PERMANENTLY_DENIED)
        assertThat(v.state.value.actionLabelFor(PermissionItem.NOTIFICATION))
            .isEqualTo("前往设置")
    }

    @Test
    fun `运行时请求被拒后立刻的 onResume 不会误报没找到`() {

        //   如果 onRuntimeResult 没清掉 pendingItem，这里会误报。
        val p = FakePlatform()
        val v = vm(p)
        v.onEnter()

        v.onItemClicked(PermissionItem.NOTIFICATION)
        v.onRuntimeResult(PermissionItem.NOTIFICATION, granted = false)
        v.onResume()

        assertThat(v.state.value.returnedWithoutChange).isFalse()
    }

    @Test
    fun `运行时请求被授予后状态直接变已开启`() {
        val p = FakePlatform()
        p.runtimeForNotification = true
        val v = vm(p)
        v.onEnter()

        v.onItemClicked(PermissionItem.NOTIFICATION)
        p.statuses[PermissionItem.NOTIFICATION] = PermissionStatus.GRANTED
        v.onRuntimeResult(PermissionItem.NOTIFICATION, granted = true)

        assertThat(v.state.value.isGranted(PermissionItem.NOTIFICATION)).isTrue()
        assertThat(v.state.value.actionLabelFor(PermissionItem.NOTIFICATION)).isEqualTo("已开启")
    }

    // ── 6. 平台层抛异常必须兜住 ─────────────────────────────

    @Test
    fun `读状态抛异常时按未授予处理而不是崩`() {

        //   引导页崩了 = 用户永远进不去 App。
        val throwing = object : PermissionPlatform {
            override fun status(item: PermissionItem): PermissionStatus =
                throw SecurityException("ROM 定制导致读取失败")

            override fun settingsIntent(item: PermissionItem) = android.content.Intent("x")
            override fun needsRuntimeRequest(item: PermissionItem) = false
            override fun isPermanentlyDenied(item: PermissionItem) = false
        }
        val v = PermissionGuideViewModel(throwing)
        v.onEnter()

        assertThat(v.state.value.coreGrantedCount).isEqualTo(0)
        assertThat(v.state.value.statuses.values.contains(PermissionStatus.GRANTED)).isFalse()
    }

    // ── 7. 预览模式 ────────────────────────────────────────

    @Test
    fun `点先看看后不再显示引导页但仍不能飞`() {
        val p = FakePlatform()
        val v = vm(p)
        v.onEnter()
        v.onEnterPreview()

        assertThat(v.state.value.shouldShowGuide).isFalse()
        assertThat(v.state.value.isReadyToFly).isFalse()
    }

    @Test
    fun `预览模式下开启全部权限后预览标记被清除`() {
        val p = FakePlatform()
        val v = vm(p)
        v.onEnter()
        v.onEnterPreview()

        p.grantAll()
        v.onEnter()   // 重新进入时读状态

        assertThat(v.state.value.previewMode).isFalse()
        assertThat(v.state.value.isReadyToFly).isTrue()
    }

    // ── 8. 无跳转也无运行时请求的退化路径 ───────────────────

    @Test
    fun `平台层返回 null 但不需运行时请求时清掉来源标记`() {

        //   给用户一个莫名其妙的提示。
        val broken = object : PermissionPlatform {
            override fun status(item: PermissionItem) = PermissionStatus.DENIED
            override fun settingsIntent(item: PermissionItem): android.content.Intent? = null
            override fun needsRuntimeRequest(item: PermissionItem) = false
            override fun isPermanentlyDenied(item: PermissionItem) = false
        }
        val v = PermissionGuideViewModel(broken)
        v.onEnter()
        v.onItemClicked(PermissionItem.OVERLAY)
        v.onResume()

        assertThat(v.state.value.returnedWithoutChange).isFalse()
    }
}
