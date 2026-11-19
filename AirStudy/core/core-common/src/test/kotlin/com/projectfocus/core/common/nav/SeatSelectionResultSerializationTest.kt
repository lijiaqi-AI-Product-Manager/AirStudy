package com.projectfocus.core.common.nav

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SeatSelectionResultSerializationTest {
    @Test
    fun `a stream captured from the old production class keeps scope and has no invented scene`() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/seat-selection-result-v1.ser"))
        val result = ObjectInputStream(stream).use { it.readObject() as SeatSelectionResult }
        assertEquals("01A", result.seat)
        assertEquals("legacy-draft", result.draftKey)
        assertEquals("legacy-record", result.recordId)
        assertNull(result.scene)
    }

    @Test
    fun `saved scene identity survives real Java serialization independently of its icon`() {
        val original = SeatSelectionResult(
            "30F", "draft-second", "record-second",
            SeatSceneResult("custom-second", "programming", "第二个场景", 9),
        )
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(original) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as SeatSelectionResult }
        assertEquals(original, restored)
        assertEquals("custom-second", restored.scene?.optionId)
        assertEquals("programming", restored.scene?.iconKey)
    }
}
