package com.projectfocus.widget.glance

import androidx.compose.ui.graphics.Color






































object WidgetPalette {

    //   （fix01 对调过日夜极性），WidgetPaletteTest 其实早就红了，只是测试源集从没编译跑过。


    /** core-ui token: bg_base = #FFF6F6FA */
    val BgBase = Color(0xFFF6F6FA)

    /** core-ui token: bg_elevated = #FFFFFFFF（进度条轨道、次级容器底） */
    val BgElevated = Color(0xFFFFFFFF)

    /** core-ui token: text_primary = #FF17171C */
    val TextPrimary = Color(0xFF17171C)

    /** core-ui token: text_secondary = #FF5C5C66 */
    val TextSecondary = Color(0xFF5C5C66)

    /** core-ui token: text_tertiary = #FF80808C */
    val TextTertiary = Color(0xFF80808C)







    val AccentPrimary = Color(0xFF0069D9)

    /** core-ui token: semantic_warning = #FFE08A00（暂停态 / 进行中） */
    val Warning = Color(0xFFE08A00)
}
