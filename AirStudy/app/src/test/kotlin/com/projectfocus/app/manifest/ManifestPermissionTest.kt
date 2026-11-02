package com.projectfocus.app.manifest

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.service.focus.monitor.FocusMonitorService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestPermissionTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()


    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val pm: PackageManager get() = context.packageManager

    private val requested: Set<String> by lazy {
        val info: PackageInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        info.requestedPermissions.orEmpty().toSet()
    }



























    private val mergedManifestXml: String by lazy {
        com.google.common.truth.Truth.assertThat(mergedManifestFile.exists())
            .isTrue() // 找不到文件时先给出可读的断言失败，而不是 FileNotFound
        mergedManifestFile.readText()
    }

    private val mergedManifestFile: java.io.File by lazy {
        val candidates = listOf(
            "build/intermediates/merged_manifest/debug/processDebugManifest/AndroidManifest.xml",
            "build/intermediates/merged_manifest/debug/AndroidManifest.xml",
            "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
            "build/intermediates/merged_manifests/debug/AndroidManifest.xml",
            "build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml",
        )

        //   但 Robolectric 可能改变它 —— 用 user.dir + 逐级向上兜底，两条都试。
        val roots = buildList {
            add(java.io.File("."))
            System.getProperty("user.dir")?.let { add(java.io.File(it)) }
        }
        candidates.asSequence()
            .flatMap { rel -> roots.asSequence().map { java.io.File(it, rel) } }
            .firstOrNull { it.exists() }
            ?: java.io.File(roots.first(), candidates.first())
    }

    /** 取 manifest 里第一条声明的 `service` 节点是否带某个属性 */
    private fun serviceNode(className: String): String {
        // <service ... android:name="X" ...> 到第一个 '>' 为止（自闭合也算）
        val regex = Regex(
            """<service\b[^>]*android:name\s*=\s*"${
                Regex.escape(className)
            }"[^>]*>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = regex.find(mergedManifestXml)
        com.google.common.truth.Truth.assertThat(match).isNotNull()
        return match!!.value
    }

    // ─────────────────────────────────────────────────────────────────
    // 权限声明
    // ─────────────────────────────────────────────────────────────────








    @Test
    fun `使用情况访问权限已声明`() {
        assertThat(requested).contains("android.permission.PACKAGE_USAGE_STATS")
    }

    @Test
    fun `悬浮窗权限已声明`() {
        assertThat(requested).contains("android.permission.SYSTEM_ALERT_WINDOW")
    }

    @Test
    fun `前台服务权限与类型权限已声明`() {
        assertThat(requested).contains("android.permission.FOREGROUND_SERVICE")
        assertThat(requested).contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")
    }

    @Test
    fun `通知权限已声明`() {
        assertThat(requested).contains("android.permission.POST_NOTIFICATIONS")
    }














    @Test
    fun `通知权限没有被篡改 protectionLevel`() {

        //
        //   Robolectric 的 ShadowPackageManager 只认识它**内置**的那份
        //   framework 权限表，`POST_NOTIFICATIONS` 不在其中 —— 直接抛
        //   `NameNotFoundException`。也就是说这条断言在单测环境里
        //   **根本没法通过 PackageManager 验证**。
        //
        //   → 换成一个在声明层等价、且真的能查的判据：

        //
        //     因为 `POST_NOTIFICATIONS` 是**系统已定义**的权限。
        //     如果哪天有人在 manifest 里写了一个同名的 `<permission>` 节点

        //     **覆盖系统定义** —— 保护级别从 dangerous 被改成 normal，
        //     于是 `checkSelfPermission` 恒返回 GRANTED，而系统根本不弹窗：
        //     引导页显示"已开启"，通知却永远不出现。
        //

        //     这条比查 protectionLevel 更根本 —— 后者只查结果，前者查病因。
        assertThat(mergedManifestXml).doesNotContain("<permission ")
        assertThat(mergedManifestXml).doesNotContain("<permission>")
    }

    // ─────────────────────────────────────────────────────────────────
    // 组件声明
    // ─────────────────────────────────────────────────────────────────

















    @Test
    fun `前台服务已注册并声明 specialUse 类型`() {
        val node = serviceNode(FocusMonitorService::class.java.name)
        assertThat(node).contains("android:foregroundServiceType=\"specialUse\"")

        //   service 没有 intent-filter，默认就是 false。显式写出来是为了让
        //   review 时能一眼确认「没有任何外部 App 能 bind 这个服务」。
        assertThat(node).contains("android:exported=\"false\"")
    }











    @Test
    fun `前台服务不会随任务被划掉而停止`() {
        val node = serviceNode(FocusMonitorService::class.java.name)

        //   在 review 时是两件事 —— 前者看代码的人要去查文档才知道默认值，
        //   后者一眼看懂意图。这条测试守的是「确实显式声明了」。
        assertThat(node).contains("android:stopWithTask=\"false\"")
    }











    @Test
    fun `应用不参与系统备份`() {

        assertThat(mergedManifestXml).contains("android:allowBackup=\"false\"")
        assertThat(mergedManifestXml).contains("android:fullBackupContent=\"false\"")
        assertThat(mergedManifestXml).contains("android:dataExtractionRules=\"@xml/data_extraction_rules\"")
    }

    // ─────────────────────────────────────────────────────────────────
    // 反向守门：代码用到的权限不能「用了但没声明」
    // ─────────────────────────────────────────────────────────────────














    @Test
    fun `代码里用到的权限全部已声明`() {
        // 每一项都在本工程的 main 源码里有真实调用点，注释里标了位置
        val usedInCode = mapOf(
            "android.permission.PACKAGE_USAGE_STATS" to
                "service-focus/.../monitor/UsageStatsPoller.kt:107 queryEvents",
            "android.permission.SYSTEM_ALERT_WINDOW" to
                "service-focus/.../monitor/FocusPermissions.kt hasOverlay",
            "android.permission.FOREGROUND_SERVICE" to
                "service-focus/.../monitor/FocusMonitorService.kt:446 startForeground",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" to
                "同上，foregroundServiceType=specialUse",
            "android.permission.POST_NOTIFICATIONS" to
                "service-focus/.../monitor/FocusPermissions.kt:146 checkSelfPermission",
            "android.permission.QUERY_ALL_PACKAGES" to
                "service-focus 屏蔽应用列表 queryIntentActivities",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to
                "feature-onboarding/.../PermissionPlatform.kt settingsIntent",
        )

        val missing = usedInCode.filterKeys { it !in requested }
        assertThat(missing).isEmpty()


        //   「一堆早就删掉的权限还留着」，那样它就不再是守门，而是噪音。
        assertThat(usedInCode).isNotEmpty()
    }












    @Test
    fun `使用情况访问权限不能靠 checkSelfPermission 判定`() {
        val result = ContextCompat.checkSelfPermission(
            context,
            "android.permission.PACKAGE_USAGE_STATS",
        )
        assertThat(result).isEqualTo(PackageManager.PERMISSION_DENIED)
    }
}
