package com.projectfocus.core.database

import java.io.File

/**
 * 测试用的资产定位器。
 *
 * Gradle 跑 unit test 时工作目录是本模块（`core/core-database`），
 * IDE 里有时是仓库根目录。两种都试，找不到就明确失败 ——
 * 而不是让测试悄悄跳过（跳过等于没测）。
 */
internal object TestAssets {

    fun airportCsv(): File {
        val direct = File("src/main/assets/airports/airports.csv")
        if (direct.isFile) return direct

        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "core/core-database/src/main/assets/airports/airports.csv")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error(
            "airport asset not found. Run `python tools/airports/build_airports_asset.py` " +
                "from the repository root first.",
        )
    }
}
