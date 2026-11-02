package com.projectfocus.core.ui.nav

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavOptions

/** 模式/首页共用同一个准备页，feature不依赖app资源ID。 */
object FlightPreparationNavigator {
    fun uri(origin: String?, destination: String?): Uri = Uri.Builder()
        .scheme("projectfocus")
        .authority("prepare")
        .appendQueryParameter("origin", origin.orEmpty())
        .appendQueryParameter("destination", destination.orEmpty())
        .build()

    fun open(nav: NavController, origin: String?, destination: String?) {
        nav.navigate(uri(origin, destination), NavOptions.Builder().setLaunchSingleTop(true).build())
    }
}
