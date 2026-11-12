package com.projectfocus.feature.completion.arrival

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.atan2

/** A decorative airport illustration, never presented as destination photography. */
class ArrivalPostcardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(300f, 42f, 150f, if (night) 0x246F94FF else 0xB2FFFFFF.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val sky = LinearGradient(0f, 0f, 360f, 360f,
        if (night) intArrayOf(0xFF17263F.toInt(), 0xFF26324E.toInt(), 0xFF263B53.toInt())
        else intArrayOf(0xFFCEE9FF.toInt(), 0xFFE9E5FF.toInt(), 0xFFD9EDEB.toInt()), null, Shader.TileMode.CLAMP)
    private val ground = path(0f, 264f, 360f, 144f, 360f, 360f, 0f, 360f)
    private val apron = path(42f, 256f, 256f, 186f, 336f, 228f, 110f, 308f)
    private val roof = path(72f, 178f, 224f, 128f, 294f, 169f, 138f, 220f)
    private val side = path(72f, 178f, 138f, 220f, 138f, 270f, 72f, 228f)
    private val front = path(138f, 220f, 294f, 169f, 294f, 219f, 138f, 270f)
    private val glass = path(147f, 228f, 284f, 183f, 284f, 210f, 147f, 255f)
    private val tower = path(249f, 174f, 259f, 171f, 259f, 111f, 249f, 114f)
    private val cabin = path(238f, 106f, 260f, 99f, 274f, 107f, 252f, 114f)
    private val cabinFront = path(252f, 114f, 274f, 107f, 274f, 123f, 252f, 130f)
    private val cabinSide = path(238f, 106f, 252f, 114f, 252f, 130f, 238f, 122f)
    private val runway = path(-40f, 330f, 300f, 216f, 380f, 251f, 48f, 375f)
    private val clouds = listOf(cloud(32f, 70f, 1f), cloud(260f, 46f, .65f), cloud(286f, 144f, .7f))
    private val flight = Path().apply { moveTo(38f, 149f);cubicTo(90f, 73f, 143f, 166f, 211f, 80f) }
    private val measure = PathMeasure(flight, false)
    private val revealed = Path()
    private val plane = path(-13f, 3f, 13f, -6f, 4f, 3f, 0f, 12f, -3f, 4f)
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)
    private val dashes = DashPathEffect(floatArrayOf(3f, 5f), 0f)
    private var fraction = 1f
    private var pending = false
    private var animator: ValueAnimator? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** One visual arrival per bound result; it never starts a business operation. */
    fun showArrival() {
        stop()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        pending = true
        fraction = 0f
        synchronizeAnimation()
    }

    private fun synchronizeAnimation() {
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE || !hasWindowFocus()) {
            if (animator != null) stop()
            return
        }
        if (!pending || animator != null) return
        pending = false
        if (!ValueAnimator.areAnimatorsEnabled()) { fraction = 1f;invalidate();return }
        val running = ValueAnimator.ofFloat(0f, 1f)
        animator = running
        running.duration = 1600L;running.interpolator = DecelerateInterpolator()
        running.addUpdateListener { if (animator === it) { fraction = it.animatedValue as Float;invalidate() } }
        running.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator === animation) { animator = null;fraction = 1f;invalidate() }
            }
        })
        running.start()
    }

    private fun stop() {
        val running = animator;animator = null;running?.cancel()
        fraction = 1f;pending = false;invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val saved = canvas.save();canvas.scale(width / 360f, height / 360f)
        fill.shader = sky;canvas.drawRect(0f, 0f, 360f, 360f, fill);fill.shader = null
        canvas.drawRect(0f, 0f, 360f, 360f, glow)
        fill.color = if (night) 0x14FFFFFF else 0x70FFFFFF;clouds.forEach { canvas.drawPath(it, fill) }
        polygon(canvas, ground, if (night) 0xFF263B4C.toInt() else 0xFFD1E1E8.toInt())
        polygon(canvas, apron, if (night) 0xFF31475E.toInt() else 0xFFBECFDE.toInt())
        polygon(canvas, runway, if (night) 0xFF192B43.toInt() else 0xFF667B9E.toInt())
        line.color = if (night) 0xFFC6CDE6.toInt() else 0xFFE8EDFF.toInt();line.strokeWidth = 2f;line.pathEffect = dashes
        canvas.drawLine(10f, 337f, 337f, 226f, line);line.pathEffect = null
        polygon(canvas, side, if (night) 0xFF374665.toInt() else 0xFFAAC4DF.toInt())
        polygon(canvas, front, if (night) 0xFF455775.toInt() else 0xFFDCEAF4.toInt())
        polygon(canvas, roof, if (night) 0xFF69748E.toInt() else 0xFFF4F7FF.toInt())
        polygon(canvas, glass, if (night) 0xFF79A0C5.toInt() else 0xFF6A9FC6.toInt())
        line.color = if (night) 0xFF283B55.toInt() else 0xFFCAE8FA.toInt();line.strokeWidth = 1.6f
        for (i in 1..7) {
            val x = 147f + i * 17.125f;val y = 228f - i * 5.625f
            canvas.drawLine(x, y, x, y + 27f, line)
        }
        line.color = if (night) 0xFFFFD898.toInt() else 0xFFFFFFFF.toInt();line.strokeWidth = 2f
        canvas.drawLine(138f, 220f, 294f, 169f, line)
        polygon(canvas, tower, if (night) 0xFF8898B6.toInt() else 0xFFE1EBF8.toInt())
        polygon(canvas, cabinSide, if (night) 0xFF426080.toInt() else 0xFFA5C3DF.toInt())
        polygon(canvas, cabinFront, if (night) 0xFF8BC4D7.toInt() else 0xFF6096BA.toInt())
        polygon(canvas, cabin, if (night) 0xFFB1BBDD.toInt() else 0xFFFAFBFF.toInt())
        line.color = if (night) 0xFFACBBEE.toInt() else 0xFF718BD1.toInt();line.strokeWidth = 1.5f;line.pathEffect = dashes
        revealed.reset();measure.getSegment(0f, measure.length * fraction, revealed, true)
        canvas.drawPath(revealed, line);line.pathEffect = null
        measure.getPosTan(measure.length * fraction, position, tangent)
        val planeSave = canvas.save();canvas.translate(position[0], position[1])
        canvas.rotate(Math.toDegrees(atan2(tangent[1], tangent[0]).toDouble()).toFloat())
        polygon(canvas, plane, if (night) 0xFFB6D6FF.toInt() else 0xFFFAFDFF.toInt())
        line.color = if (night) 0xFF80AFF7.toInt() else 0xFF5C88CE.toInt();line.strokeWidth = 1f
        canvas.drawPath(plane, line);canvas.restoreToCount(planeSave)
        canvas.restoreToCount(saved)
    }

    private fun polygon(canvas: Canvas, path: Path, color: Int) { fill.color = color;canvas.drawPath(path, fill) }
    override fun onAttachedToWindow() { super.onAttachedToWindow();synchronizeAnimation() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus);synchronizeAnimation() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility);synchronizeAnimation() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility);synchronizeAnimation() }
    override fun onDetachedFromWindow() { stop();super.onDetachedFromWindow() }

    private fun path(vararg xy: Float): Path = Path().apply {
        moveTo(xy[0], xy[1]);for (index in 2 until xy.size step 2) lineTo(xy[index], xy[index + 1]);close()
    }
    private fun cloud(x: Float, y: Float, scale: Float): Path = Path().apply {
        moveTo(x, y);cubicTo(x - 7f * scale, y, x - 7f * scale, y - 12f * scale, x + 2f * scale, y - 12f * scale)
        cubicTo(x + 2f * scale, y - 29f * scale, x + 25f * scale, y - 31f * scale, x + 29f * scale, y - 16f * scale)
        cubicTo(x + 45f * scale, y - 20f * scale, x + 49f * scale, y, x + 35f * scale, y)
        close()
    }
}
