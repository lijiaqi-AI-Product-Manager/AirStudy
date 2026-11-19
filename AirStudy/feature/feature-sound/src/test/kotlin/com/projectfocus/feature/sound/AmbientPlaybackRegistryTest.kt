package com.projectfocus.feature.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test


class AmbientPlaybackRegistryTest {

    private class FakeHandle : AmbientPlaybackHandle {
        var stops: Int = 0
        var releases: Int = 0

        override fun stop() {
            stops++
        }

        override fun release() {
            releases++
        }
    }

    @Before
    fun setUp() {
        AmbientPlaybackRegistry.clearForTest()
    }

    @Test
    fun `play stops previous player and registers new one`() {
        val first = FakeHandle()
        val second = FakeHandle()

        assertTrue(AmbientPlaybackRegistry.play(first) { true })
        assertTrue(AmbientPlaybackRegistry.play(second) { true })

        assertEquals(1, first.stops)
        assertFalse(AmbientPlaybackRegistry.isActiveForTest(first))
        assertTrue(AmbientPlaybackRegistry.isActiveForTest(second))
    }

    @Test
    fun `failed play clears active instead of leaving stopped player`() {
        val first = FakeHandle()
        val second = FakeHandle()

        AmbientPlaybackRegistry.play(first) { true }
        assertFalse(AmbientPlaybackRegistry.play(second) { false })

        assertEquals(1, first.stops)
        assertFalse(AmbientPlaybackRegistry.isActiveForTest(first))
        assertFalse(AmbientPlaybackRegistry.isActiveForTest(second))
    }

    @Test
    fun `replaying same handle does not stop itself`() {
        val handle = FakeHandle()
        AmbientPlaybackRegistry.play(handle) { true }
        AmbientPlaybackRegistry.play(handle) { true }

        assertEquals(0, handle.stops)
        assertTrue(AmbientPlaybackRegistry.isActiveForTest(handle))
    }

    @Test
    fun `stop clears active`() {
        val handle = FakeHandle()
        AmbientPlaybackRegistry.play(handle) { true }

        AmbientPlaybackRegistry.stop(handle)

        assertEquals(1, handle.stops)
        assertFalse(AmbientPlaybackRegistry.isActiveForTest(handle))
    }

    @Test
    fun `release clears active`() {
        val handle = FakeHandle()
        AmbientPlaybackRegistry.play(handle) { true }

        AmbientPlaybackRegistry.release(handle)

        assertEquals(1, handle.releases)
        assertFalse(AmbientPlaybackRegistry.isActiveForTest(handle))
    }
}
