package com.projectfocus.service.focus.monitor

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.net.toUri


object FocusPermissions {

    /** 权限项。顺序即引导页的展示顺序（从「不授权就完全不能用」到「体验优化」）。 */
    enum class Item {
        /** 使用情况访问 —— **核心**，不给就完全无法检测前台应用 */
        USAGE_STATS,

        /** 悬浮窗 —— 用于「立刻弹出遮挡页」；没有它只能靠 Activity 拉起，会有延迟 */
        OVERLAY,

        /** 忽略电池优化 —— 不给的话后台 10 分钟后被冻结，屏蔽静默失效 */
        BATTERY_OPTIMIZATION,

        /** 通知 —— 必须能发通知，否则前台服务会被系统降级成普通服务并被杀 */
        NOTIFICATION,
    }










    fun hasUsageStats(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        // MODE_ALLOWED = 用户手动开启；MODE_DEFAULT 需要回落到「是否被系统默认允许」
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> hasUsageStatsByDefault(context)
            else -> false
        }
    }










    private fun hasUsageStatsByDefault(context: Context): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return false
        val end = System.currentTimeMillis()
        val start = end - 60_000L
        val stats = runCatching { usm.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end,
        ) }.getOrNull()
        return !stats.isNullOrEmpty()
    }

    /** 悬浮窗权限是否已授予 */
    fun hasOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)








    fun isIgnoringBatteryOptimizations(context: Context): Boolean {

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 通知权限是否已授予（API 33 以下是编译期权限，恒 true） */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun isGranted(context: Context, item: Item): Boolean = when (item) {
        Item.USAGE_STATS -> hasUsageStats(context)
        Item.OVERLAY -> hasOverlay(context)
        Item.BATTERY_OPTIMIZATION -> isIgnoringBatteryOptimizations(context)
        Item.NOTIFICATION -> hasNotificationPermission(context)
    }









    fun isCoreGranted(context: Context): Boolean =
        hasUsageStats(context) && hasOverlay(context)

    // ==================================================================
    // 跳转
    // ==================================================================


















    fun usageAccessIntent(context: Context): Intent {
        val candidates = buildList {
            add(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            when (Build.MANUFACTURER.lowercase()) {
                "xiaomi", "redmi" -> add(
                    Intent().setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    ),
                )
                "huawei", "honor" -> add(
                    Intent().setClassName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.mainscreen.MainScreenActivity",
                    ),
                )
                "oppo", "realme", "oneplus" -> add(
                    Intent().setClassName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.ui.ManagePermissionsActivity",
                    ),
                )
                "vivo", "iqoo" -> add(
                    Intent().setClassName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.PurviewTabActivity",
                    ),
                )
            }
        }

        //
        //   Android 11（API 30）起有包可见性限制：不声明 `<queries>`，
        //   `resolveActivity` 对**第三方包**一律返回 null。
        //
        //   而这里要探测的恰恰全是第三方 ROM 的系统设置页
        //   （com.miui.securitycenter / com.huawei.systemmanager / ...）。
        //   没有 `<queries>` 的话，**每一次探测都返回 null** →
        //   这段「按 ROM 分派」的代码在所有小米/华为/OPPO/vivo 上
        //   **完全失效**，永远落到最后的标准 Action。
        //
        //   → 已在 `service-focus/src/main/AndroidManifest.xml` 里
        //     为这 5 个包声明 `<queries>`。这一段注释与那份 manifest 是一对，
        //     改一边必须改另一边。
        return candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
            // 全部落空时返回标准 Action（哪怕会抛，也让调用方 try/catch 并给手动引导）
            ?: Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    /** 悬浮窗权限页 */
    fun overlayIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())










    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 应用通知设置页 */
    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
            Settings.EXTRA_APP_PACKAGE,
            context.packageName,
        )

    fun settingsIntent(context: Context, item: Item): Intent = when (item) {
        Item.USAGE_STATS -> usageAccessIntent(context)
        Item.OVERLAY -> overlayIntent(context)
        Item.BATTERY_OPTIMIZATION -> batteryOptimizationIntent(context)
        Item.NOTIFICATION -> notificationSettingsIntent(context)
    }
}
