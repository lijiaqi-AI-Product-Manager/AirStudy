package com.projectfocus.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsUiStateTest {

    @Test
    fun `默认状态是加载中`() {
        assertTrue(StatsUiState().isLoading)
    }

    @Test
    fun `0 分钟没有小时`() {
        val state = StatsUiState(focusMinutes = 0)
        assertFalse(state.hasFocusHours)
        assertEquals(0, state.focusHours)
        assertEquals(0, state.focusRemainderMinutes)
    }

    @Test
    fun `59 分钟没有小时`() {
        val state = StatsUiState(focusMinutes = 59)
        assertFalse(state.hasFocusHours)
        assertEquals(0, state.focusHours)
        assertEquals(59, state.focusRemainderMinutes)
    }

    @Test
    fun `60 分钟正好 1 小时`() {
        val state = StatsUiState(focusMinutes = 60)
        assertTrue(state.hasFocusHours)
        assertEquals(1, state.focusHours)
        assertEquals(0, state.focusRemainderMinutes)
    }

    @Test
    fun `61 分钟是 1 小时 1 分钟`() {
        val state = StatsUiState(focusMinutes = 61)
        assertEquals(1, state.focusHours)
        assertEquals(1, state.focusRemainderMinutes)
    }

    @Test
    fun `1440 分钟是 24 小时`() {
        val state = StatsUiState(focusMinutes = 1440)
        assertEquals(24, state.focusHours)
        assertEquals(0, state.focusRemainderMinutes)
    }
}
