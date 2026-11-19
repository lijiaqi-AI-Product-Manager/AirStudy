package com.projectfocus.service.focus.monitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager

/** Keep core system tasks available even if an earlier selection included their apps. */
object DeviceShieldProtection {
    @Suppress("DEPRECATION")
    fun protectedPackages(context: Context): Set<String> {
        val dialer = try {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        } catch (_: SecurityException) {
            null
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return FocusMonitorConfig.DEFAULT_WHITELIST + setOfNotNull(context.packageName, dialer, launcher)
    }

    fun isProtected(packageName: String, protectedPackages: Set<String>): Boolean =
        packageName in protectedPackages || FocusMonitorConfig.DEFAULT_WHITELIST_PREFIXES.any { packageName.startsWith(it) }
}
