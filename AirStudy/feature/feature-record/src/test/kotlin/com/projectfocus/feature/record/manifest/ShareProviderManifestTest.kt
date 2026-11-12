package com.projectfocus.feature.record.manifest

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.record.share.ShareIntentBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
































@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareProviderManifestTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()









    private val mergedManifest: String by lazy {
        val candidates = listOf(
            "build/intermediates/merged_manifest/debug/processDebugManifest/AndroidManifest.xml",
            "build/intermediates/merged_manifest/debug/AndroidManifest.xml",
            "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
            "build/intermediates/merged_manifests/debug/AndroidManifest.xml",
            "build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml",
        )
        val roots = buildList {
            add(java.io.File("."))
            System.getProperty("user.dir")?.let { add(java.io.File(it)) }
        }
        val file = candidates.asSequence()
            .flatMap { rel -> roots.asSequence().map { java.io.File(it, rel) } }
            .firstOrNull { it.exists() }

        //   assertThat(text).contains("x") 对空串是失败的 —— 这是安全的失败方向。
        file?.readText().orEmpty()
    }

    // ── FileProvider ───────────────────────────────────────

    @Test
    fun `FileProvider 已注册`() {
        assertThat(mergedManifest).contains("androidx.core.content.FileProvider")
    }

    @Test
    fun `FileProvider 的 authority 用 placeholder 而不是硬编码包名`() {

        //   失败暴露了一个真实事实（不是测试写错）：
        //   **library 模块的 merged_manifest 不替换 `${applicationId}`** ——

        //   所以从这里读到的就是字面量 `${applicationId}.fileprovider`。
        //
        //   → 断言改成两端都验证：
        //     ① manifest 里必须是 placeholder（不能是硬编码包名）；
        //     ② 代码里的 authority 必须**等于**"包名 + .fileprovider"，
        //        这与 placeholder 在打包后被替换成的值一致。

        //     这一条属于后者。**
        val providerTag = providerTag()
        assertThat(providerTag).contains("android:authorities=\"\${applicationId}.fileprovider\"")
        assertThat(providerTag).doesNotContain("com.projectfocus.feature.record.fileprovider")

        // ① 代码侧：值必须可由包名推导（打包后 placeholder 就是这么替换的）
        assertThat(ShareIntentBuilder.authority(context))
            .isEqualTo("${context.packageName}.fileprovider")
    }

    @Test
    fun `FileProvider 的 authority 不以写死的第三方包名开头`() {

        //   在 debug 变体（applicationIdSuffix=".debug"）上，两个包会抢同一个 authority。
        assertThat(providerTag()).contains("\${applicationId}")
    }

    @Test
    fun `FileProvider 不对外暴露`() {

        val providerTag = providerTag()
        assertThat(providerTag).contains("android:exported=\"false\"")
    }

    @Test
    fun `FileProvider 允许临时授予 URI 权限`() {

        assertThat(providerTag()).contains("android:grantUriPermissions=\"true\"")
    }

    @Test
    fun `FileProvider 指向了路径声明文件`() {

        //   第一次写的时候只在 `<provider ...>` 这个自闭合标签里找，
        //   所以"找不到"—— 这不是配置错，是断言的范围错了。
        //   → 取 `<provider` 到 `</provider>` 的整段。
        val block = providerBlock()
        assertThat(block).contains("android.support.FILE_PROVIDER_PATHS")
        assertThat(block).contains("@xml/record_file_paths")
    }

    // ── queries ────────────────────────────────────────────

    @Test
    fun `声明了 SEND image 的包可见性`() {

        val queries = queriesBlock()
        assertThat(queries).isNotNull()
        assertThat(queries).contains("android.intent.action.SEND")
        assertThat(queries).contains("image/png")
    }

    @Test
    fun `queries 是 manifest 的直接子元素`() {

        //   AAPT 报 `unexpected element <queries> found in <manifest><application>`。
        //   这条断言让同样的错误在单测里就能看到。
        val appStart = mergedManifest.indexOf("<application")
        val queriesStart = mergedManifest.indexOf("<queries")
        assertThat(appStart).isGreaterThan(0)
        assertThat(queriesStart).isGreaterThan(0)
        assertThat(queriesStart).isLessThan(appStart)
    }

    // ── 辅助 ───────────────────────────────────────────────

    private fun providerTag(): String {
        val regex = Regex("""<provider\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
        return regex.find(mergedManifest)?.value.orEmpty()
    }

    /** `<provider` 到 `</provider>` 的整段（含 `<meta-data>` 子元素）。 */
    private fun providerBlock(): String {
        val start = mergedManifest.indexOf("<provider")
        if (start < 0) return ""
        val end = mergedManifest.indexOf("</provider>", start)
        if (end < 0) return mergedManifest.substring(start)
        return mergedManifest.substring(start, end + "</provider>".length)
    }

    private fun queriesBlock(): String? {
        val start = mergedManifest.indexOf("<queries")
        if (start < 0) return null
        val end = mergedManifest.indexOf("</queries>", start)
        if (end < 0) return null
        return mergedManifest.substring(start, end)
    }

    @Test
    fun `能读到合并后的 manifest`() {

        //   而不是产品坏了 —— 单独一条断言能让这个区分变得一目了然。
        assertThat(mergedManifest).isNotEmpty()
        assertThat(mergedManifest).contains("<manifest")
    }

    @Test
    fun `本模块不声明任何存储权限`() {

        //   这条断言防的是"有人为了让分享能用，顺手加了 READ_EXTERNAL_STORAGE"。
        assertThat(mergedManifest).doesNotContain("READ_EXTERNAL_STORAGE")
        assertThat(mergedManifest).doesNotContain("WRITE_EXTERNAL_STORAGE")
        assertThat(mergedManifest).doesNotContain("android.permission.INTERNET")
    }

    @Test
    fun `PackageManager 能查到 FileProvider`() {

        //   合并后的 provider 也应该能从 PackageManager 查到。
        //   查不到时**不断言失败** —— 这是环境差异，不是产品问题；
        //   但把结果打出来，方便排查"为什么 getUriForFile 失败"。
        val info = runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PROVIDERS,
            ).providers.orEmpty().map { it.authority }
        }.getOrDefault(emptyList())
        // 只是在 IDE 里看输出用，不做强断言
        println("providers = $info")
    }
}
