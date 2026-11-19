package com.projectfocus.app.freeflight

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.projectfocus.app.R
import com.projectfocus.core.ui.sky.SkyBackdropView
import com.projectfocus.core.ui.sky.SkyPressFeedback
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.core.ui.R as CoreUiR
import com.projectfocus.feature.booking.R as BookingR

internal class FreeFlightHeadingSurface(
    context: Context,
    initial: Float,
    feedback: FreeFlightRulerFeedback,
    night: Boolean,
) : FrameLayout(context) {
    val ruler = FreeFlightHeadingRulerView(context).apply {
        id = R.id.pf_ff_heading_ruler
        this.feedback = feedback
        setHeading(initial)
        setUiScale(.74f)
        clipToOutline = true
    }
    val cancel = button(R.id.pf_ff_heading_cancel, R.string.pf_ff_common_cancel)
    val next = button(R.id.pf_ff_heading_continue, BookingR.string.seat_selection_title)

    private val title = TextView(context).apply {
        id = R.id.pf_ff_heading_title
        setText(R.string.pf_ff_free_flight_adjust_heading)
        textSize = 22f
        typeface = ResourcesCompat.getFont(context, CoreUiR.font.harmonyos_sans_sc_bold)
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val hint = TextView(context).apply {
        setText(R.string.pf_ff_heading_hint)
        textSize = 14f
    }
    private val dock = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
    }
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val headingText = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var appliedNight: Boolean? = null
    private var mapReady = false
    private var backdrop: SkyBackdropView? = null

    init {
        id = R.id.pf_ff_heading_body
        clipChildren = false
        clipToPadding = false
        val pad = context.ffDp(16f)
        panel.setPadding(pad, pad, pad, pad)
        addView(dock, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dock.addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        dock.setPadding(pad, pad, pad, pad)

        headingText.addView(title, row())
        headingText.addView(hint, row(4f))
        body.addView(headingText, row())
        body.addView(ruler, row(12f).apply { height = context.ffDp(140f) })
        val viewport = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }
        panel.addView(viewport, row().apply { weight = 1f })

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cancel, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(next, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.6f).apply {
                marginStart = context.ffDp(10f)
            })
        }
        panel.addView(controls, row(12f))
        applyNight(night)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            dock.setPadding(safe.left + pad, safe.top + pad, safe.right + pad, safe.bottom + pad)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (MeasureSpec.getSize(widthMeasureSpec) - dock.paddingLeft - dock.paddingRight).coerceAtLeast(0)
        val height = MeasureSpec.getSize(heightMeasureSpec) - dock.paddingTop - dock.paddingBottom
        val compact = height < context.ffDp(340f)
        val split = compact && width >= context.ffDp(500f)
        panel.layoutParams.width = minOf(width, context.ffDp(if (split) 680f else 540f))
        body.orientation = if (split) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        body.gravity = if (split) Gravity.CENTER_VERTICAL else Gravity.START
        (headingText.layoutParams as LinearLayout.LayoutParams).apply {
            this.width = if (split) 0 else LayoutParams.MATCH_PARENT
            weight = if (split) 1f else 0f
        }
        (ruler.layoutParams as LinearLayout.LayoutParams).apply {
            this.width = if (split) 0 else LayoutParams.MATCH_PARENT
            this.height = context.ffDp(if (compact) 112f else 140f)
            weight = if (split) 1.6f else 0f
            topMargin = if (split) 0 else context.ffDp(12f)
            marginStart = if (split) context.ffDp(14f) else 0
        }
        ruler.setUiScale(if (compact) .62f else .74f)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun applyNight(night: Boolean) {
        if (appliedNight == night) return
        appliedNight = night
        val configuration = Configuration(resources.configuration).apply {
            val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        }
        val palette = context.createConfigurationContext(configuration)
        backdrop?.let(::removeView)
        backdrop = SkyBackdropView(palette).apply {
            visibility = if (mapReady) GONE else VISIBLE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { addView(it, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        val ink = palette.getColor(CoreUiR.color.sky_ink)
        title.setTextColor(ink)
        hint.setTextColor(palette.getColor(CoreUiR.color.sky_ink_secondary))
        cancel.setTextColor(ink)
        panel.background = SkySurfaces.shape(palette, radius = 26f, solid = true)
        panel.elevation = context.ffDp(10f).toFloat()
        cancel.background = RippleDrawable(ColorStateList.valueOf(0x186A8FE8), SkySurfaces.shape(palette, 18f), null)
        next.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xBFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
        ))
        SkySurfaces.action(next)
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (night) {
            intArrayOf(0x18364B7D, 0x76486A9D, 0xEC10182A.toInt())
        } else {
            intArrayOf(0x1F82BBFA, 0x5BB7CCF2, 0xEEF1F4FD.toInt())
        })
        ruler.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (night) {
            intArrayOf(0xFF111D36.toInt(), 0xFF243E68.toInt(), 0xFF172139.toInt())
        } else {
            intArrayOf(0xFF233E69.toInt(), 0xFF2B5280.toInt(), 0xFF1B315D.toInt())
        }).apply {
            cornerRadius = context.ffDp(18f).toFloat()
            setStroke(context.ffDp(1f), 0x448DBCFF)
        }
    }

    fun showMapWhenLoaded(loaded: Boolean) {
        if (mapReady == loaded) return
        mapReady = loaded
        backdrop?.visibility = if (loaded) GONE else VISIBLE
    }

    fun enter() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        panel.translationY = context.ffDp(20f).toFloat()
        panel.alpha = .4f
        panel.animate().translationY(0f).alpha(1f).setDuration(240L).setInterpolator(DecelerateInterpolator()).start()
    }

    fun leave(done: () -> Unit) {
        panel.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            done()
            return
        }
        panel.animate().translationY(context.ffDp(20f).toFloat()).alpha(0f).setDuration(180L).withEndAction(done).start()
    }

    override fun onDetachedFromWindow() {
        panel.animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun row(gap: Float = 0f) = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.ffDp(gap)
    }

    private fun button(viewId: Int, label: Int) = AppCompatButton(context).apply {
        id = viewId
        setText(label)
        textSize = 16f
        typeface = ResourcesCompat.getFont(context, CoreUiR.font.harmonyos_sans_sc_bold)
        minHeight = context.ffDp(50f)
        minimumHeight = minHeight
        minWidth = 0
        minimumWidth = 0
        isAllCaps = false
        supportBackgroundTintList = null
        setPadding(context.ffDp(8f), context.ffDp(8f), context.ffDp(8f), context.ffDp(8f))
        SkyPressFeedback.install(this)
    }
}
