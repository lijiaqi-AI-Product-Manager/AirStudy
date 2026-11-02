package com.projectfocus.core.common.coroutines

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test

class CancellableTest {

    @Test
    fun `cancellation is rethrown and not captured`() {
        var rethrown = false
        try {
            runCatchingCancellable<Unit> { throw CancellationException("cancelled") }
        } catch (_: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
    }

    @Test
    fun `ordinary failure is captured`() {
        val result = runCatchingCancellable<Unit> { error("boom") }
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is CancellationException)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `success keeps value`() {
        assertEquals(7, runCatchingCancellable { 7 }.getOrNull())
    }
}
