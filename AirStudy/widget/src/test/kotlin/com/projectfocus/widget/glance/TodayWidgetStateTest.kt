package com.projectfocus.widget.glance

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.projectfocus.widget.snapshot.TodayStats
import org.junit.Test






















class TodayWidgetStateTest {

    private val today = java.time.LocalDate.of(2026, 10, 26).toEpochDay()
    private val yesterday = today - 1

    private val sample = TodayStats(
        sessionCount = 3,
        focusMinutes = 135,
        distanceKm = 1800,
        streakDays = 6,
        flewToday = true,
    )

    @Test
    fun `写入再读出往返一致`() {
        val prefs = mutablePreferencesOf()
        TodayWidgetUpdater.fromStats(prefs, sample, today)
        assertThat(TodayWidgetUpdater.toStats(prefs, today)).isEqualTo(sample)
    }

    @Test
    fun `空状态也能往返`() {
        val prefs = mutablePreferencesOf()
        TodayWidgetUpdater.fromStats(prefs, TodayStats.EMPTY, today)
        assertThat(TodayWidgetUpdater.toStats(prefs, today)).isEqualTo(TodayStats.EMPTY)
    }




    @Test
    fun `昨天的快照在今天读出来是空态`() {
        val prefs = mutablePreferencesOf()
        TodayWidgetUpdater.fromStats(prefs, sample, yesterday)
        val read = TodayWidgetUpdater.toStats(prefs, today)
        assertThat(read).isEqualTo(TodayStats.EMPTY)

        //   （连续天数是唯一"跨天仍然有效"的数，所以最容易被留下来，
        //     而一旦留下，用户会看到「连续 6 天 / 0 分钟」这种自相矛盾的组合）
        assertThat(read.streakDays).isEqualTo(0)
        assertThat(read.focusMinutes).isEqualTo(0)
        assertThat(read.sessionCount).isEqualTo(0)
    }

    @Test
    fun `没有快照时读出空态而不是崩溃`() {
        val prefs = mutablePreferencesOf()
        assertThat(TodayWidgetUpdater.toStats(prefs, today)).isEqualTo(TodayStats.EMPTY)
    }






    @Test
    fun `未来日期的快照同样降级`() {
        val prefs = mutablePreferencesOf()
        TodayWidgetUpdater.fromStats(prefs, sample, today + 1)
        assertThat(TodayWidgetUpdater.toStats(prefs, today)).isEqualTo(TodayStats.EMPTY)
    }









    @Test
    fun `数值键缺失时用零兜底`() {
        val prefs = mutablePreferencesOf()
        // 只写日期，不写数值
        prefs[TodayWidgetKeys.SNAPSHOT_DAY] = today
        val read = TodayWidgetUpdater.toStats(prefs, today)
        assertThat(read.sessionCount).isEqualTo(0)
        assertThat(read.focusMinutes).isEqualTo(0)
        assertThat(read.distanceKm).isEqualTo(0)
        assertThat(read.streakDays).isEqualTo(0)
        assertThat(read.flewToday).isFalse()
    }









    @Test
    fun `快照日期与分钟数不会共用同一个键`() {
        assertThat(TodayWidgetKeys.SNAPSHOT_DAY.name).isNotEqualTo(TodayWidgetKeys.FOCUS_MINUTES.name)
        assertThat(TodayWidgetKeys.SESSION_COUNT.name).isNotEqualTo(TodayWidgetKeys.FOCUS_MINUTES.name)
        assertThat(TodayWidgetKeys.DISTANCE_KM.name).isNotEqualTo(TodayWidgetKeys.FOCUS_MINUTES.name)
        assertThat(TodayWidgetKeys.STREAK_DAYS.name).isNotEqualTo(TodayWidgetKeys.FOCUS_MINUTES.name)
        assertThat(TodayWidgetKeys.FLEW_TODAY.name).isNotEqualTo(TodayWidgetKeys.SESSION_COUNT.name)
    }










    @Test
    fun `所有键都带 today 前缀`() {
        val keys = listOf(
            TodayWidgetKeys.SESSION_COUNT, TodayWidgetKeys.FOCUS_MINUTES,
            TodayWidgetKeys.DISTANCE_KM, TodayWidgetKeys.STREAK_DAYS,
            TodayWidgetKeys.FLEW_TODAY, TodayWidgetKeys.SNAPSHOT_DAY,
        )
        keys.forEach { assertThat(it.name.startsWith("today_")).isTrue() }
    }






















    @Test
    fun `用错误类型读会抛而不是静默返回 null`() {
        val prefs = mutablePreferencesOf()
        TodayWidgetUpdater.fromStats(prefs, sample, today)

        // 正确类型：读得到，值正确
        assertThat(prefs[TodayWidgetKeys.SNAPSHOT_DAY]).isEqualTo(today)
        assertThat(prefs[TodayWidgetKeys.FOCUS_MINUTES]).isEqualTo(135)

        // 错误类型：抛 ClassCastException（不是 null）
        val wrongType = runCatching { prefs[longPreferencesKey(TodayWidgetKeys.FOCUS_MINUTES.name)] }
        assertThat(wrongType.exceptionOrNull()).isInstanceOf(ClassCastException::class.java)

        val wrongType2 = runCatching { prefs[booleanPreferencesKey(TodayWidgetKeys.DISTANCE_KM.name)] }
        assertThat(wrongType2.exceptionOrNull()).isInstanceOf(ClassCastException::class.java)
    }








    @Test
    fun `键名不存在时返回 null`() {
        val prefs = mutablePreferencesOf()
        TodayWidgetUpdater.fromStats(prefs, sample, today)
        assertThat(prefs[intPreferencesKey("today_not_a_real_key")]).isNull()
    }
}
