package com.projectfocus.feature.record.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException




















































object BoardingPassRenderer {

    /** 缓存子目录名 */
    const val CACHE_DIR = "share"

    /** 最多保留几次分享的产物（一次分享一个时间戳子目录） */
    const val MAX_KEPT = 8

    /** 卡片的标准输出宽度（px）。@see [renderToFile] 的 `widthPx` 参数说明 */
    const val DEFAULT_WIDTH_PX = 1080









    interface CardSource {
        /** 让 View 知道自己将被画到多大 */
        fun measureForExport(widthPx: Int, heightPx: Int)

        /** 把内容画到 canvas 上（canvas 已被铺好背景） */
        fun drawTo(canvas: Canvas)

        /** 期望高度 / 宽度比。用于在只知道宽度时算出高度 */
        fun aspectRatio(): Float
    }











    @WorkerThread
    fun renderToFile(
        context: Context,
        source: CardSource,
        fileName: String,
        backgroundColor: Int,
        widthPx: Int = DEFAULT_WIDTH_PX,
    ): File {

        //   避免"宽度 1080、高度按 340dp 算"导致图片被垂直拉伸。
        val ratio = source.aspectRatio().takeIf { it > 0f } ?: DEFAULT_ASPECT_RATIO
        val heightPx = (widthPx / ratio).toInt().coerceAtLeast(1)

        source.measureForExport(widthPx, heightPx)


        //   前者是 KTX 的等价封装，行为完全一样（内部就是 TODO 的同一个调用），
        //   但它给参数做了默认值，而且能跟着 minSdk 演进挑最优重载。
        //   lint 的 `UseKtx` 就是盯这一处 —— 与其 @Suppress，不如就用 KTX 版。
        val bitmap = createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)

            canvas.drawColor(backgroundColor)
            source.drawTo(canvas)

            val dir = newShareDir(context)
            val out = File(dir, fileName)
            writePng(bitmap, out)
            trimOldShares(context)
            return out
        } finally {

            //   不回收时连点分享几次就会触发 GC 抖动（低端机上能感知到卡顿）。

            //     这也是 `try/finally` 包住整个流程而不是只包住 draw 的原因。
            bitmap.recycle()
        }
    }

    /** 默认宽高比（340 × ~350 的设计尺寸）。只在 `aspectRatio()` 返回非法值时用 */
    private const val DEFAULT_ASPECT_RATIO = 340f / 350f

    private fun writePng(bitmap: Bitmap, out: File) {
        FileOutputStream(out).use { stream ->

            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IOException("PNG 编码失败：${out.absolutePath}")
            }
            stream.flush()
        }
    }







    private fun newShareDir(context: Context): File {
        val root = File(context.cacheDir, CACHE_DIR)
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("无法创建分享缓存目录：${root.absolutePath}")
        }
        val dir = File(root, System.currentTimeMillis().toString())
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建分享缓存目录：${dir.absolutePath}")
        }
        return dir
    }









    private fun trimOldShares(context: Context) {
        runCatching {
            val root = File(context.cacheDir, CACHE_DIR)
            val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
            if (dirs.size <= MAX_KEPT) return
            dirs.sortedByDescending { it.name.toLongOrNull() ?: 0L }
                .drop(MAX_KEPT)
                .forEach { it.deleteRecursively() }
        }.onFailure { Timber.w(it, "清理旧分享缓存失败（不影响分享）") }
    }
}
