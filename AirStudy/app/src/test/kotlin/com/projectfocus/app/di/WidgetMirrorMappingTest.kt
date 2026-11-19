package com.projectfocus.app.di

import com.google.common.truth.Truth.assertThat
import com.projectfocus.service.focus.session.WidgetMirror
import com.projectfocus.widget.snapshot.WidgetSnapshot
import org.junit.Test























class WidgetMirrorMappingTest {

    @Test
    fun `Idle 映射成 Idle`() {
        assertThat(WidgetMirror.Idle.toSnapshot()).isEqualTo(WidgetSnapshot.Idle)
    }

    @Test
    fun `Flying 的四个字段逐一对齐，不发生串位`() {
        val mirror = WidgetMirror.Flying(
            remainingMs = 111_000,
            totalMs = 999_000,
            destinationCode = "NRT",
            scenarioLabel = "阅读",
        )

        val snapshot = mirror.toSnapshot() as WidgetSnapshot.Flying

        assertThat(snapshot.remainingMs).isEqualTo(111_000)
        assertThat(snapshot.totalMs).isEqualTo(999_000)
        assertThat(snapshot.destinationCode).isEqualTo("NRT")
        assertThat(snapshot.scenarioLabel).isEqualTo("阅读")
    }

    @Test
    fun `Flying 的目的地为 null 时不被写成空串`() {

        //   （null → 显示出发地/目的地文字，"" → 显示占位方块）
        val mirror = WidgetMirror.Flying(
            remainingMs = 1_000,
            totalMs = 2_000,
            destinationCode = null,
            scenarioLabel = "工作",
        )
        assertThat((mirror.toSnapshot() as WidgetSnapshot.Flying).destinationCode).isNull()
    }

    @Test
    fun `Paused 的 elapsedMs 不被写成 remainingMs`() {

        //   写成 "totalMs - elapsedMs" 在 45 分钟的会话里恰好
        //   只差个符号，肉眼极难发现。
        val mirror = WidgetMirror.Paused(
            elapsedMs = 888_000,
            totalMs = 999_000,
            scenarioLabel = "工作",
        )

        val snapshot = mirror.toSnapshot() as WidgetSnapshot.Paused

        assertThat(snapshot.elapsedMs).isEqualTo(888_000)
        assertThat(snapshot.totalMs).isEqualTo(999_000)
        assertThat(snapshot.scenarioLabel).isEqualTo("工作")
    }






    @Test
    fun `转换后 Flying 的 progress 与 elapsed 自洽`() {
        val mirror = WidgetMirror.Flying(remainingMs = 300_000, totalMs = 600_000,
            destinationCode = "KIX", scenarioLabel = "大阪")
        val snapshot = mirror.toSnapshot() as WidgetSnapshot.Flying

        assertThat(snapshot.progress).isWithin(1e-6f).of(0.5f)
        assertThat(snapshot.elapsedMs).isEqualTo(300_000)
    }

    @Test
    fun `转换后 Paused 的 remainingMs 与 Flying 口径一致`() {
        // 同为「已飞 4 分钟 / 共 6 分钟」的两种状态，剩余必须相同
        val flying = WidgetMirror.Flying(remainingMs = 120_000, totalMs = 360_000,
            destinationCode = null, scenarioLabel = "x").toSnapshot() as WidgetSnapshot.Flying
        val paused = WidgetMirror.Paused(elapsedMs = 240_000, totalMs = 360_000,
            scenarioLabel = "x").toSnapshot() as WidgetSnapshot.Paused

        assertThat(paused.remainingMs).isEqualTo(flying.remainingMs)
    }
}
