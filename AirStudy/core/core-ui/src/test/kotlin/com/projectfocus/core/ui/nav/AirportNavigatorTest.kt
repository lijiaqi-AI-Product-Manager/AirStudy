package com.projectfocus.core.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner


@RunWith(RobolectricTestRunner::class)
class AirportNavigatorTest {

    @Test
    fun `plain iata is kept verbatim`() {
        assertEquals(
            "projectfocus://airport/PEK",
            AirportNavigator.uri("PEK").toString(),
        )
    }

    @Test
    fun `space is percent-encoded`() {
        assertEquals(
            "projectfocus://airport/Los%20Angeles",
            AirportNavigator.uri("Los Angeles").toString(),
        )
    }

    @Test
    fun `slash is percent-encoded so it cannot become a path segment`() {
        assertEquals(
            "projectfocus://airport/SHA%2F1",
            AirportNavigator.uri("SHA/1").toString(),
        )
    }

    @Test
    fun `options launch single top and pop to current`() {
        val options = AirportNavigator.options(7)
        assertTrue(options.shouldLaunchSingleTop())
        assertEquals(7, options.popUpToId)
    }

    @Test
    fun `options without current destination does not pop`() {
        val options = AirportNavigator.options(null)
        assertTrue(options.shouldLaunchSingleTop())
        assertEquals(-1, options.popUpToId)
    }
}
