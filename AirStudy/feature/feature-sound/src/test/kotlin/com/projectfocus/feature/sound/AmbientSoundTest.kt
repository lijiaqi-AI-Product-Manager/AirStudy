package com.projectfocus.feature.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


class AmbientSoundTest {

    @Test
    fun `has exactly four ambient tracks`() {
        assertEquals(4, AmbientSound.values().size)
    }

    @Test
    fun `all four raw resources are distinct`() {
        val ids = AmbientSound.values().map { it.rawRes }
        assertEquals(4, ids.toSet().size)
        assertTrue(ids.all { it != 0 })
    }

    @Test
    fun `declaration order is rain bonfire forest ocean`() {
        assertEquals(
            listOf("RAIN", "BONFIRE", "FOREST", "OCEAN"),
            AmbientSound.values().map { it.name },
        )
    }

    @Test
    fun `names are stable for persisted state`() {
        // 若未来改名，旧 SharedPreferences/日志里的 name 会失配；这里显式锁住。
        assertEquals("RAIN", AmbientSound.RAIN.name)
        assertEquals("BONFIRE", AmbientSound.BONFIRE.name)
        assertEquals("FOREST", AmbientSound.FOREST.name)
        assertEquals("OCEAN", AmbientSound.OCEAN.name)
    }
}
