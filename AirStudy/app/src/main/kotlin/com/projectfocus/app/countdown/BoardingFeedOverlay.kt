package com.projectfocus.app.countdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One bounded snapshot forms a moving sheet; its owner controls timing and flight state. */
internal class BoardingFeedOverlay(context: Context, private val night: Boolean) : View(context) {
    private var image: Bitmap? = null
    private var configured = false
    private var imageWidth = 0f
    private var imageHeight = 0f
    private val paper = RectF()
    private val outlet = RectF()
    private var baseX = 0f
    private var baseY = 0f
    private var displayScale = 1f
    private var foldLength = 1f
    private var progress = 0f
    var outletOpacity = 1f
        set(value) { field = value.coerceIn(0f, 1f);invalidate() }
    private val density = resources.displayMetrics.density
    private val angle = Math.toRadians(78.0)
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, 1f,
            intArrayOf(Color.TRANSPARENT, if (night) 0x80000000.toInt() else 0x550F172A), null, Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    private val stripMatrix = Matrix()
    private val shadeMatrix = Matrix()
    private val sourceQuad = FloatArray(8)
    private val targetQuad = FloatArray(8)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun bind(snapshot: Bitmap, bounds: RectF) {
        require(!snapshot.isRecycled && snapshot.width > 0 && snapshot.height > 0)
        image = snapshot;imageWidth = snapshot.width.toFloat();imageHeight = snapshot.height.toFloat()
        paper.set(bounds)
        if (paper.width() <= 0f || paper.height() <= 0f || !paper.intersect(0f, 0f, imageWidth, imageHeight)) {
            paper.set(imageWidth * .05f, imageHeight * .045f, imageWidth * .95f, imageHeight * .858f)
        }
        foldLength = max(1f, min(16f * density, paper.height() * .25f))
        progress = 0f;outletOpacity = 1f;configured = true
        invalidate()
    }

    fun place(x: Float, y: Float, scale: Float) {
        baseX = x;baseY = y;displayScale = scale.coerceAtLeast(.01f)
        invalidate()
    }

    fun showProgress(value: Float) {
        progress = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        invalidate()
    }

    /** The complete live paper is visible now; keep only the fading outlet. */
    fun finish() {
        progress = 1f;image = null;invalidate()
    }

    fun clear() {
        image = null;configured = false;invalidate()
    }

    private val cutoff: Float get() = paper.top + (paper.height() + foldLength) * progress - foldLength
    private val foldProjection: Float get() = (sin(angle) * foldLength / angle).toFloat()
    private fun projectedY(y: Float): Float {
        val start = cutoff
        if (y <= start) return y
        val fraction = ((y - start) / foldLength).coerceIn(0f, 1f)
        return start + (sin(fraction * angle) * foldLength / angle).toFloat()
    }
    private fun widthScale(y: Float): Float {
        val fraction = ((y - cutoff) / foldLength).coerceIn(0f, 1f)
        return 1f - (((1.0 - cos(fraction * angle)) / (1.0 - cos(angle))) * .055).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        if (!configured) return
        val slotWidth = min(paper.width() * displayScale + 6f * density, max(1f, width - 32f * density))
        val slotX = baseX + paper.centerX() * displayScale
        val slotY = baseY + (paper.bottom + foldProjection + 4f * density) * displayScale
        outlet.set(slotX - slotWidth * .5f, slotY - 7f * density, slotX + slotWidth * .5f, slotY + 7f * density)
        drawOutlet(canvas, lower = true)
        val snapshot = image
        if (progress > 0f && progress < 1f && snapshot != null && !snapshot.isRecycled) {
            val save = canvas.save()
            val feed = paper.bottom - cutoff + 4f * density
            canvas.translate(baseX, baseY + feed * displayScale)
            canvas.scale(displayScale, displayScale)
            val flatBottom = cutoff.coerceIn(0f, imageHeight)
            if (flatBottom > 0f) {
                val flat = canvas.save()
                canvas.clipRect(0f, 0f, imageWidth, flatBottom.roundToInt().toFloat())
                canvas.drawBitmap(snapshot, 0f, 0f, paperPaint)
                canvas.restoreToCount(flat)
            }
            drawFold(canvas, snapshot)
            canvas.restoreToCount(save)
        }
        drawOutlet(canvas, lower = false)
    }

    private fun drawFold(canvas: Canvas, snapshot: Bitmap) {
        val start = max(0f, cutoff)
        val end = min(paper.bottom, cutoff + foldLength)
        if (end <= start) return
        val top = projectedY(start).roundToInt().toFloat()
        val bottom = projectedY(end).roundToInt().toFloat()
        if (bottom <= top) return
        val layer = canvas.saveLayer(0f, top, imageWidth, bottom, null)
        repeat(36) { index ->
            val sourceTop = start + (end - start) * index / 36f
            val sourceBottom = start + (end - start) * (index + 1) / 36f
            val targetTop = projectedY(sourceTop).roundToInt().toFloat()
            val targetBottom = projectedY(sourceBottom).roundToInt().toFloat()
            if (targetBottom > targetTop) {
                val topScale = widthScale(sourceTop);val bottomScale = widthScale(sourceBottom)
                val centre = paper.centerX();val right = imageWidth - centre
                sourceQuad[0] = 0f;sourceQuad[1] = sourceTop
                sourceQuad[2] = imageWidth;sourceQuad[3] = sourceTop
                sourceQuad[4] = imageWidth;sourceQuad[5] = sourceBottom
                sourceQuad[6] = 0f;sourceQuad[7] = sourceBottom
                targetQuad[0] = (1f - topScale) * centre;targetQuad[1] = targetTop
                targetQuad[2] = centre + topScale * right;targetQuad[3] = targetTop
                targetQuad[4] = centre + bottomScale * right;targetQuad[5] = targetBottom
                targetQuad[6] = (1f - bottomScale) * centre;targetQuad[7] = targetBottom
                if (stripMatrix.setPolyToPoly(sourceQuad, 0, targetQuad, 0, 4)) {
                    val strip = canvas.save()
                    canvas.clipRect(0f, targetTop, imageWidth, targetBottom)
                    canvas.concat(stripMatrix)
                    canvas.drawBitmap(snapshot, 0f, 0f, paperPaint)
                    canvas.restoreToCount(strip)
                }
            }
        }
        shadeMatrix.setScale(1f, foldProjection)
        shadeMatrix.postTranslate(0f, cutoff)
        edgePaint.shader?.setLocalMatrix(shadeMatrix)
        canvas.drawRect(0f, top, imageWidth, bottom, edgePaint)
        canvas.restoreToCount(layer)
    }

    private fun drawOutlet(canvas: Canvas, lower: Boolean) {
        if (outletOpacity <= 0f) return
        val save = canvas.save()
        if (lower) canvas.clipRect(outlet.left - density, outlet.centerY(), outlet.right + density, outlet.bottom + density)
        else canvas.clipRect(outlet.left - density, outlet.top - density, outlet.right + density, outlet.centerY())
        slotPaint.style = Paint.Style.FILL
        slotPaint.color = if (night) 0xFF152039.toInt() else 0xFFDEE7F5.toInt()
        slotPaint.alpha = (255 * outletOpacity).roundToInt()
        canvas.drawRoundRect(outlet, 7f * density, 7f * density, slotPaint)
        slotPaint.style = Paint.Style.STROKE;slotPaint.strokeWidth = 3f * density
        slotPaint.color = if (night) 0xFF091322.toInt() else 0xFFB5C6E0.toInt()
        slotPaint.alpha = (255 * outletOpacity).roundToInt()
        canvas.drawRoundRect(outlet, 7f * density, 7f * density, slotPaint)
        canvas.restoreToCount(save)
    }

    companion object {
        fun paperBounds(snapshot: Bitmap): RectF {
            val width = snapshot.width;val height = snapshot.height
            val pixels = IntArray(width * height)
            snapshot.getPixels(pixels, 0, width, 0, 0, width, height)
            var left = width;var top = height;var right = -1;var bottom = -1
            for (y in 0 until height) for (x in 0 until width) {
                if ((pixels[y * width + x] ushr 24) >= 128) {
                    left = min(left, x);top = min(top, y);right = max(right, x);bottom = max(bottom, y)
                }
            }
            return if (right >= left && bottom >= top) RectF(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
            else RectF(width * .05f, height * .045f, width * .95f, height * .858f)
        }
    }
}
