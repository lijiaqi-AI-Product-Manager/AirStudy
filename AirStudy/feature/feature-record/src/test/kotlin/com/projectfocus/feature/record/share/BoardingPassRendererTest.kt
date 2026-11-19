package com.projectfocus.feature.record.share

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File






























@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardingPassRendererTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val cacheRoot: File get() = File(context.cacheDir, BoardingPassRenderer.CACHE_DIR)

    @Before
    fun setUp() {

        //   残留会让"保留最近 N 次"的断言数不准。
        cacheRoot.deleteRecursively()
    }

    @After
    fun tearDown() {
        cacheRoot.deleteRecursively()
    }

    /** 画一个实心圆角块的假卡片。 */
    private class FakeCard(
        private val ratio: Float = 0.6f,
        private val throwOnDraw: Boolean = false,
    ) : BoardingPassRenderer.CardSource {
        var measuredW = 0
        var measuredH = 0

        override fun measureForExport(widthPx: Int, heightPx: Int) {
            measuredW = widthPx
            measuredH = heightPx
        }

        override fun drawTo(canvas: android.graphics.Canvas) {
            if (throwOnDraw) error("模拟绘制失败")
            val paint = android.graphics.Paint().apply { color = 0xFF112233.toInt() }
            canvas.drawRect(
                measuredW * 0.1f, measuredH * 0.1f,
                measuredW * 0.9f, measuredH * 0.9f,
                paint,
            )
        }

        override fun aspectRatio(): Float = ratio
    }

    // ── 正常路径 ───────────────────────────────────────────

    @Test
    fun `生成的文件存在且非空`() {
        val file = BoardingPassRenderer.renderToFile(
            context = context,
            source = FakeCard(),
            fileName = "boarding-pass-20261026-PF2645.png",
            backgroundColor = 0xFFFFFFFF.toInt(),
            widthPx = 540,
        )
        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isGreaterThan(0L)
        assertThat(file.name).isEqualTo("boarding-pass-20261026-PF2645.png")
    }

    @Test
    fun `生成的文件落在 cache 的 share 子目录里`() {

        //   写到别的目录 → `getUriForFile` 抛
        //   "Failed to find configured root"，而那个错误信息极具误导性。
        val file = BoardingPassRenderer.renderToFile(
            context, FakeCard(), "x.png", 0xFFFFFFFF.toInt(), widthPx = 200,
        )
        assertThat(file.parentFile?.parentFile?.canonicalFile).isEqualTo(cacheRoot.canonicalFile)
        assertThat(file.canonicalFile.toPath().startsWith(cacheRoot.canonicalFile.toPath())).isTrue()
    }

    @Test
    fun `写出来的是合法 PNG`() {
        val file = BoardingPassRenderer.renderToFile(
            context, FakeCard(), "x.png", 0xFFFFFFFF.toInt(), widthPx = 200,
        )

        val head = file.readBytes().take(8).toByteArray()
        assertThat(head).isEqualTo(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            ),
        )
    }

    @Test
    fun `读回来的位图尺寸与请求一致`() {
        val file = BoardingPassRenderer.renderToFile(
            context, FakeCard(ratio = 0.5f), "x.png", 0xFFFFFFFF.toInt(), widthPx = 400,
        )
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        assertThat(opts.outWidth).isEqualTo(400)
        // ratio 0.5 → 高度 = 400 / 0.5 = 800
        assertThat(opts.outHeight).isEqualTo(800)
        assertThat(opts.outMimeType).isEqualTo("image/png")
    }

    @Test
    fun `画出了非透明像素`() {

        val file = BoardingPassRenderer.renderToFile(
            context, FakeCard(), "x.png", 0xFFFFFFFF.toInt(), widthPx = 200,
        )
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        val opaque = countOpaque(bmp)
        bmp.recycle()
        assertThat(opaque).isGreaterThan(0)
    }

    @Test
    fun `背景色被铺满（不留透明边）`() {

        //   → 断言四角都**不透明**（那是背景铺到的证明）。
        val file = BoardingPassRenderer.renderToFile(
            context, FakeCard(), "x.png", 0xFF00FF00.toInt(), widthPx = 200,
        )
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        val corners = listOf(
            0 to 0,
            bmp.width - 1 to 0,
            0 to bmp.height - 1,
            bmp.width - 1 to bmp.height - 1,
        )
        for ((x, y) in corners) {
            val px = bmp.getPixel(x, y)
            assertThat(android.graphics.Color.alpha(px)).isEqualTo(255)
            assertThat(android.graphics.Color.green(px)).isEqualTo(255)
        }
        bmp.recycle()
    }

    @Test
    fun `宽高比非法时退化为默认比例而不是除零`() {
        val file = BoardingPassRenderer.renderToFile(
            context, FakeCard(ratio = 0f), "x.png", 0xFFFFFFFF.toInt(), widthPx = 340,
        )
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        assertThat(opts.outHeight).isGreaterThan(0)
        assertThat(opts.outWidth).isEqualTo(340)
    }

    // ── 目录管理 ───────────────────────────────────────────

    @Test
    fun `每次分享用独立子目录（同名文件不覆盖上一张）`() {
        val a = BoardingPassRenderer.renderToFile(
            context, FakeCard(), "same.png", 0xFFFFFFFF.toInt(), widthPx = 100,
        )

        Thread.sleep(2)
        val b = BoardingPassRenderer.renderToFile(
            context, FakeCard(), "same.png", 0xFFFFFFFF.toInt(), widthPx = 100,
        )
        assertThat(a.parentFile).isNotEqualTo(b.parentFile)
        assertThat(a.exists()).isTrue()
        assertThat(b.exists()).isTrue()
    }

    @Test
    fun `超过上限时清掉最旧的目录`() {

        repeat(BoardingPassRenderer.MAX_KEPT + 3) { i ->
            // 用递增的假时间戳目录名，保证排序确定
            File(cacheRoot, "${1000 + i}").mkdirs()
        }
        BoardingPassRenderer.renderToFile(
            context, FakeCard(), "x.png", 0xFFFFFFFF.toInt(), widthPx = 50,
        )
        val remaining = cacheRoot.listFiles()?.count { it.isDirectory } ?: 0

        assertThat(remaining).isAtMost(BoardingPassRenderer.MAX_KEPT + 1)
    }

    @Test
    fun `清理时留下的是最新的目录`() {

        //   如果排序写反，用户点分享拿到的是**很久以前**的一张图 ——
        //   而且"图能打开"，所以不会有人怀疑到这。
        repeat(BoardingPassRenderer.MAX_KEPT + 2) { i ->
            File(cacheRoot, "${5000 + i}").mkdirs()
        }
        BoardingPassRenderer.renderToFile(
            context, FakeCard(), "x.png", 0xFFFFFFFF.toInt(), widthPx = 50,
        )
        val names = cacheRoot.listFiles()?.filter { it.isDirectory }?.mapNotNull { it.name.toLongOrNull() }
            ?.sortedDescending().orEmpty()
        // 最新的那个假目录（5000 + MAX_KEPT + 1）必须还在
        assertThat(names).contains(5000L + BoardingPassRenderer.MAX_KEPT + 1)
        // 最旧的那个（5000）必须被删
        assertThat(names).doesNotContain(5000L)
    }

    // ── 失败路径 ───────────────────────────────────────────

    @Test
    fun `绘制抛异常时不留下半截文件`() {


        //   `renderToFile` 的 `finally` 只回收 bitmap，不删半截文件 ——
        //   所以这里断言的是"文件根本没被创建"（异常发生在写盘之前）。
        val thrown = runCatching {
            BoardingPassRenderer.renderToFile(
                context, FakeCard(throwOnDraw = true), "broken.png", 0xFFFFFFFF.toInt(), widthPx = 100,
            )
        }.exceptionOrNull()
        assertThat(thrown).isNotNull()
        val leftovers = cacheRoot.listFiles()?.flatMap { it.listFiles()?.asList().orEmpty() }.orEmpty()
        assertThat(leftovers.map { it.name }).doesNotContain("broken.png")
    }

    private fun countOpaque(bmp: Bitmap): Int {
        var count = 0
        // 全量扫描在 200×333 上约 66k 次，够快
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 0) count++
            }
        }
        return count
    }
}
