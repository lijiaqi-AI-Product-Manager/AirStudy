package com.projectfocus.feature.achievement.catalog

import androidx.annotation.StringRes
import com.projectfocus.feature.achievement.R




object AchievementCatalog {
    val all: List<AchievementDefinition> = listOf(
        AchievementDefinition("absolute_sky", R.string.ach_tag_absolute_sky, R.string.ach_desc_absolute_sky, AchievementRule.DistanceKm(1000000.0)),
        AchievementDefinition("city_collector", R.string.ach_tag_city_collector, R.string.ach_desc_city_collector, AchievementRule.Cities(10)),
        AchievementDefinition("cloud_warrior", R.string.ach_tag_cloud_warrior, R.string.ach_desc_cloud_warrior, AchievementRule.DistanceKm(100000.0)),
        AchievementDefinition("creation", R.string.ach_tag_creation, R.string.ach_desc_creation, AchievementRule.Unsupported),
        AchievementDefinition("daze", R.string.ach_tag_daze, R.string.ach_desc_daze, AchievementRule.Unsupported),
        AchievementDefinition("dedicated_route", R.string.ach_tag_dedicated_route, R.string.ach_desc_dedicated_route, AchievementRule.Unsupported),
        AchievementDefinition("delay_lucky", R.string.ach_tag_delay_lucky, R.string.ach_desc_delay_lucky, AchievementRule.Unsupported),
        AchievementDefinition("design", R.string.ach_tag_design, R.string.ach_desc_design, AchievementRule.Unsupported),
        AchievementDefinition("dusk_traveler", R.string.ach_tag_dusk_traveler, R.string.ach_desc_dusk_traveler, AchievementRule.Unsupported),
        AchievementDefinition("earth_wanderer", R.string.ach_tag_earth_wanderer, R.string.ach_desc_earth_wanderer, AchievementRule.Cities(30)),
        AchievementDefinition("entertainment_video", R.string.ach_tag_entertainment_video, R.string.ach_desc_entertainment_video, AchievementRule.Unsupported),
        AchievementDefinition("exploration_start", R.string.ach_tag_exploration_start, R.string.ach_desc_exploration_start, AchievementRule.Cities(5)),
        AchievementDefinition("explore_world", R.string.ach_tag_explore_world, R.string.ach_desc_explore_world, AchievementRule.Unsupported),
        AchievementDefinition("flight_expert", R.string.ach_tag_flight_expert, R.string.ach_desc_flight_expert, AchievementRule.DistanceKm(30000.0)),
        AchievementDefinition("flight_plan", R.string.ach_tag_flight_plan, R.string.ach_desc_flight_plan, AchievementRule.Completed(1)),
        AchievementDefinition("focus_master", R.string.ach_tag_focus_master, R.string.ach_desc_focus_master, AchievementRule.Unsupported),
        AchievementDefinition("focus_moment", R.string.ach_tag_focus_moment, R.string.ach_desc_focus_moment, AchievementRule.Unsupported),
        AchievementDefinition("geographer", R.string.ach_tag_geographer, R.string.ach_desc_geographer, AchievementRule.Cities(500)),
        AchievementDefinition("global_explorer", R.string.ach_tag_global_explorer, R.string.ach_desc_global_explorer, AchievementRule.Cities(200)),
        AchievementDefinition("hundred_city_walker", R.string.ach_tag_hundred_city_walker, R.string.ach_desc_hundred_city_walker, AchievementRule.Cities(100)),
        AchievementDefinition("intercontinental_traveler", R.string.ach_tag_intercontinental_traveler, R.string.ach_desc_intercontinental_traveler, AchievementRule.Cities(300)),
        AchievementDefinition("legendary_captain", R.string.ach_tag_legendary_captain, R.string.ach_desc_legendary_captain, AchievementRule.DistanceKm(500000.0)),
        AchievementDefinition("longhaul_endurance_king", R.string.ach_tag_longhaul_endurance_king, R.string.ach_desc_longhaul_endurance_king, AchievementRule.AverageFocusMinutes(300)),
        AchievementDefinition("lunch_time_flyer", R.string.ach_tag_lunch_time_flyer, R.string.ach_desc_lunch_time_flyer, AchievementRule.Unsupported),
        AchievementDefinition("meditation_relax", R.string.ach_tag_meditation_relax, R.string.ach_desc_meditation_relax, AchievementRule.Unsupported),
        AchievementDefinition("morning_walker", R.string.ach_tag_morning_walker, R.string.ach_desc_morning_walker, AchievementRule.Unsupported),
        AchievementDefinition("never_go_home", R.string.ach_tag_never_go_home, R.string.ach_desc_never_go_home, AchievementRule.Cities(50)),
        AchievementDefinition("newbie_pilot", R.string.ach_tag_newbie_pilot, R.string.ach_desc_newbie_pilot, AchievementRule.Completed(1)),
        AchievementDefinition("night_navigator", R.string.ach_tag_night_navigator, R.string.ach_desc_night_navigator, AchievementRule.Unsupported),
        AchievementDefinition("noon_cruise", R.string.ach_tag_noon_cruise, R.string.ach_desc_noon_cruise, AchievementRule.Unsupported),
        AchievementDefinition("ocd_captain", R.string.ach_tag_ocd_captain, R.string.ach_desc_ocd_captain, AchievementRule.Unsupported),
        AchievementDefinition("overnight_runner", R.string.ach_tag_overnight_runner, R.string.ach_desc_overnight_runner, AchievementRule.Unsupported),
        AchievementDefinition("programming", R.string.ach_tag_programming, R.string.ach_desc_programming, AchievementRule.Unsupported),
        AchievementDefinition("reading", R.string.ach_tag_reading, R.string.ach_desc_reading, AchievementRule.Unsupported),
        AchievementDefinition("reliable_role", R.string.ach_tag_reliable_role, R.string.ach_desc_reliable_role, AchievementRule.Unsupported),
        AchievementDefinition("route_pioneer", R.string.ach_tag_route_pioneer, R.string.ach_desc_route_pioneer, AchievementRule.Cities(20)),
        AchievementDefinition("short_trip_quickhand", R.string.ach_tag_short_trip_quickhand, R.string.ach_desc_short_trip_quickhand, AchievementRule.AverageFocusMinutesMax(90)),
        AchievementDefinition("sky_runner", R.string.ach_tag_sky_runner, R.string.ach_desc_sky_runner, AchievementRule.TotalFocusMinutes(6000)),
        AchievementDefinition("sleep", R.string.ach_tag_sleep, R.string.ach_desc_sleep, AchievementRule.Unsupported),
        AchievementDefinition("soaring_up", R.string.ach_tag_soaring_up, R.string.ach_desc_soaring_up, AchievementRule.DistanceKm(200000.0)),
        AchievementDefinition("study", R.string.ach_tag_study, R.string.ach_desc_study, AchievementRule.Unsupported),
        AchievementDefinition("sunrise_chaser", R.string.ach_tag_sunrise_chaser, R.string.ach_desc_sunrise_chaser, AchievementRule.Unsupported),
        AchievementDefinition("thinking", R.string.ach_tag_thinking, R.string.ach_desc_thinking, AchievementRule.Unsupported),
        AchievementDefinition("thousand_city_king", R.string.ach_tag_thousand_city_king, R.string.ach_desc_thousand_city_king, AchievementRule.Cities(1000)),
        AchievementDefinition("walk_in_clouds", R.string.ach_tag_walk_in_clouds, R.string.ach_desc_walk_in_clouds, AchievementRule.TotalFocusMinutes(60000)),
        AchievementDefinition("work", R.string.ach_tag_work, R.string.ach_desc_work, AchievementRule.Unsupported),
        AchievementDefinition("world_traveler", R.string.ach_tag_world_traveler, R.string.ach_desc_world_traveler, AchievementRule.TotalFocusMinutes(30000)),
    )

    val byId: Map<String, AchievementDefinition> = all.associateBy { it.id }
}
