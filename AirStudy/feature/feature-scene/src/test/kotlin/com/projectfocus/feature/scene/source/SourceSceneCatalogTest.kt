package com.projectfocus.feature.scene.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceSceneCatalogTest {
    @Test
    fun `source builtin namespace is eleven with original color and native sleep label`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            listOf("study", "work", "reading", "meditation", "creative", "relax", "exercise", "music", "programming", "gaming", "starchasing"),
            SourceSceneCatalog.builtins.map { it.iconKey },
        )
        assertEquals((1..11).toList(), SourceSceneCatalog.builtins.map { it.type })
        assertEquals(0xFF3CC612.toInt(), SourceSceneCatalog.builtins[0].color)
        assertNotEquals(0xFF74EB50.toInt(), SourceSceneCatalog.builtins[0].color)
        assertEquals("睡眠", SourceSceneCatalog.label(context, SourceSceneCatalog.builtins[3].record()))
    }

    @Test
    fun `custom presentation preserves title color and uses native unknown icon fallback with type zero`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val record = SourceSceneRecord("自定义工作", "work", 0xFF010203.toInt(), true, 50L, "uuid")
        val option = SourceSceneOptionIds.snapshot(SourceSceneScope("local"), SourceSceneCollection.ENABLED, listOf(record)).options.single()
        val presentation = SourceSceneCatalog.present(context, option)
        assertEquals(option.optionId, presentation.optionId)
        assertEquals("自定义工作", presentation.title)
        assertEquals(2, presentation.type)
        assertEquals(record.color, presentation.color)
        assertEquals(SourceSceneCatalog.icon("study"), SourceSceneCatalog.icon("unknown-source-icon"))
        assertEquals(0, SourceSceneCatalog.type("unknown-source-icon"))
    }
}
