package com.projectfocus.app.auth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.projectfocus.app.R
import com.projectfocus.core.ui.sky.SkySurfaces

/** A scenic cover with room for the back control and the full welcome copy. */
class AuthCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val roundedFrame = Path()
    private val cornerRadius = 24f * resources.displayMetrics.density

    init {
        background = AuthSkyDrawable(context, cover = true)
        clipToOutline = true
        elevation = SkySurfaces.dp(context, 7f).toFloat()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        roundedFrame.reset()
        roundedFrame.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val layer = canvas.save()
        canvas.clipPath(roundedFrame)
        super.draw(canvas)
        canvas.restoreToCount(layer)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val copy = findViewById<View>(R.id.authSkyCoverCopy)
        if (copy == null || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        copy.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val config = resources.configuration
        val compact = config.screenHeightDp < 680 || config.screenWidthDp < 360
        val preferred = SkySurfaces.dp(context, if (compact) 220f else 268f)
        val height = copy.measuredHeight + SkySurfaces.dp(context, 86f) + paddingTop + paddingBottom
        val resolved = resolveSize(maxOf(preferred, height), heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(resolved, MeasureSpec.EXACTLY))
    }
}
