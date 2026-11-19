package com.projectfocus.app.shield

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.projectfocus.service.focus.monitor.DeviceShieldProtection
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ShieldApp(val packageName: String, val name: String, val installed: Boolean = true)

/** Only applications with a launcher entry are queried in the current Android profile. */
internal class InstalledShieldApps(context: Context) {
    private val app = context.applicationContext
    private val packages = app.packageManager

    @Suppress("DEPRECATION")
    suspend fun list(locale: Locale): List<ShieldApp> = withContext(Dispatchers.IO) {
        val protectedPackages = DeviceShieldProtection.protectedPackages(app)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val entries = if (Build.VERSION.SDK_INT >= 33) {
            packages.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packages.queryIntentActivities(launcher, 0)
        }
        val apps = entries.mapNotNull { entry ->
            val activity = entry.activityInfo ?: return@mapNotNull null
            val application = activity.applicationInfo ?: return@mapNotNull null
            val name = activity.packageName ?: return@mapNotNull null
            if (!activity.enabled || !application.enabled ||
                DeviceShieldProtection.isProtected(name, protectedPackages)) return@mapNotNull null
            val label = application.loadLabel(packages).toString().trim().ifEmpty { name }
            ShieldApp(name, label)
        }.distinctBy(ShieldApp::packageName)
        apps.sortedWith(compareBy(Collator.getInstance(locale), ShieldApp::name).thenBy(ShieldApp::packageName))
    }
}
