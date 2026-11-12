package com.projectfocus.app.route

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.projectfocus.feature.airport.ui.AirportSearchFragment
import com.projectfocus.feature.airport.ui.AirportReferencePoint
import com.projectfocus.feature.airport.ui.AirportSearchReferenceArgs
import com.projectfocus.feature.record.ui.RecordListFragment
import com.projectfocus.feature.record.list.RecordItem










object RouteArgs {
    /** 页面用途，决定"操作完之后去哪" */
    const val ARG_MODE = "arg_mode"

    /** Optional route-opposite coordinates; missing/partial/invalid is no reference, never 0,0. */
    const val ARG_REFERENCE_LATITUDE = AirportSearchReferenceArgs.LATITUDE
    const val ARG_REFERENCE_LONGITUDE = AirportSearchReferenceArgs.LONGITUDE

    /** 机场搜索：选目的地 */
    const val MODE_PICK_DESTINATION = "pick_destination"

    /** 机场搜索：选出发地 */
    const val MODE_PICK_ORIGIN = "pick_origin"

    /** 飞行记录：纯浏览 */
    const val MODE_HISTORY = "history"
}

















































object FragmentRoutes {


    private fun install(
        fm: FragmentManager,
        @IdRes containerId: Int,
        fragment: Fragment,
        args: Bundle?,
    ) {
        fragment.arguments = args

        //   用 Activity 的会把真页面挂到 NavHost 的容器里 ——
        //   壳自己的 FrameLayout 永远是空的，屏幕一片空白。
        fm.commit {
            replace(containerId, fragment)
        }
    }

    // ─────────────────────────────────────────────────────────
    // 机场搜索
    // ─────────────────────────────────────────────────────────







    fun installAirportSearch(
        fm: FragmentManager,
        @IdRes containerId: Int,
        mode: String,
        onPicked: (iataCode: String, origin: Boolean) -> Unit,
        onBack: () -> Unit,
        referencePoint: AirportReferencePoint? = null,
    ): AirportSearchFragment {
        val fragment = AirportSearchFragment().apply {
            onAirportSelected = { airport ->

                //
                //   编译期就拦住了：`Argument type mismatch: actual type is
                //   'String?', but 'String' was expected`。

                //     全球 8 万多个机场里有大量小型机场**没有 IATA 码**
                //     （只有 ICAO 四字码，如 "ZBAD"），还有少数只有名字。
                //   → `Airport.displayCode` 已经编好了三级降级：
                //      IATA → ICAO → name
                //

                //     没有 IATA 码的小机场时会**直接崩**（KotlinNullPointerException）。
                //     而搜索索引里确实收录了这类机场 —— 崩溃路径真实可达。
                onPicked(airport.displayCode, mode == RouteArgs.MODE_PICK_ORIGIN)
            }
            this.onBack = onBack
        }
        install(fm, containerId, fragment, Bundle().apply {
            putString(RouteArgs.ARG_MODE, mode)
            AirportSearchReferenceArgs.writeTo(this, referencePoint)
        })
        return fragment
    }

    // ─────────────────────────────────────────────────────────
    // 飞行记录
    // ─────────────────────────────────────────────────────────







    fun installRecordList(
        fm: FragmentManager,
        @IdRes containerId: Int,
        onOpen: (RecordItem) -> Unit,
        onBack: () -> Unit,
    ): RecordListFragment {
        val fragment = RecordListFragment().apply {
            onRecordOpen = onOpen
            this.onBack = onBack
        }
        install(fm, containerId, fragment, Bundle().apply {
            putString(RouteArgs.ARG_MODE, RouteArgs.MODE_HISTORY)
        })
        return fragment
    }
}
