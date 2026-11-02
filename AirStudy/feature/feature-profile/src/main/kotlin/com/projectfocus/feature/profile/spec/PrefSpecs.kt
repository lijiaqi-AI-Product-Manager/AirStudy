package com.projectfocus.feature.profile.spec

import com.projectfocus.core.datastore.Gender
import com.projectfocus.core.datastore.Orientation
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.ThemeMode
import com.projectfocus.feature.profile.R


/** 一行在界面上的**形状**。渲染层按这个类型决定塞什么控件。 */
sealed interface PrefControl {
    data object Switch : PrefControl
    /** 点击后弹出选择器/进入二级页 */
    data object Chevron : PrefControl
    /** 分段选择器 */
    data class Segmented(val options: List<SegmentOption>) : PrefControl
    /** 滑杆 */
    data class Slider(val min: Float, val max: Float, val step: Float, val suffix: String = "") : PrefControl
    /** 只读展示（当前值） */
    data object Value : PrefControl
}

/** 分段控件的单个选项：[labelRes] 是模块内 string 资源，[value] 是要写进 PrefKey 的值 */
data class SegmentOption(val labelRes: Int, val value: String)


data class DiscreteLevels(val labels: List<Int>)

/** 设置行定义 */
data class PrefItemSpec(
    val key: PrefKey<*>,
    /** 标题资源 id（模块内 `R.string`） */
    val titleRes: Int,
    /** 副标题（解释这一项是干什么的），可为 null */
    val subtitleRes: Int? = null,
    val control: PrefControl = PrefControl.Switch,


    val enabledWhen: PrefKey<Boolean>? = null,
    /** 实验性功能：行尾显示"实验室"标签，并允许标记为不可用 */
    val experimental: Boolean = false,




    val interactive: Boolean = true,
)

/** 设置分组 */
data class PrefSectionSpec(
    val titleRes: Int,
    val items: List<PrefItemSpec>,
)


object PrefSpecs {

    /** 运行时状态键，明确不是设置项。见类注释。 */
    val NOT_A_SETTING: Set<PrefKey<*>> = setOf(
        PrefKey.SelectedCategoryId,
        PrefKey.ActiveSessionCategoryId,
        PrefKey.SessionPaused,
        PrefKey.RecentAirportIds,
    )

    val sections: List<PrefSectionSpec> = listOf(
        PrefSectionSpec(
            titleRes = R.string.pref_section_general,
            items = listOf(
                PrefItemSpec(
                    key = PrefKey.ThemeMode,
                    titleRes = R.string.pref_theme_mode,
                    subtitleRes = R.string.pref_theme_mode_desc,
                    control = PrefControl.Segmented(
                        listOf(
                            SegmentOption(R.string.pref_theme_auto, ThemeMode.AUTO),
                            SegmentOption(R.string.pref_theme_day, ThemeMode.DAY),
                            SegmentOption(R.string.pref_theme_night, ThemeMode.NIGHT),
                        ),
                    ),
                ),
                PrefItemSpec(
                    key = PrefKey.ScreenOrientation,

                    titleRes = R.string.pref_screen_orientation,
                    subtitleRes = R.string.pref_screen_orientation_desc,
                    control = PrefControl.Segmented(
                        listOf(
                            SegmentOption(R.string.pref_orientation_auto, Orientation.AUTO),
                            SegmentOption(R.string.pref_orientation_landscape, Orientation.LANDSCAPE),
                            SegmentOption(R.string.pref_orientation_portrait, Orientation.PORTRAIT),
                        ),
                    ),
                ),
                PrefItemSpec(
                    key = PrefKey.BlacklistEnabled,
                    titleRes = R.string.pref_blacklist,
                    subtitleRes = R.string.pref_blacklist_desc,
                ),
                PrefItemSpec(
                    key = PrefKey.MaterialAlpha,
                    titleRes = R.string.pref_material_alpha,
                    subtitleRes = R.string.pref_material_alpha_desc,
                    // 0.15 ~ 0.85：低于 0.15 卡片几乎透明看不出边界，高于 0.85 就失去"玻璃"感
                    control = PrefControl.Slider(min = 0.15f, max = 0.85f, step = 0.01f),
                ),
            ),
        ),

        PrefSectionSpec(
            titleRes = R.string.pref_section_flight,
            items = listOf(
                PrefItemSpec(
                    key = PrefKey.CountUpFlight,
                    titleRes = R.string.pref_countup,
                    subtitleRes = R.string.pref_countup_desc,
                    experimental = true,
                ),
                PrefItemSpec(
                    key = PrefKey.CruiseZoom,
                    titleRes = R.string.pref_cruise_zoom,
                    subtitleRes = R.string.pref_cruise_zoom_desc,
                    // 3 ~ 18：低于 3 整条航线看不见，高于 18 瓦片加载不过来
                    control = PrefControl.Slider(min = 3f, max = 18f, step = 0.5f),
                ),
                PrefItemSpec(
                    key = PrefKey.RouteColor,
                    titleRes = R.string.pref_route_color,
                    subtitleRes = R.string.pref_route_color_desc,
                    control = PrefControl.Chevron,
                ),
            ),
        ),

        PrefSectionSpec(
            titleRes = R.string.pref_section_glass,
            items = listOf(
                PrefItemSpec(
                    key = PrefKey.GlassEnabled,
                    titleRes = R.string.pref_glass_enabled,
                    subtitleRes = R.string.pref_glass_enabled_desc,
                ),
                PrefItemSpec(
                    key = PrefKey.GlassBlurRadius,
                    titleRes = R.string.pref_glass_blur,
                    control = PrefControl.Slider(min = 0f, max = 40f, step = 1f),
                    enabledWhen = PrefKey.GlassEnabled,
                ),
                PrefItemSpec(
                    key = PrefKey.GlassRefractionHeight,
                    titleRes = R.string.pref_glass_refract_h,
                    control = PrefControl.Slider(min = 0f, max = 4f, step = 0.1f),
                    enabledWhen = PrefKey.GlassEnabled,
                ),
                PrefItemSpec(
                    key = PrefKey.GlassRefractionOffset,
                    titleRes = R.string.pref_glass_refract_off,
                    control = PrefControl.Slider(min = 0f, max = 3f, step = 0.1f),
                    enabledWhen = PrefKey.GlassEnabled,
                ),
                PrefItemSpec(
                    key = PrefKey.GlassDispersion,
                    titleRes = R.string.pref_glass_dispersion,
                    control = PrefControl.Slider(min = 0f, max = 1f, step = 0.01f),
                    enabledWhen = PrefKey.GlassEnabled,
                ),
                PrefItemSpec(
                    key = PrefKey.GlassLiquid,
                    titleRes = R.string.pref_glass_liquid,
                    control = PrefControl.Slider(min = 0f, max = 1f, step = 0.01f),
                    enabledWhen = PrefKey.GlassEnabled,
                ),
                PrefItemSpec(
                    key = PrefKey.GlassTintAlphaDay,
                    titleRes = R.string.pref_glass_tint_day,
                    control = PrefControl.Slider(min = 0f, max = 0.9f, step = 0.01f),
                    enabledWhen = PrefKey.GlassEnabled,
                ),
                PrefItemSpec(
                    key = PrefKey.GlassTintAlphaNight,
                    titleRes = R.string.pref_glass_tint_night,
                    control = PrefControl.Slider(min = 0f, max = 0.9f, step = 0.01f),
                    enabledWhen = PrefKey.GlassEnabled,
                ),
            ),
        ),

        PrefSectionSpec(
            titleRes = R.string.pref_section_haptics,
            items = listOf(
                PrefItemSpec(
                    key = PrefKey.HapticMaster,
                    titleRes = R.string.pref_haptic_master,
                    subtitleRes = R.string.pref_haptic_master_desc,
                ),
                PrefItemSpec(
                    key = PrefKey.HapticArrival,
                    titleRes = R.string.pref_haptic_arrival,
                    enabledWhen = PrefKey.HapticMaster,
                ),
                PrefItemSpec(
                    key = PrefKey.HapticTear,
                    titleRes = R.string.pref_haptic_tear,
                    enabledWhen = PrefKey.HapticMaster,
                ),
                PrefItemSpec(
                    key = PrefKey.HapticTimeline,
                    titleRes = R.string.pref_haptic_timeline,
                    enabledWhen = PrefKey.HapticMaster,
                ),
                PrefItemSpec(
                    key = PrefKey.HapticPauseHold,
                    titleRes = R.string.pref_haptic_pause,
                    enabledWhen = PrefKey.HapticMaster,
                ),
                PrefItemSpec(
                    key = PrefKey.HapticBoardingPass,
                    titleRes = R.string.pref_haptic_boarding,
                    enabledWhen = PrefKey.HapticMaster,
                ),
                PrefItemSpec(
                    key = PrefKey.WhiteNoiseEnabled,
                    titleRes = R.string.pref_white_noise,
                    subtitleRes = R.string.pref_white_noise_desc,
                ),
                PrefItemSpec(
                    key = PrefKey.WhiteNoiseEnvVolume,
                    titleRes = R.string.pref_white_noise_env_vol,
                    control = PrefControl.Slider(min = 0f, max = 1f, step = 0.01f),
                    enabledWhen = PrefKey.WhiteNoiseEnabled,
                ),
                PrefItemSpec(
                    key = PrefKey.WhiteNoisePlaneVolume,
                    titleRes = R.string.pref_white_noise_plane_vol,
                    control = PrefControl.Slider(min = 0f, max = 1f, step = 0.01f),
                    enabledWhen = PrefKey.WhiteNoiseEnabled,
                ),
            ),
        ),

        PrefSectionSpec(
            titleRes = R.string.pref_section_tts,
            items = listOf(
                PrefItemSpec(
                    key = PrefKey.TtsEnabled,
                    titleRes = R.string.pref_tts_enabled,
                    subtitleRes = R.string.pref_tts_enabled_desc,
                ),
                PrefItemSpec(
                    key = PrefKey.TtsGender,
                    titleRes = R.string.pref_tts_gender,
                    control = PrefControl.Segmented(
                        listOf(
                            SegmentOption(R.string.pref_tts_female, Gender.FEMALE),
                            SegmentOption(R.string.pref_tts_male, Gender.MALE),
                        ),
                    ),
                    enabledWhen = PrefKey.TtsEnabled,
                ),
                PrefItemSpec(
                    key = PrefKey.TtsLang1,
                    titleRes = R.string.pref_tts_lang1,
                    control = PrefControl.Chevron,
                    enabledWhen = PrefKey.TtsEnabled,
                ),
                PrefItemSpec(
                    key = PrefKey.TtsLang2,
                    titleRes = R.string.pref_tts_lang2,
                    control = PrefControl.Chevron,
                    enabledWhen = PrefKey.TtsEnabled,
                ),
            ),
        ),

        PrefSectionSpec(
            titleRes = R.string.pref_section_push,
            items = listOf(
                PrefItemSpec(
                    key = PrefKey.PushMaster,
                    titleRes = R.string.pref_push_master,
                    subtitleRes = R.string.pref_push_master_desc,
                ),
                PrefItemSpec(
                    key = PrefKey.PushDailyReport,
                    titleRes = R.string.pref_push_daily,
                    enabledWhen = PrefKey.PushMaster,
                ),
                PrefItemSpec(
                    key = PrefKey.PushWeeklyReport,
                    titleRes = R.string.pref_push_weekly,
                    enabledWhen = PrefKey.PushMaster,
                ),
                PrefItemSpec(
                    key = PrefKey.PushMonthlyReport,
                    titleRes = R.string.pref_push_monthly,
                    enabledWhen = PrefKey.PushMaster,
                ),
                PrefItemSpec(
                    key = PrefKey.PushPlanReminder,
                    titleRes = R.string.pref_push_plan,
                    enabledWhen = PrefKey.PushMaster,
                ),
            ),
        ),

        PrefSectionSpec(
            titleRes = R.string.pref_section_lab,
            items = listOf(
                PrefItemSpec(
                    key = PrefKey.ShowWsLatency,
                    titleRes = R.string.pref_ws_latency,
                    subtitleRes = R.string.pref_ws_latency_desc,
                ),
                PrefItemSpec(
                    key = PrefKey.DeveloperMode,
                    titleRes = R.string.pref_dev_mode,
                    subtitleRes = R.string.pref_dev_mode_desc,
                ),
            ),
        ),
    )

    /** 规格表里出现的全部键（含重复，供重复检查用） */
    fun allKeys(): List<PrefKey<*>> = sections.flatMap { s -> s.items.map { it.key } }

    /**
     * 某个键在规格表里的唯一位置。
     * @return 未登记时返回 null
     */
    fun specOf(key: PrefKey<*>): PrefItemSpec? =
        sections.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.key === key }







    fun dependentsOf(parent: PrefKey<*>): List<PrefItemSpec> =
        sections.asSequence().flatMap { it.items.asSequence() }.filter { it.enabledWhen === parent }.toList()







    fun diagnose(): List<String> {
        val problems = mutableListOf<String>()
        val seen = mutableMapOf<PrefKey<*>, Int>()
        sections.forEachIndexed { si, section ->
            if (section.items.isEmpty()) problems += "第 $si 组没有行"
            section.items.forEach { item ->
                seen[item.key] = (seen[item.key] ?: 0) + 1
                if (item.key in NOT_A_SETTING) {
                    problems += "${item.key.id} 被登记为「非设置项」，却出现在第 $si 组"
                }
                // 分段控件的选项至少要 2 个，否则不如用一个开关
                (item.control as? PrefControl.Segmented)?.let {
                    if (it.options.size < 2) {
                        problems += "${item.key.id} 的分段控件只有 ${it.options.size} 个选项"
                    }
                    val values = it.options.map { o -> o.value }
                    if (values.size != values.toSet().size) {
                        problems += "${item.key.id} 的分段控件有重复 value：$values"
                    }
                }
                (item.control as? PrefControl.Slider)?.let {
                    if (it.max <= it.min) {
                        problems += "${item.key.id} 的滑杆 max(${it.max}) <= min(${it.min})"
                    }
                    if (it.step <= 0f) {
                        problems += "${item.key.id} 的滑杆 step 必须为正，实际 ${it.step}"
                    }
                }
            }
        }
        seen.filterValues { it > 1 }.keys.forEach {
            problems += "${it.id} 在规格表里出现 ${seen[it]} 次"
        }
        // 依赖必须在表里
        sections.forEach { s ->
            s.items.filter { it.enabledWhen != null }.forEach { item ->
                val gate = item.enabledWhen
                if (gate != null && !seen.containsKey(gate)) {
                    problems += "${item.key.id} 依赖 ${gate?.id}，但母项不在规格表里"
                }
            }
        }
        return problems
    }

    /**
     * 检查 [PrefKey.ALL] 是否被完整覆盖。
     * @return 既不是设置项、也没被登记进 [NOT_A_SETTING] 的键（应当为空）
     */
    fun uncoveredKeys(all: List<PrefKey<*>>): List<String> {
        val covered = allKeys().toSet()
        return all.filter { it !in covered && it !in NOT_A_SETTING }.map { it.id }.sorted()
    }
}
