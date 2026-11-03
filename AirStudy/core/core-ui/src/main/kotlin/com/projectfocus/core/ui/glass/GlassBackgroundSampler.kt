package com.projectfocus.core.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


class GlassBackgroundSampler(
    /** 降采样倍率。2 = 宽高各取一半（像素量 1/4）。 */
    private val downscale: Int = DEFAULT_DOWNSCALE,
) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        isAntiAlias = false
    }

    private var reusable: Bitmap? = null











    fun beginFrame(contentWidth: Int, contentHeight: Int): Canvas? {
        if (contentWidth <= 0 || contentHeight <= 0) return null
        val sampleW = (contentWidth / downscale).coerceAtLeast(1)
        val sampleH = (contentHeight / downscale).coerceAtLeast(1)

        val bmp = reusable?.takeIf { it.width == sampleW && it.height == sampleH && !it.isRecycled }
            ?: createBitmap(sampleW, sampleH).also {
                reusable?.recycle()
                reusable = it
            }

        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.scale(1f / downscale, 1f / downscale)
        return canvas
    }

    /** 当前帧的底板位图（[beginFrame] 之后有效） */
    fun currentFrame(): Bitmap? = reusable











    fun crop(source: Bitmap, targetRect: Rect): Bitmap {
        val l = (targetRect.left / downscale).coerceIn(0, source.width - 1)
        val t = (targetRect.top / downscale).coerceIn(0, source.height - 1)
        val r = (targetRect.right / downscale).coerceIn(l + 1, source.width)
        val b = (targetRect.bottom / downscale).coerceIn(t + 1, source.height)
        return Bitmap.createBitmap(source, l, t, r - l, b - t)
    }

    fun release() {
        reusable?.recycle()
        reusable = null
    }

    companion object {









        const val DEFAULT_DOWNSCALE = 2
    }
}



















class GlassTicker(
    private val scope: CoroutineScope,
    private val frameIntervalMillis: Long = DEFAULT_FRAME_INTERVAL,
) {

    private var job: Job? = null
    private var listeners = mutableListOf<(elapsedSeconds: Float) -> Unit>()
    private var startNanos = 0L
    private var pausedAtSeconds = 0f
    private var accumulated = 0f

    val isRunning: Boolean get() = job?.isActive == true

    /** 当前已推进的秒数（用于给 shader 的 uTime） */
    @Volatile
    var elapsedSeconds: Float = 0f
        private set

    fun addListener(listener: (Float) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Float) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) pause()
    }

    fun resume() {
        if (isRunning) return
        startNanos = System.nanoTime()

        //   delay 在主调度器上不阻塞 UI（只是挂起），而 invalidate 必须在主线程。
        //   不在这里传 DispatcherProvider —— 被误配成 Default 会直接崩。
        job = scope.launch {
            while (isActive) {
                delay(frameIntervalMillis)
                elapsedSeconds = accumulated + (System.nanoTime() - startNanos) / 1_000_000_000f

                //   控件在帧中途 setVisibility(GONE)），直接遍历会 ConcurrentModificationException
                val snapshot = listeners.toTypedArray()
                for (l in snapshot) l(elapsedSeconds)
            }
        }
    }

    fun pause() {
        accumulated = elapsedSeconds
        startNanos = 0L
        job?.cancel()
        job = null
    }

    fun reset() {
        pause()
        accumulated = 0f
        elapsedSeconds = 0f
    }

    fun dispose() {
        pause()
        listeners.clear()
    }

    companion object {
        /** 30fps = 33.33ms。取 33 而不是 34，长期累积不会偏慢。 */
        const val DEFAULT_FRAME_INTERVAL = 33L
    }
}
