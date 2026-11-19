package com.projectfocus.service.focus.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualShieldRulesTest {
    @Test fun deviceChoicesExtendTheSavedRulesWithoutReplacingTheirDetails() {
        val context = RuntimeEnvironment.getApplication()
        val assembler = BlockRuleAssembler(context)
        val saved = listOf(BlockedRow("APP", "example.books", "Reading"),
            BlockedRow("WEBSITE", "example.org", "Reading"))
        val baseline = assembler.assemble(saved)
        val combined = assembler.assemble(saved, setOf("example.social", "example.books", "com.android.dialer", context.packageName))
        assertEquals(3, combined.size)
        assertTrue(combined.containsAll(baseline))
        assertEquals(BlockRule(RuleType.PACKAGE, "example.social"), combined.single { it.pattern == "example.social" })
        assertEquals(baseline, assembler.assemble(saved, emptySet()))
    }
}
