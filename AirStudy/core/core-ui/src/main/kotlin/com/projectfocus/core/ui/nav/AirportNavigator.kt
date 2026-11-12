package com.projectfocus.core.ui.nav

import android.net.Uri
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController


object AirportNavigator {

    const val SCHEME: String = "projectfocus"
    const val HOST: String = "airport"

    fun uri(code: String): Uri =
        Uri.parse("$SCHEME://$HOST/${Uri.encode(code)}")

    fun options(controller: NavController): NavOptions =
        options(controller.currentDestination?.id)

    /**
     * 纯参数版本，供 Robolectric 直接断言 launchSingleTop/popUpTo；
     * 传 null 表示没有当前目的地，不设 popUpTo。
     */
    fun options(currentId: Int?): NavOptions =
        NavOptions.Builder()
            .setLaunchSingleTop(true)
            .apply {
                currentId?.let { setPopUpTo(it, false) }
            }
            .build()

    fun open(fragment: Fragment, code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        val controller = runCatching { fragment.findNavController() }.getOrNull() ?: return false
        return runCatching {
            controller.navigate(uri(code), options(controller))
            true
        }.getOrDefault(false)
    }
}
