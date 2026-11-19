package com.projectfocus.feature.scene.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePaletteTest {

    @Test
    fun `fifteen unique scenes`() {
        assertEquals(15, ScenePalette.all.size)
        assertEquals(15, ScenePalette.all.map { it.id }.distinct().size)
    }

    @Test
    fun `nine scenes have APK2 labels`() {
        assertEquals(9, ScenePalette.all.count { it.labelRes != null })
        assertEquals(6, ScenePalette.all.count { it.labelRes == null })
    }

    @Test
    fun `all colors are six uppercase hex digits`() {
        ScenePalette.all.forEach { scene ->
            assertTrue(scene.id, Regex("^#[0-9A-F]{6}$").matches(scene.colorHex))
        }
    }

    @Test
    fun `known colors convert to opaque argb`() {
        assertEquals(0xFFE73F77.toInt(), ScenePalette.argb(ScenePalette.byId.getValue("creative_pink")))
        assertEquals(0xFF2B71C2.toInt(), ScenePalette.argb(ScenePalette.byId.getValue("work_blue")))
    }

    @Test
    fun `unlabeled scenes are the six color-only keys`() {
        val unlabeled = ScenePalette.all.filter { it.labelRes == null }.map { it.id }.toSet()
        assertEquals(
            setOf("driving_blue", "earth_orange", "growth_green", "lime_yellow", "social_pink", "teal_blue"),
            unlabeled,
        )
    }
}
