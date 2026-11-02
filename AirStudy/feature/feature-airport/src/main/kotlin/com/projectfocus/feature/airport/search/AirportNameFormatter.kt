package com.projectfocus.feature.airport.search

import com.projectfocus.core.model.airport.Airport




























object AirportNameFormatter {
















    fun primary(airport: Airport, preferChinese: Boolean): String =
        if (preferChinese) airport.nameZh?.takeIf { it.isNotBlank() } ?: airport.name
        else airport.name

    /** 副标题里的城市名。同上规则 */
    fun city(airport: Airport, preferChinese: Boolean): String? =
        if (preferChinese) airport.cityZh?.takeIf { it.isNotBlank() } ?: airport.city?.takeIf { it.isNotBlank() }
        else airport.city?.takeIf { it.isNotBlank() }


    fun subtitle(airport: Airport, preferChinese: Boolean): String {
        val c = city(airport, preferChinese)
        val country = airport.country?.takeIf { it.isNotBlank() }
        return listOfNotNull(c, country).joinToString(" · ")
    }

    /**
     * 单行完整标签：`PEK · 北京首都国际机场`。
     *
     * 用于「已选目的地」这种空间充足但需要一眼确认的场合。
     */
    fun label(airport: Airport, preferChinese: Boolean): String =
        "${airport.displayCode} · ${primary(airport, preferChinese)}"
}
