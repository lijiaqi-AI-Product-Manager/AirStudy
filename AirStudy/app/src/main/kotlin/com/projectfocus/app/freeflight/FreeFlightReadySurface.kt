package com.projectfocus.app.freeflight

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.projectfocus.app.R
import com.projectfocus.core.ui.sky.SkyBackdropView
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.core.ui.R as CoreUiR
import com.projectfocus.feature.booking.R as BookingR

internal data class DepartureDetails(val origin: String?, val destination: String?, val seat: String?)

internal class FreeFlightReadySurface(
    context: Context,
    night: Boolean,
    details: DepartureDetails,
) : FrameLayout(context) {
    val depart = FreeFlightDepartureButton(context).apply { id = R.id.pf_ff_depart }

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val inset = context.ffDp(20f)
        setPadding(inset, inset, inset, inset)
    }
    private val summary = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val runway = DepartureRunwayView(context)
    private val artwork = FrameLayout(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val plane = ImageView(context).apply {
        id = R.id.pf_ff_ready_plane
        setImageResource(R.drawable.pf_ff_ready_plane)
    }
    private val status = label(R.id.pf_ff_ready_status, 23f).apply {
        setText(R.string.pf_ff_ready_to_fly_status)
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val route = label(View.NO_ID, 20f).apply {
        tag = "departure-route"
        text = when {
            details.origin.isNullOrBlank() -> ""
            details.destination.isNullOrBlank() -> details.origin
            else -> "${details.origin} → ${details.destination}"
        }
        visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }
    private val seat = label(View.NO_ID, 15f).apply {
        tag = "departure-seat"
        text = details.seat?.let { context.getString(BookingR.string.pf_seat_scene_seat_format, it) }.orEmpty()
        visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    private var backdrop: SkyBackdropView? = null
    private var appliedNight: Boolean? = null
    private var entering = false

    init {
        id = R.id.pf_ff_ready
        isClickable = true
        val side = context.ffDp(16f)
        content.setPadding(side, 0, side, 0)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(context).apply {
                addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            })
        }
        content.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        artwork.addView(runway, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val icon = context.ffDp(54f)
        artwork.addView(plane, LayoutParams(icon, icon, Gravity.CENTER))
        body.addView(artwork, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.ffDp(180f)))
        body.addView(summary, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        summary.addView(status, row(16f))
        summary.addView(route, row(12f))
        summary.addView(seat, row(4f))

        content.addView(FrameLayout(context).apply {
            val gap = context.ffDp(12f)
            setPadding(0, gap, 0, gap)
            addView(depart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        applyNight(night)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val inset = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val side = context.ffDp(16f)
            content.setPadding(inset.left + side, inset.top, inset.right + side, inset.bottom)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - content.paddingLeft - content.paddingRight).coerceAtLeast(1)
        val height = MeasureSpec.getSize(heightMeasureSpec) - content.paddingTop - content.paddingBottom
        val compact = height < context.ffDp(500f)
        val split = compact && available >= context.ffDp(540f)
        body.layoutParams.width = minOf(available, context.ffDp(if (split) 720f else 430f))
        depart.layoutParams.width = minOf(available, context.ffDp(430f))
        body.orientation = if (split) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        body.gravity = if (split) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        (artwork.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (split) 0 else LayoutParams.MATCH_PARENT
            weight = if (split) 1f else 0f
            this.height = context.ffDp(if (split) 132f else if (compact) 112f else 190f)
        }
        (summary.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (split) 0 else LayoutParams.MATCH_PARENT
            weight = if (split) 1f else 0f
            marginStart = if (split) context.ffDp(16f) else 0
        }
        (status.layoutParams as LinearLayout.LayoutParams).topMargin = if (split) 0 else context.ffDp(16f)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun applyNight(night: Boolean) {
        if (night == appliedNight) return
        appliedNight = night
        val configuration = Configuration(resources.configuration).apply {
            val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        }
        val palette = context.createConfigurationContext(configuration)
        backdrop?.let(::removeView)
        backdrop = SkyBackdropView(palette).also { addView(it, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        body.background = SkySurfaces.shape(palette, 28f)
        status.setTextColor(palette.getColor(CoreUiR.color.sky_ink))
        route.setTextColor(palette.getColor(CoreUiR.color.sky_ink))
        seat.setTextColor(palette.getColor(CoreUiR.color.sky_ink_secondary))
        plane.setColorFilter(if (night) Color.rgb(235, 244, 255) else Color.rgb(36, 71, 134))
        runway.setNight(night)
    }

    fun enter() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            alpha = 1f
            plane.translationY = 0f
            return
        }
        entering = true
        alpha = 0f
        plane.translationY = context.ffDp(10f).toFloat()
        plane.animate().translationY(0f).setDuration(420L).setInterpolator(DecelerateInterpolator()).start()
        animate().alpha(1f).setDuration(420L).setInterpolator(DecelerateInterpolator())
            .withEndAction { entering = false }.start()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE && entering) {
            entering = false
            animate().withEndAction(null).cancel()
            plane.animate().cancel()
            plane.translationY = 0f
            alpha = 1f
        }
    }

    override fun onDetachedFromWindow() {
        plane.animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun label(viewId: Int, size: Float) = TextView(context).apply {
        id = viewId
        gravity = Gravity.CENTER
        textSize = size
        typeface = ResourcesCompat.getFont(context, CoreUiR.font.harmonyos_sans_sc_medium)
    }

    private fun row(gap: Float) = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.ffDp(gap)
    }
}

private class DepartureRunwayView(context: Context) : View(context) {
    private val horizon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lines = Paint(Paint.ANTI_ALIAS_FLAG)
    private val surface = Path()
    private var night = false

    fun setNight(value: Boolean) {
        night = value
        update(width.toFloat(), height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        update(w.toFloat(), h.toFloat())
    }

    private fun update(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        horizon.shader = RadialGradient(w / 2f, h * .56f, w * .46f,
            if (night) 0xCA718BFE.toInt() else 0x9ABBD8FF.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        track.shader = LinearGradient(0f, h * .46f, 0f, h,
            intArrayOf(if (night) 0x553650A0 else 0x4B8ABCF3,
                if (night) 0xD229407F.toInt() else 0xC66A90DB.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, .78f, 1f), Shader.TileMode.CLAMP)
        surface.reset()
        surface.moveTo(w * .44f, h * .53f)
        surface.lineTo(w * .56f, h * .53f)
        surface.lineTo(w * .88f, h * .98f)
        surface.lineTo(w * .12f, h * .98f)
        surface.close()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, horizon)
        canvas.drawPath(surface, track)
        lines.color = if (night) 0xB9CBDCFF.toInt() else 0xCBFFFFFF.toInt()
        lines.strokeWidth = resources.displayMetrics.density
        canvas.drawLine(w * .44f, h * .53f, w * .12f, h * .98f, lines)
        canvas.drawLine(w * .56f, h * .53f, w * .88f, h * .98f, lines)
        for (index in 0..4) {
            val fraction = index / 5f
            val y = h * (.59f + .30f * fraction * fraction)
            val radius = resources.displayMetrics.density * (1f + fraction * 1.7f)
            val distance = w * ((y / h - .53f) / .45f * .32f + .06f)
            canvas.drawCircle(w / 2f - distance, y, radius, lines)
            canvas.drawCircle(w / 2f + distance, y, radius, lines)
            canvas.drawLine(w / 2f, y, w / 2f, y + h * (.012f + .028f * fraction), lines)
        }
    }
}
