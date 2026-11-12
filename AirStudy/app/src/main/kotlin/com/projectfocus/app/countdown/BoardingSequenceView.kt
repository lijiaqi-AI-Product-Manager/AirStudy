package com.projectfocus.app.countdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Bounded, conflated decoding keeps a drag from queuing every intermediate image. */
internal class BoardingSequenceView(context: Context, night: Boolean) : View(context) {
    private val directory = if (night) "night" else "day"
    private val lock = Any()
    private val cache = LruCache<Int, Bitmap>(5)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "boarding-artwork") }
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = Rect()
    private var bitmap: Bitmap? = null
    private var wanted = 0
    private var loading = false
    private var released = false
    private var afterLastFrame: (() -> Unit)? = null
    var renderedFrame: Int = -1
        private set
    var onFirstFrame: (() -> Unit)? = null
    private val sampleSize: Int = run {
        val density = resources.displayMetrics.density
        val width = (300 * density).roundToInt().coerceIn(1, 900)
        val height = (400 * density).roundToInt().coerceIn(1, 1200)
        val pixels = (width * height).coerceIn(320_000, 720_000)
        var sample = 1
        while (900 / sample > width || 1200 / sample > height || 1_080_000L / sample / sample > pixels) sample *= 2
        sample
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(0, 0, w, h)
        showProgress(wanted / 179f)
    }

    fun showProgress(progress: Float) {
        val index = (progress.coerceIn(0f, 1f) * 179).roundToInt()
        synchronized(lock) {
            if (released) return
            wanted = index
            cache.get(index)?.let { present(index, it); return }
            if (loading) return
            loading = true
            executor.execute(::loadLatest)
        }
    }

    private fun loadLatest() {
        while (true) {
            val index = synchronized(lock) { if (released) { loading = false; return }; wanted }
            val decoded = runCatching {
                context.assets.open(String.format(Locale.ROOT, "ticket_sequence/%s/%05d.webp", directory, index)).use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
                }
            }.getOrNull()
            synchronized(lock) {
                if (released) { loading = false; return }
                if (decoded != null) {
                    cache.put(index, decoded)
                    main.post { synchronized(lock) { if (!released && wanted == index) present(index, decoded) } }
                }
                if (wanted == index) { loading = false; return }
            }
        }
    }

    private fun present(index: Int, decoded: Bitmap) {
        bitmap = decoded
        renderedFrame = index
        invalidate()
        if (index == 0) onFirstFrame?.also { onFirstFrame = null; it() }
        if (index == 179) afterLastFrame?.also { afterLastFrame = null; it() }
    }

    fun afterFinalFrame(action: () -> Unit) {
        if (renderedFrame == 179) action() else { afterLastFrame = action; showProgress(1f) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, null, bounds, paint) }
    }

    fun release() {
        synchronized(lock) {
            released = true
            onFirstFrame = null
            afterLastFrame = null
            cache.evictAll()
            bitmap = null
        }
        executor.shutdownNow()
    }
}
