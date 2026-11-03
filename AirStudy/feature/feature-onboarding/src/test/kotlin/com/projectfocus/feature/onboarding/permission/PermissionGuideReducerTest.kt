package com.projectfocus.feature.onboarding.permission

import com.google.common.truth.Truth.assertThat
import org.junit.Test














class PermissionGuideReducerTest {

    private fun state(
        usage: PermissionStatus = PermissionStatus.DENIED,
        overlay: PermissionStatus = PermissionStatus.DENIED,
        notification: PermissionStatus = PermissionStatus.DENIED,
        battery: PermissionStatus = PermissionStatus.DENIED,
    ) = PermissionGuideState(
        statuses = mapOf(
            PermissionItem.USAGE_STATS to usage,
            PermissionItem.OVERLAY to overlay,
            PermissionItem.NOTIFICATION to notification,
            PermissionItem.BATTERY_OPTIMIZATION to battery,
        ),
    )

    private fun allCore(granted: Boolean = true): Map<PermissionItem, PermissionStatus> =
        PermissionItem.entries.associateWith {
            if (it.core == granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }

    // ==================================================================
    // ① 放行条件
    // ==================================================================

    @Test
    fun `核心权限全齐才能开始飞行`() {
        val ready = state(
            usage = PermissionStatus.GRANTED,
            overlay = PermissionStatus.GRANTED,
            notification = PermissionStatus.GRANTED,
        )
        assertThat(ready.coreGranted).isTrue()
        assertThat(ready.isReadyToFly).isTrue()
    }







    @Test
    fun `缺任意一项核心权限都不能开始飞行`() {
        PermissionItem.CORE.forEach { missing ->
            val statuses = PermissionItem.entries.associateWith {
                if (it == missing) PermissionStatus.DENIED else PermissionStatus.GRANTED
            }
            val s = PermissionGuideState(statuses)
            assertThat(s.coreGranted).isFalse()
            assertThat(s.isReadyToFly).isFalse()
        }
    }







    @Test
    fun `电池优化没开也不影响能否飞行`() {
        val s = state(
            usage = PermissionStatus.GRANTED,
            overlay = PermissionStatus.GRANTED,
            notification = PermissionStatus.GRANTED,
            battery = PermissionStatus.DENIED,
        )
        assertThat(s.isReadyToFly).isTrue()
        // 但它在待办列表里仍然可见（不在核心列表，在可选列表）
        assertThat(PermissionItem.OPTIONAL).containsExactly(PermissionItem.BATTERY_OPTIMIZATION)
    }

    // ==================================================================
    // ② 下一个该做什么
    // ==================================================================

    @Test
    fun `下一个待办按固定顺序推进，不会跳步`() {
        // 全没开 → 使用情况访问
        assertThat(state().nextRequired).isEqualTo(PermissionItem.USAGE_STATS)

        // 开了使用情况访问 → 悬浮窗
        assertThat(state(usage = PermissionStatus.GRANTED).nextRequired)
            .isEqualTo(PermissionItem.OVERLAY)

        // 再开悬浮窗 → 通知
        assertThat(
            state(usage = PermissionStatus.GRANTED, overlay = PermissionStatus.GRANTED).nextRequired,
        ).isEqualTo(PermissionItem.NOTIFICATION)

        // 全开 → 没有待办
        assertThat(state(
            usage = PermissionStatus.GRANTED,
            overlay = PermissionStatus.GRANTED,
            notification = PermissionStatus.GRANTED,
        ).nextRequired).isNull()
    }






    @Test
    fun `先开了后面的权限也能正确指出最靠前的缺口`() {
        val s = state(notification = PermissionStatus.GRANTED)
        assertThat(s.nextRequired).isEqualTo(PermissionItem.USAGE_STATS)
        assertThat(s.coreGrantedCount).isEqualTo(1)
    }

    // ==================================================================
    // ③ 预览模式
    // ==================================================================

    @Test
    fun `初始状态显示引导页`() {
        assertThat(PermissionGuideReducer.initial().shouldShowGuide).isTrue()
    }

    @Test
    fun `点了先看看之后不再显示引导页，但仍不能飞`() {
        val s = PermissionGuideReducer.reduce(
            PermissionGuideReducer.initial(),
            PermissionGuideReducer.Event.EnteredPreview,
        )
        assertThat(s.shouldShowGuide).isFalse()

        assertThat(s.isReadyToFly).isFalse()
    }










    @Test
    fun `预览模式下开启全部权限后预览标记被清除`() {
        val inPreview = PermissionGuideReducer.reduce(
            PermissionGuideReducer.initial(),
            PermissionGuideReducer.Event.EnteredPreview,
        )
        val afterGrant = PermissionGuideReducer.reduce(
            inPreview,
            PermissionGuideReducer.Event.Refreshed(allCore()),
        )
        assertThat(afterGrant.previewMode).isFalse()
        assertThat(afterGrant.isReadyToFly).isTrue()
    }

    @Test
    fun `预览模式下部分开启权限时预览标记保留`() {
        val inPreview = PermissionGuideReducer.reduce(
            PermissionGuideReducer.initial(),
            PermissionGuideReducer.Event.EnteredPreview,
        )
        val afterPartial = PermissionGuideReducer.reduce(
            inPreview,
            PermissionGuideReducer.Event.Refreshed(
                mapOf(PermissionItem.USAGE_STATS to PermissionStatus.GRANTED),
            ),
        )
        assertThat(afterPartial.previewMode).isTrue()
    }

    // ==================================================================
    // ④ 从设置页返回
    // ==================================================================

    @Test
    fun `从设置页返回且状态变化时不显示没找到提示`() {
        val prev = PermissionGuideReducer.initial()
        val next = PermissionGuideReducer.reduce(
            prev,
            PermissionGuideReducer.Event.Refreshed(
                mapOf(PermissionItem.USAGE_STATS to PermissionStatus.GRANTED),
            ),
        )
        assertThat(next.returnedWithoutChange).isFalse()
    }


    @Test
    fun `从设置页返回但状态没变化时显示没找到提示`() {
        val prev = PermissionGuideReducer.initial()
        val next = PermissionGuideReducer.reduce(
            prev,
            PermissionGuideReducer.Event.Refreshed(prev.statuses),
        )
        assertThat(next.returnedWithoutChange).isTrue()
    }

    @Test
    fun `核心权限已全齐时不再显示没找到提示`() {
        val ready = PermissionGuideState(allCore())
        val next = PermissionGuideReducer.reduce(
            ready,
            PermissionGuideReducer.Event.Refreshed(ready.statuses),
        )
        assertThat(next.returnedWithoutChange).isFalse()
    }

    // ==================================================================
    // ⑤ 按钮文案
    // ==================================================================

    @Test
    fun `按钮文案随权限状态变化`() {
        val s = state(
            usage = PermissionStatus.DENIED,
            overlay = PermissionStatus.PERMANENTLY_DENIED,
            notification = PermissionStatus.GRANTED,
        )
        assertThat(s.actionLabelFor(PermissionItem.USAGE_STATS)).isEqualTo("开启")

        assertThat(s.actionLabelFor(PermissionItem.OVERLAY)).isEqualTo("前往设置")
        assertThat(s.actionLabelFor(PermissionItem.NOTIFICATION)).isEqualTo("已开启")
    }






    @Test
    fun `永久拒绝的权限文案不是开启`() {
        val s = state(usage = PermissionStatus.PERMANENTLY_DENIED)
        assertThat(s.actionLabelFor(PermissionItem.USAGE_STATS)).isNotEqualTo("开启")
    }
}
