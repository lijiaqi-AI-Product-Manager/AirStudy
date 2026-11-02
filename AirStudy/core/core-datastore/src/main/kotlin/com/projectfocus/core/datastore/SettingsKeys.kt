package com.projectfocus.core.datastore


/** 主题模式。存字符串而不是枚举序号 —— 枚举重排会让老用户的设置变成别的模式。 */
object ThemeMode {
    const val AUTO = "auto"
    const val DAY = "day"
    const val NIGHT = "night"
    val ALL = setOf(AUTO, DAY, NIGHT)
}


object Orientation {
    const val AUTO = "auto"
    const val PORTRAIT = "portrait"
    const val LANDSCAPE = "landscape"
    val ALL = setOf(AUTO, LANDSCAPE, PORTRAIT)
}

/** TTS 音色 */
object Gender {
    const val FEMALE = "female"
    const val MALE = "male"
    val ALL = setOf(FEMALE, MALE)
}

sealed class PrefKey<T>(



















    val id: String,
    /** DataStore 里的实际键名。命名统一 `pf_` 前缀，避免与第三方 SDK 撞名。 */
    val name: String,
    val default: T,


    val legacyNames: List<String> = emptyList(),
) {
    data object BlacklistEnabled : PrefKey<Boolean>(

        id = "PrefKey.BlacklistEnabled",
        "pf_blacklist_enabled", true, listOf("isBlacklistEnabled"))


    data object SelectedCategoryId : PrefKey<Long>(
        id = "PrefKey.SelectedCategoryId",
        "pf_selected_category_id", -1L, emptyList())

    data object ThemeMode : PrefKey<String>(
        id = "PrefKey.ThemeMode",
        "pf_theme_mode", "auto", listOf("flight_settings_style"))

    data object MaterialAlpha : PrefKey<Float>(
        id = "PrefKey.MaterialAlpha",
        "pf_material_alpha", 0.42f)

    // ---- 飞行偏好 ----
    data object CountUpFlight : PrefKey<Boolean>(
        id = "PrefKey.CountUpFlight",
        "pf_countup_flight", false, listOf("flight_settings_countup_flight"))
    data object CruiseZoom : PrefKey<Float>(
        id = "PrefKey.CruiseZoom",
        "pf_cruise_zoom", 12.0f, listOf("flight_settings_cruise_zoom"))
    data object RouteColor : PrefKey<Int>(
        id = "PrefKey.RouteColor",
        "pf_route_color", 0xFF0A59F7.toInt(), listOf("flight_settings_route_color"))
    data object ScreenOrientation : PrefKey<String>(
        id = "PrefKey.ScreenOrientation",
        "pf_screen_orientation", Orientation.AUTO)


    data object GlassEnabled : PrefKey<Boolean>(
        id = "PrefKey.GlassEnabled",
        "pf_glass_enabled", true, listOf("flight_settings_liquid_glass"))
    data object GlassBlurRadius : PrefKey<Float>(
        id = "PrefKey.GlassBlurRadius",
        "pf_glass_blur", 18f, listOf("flight_settings_liquid_glass_slider_blur_frost"))
    data object GlassRefractionHeight : PrefKey<Float>(
        id = "PrefKey.GlassRefractionHeight",
        "pf_glass_refract_h", 1.6f, listOf("flight_settings_liquid_glass_slider_refraction_height"))
    data object GlassRefractionOffset : PrefKey<Float>(
        id = "PrefKey.GlassRefractionOffset",
        "pf_glass_refract_off", 0.9f, listOf("flight_settings_liquid_glass_slider_refraction_offset"))
    data object GlassDispersion : PrefKey<Float>(
        id = "PrefKey.GlassDispersion",
        "pf_glass_dispersion", 0.35f, listOf("flight_settings_liquid_glass_slider_dispersion"))
    data object GlassLiquid : PrefKey<Float>(
        id = "PrefKey.GlassLiquid",
        "pf_glass_liquid", 0.5f, listOf("flight_settings_liquid_glass_slider_blur_liquid"))
    data object GlassTintAlphaDay : PrefKey<Float>(
        id = "PrefKey.GlassTintAlphaDay",
        "pf_glass_tint_day", 0.42f, listOf("flight_settings_liquid_glass_slider_tint_alpha_day"))
    data object GlassTintAlphaNight : PrefKey<Float>(
        id = "PrefKey.GlassTintAlphaNight",
        "pf_glass_tint_night", 0.14f, listOf("flight_settings_liquid_glass_slider_tint_alpha_night"))

    // ---- 触感与声音 ----
    data object HapticMaster : PrefKey<Boolean>(
        id = "PrefKey.HapticMaster",
        "pf_haptic_master", true, listOf("flight_settings_haptic_master"))
    data object HapticArrival : PrefKey<Boolean>(
        id = "PrefKey.HapticArrival",
        "pf_haptic_arrival", true, listOf("flight_settings_haptic_arrival"))
    data object HapticTear : PrefKey<Boolean>(
        id = "PrefKey.HapticTear",
        "pf_haptic_tear", true, listOf("flight_settings_haptic_tear"))
    data object HapticTimeline : PrefKey<Boolean>(
        id = "PrefKey.HapticTimeline",
        "pf_haptic_timeline", true, listOf("flight_settings_haptic_timeline"))
    data object HapticPauseHold : PrefKey<Boolean>(
        id = "PrefKey.HapticPauseHold",
        "pf_haptic_pause_hold", true, listOf("flight_settings_haptic_pause_hold"))
    data object HapticBoardingPass : PrefKey<Boolean>(
        id = "PrefKey.HapticBoardingPass",
        "pf_haptic_boarding", true, listOf("flight_settings_haptic_boarding_pass"))
    data object WhiteNoiseEnabled : PrefKey<Boolean>(
        id = "PrefKey.WhiteNoiseEnabled",
        "pf_white_noise", false, listOf("flight_settings_white_noise_enable"))
    data object WhiteNoiseEnvVolume : PrefKey<Float>(
        id = "PrefKey.WhiteNoiseEnvVolume",
        "pf_white_noise_env_vol", 0.5f, listOf("flight_settings_white_noise_env_vol"))
    data object WhiteNoisePlaneVolume : PrefKey<Float>(
        id = "PrefKey.WhiteNoisePlaneVolume",
        "pf_white_noise_plane_vol", 0.5f, listOf("flight_settings_white_noise_plane_vol"))

    // ---- 语音播报 ----
    data object TtsEnabled : PrefKey<Boolean>(
        id = "PrefKey.TtsEnabled",
        "pf_tts_enabled", true, listOf("flight_settings_tts_title"))
    data object TtsGender : PrefKey<String>(
        id = "PrefKey.TtsGender",
        "pf_tts_gender", "female", listOf("flight_settings_voice_gender_title"))
    data object TtsLang1 : PrefKey<String>(
        id = "PrefKey.TtsLang1",
        "pf_tts_lang1", "cn", listOf("flight_settings_voice_lang1_title"))
    data object TtsLang2 : PrefKey<String>(
        id = "PrefKey.TtsLang2",
        "pf_tts_lang2", "en", listOf("flight_settings_voice_lang2_title"))


    //

    //
    //   Service 会被系统杀掉（低内存、厂商清理、用户手动停止）。
    //   被杀之后重启，如果「当前是否在飞」只存在内存，重启后就是 false ——
    //   于是用户正在飞行中，屏蔽引擎悄悄失效了，而界面还显示在飞。


    //

    //


    //
    //   `SelectedCategoryId` 已经声明了 `active_flight_category_id` 为它的 legacyName。

    //

    //       「当前正在飞的分类」——对 ActiveSessionCategoryId 是对的，
    //       对 SelectedCategoryId（首页上次选中的分类）是错的。
    //     · 写：迁移工具一旦「读 legacy → 写 primary」，
    //       会把同一个值写进两个 pf_* 键，之后两者永远耦合。
    //
    //   PrefKeyRegistryTest 的「没有任何键名重复」用例正是为这种场景写的：
    //   它无法判断语义谁对，只能拦住「一个 legacy 名被两处认领」这个结构错误。

    //   所以 legacyName 归 ActiveSessionCategoryId 更合理。
    //
    //   ⇒ 因此从 SelectedCategoryId 里移除这个 legacyName（见那边的注释）。

    /** 当前专注会话归属的分类 id；-1 表示没有进行中的会话 */
    data object ActiveSessionCategoryId : PrefKey<Long>(
        id = "PrefKey.ActiveSessionCategoryId",
        "pf_active_session_category_id", -1L, listOf("active_flight_category_id"))

    /** 当前会话是否暂停（暂停时屏蔽引擎不拦截） */
    data object SessionPaused : PrefKey<Boolean>(
        id = "PrefKey.SessionPaused",
        "pf_session_paused", false, listOf("session_pause"))

    // ---- 推送 ----
    data object PushMaster : PrefKey<Boolean>(
        id = "PrefKey.PushMaster",
        "pf_push_master", true, listOf("flight_settings_push_master_switch"))
    data object PushDailyReport : PrefKey<Boolean>(
        id = "PrefKey.PushDailyReport",
        "pf_push_daily", true, listOf("flight_settings_push_focus_daily_report"))
    data object PushWeeklyReport : PrefKey<Boolean>(
        id = "PrefKey.PushWeeklyReport",
        "pf_push_weekly", true, listOf("flight_settings_push_focus_weekly_report"))
    data object PushMonthlyReport : PrefKey<Boolean>(
        id = "PrefKey.PushMonthlyReport",
        "pf_push_monthly", false, listOf("flight_settings_push_focus_monthly_report"))
    data object PushPlanReminder : PrefKey<Boolean>(
        id = "PrefKey.PushPlanReminder",
        "pf_push_plan", true, listOf("flight_settings_push_flight_plan_prepare_reminder"))

    // ---- 开发者/调试 ----
    data object DeveloperMode : PrefKey<Boolean>(
        id = "PrefKey.DeveloperMode",
        "pf_dev_mode", false)
    data object ShowWsLatency : PrefKey<Boolean>(
        id = "PrefKey.ShowWsLatency",
        "pf_show_ws_latency", false, listOf("flight_settings_websocket_latency_delay"))


































    data object RecentAirportIds : PrefKey<Set<String>>(
        id = "PrefKey.RecentAirportIds",
        "pf_recent_airport_ids", emptySet())

    companion object {
        /** 全部键的清单。用于「清空偏好」「导出诊断包」 */













































        val ALL: List<PrefKey<*>> by lazy {
            arrayListOf(
                BlacklistEnabled, SelectedCategoryId, ThemeMode, MaterialAlpha,
                CountUpFlight, CruiseZoom, RouteColor, ScreenOrientation,
                GlassEnabled, GlassBlurRadius, GlassRefractionHeight, GlassRefractionOffset,
                GlassDispersion, GlassLiquid, GlassTintAlphaDay, GlassTintAlphaNight,
                HapticMaster, HapticArrival, HapticTear, HapticTimeline, HapticPauseHold, HapticBoardingPass,
                WhiteNoiseEnabled, WhiteNoiseEnvVolume, WhiteNoisePlaneVolume,
                TtsEnabled, TtsGender, TtsLang1, TtsLang2,
                ActiveSessionCategoryId, SessionPaused,
                PushMaster, PushDailyReport, PushWeeklyReport, PushMonthlyReport, PushPlanReminder,
                DeveloperMode, ShowWsLatency, RecentAirportIds,
            )
        }




























        fun missingFromAll(): List<String> {
            val registered = ALL.map { it.id }.toSet()
            // `data object` 编译成 PrefKey 的嵌套类（`PrefKey$Xxx`）。

            //   所以下面必须按"是不是 PrefKey 子类"过滤 —— Companion 会混进来。
            val defined = PrefKey::class.java.declaredClasses
                .filter { PrefKey::class.java.isAssignableFrom(it) }
                .map { "PrefKey.${it.simpleName}" }
            return defined.filter { it !in registered }.sorted()
        }

        fun duplicates(): Map<String, List<String>> {

            //   （极罕见，通常是编译器/序列化产生的），下面 key.name 会 NPE。
            //   一个「自检函数」自己崩掉是最坏的情况 —— 它应该在发现问题时
            //   返回一个能读的报告，而不是抛异常。
            val clean = ALL.filterNotNull()
            val nulls = ALL.size - clean.size
            val seen = mutableMapOf<String, MutableList<String>>()
            if (nulls > 0) seen["<NULL>"] = mutableListOf("COUNT=$nulls")
            clean.forEach { key ->

                val label = key.id
                seen.getOrPut(key.name) { mutableListOf() }.add(label)
                key.legacyNames.forEach { legacy ->
                    seen.getOrPut(legacy) { mutableListOf() }.add("$label(legacy)")
                }
            }
            return seen.filterValues { it.size > 1 }
        }
    }
}
