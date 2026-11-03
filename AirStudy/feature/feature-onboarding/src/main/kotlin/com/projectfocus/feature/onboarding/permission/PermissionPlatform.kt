package com.projectfocus.feature.onboarding.permission

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


interface PermissionPlatform {

    /** 读一项权限的当前状态 */
    fun status(item: PermissionItem): PermissionStatus











    fun settingsIntent(item: PermissionItem): Intent?

    /** 是否需要走运行时权限请求（目前只有 Android 13+ 的通知） */
    fun needsRuntimeRequest(item: PermissionItem): Boolean






    fun isPermanentlyDenied(item: PermissionItem): Boolean
}














@Singleton
class AndroidPermissionPlatform @Inject constructor(
    @ApplicationContext private val context: Context,
) : PermissionPlatform {

    override fun status(item: PermissionItem): PermissionStatus = when (item) {
        PermissionItem.USAGE_STATS ->
            if (hasUsageStats()) PermissionStatus.GRANTED else PermissionStatus.DENIED

        PermissionItem.OVERLAY ->
            if (runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }

        PermissionItem.NOTIFICATION ->
            if (runCatching {
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                }.getOrDefault(false)
            ) {
                PermissionStatus.GRANTED
            } else if (isPermanentlyDenied(PermissionItem.NOTIFICATION)) {
                PermissionStatus.PERMANENTLY_DENIED
            } else {
                PermissionStatus.DENIED
            }

        PermissionItem.BATTERY_OPTIMIZATION ->
            if (isIgnoringBatteryOptimizations()) PermissionStatus.GRANTED
            else PermissionStatus.DENIED
    }

    override fun settingsIntent(item: PermissionItem): Intent? = when (item) {
        // 使用情况访问：只有 SETTINGS 页，没有专用 Intent。

        //   但它在部分 ROM 上会忽略参数、落到列表页顶部 —— 用户还得自己找。

        PermissionItem.USAGE_STATS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // 悬浮窗：带 package 能直接落到「本应用」的开关页，少两次点击
        PermissionItem.OVERLAY -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // 通知：
        // · Android 13+ → 返回 null，由 Fragment 用 ActivityResult 发起运行时请求
        //   （因为「拒绝两次后永久拒绝」时，运行时请求会立即返回拒绝而不弹窗，
        //     此时 Fragment 需要改跳设置页 —— 见 isPermanentlyDenied）
        // · Android 12- → 没有运行时权限，直接跳通知设置
        PermissionItem.NOTIFICATION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            null
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 电池优化：用 REQUEST_IGNORE 会直接弹系统确认框（一键允许），
        // 比跳列表页找应用快得多。代价是 Google Play 对它的使用有政策要求，

        PermissionItem.BATTERY_OPTIMIZATION -> Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }


    //   调用点拿它当守卫，就能直接写 API 33 的符号而不必再写一遍 SDK_INT。
    @androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    override fun needsRuntimeRequest(item: PermissionItem): Boolean =
        item == PermissionItem.NOTIFICATION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

















    override fun isPermanentlyDenied(item: PermissionItem): Boolean {
        if (item != PermissionItem.NOTIFICATION) return false
        // Android 13+ 才存在「运行时权限被永久拒绝」这个概念
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        // 平台层无法直接判断，返回 false；精确判定交给 ViewModel 的会话记录
        return false
    }

    // ─────────────────────────────────────────────────────────
    // 单项实现
    // ─────────────────────────────────────────────────────────



















    private fun hasUsageStats(): Boolean {
        val appOpsOk = runCatching { checkUsageStatsOpIsAllowed() }.getOrDefault(false)
        if (appOpsOk) return true

        // 反射说不允许时，再用官方 API 复核一次（反向兜底）
        return runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - 24L * 60 * 60 * 1000

            //
            //   lint 看到 `queryUsageStats` 就要求 `PACKAGE_USAGE_STATS` 被声明
            //   并**在调用点做检查**。这条规则在普通运行时权限上是合理的，
            //   但对 PACKAGE_USAGE_STATS 完全不适用：
            //
            //     · 它**没有** `checkSelfPermission` 这条通路 —— 是 appop 权限，
            //       只能靠 `AppOpsManager` 或「试着查一次」判断；
            //     · 它**不抛** SecurityException：没权限时直接返回空列表。
            //
            //   → 这里的「调用」本身**就是**权限检查（返回值非空 = 已授予）。
            //     lint 看不到这个语义，所以必须就地抑制并写清理由。
            //

            //     抑制注释必须紧跟调用点，且在注释里写清"为什么这里不需要检查" ——
            //     否则下一个人会以为这是在掩盖问题。**
            @SuppressLint("MissingPermission")
            usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY, begin, end,
            ).orEmpty().isNotEmpty()
        }.getOrDefault(false)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

























    private fun checkUsageStatsOpIsAllowed(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkOpQ(appOps)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                OP_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun checkOpQ(appOps: android.app.AppOpsManager): Int = appOps.unsafeCheckOpNoThrow(
        OP_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName,
    )

    private companion object {
        /** AppOps 的 op 名。写常量而不是内联字符串，是为了只错一次的机会。 */
        const val OP_GET_USAGE_STATS = "android:get_usage_stats"
    }
}
