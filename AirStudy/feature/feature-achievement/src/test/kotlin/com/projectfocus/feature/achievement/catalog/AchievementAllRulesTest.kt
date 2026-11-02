package com.projectfocus.feature.achievement.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 22 条可判定规则的“刚好/差一点”边界，按规则类型分组覆盖。
 *
 * 目的不是替代逐条业务验收，而是保证每条 supported 规则都至少被
 * 一个边界用例执行到，避免新加成就时忘记加测试。
 */
class AchievementAllRulesTest {

    private val supported = AchievementCatalog.all.filter { it.rule != AchievementRule.Unsupported }

    @Test
    fun completed_rules_are_inclusive_at_threshold() {
        val rules = supported.mapNotNull { def -> (def.rule as? AchievementRule.Completed)?.let { def to it } }
        assertTrue("no Completed rules", rules.isNotEmpty())
        rules.forEach { (definition, rule) ->
            assertFalse(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(completedCount = rule.count - 1)))
            assertTrue(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(completedCount = rule.count)))
        }
    }

    @Test
    fun distance_rules_are_inclusive_at_threshold() {
        val rules = supported.mapNotNull { def -> (def.rule as? AchievementRule.DistanceKm)?.let { def to it } }
        assertTrue("no DistanceKm rules", rules.isNotEmpty())
        rules.forEach { (definition, rule) ->
            assertFalse(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(totalDistanceKm = rule.km - 0.1)))
            assertTrue(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(totalDistanceKm = rule.km)))
        }
    }

    @Test
    fun city_rules_require_more_than_threshold() {
        val rules = supported.mapNotNull { def -> (def.rule as? AchievementRule.Cities)?.let { def to it } }
        assertTrue("no Cities rules", rules.isNotEmpty())
        rules.forEach { (definition, rule) ->
            assertFalse(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(visitedCityCount = rule.count)))
            assertTrue(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(visitedCityCount = rule.count + 1)))
        }
    }

    @Test
    fun total_focus_rules_require_more_than_threshold() {
        val rules = supported.mapNotNull { def -> (def.rule as? AchievementRule.TotalFocusMinutes)?.let { def to it } }
        assertTrue("no TotalFocusMinutes rules", rules.isNotEmpty())
        rules.forEach { (definition, rule) ->
            assertFalse(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(totalFocusMinutes = rule.minutes)))
            assertTrue(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(totalFocusMinutes = rule.minutes + 1)))
        }
    }

    @Test
    fun average_focus_rules_require_more_than_threshold() {
        val rules = supported.mapNotNull { def -> (def.rule as? AchievementRule.AverageFocusMinutes)?.let { def to it } }
        assertTrue("no AverageFocusMinutes rules", rules.isNotEmpty())
        rules.forEach { (definition, rule) ->
            assertFalse(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(averageFocusMinutes = rule.minutes)))
            assertTrue(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(averageFocusMinutes = rule.minutes + 1)))
        }
    }

    @Test
    fun unsupported_rules_never_unlock() {
        val unsupported = AchievementCatalog.all.filter { it.rule == AchievementRule.Unsupported }
        assertEquals(25, unsupported.size)
        val maxed = AchievementStats(
            completedCount = 10_000,
            totalDistanceKm = 10_000_000.0,
            visitedCityCount = 10_000,
            totalFocusMinutes = 10_000_000,
            averageFocusMinutes = 10_000,
        )
        unsupported.forEach { definition ->
            assertFalse(definition.id, AchievementEvaluator.isUnlocked(definition, maxed))
        }
    }

    @Test
    fun average_focus_max_rules_require_less_than_threshold() {
        val rules = supported.mapNotNull { def -> (def.rule as? AchievementRule.AverageFocusMinutesMax)?.let { def to it } }
        assertTrue("no AverageFocusMinutesMax rules", rules.isNotEmpty())
        rules.forEach { (definition, rule) ->

            assertFalse(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(completedCount = 1, averageFocusMinutes = rule.minutes)))
            assertTrue(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(completedCount = 1, averageFocusMinutes = rule.minutes - 1)))
            assertFalse(definition.id, AchievementEvaluator.isUnlocked(definition, AchievementStats(completedCount = 0, averageFocusMinutes = rule.minutes - 1)))
        }
    }
}
