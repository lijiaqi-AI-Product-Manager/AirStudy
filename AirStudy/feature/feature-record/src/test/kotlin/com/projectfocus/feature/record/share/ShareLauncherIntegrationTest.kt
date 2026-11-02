package com.projectfocus.feature.record.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.completion.pass.BoardingPassData
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId




























@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareLauncherIntegrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-10-26 08:00 CST */
    private val startMs = 1792972800_000L

    private lateinit var composer: ShareCardComposer
    private lateinit var launcher: ShareLauncher

    @Before
    fun setUp() {
        File(context.cacheDir, BoardingPassRenderer.CACHE_DIR).deleteRecursively()
        composer = ShareCardComposer(context)
        launcher = ShareLauncher(composer, context)
        resetFileProviderStrategyCache()

        registerFileProvider()
        registerFakeImageReceiver()
    }

    @After
    fun tearDown() {
        File(context.cacheDir, BoardingPassRenderer.CACHE_DIR).deleteRecursively()
    }


















    private fun registerFakeImageReceiver() {
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        val receiver = android.content.ComponentName(
            "com.fake.im",
            "com.fake.im.ShareReceiver",
        )
        pm.addActivityIfNotPresent(receiver)
        pm.addIntentFilterForActivity(
            receiver,
            android.content.IntentFilter(Intent.ACTION_SEND).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType("image/png")
                addDataType("image/*")
            },
        )
    }




























    private fun resetFileProviderStrategyCache() {
        val field = androidx.core.content.FileProvider::class.java
            .getDeclaredField("sCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(null) as MutableMap<Any?, Any?>
        cache.clear()
    }

    private fun registerFileProvider() {
        val info = android.content.pm.ProviderInfo().apply {
            name = "androidx.core.content.FileProvider"
            authority = "${context.packageName}.fileprovider"
            exported = false
            grantUriPermissions = true
            packageName = context.packageName
            applicationInfo = context.applicationInfo
            metaData = android.os.Bundle().apply {
                putInt(
                    "android.support.FILE_PROVIDER_PATHS",
                    com.projectfocus.feature.record.R.xml.record_file_paths,
                )
            }
        }
        org.robolectric.Shadows.shadowOf(context.packageManager).addOrUpdateProvider(info)
    }

    private fun pass() = BoardingPassData.from(
        departureCode = "PEK",
        departureCity = "北京",
        arrivalCode = "KIX",
        arrivalCity = "大阪",
        startWallMs = startMs,
        endWallMs = startMs + 45 * 60_000L,
        zone = zone,
        plannedMinutes = 45,
        actualFocusMinutes = 45,
        distanceKm = 600.0,
        seat = "12A",
    )

    private fun spec() = ShareCardSpec(
        detail = ShareDetail(
            fromCity = "北京",
            toCity = "大阪",
            fromCode = "PEK",
            toCode = "KIX",
            distanceText = "600.0",
            actualText = "45 分钟",
        ),
        flightNo = "PF2645",
        startWallMs = startMs,
        zone = zone,
        templates = context.shareTemplates(),
    )

    // ── 主链路 ─────────────────────────────────────────────

    @Test
    fun `端到端生成分享 Intent 并交给 startActivity`() = runTest {
        val started = mutableListOf<Intent>()

        val outcome = launcher.share(
            spec = spec(),
            data = pass(),
            startActivity = { started += it },
            mainDispatcher = testMainDispatcher(),
        )

        assertThat(outcome).isEqualTo(ShareLauncher.Outcome.Launched)
        assertThat(started).hasSize(1)

        val chooser = started.single()
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)

        assertThat(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        @Suppress("DEPRECATION")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertThat(inner).isNotNull()
        assertThat(inner!!.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(inner.type).isEqualTo("image/png")

        @Suppress("DEPRECATION")
        val uri = inner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertThat(uri).isNotNull()
        assertThat(uri!!.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo("${context.packageName}.fileprovider")
    }

    @Test
    fun `URI 指向的文件真的存在且是 PNG`() = runTest {

        //   "路径在某个已声明的 root 下"，**不**说明文件存在。
        //   → 通过 ContentResolver 真的读一遍（走 provider 的真实 query/openFile 通路）。
        val started = mutableListOf<Intent>()
        launcher.share(spec(), pass(), { started += it }, testMainDispatcher())

        val inner = innerIntent(started.single())
        @Suppress("DEPRECATION")
        val uri = inner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!

        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertThat(bytes.size).isGreaterThan(1000)
        // PNG 魔数
        assertThat(bytes.take(8).toByteArray()).isEqualTo(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        )
    }

    @Test
    fun `卡片不是白底白字（真的用主题画出了颜色）`() = runTest {

        //   如果 `ShareCardComposer` 用 application context（无主题）创建 View，
        //   `getColor(bg_elevated)` 会拿到 framework 默认值 ——
        //   在 Robolectric 下通常是白色，卡片上的文字也是白色，
        //   于是分享出去的图**一片纯白**。
        //   → 断言：图里至少有 3 种不同颜色（底色、纸面、文字）。
        val started = mutableListOf<Intent>()
        launcher.share(spec(), pass(), { started += it }, testMainDispatcher())

        val inner = innerIntent(started.single())
        @Suppress("DEPRECATION")
        val uri = inner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        val bmp = context.contentResolver.openInputStream(uri)!!.use {
            android.graphics.BitmapFactory.decodeStream(it)
        }
        val colors = HashSet<Int>()
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                colors += bmp.getPixel(x, y)
                x += 4
            }
            y += 4
        }
        bmp.recycle()
        assertThat(colors.size).isAtLeast(3)
    }

    @Test
    fun `正文与主题都进了 Intent`() = runTest {
        val started = mutableListOf<Intent>()
        launcher.share(spec(), pass(), { started += it }, testMainDispatcher())
        val inner = innerIntent(started.single())

        assertThat(inner.getStringExtra(Intent.EXTRA_TEXT)).contains("北京 → 大阪")
        assertThat(inner.getStringExtra(Intent.EXTRA_TEXT)).contains("专注 45 分钟")
        assertThat(inner.getStringExtra(Intent.EXTRA_SUBJECT)).isNotNull()
    }

    @Test
    fun `连续分享两次生成两个不同的文件`() = runTest {
        val started = mutableListOf<Intent>()
        launcher.share(spec(), pass(), { started += it }, testMainDispatcher())
        Thread.sleep(2)
        launcher.share(spec(), pass(), { started += it }, testMainDispatcher())

        val uris = started.map { innerIntent(it).getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!! }
        assertThat(uris).hasSize(2)
        assertThat(uris[0]).isNotEqualTo(uris[1])
    }

    // ── 结果映射 ───────────────────────────────────────────

    @Test
    fun `startActivity 抛 ActivityNotFound 时结果是 NoTarget`() = runTest {
        val outcome = launcher.share(
            spec(), pass(),
            startActivity = { throw android.content.ActivityNotFoundException() },
            mainDispatcher = testMainDispatcher(),
        )
        assertThat(outcome).isEqualTo(ShareLauncher.Outcome.NoTarget)
    }

    @Test
    fun `生成过程中的其它异常被翻译成 Failed 而不是抛出`() = runTest {
        val expectedError = IllegalStateException("模拟未知错误")
        var startActivityReached = false
        val outcome = launcher.share(
            spec(), pass(),
            startActivity = {
                startActivityReached = true
                throw expectedError
            },
            mainDispatcher = testMainDispatcher(),
        )
        // 同时核对执行位置与异常对象，明确暴露真实分享链中的前置失败。
        assertTrue("startActivity 未执行，实际分享结果：$outcome", startActivityReached)
        assertThat(outcome).isInstanceOf(ShareLauncher.Outcome.Failed::class.java)
        assertThat((outcome as ShareLauncher.Outcome.Failed).error).isSameInstanceAs(expectedError)
    }

    @Test
    fun `文案映射覆盖全部三种结果`() {
        assertThat(launcher.messageRes(ShareLauncher.Outcome.Launched)).isNull()
        assertThat(launcher.messageRes(ShareLauncher.Outcome.NoTarget)).isNotNull()
        assertThat(
            launcher.messageRes(ShareLauncher.Outcome.Failed(null)),
        ).isNotNull()

        //   合并成同一句会让用户对着"重试"按钮做无用功。
        assertThat(launcher.messageRes(ShareLauncher.Outcome.NoTarget))
            .isNotEqualTo(launcher.messageRes(ShareLauncher.Outcome.Failed(null)))
    }




















































    private fun testMainDispatcher(): kotlinx.coroutines.CoroutineDispatcher =
        kotlinx.coroutines.Dispatchers.Unconfined

    private fun innerIntent(chooser: Intent): Intent {
        @Suppress("DEPRECATION")
        return requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT))
    }
}
