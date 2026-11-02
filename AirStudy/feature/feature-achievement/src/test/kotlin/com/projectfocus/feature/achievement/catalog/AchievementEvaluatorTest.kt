package com.projectfocus.feature.achievement.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementEvaluatorTest {

    private fun def(id: String): AchievementDefinition =
        requireNotNull(AchievementCatalog.byId[id]) { "missing $id" }

    @Test
    fun catalog_has_47_unique_achievements() {
        assertEquals(47, AchievementCatalog.all.size)
        assertEquals(47, AchievementCatalog.all.map { it.id }.distinct().size)
    }

    @Test
    fun newbie_pilot_unlocks_after_first_completed_flight() {
        assertFalse(AchievementEvaluator.isUnlocked(def("newbie_pilot"), AchievementStats()))
        assertTrue(
            AchievementEvaluator.isUnlocked(
                def("newbie_pilot"),
                AchievementStats(completedCount = 1),
            ),
        )
    }

    @Test
    fun distance_rule_is_inclusive() {
        val def = def("flight_expert") // 3 万公里
        assertFalse(
            AchievementEvaluator.isUnlocked(
                def,
                AchievementStats(totalDistanceKm = 29_999.9),
            ),
        )
        assertTrue(
            AchievementEvaluator.isUnlocked(
                def,
                AchievementStats(totalDistanceKm = 30_000.0),
            ),
        )
    }

    @Test
    fun cities_rule_requires_strictly_more_than_threshold() {
        val def = def("exploration_start") // 超过 5 个
        assertFalse(
            AchievementEvaluator.isUnlocked(def, AchievementStats(visitedCityCount = 5)),
        )
        assertTrue(
            AchievementEvaluator.isUnlocked(def, AchievementStats(visitedCityCount = 6)),
        )
    }

    @Test
    fun total_focus_rule_requires_strictly_more_than_threshold() {
        val def = def("sky_runner") // 超过 100 小时
        assertFalse(
            AchievementEvaluator.isUnlocked(
                def,
                AchievementStats(totalFocusMinutes = 6_000),
            ),
        )
        assertTrue(
            AchievementEvaluator.isUnlocked(
                def,
                AchievementStats(totalFocusMinutes = 6_001),
            ),
        )
    }

    @Test
    fun average_long_haul_rule() {
        val def = def("longhaul_endurance_king") // 平均超过 5 小时
        assertFalse(
            AchievementEvaluator.isUnlocked(
                def,
                AchievementStats(averageFocusMinutes = 300),
            ),
        )
        assertTrue(
            AchievementEvaluator.isUnlocked(
                def,
                AchievementStats(averageFocusMinutes = 301),
            ),
        )
    }

    @Test
    fun average_short_trip_rule_is_strict_less_than() {
        val def = def("short_trip_quickhand") // 少于 1.5 小时

        assertFalse(
            AchievementEvaluator.isUnlocked(
                def,
                AchievementStats(completedCount = 1, averageFocusMinutes = 90),
            ),
        )
        assertTrue(
            AchievementEvaluator.isUnlocked(
                def,
                AchievementStats(completedCount = 1, averageFocusMinutes = 89),
            ),
        )
    }

    @Test
    fun unsupported_never_unlocks_even_with_huge_stats() {
        val unsupported = AchievementCatalog.all.first {
            it.rule == AchievementRule.Unsupported
        }
        val huge = AchievementStats(
            completedCount = 10_000,
            totalDistanceKm = 10_000_000.0,
            visitedCityCount = 10_000,
            totalFocusMinutes = 10_000_000,
            averageFocusMinutes = 10_000,
        )
        assertFalse(AchievementEvaluator.isUnlocked(unsupported, huge))
    }

    @Test
    fun empty_stats_never_unlock_anything() {
        assertTrue(AchievementEvaluator.unlockedIds(AchievementStats()).isEmpty())
    }

    /**
     * 22 条“可判定”规则逐条验证边界：
     * 差一点必须 false，达到/越过必须 true。
     *
     * 这不是参数化测试，但一条用例覆盖全部规则；新增规则若忘记补边界，
     * 会在 supportedCount 与遍历数量不一致时暴露。
     */
    @Test
    fun every_supported_rule_unlocks_exactly_at_its_boundary() {
        val supported = AchievementCatalog.all.filter {
            it.rule != AchievementRule.Unsupported
        }
        assertEquals(22, supported.size)

        supported.forEach { definition ->
            when (val rule = definition.rule) {
                is AchievementRule.Completed -> {
                    assertFalse(definition.id, unlocked(definition, completed = rule.count - 1))
                    assertTrue(definition.id, unlocked(definition, completed = rule.count))
                }

                is AchievementRule.DistanceKm -> {
                    assertFalse(
                        definition.id,
                        unlocked(definition, distanceKm = rule.km - 0.1),
                    )
                    assertTrue(definition.id, unlocked(definition, distanceKm = rule.km))
                }

                is AchievementRule.Cities -> {
                    assertFalse(definition.id, unlocked(definition, cities = rule.count))
                    assertTrue(definition.id, unlocked(definition, cities = rule.count + 1))
                }

                is AchievementRule.TotalFocusMinutes -> {
                    assertFalse(
                        definition.id,
                        unlocked(definition, totalFocusMinutes = rule.minutes),
                    )
                    assertTrue(
                        definition.id,
                        unlocked(definition, totalFocusMinutes = rule.minutes + 1),
                    )
                }

                is AchievementRule.AverageFocusMinutes -> {
                    assertFalse(
                        definition.id,
                        unlocked(definition, averageFocusMinutes = rule.minutes),
                    )
                    assertTrue(
                        definition.id,
                        unlocked(definition, averageFocusMinutes = rule.minutes + 1),
                    )
                }

                is AchievementRule.AverageFocusMinutesMax -> {

                    //       0 次飞行的情形由 empty_stats_never_unlock_anything 单独钉住。
                    assertFalse(
                        definition.id,
                        unlocked(definition, completed = 1, averageFocusMinutes = rule.minutes),
                    )
                    assertTrue(
                        definition.id,
                        unlocked(definition, completed = 1, averageFocusMinutes = rule.minutes - 1),
                    )
                    assertFalse(
                        "${definition.id} 未飞行不应解锁",
                        unlocked(definition, completed = 0, averageFocusMinutes = rule.minutes - 1),
                    )
                }

                AchievementRule.Unsupported -> {
                    // 已过滤；走到这里说明规则分类被新代码改坏。
                    throw AssertionError("${definition.id} should not be supported")
                }
            }
        }
    }

    private fun unlocked(
        definition: AchievementDefinition,
        completed: Int = 0,
        distanceKm: Double = 0.0,
        cities: Int = 0,
        totalFocusMinutes: Int = 0,
        averageFocusMinutes: Int = 0,
    ): Boolean = AchievementEvaluator.isUnlocked(
        definition,
        AchievementStats(
            completedCount = completed,
            totalDistanceKm = distanceKm,
            visitedCityCount = cities,
            totalFocusMinutes = totalFocusMinutes,
            averageFocusMinutes = averageFocusMinutes,
        ),
    )
}
