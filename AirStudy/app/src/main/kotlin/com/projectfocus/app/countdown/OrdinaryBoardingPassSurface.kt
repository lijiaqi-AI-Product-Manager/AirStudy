package com.projectfocus.app.countdown

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.projectfocus.app.R
import com.projectfocus.feature.completion.pass.BoardingPassBinder
import com.projectfocus.feature.completion.pass.BoardingPassData
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.feature.completion.R as PassR

/** The paper and its printed values survive inspection and the separate boarding confirmation. */
internal class OrdinaryBoardingPassSurface(
    context: Context,
    state: OrdinaryPreparationState,
    private val night: Boolean,
    private val onCheckIn: () -> Unit,
    private val onInspected: () -> Unit,
    onPrepare: () -> Unit,
) : FrameLayout(context) {
    val paper: FrameLayout = FrameLayout(context).apply {
        val content = LayoutInflater.from(context).inflate(PassR.layout.boarding_pass_layout, this, false) as ViewGroup
        id = content.id
        while (content.childCount > 0) {
            val child = content.getChildAt(0)
            val params = child.layoutParams
            content.removeViewAt(0)
            addView(child, params)
        }
    }
    val artwork = BoardingSequenceView(context, night).apply { id = R.id.ordinaryBoardingArtwork }
    val checkIn = button(R.string.ordinary_check_in).apply { id = R.id.ordinaryCheckIn; isEnabled = false }
    val prepare = button(R.string.ordinary_prepare_boarding).apply { id = R.id.ordinaryPrepareBoarding }
    private val instruction = label().apply {
        setText(PassR.string.boarding_pass_instruction_swipe)
        textSize = 18f
        visibility = GONE
    }
    private val blockingStatus = label().apply { id = R.id.ordinaryBlockingStatus; textSize = 14f }
    private val modePanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        visibility = GONE
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label().apply { setText(R.string.ordinary_focus_mode); textSize = 16f }, LinearLayout.LayoutParams(0, dp(40f), 1f))
            addView(blockingStatus, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40f)))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(prepare, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56f)).apply { topMargin = dp(20f) })
    }
    private var inspection = false
    private var preparing = false
    private var released = false
    private var completed = false
    private var downX = 0f
    private var dragging = false
    private var progress = 0f
    private var tearAnimation: ValueAnimator? = null
    private val finished = Runnable { if (!released && completed && !preparing) onInspected() }

    init {
        id = R.id.ordinaryBoarding
        isClickable = true
        isFocusable = true
        clipChildren = false
        val route = requireNotNull(state.route)
        val time = requireNotNull(state.boardingAt)
        val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        val data = BoardingPassData(route.departure.code, OrdinaryFlightNumber.city(route.departure), clock.format(time),
            route.destination.code, OrdinaryFlightNumber.city(route.destination), clock.format(time.plusMinutes(route.durationMinutes.toLong())),
            requireNotNull(route.flightNumber), requireNotNull(route.seat), time.toLocalDate().toString(),
            String.format(Locale.ROOT, "%.1f km", route.distanceKm), "", route.durationMinutes, 0, false)
        BoardingPassBinder(paper).bind(data)
        paper.findViewById<TextView>(PassR.id.tv_departure_city).text = data.departureCity
        paper.findViewById<TextView>(PassR.id.tv_destination_city).text = data.arrivalCity
        val primary = if (night) Color.WHITE else Color.BLACK
        val secondary = if (night) Color.argb(204, 255, 255, 255) else 0xFF444444.toInt()
        for (id in listOf(PassR.id.tv_departure, PassR.id.tv_destination, PassR.id.tv_flight_code,
                PassR.id.tv_boarding_time, PassR.id.tv_date, PassR.id.tv_passenger_name, PassR.id.tv_seat))
            paper.findViewById<TextView>(id).setTextColor(primary)
        for (id in listOf(PassR.id.tv_departure_city, PassR.id.tv_destination_city))
            paper.findViewById<TextView>(id).setTextColor(secondary)
        paper.addView(artwork, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        artwork.onFirstFrame = { if (!released && !inspection && !preparing) checkIn.isEnabled = true }
        addView(paper, LayoutParams(dp(300f), dp(400f)))
        addView(checkIn, LayoutParams(dp(240f), dp(56f)))
        addView(instruction, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(modePanel, LayoutParams(dp(300f), LayoutParams.WRAP_CONTENT))
        checkIn.setOnClickListener { if (!released && !inspection && checkIn.isEnabled) onCheckIn() }
        prepare.setOnClickListener { if (!released && preparing && prepare.isEnabled) onPrepare() }
        installInspection()
    }

    fun inspect(night: Boolean) {
        if (inspection || preparing) return
        inspection = true
        setBackgroundColor(if (night) Color.argb(200, 0, 0, 0) else Color.argb(200, 255, 255, 255))
        checkIn.visibility = GONE
        instruction.visibility = VISIBLE
        paper.findViewById<View>(PassR.id.tear_touch_band).apply {
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.ordinary_complete_inspection)
        }
        positionViews()
    }

    fun showPreparation() {
        if (preparing) return
        inspect(night)
        preparing = true
        completed = true
        dragging = false
        instruction.visibility = GONE
        artwork.showProgress(1f)
        modePanel.visibility = VISIBLE
        paper.findViewById<View>(PassR.id.tear_touch_band).apply {
            isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        positionViews()
    }

    fun updateBlocking(enabled: Boolean, apps: Int) {
        blockingStatus.text = if (enabled) context.getString(R.string.ordinary_focus_apps, apps)
            else context.getString(R.string.ordinary_focus_disabled)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installInspection() {
        val band = paper.findViewById<View>(PassR.id.tear_touch_band)
        band.setOnClickListener { finishTear() }
        band.isClickable = false
        band.isFocusable = false
        band.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val listener = OnTouchListener { _, event ->
            if (!inspection || completed || preparing || released || artwork.renderedFrame < 0) false else when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tearAnimation?.cancel()
                    downX = event.rawX
                    dragging = true
                    progress = 0f
                    artwork.showProgress(0f)
                    instruction.visibility = INVISIBLE
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val distance = ((event.rawX - downX) / 300f).coerceIn(0f, 1f)
                        progress = distance * .5f
                        artwork.showProgress(progress)
                        if (distance >= 1f) finishTear()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    resetTear()
                    true
                }
                else -> true
            }
        }
        band.setOnTouchListener(listener)
        setOnTouchListener(listener)
    }

    private fun resetTear() {
        if (completed || released) return
        animateTear(progress, 0f, 300L) { if (!released && !completed) instruction.visibility = VISIBLE }
    }

    private fun finishTear() {
        if (!inspection || completed || preparing || released || artwork.renderedFrame < 0) return
        completed = true
        dragging = false
        instruction.visibility = GONE
        val from = progress.coerceAtLeast(.5f)
        animateTear(from, 1f, if (from >= .86f) 280L else ((1f - from) / .5f * 450L).toLong().coerceAtLeast(200L)) {
            artwork.afterFinalFrame { if (!released) postDelayed(finished, 50L) }
        }
    }

    private fun animateTear(from: Float, to: Float, duration: Long, after: () -> Unit) {
        tearAnimation?.cancel()
        tearAnimation = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            var cancelled = false
            addUpdateListener { if (!released) { progress = it.animatedValue as Float; artwork.showProgress(progress) } }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled && !released) after() }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        positionViews()
    }

    private fun positionViews() {
        if (width <= 0 || height <= 0) return
        val compact = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && height < dp(550f)
        val scale = min(1f, min((width - dp(32f)).toFloat() / dp(300f),
            (height - dp(if (compact) 48f else 80f)).toFloat() / dp(400f))).coerceAtLeast(.1f)
        paper.pivotX = 0f; paper.pivotY = 0f
        paper.scaleX = scale; paper.scaleY = scale
        val paperWidth = dp(300f) * scale
        val paperHeight = dp(400f) * scale
        val sideWidth = if (preparing) min(paperWidth, dp(280f).toFloat()) else dp(160f).toFloat()
        paper.x = if (compact) ((width - paperWidth - dp(if (preparing) 20f else 24f) - sideWidth) / 2f).coerceAtLeast(dp(16f).toFloat())
            else (width - paperWidth) / 2f
        paper.y = if (inspection || compact) (height - paperHeight) / 2f
            else (height - paperHeight - dp(126f)).coerceAtLeast(dp(40f).toFloat())
        checkIn.x = if (compact) min(paper.x + paperWidth + dp(24f), (width - checkIn.layoutParams.width - dp(16f)).toFloat())
            else (width - dp(240f)) / 2f
        if (compact) checkIn.layoutParams = (checkIn.layoutParams as LayoutParams).apply { width = dp(160f) }
        checkIn.y = if (compact) (height - dp(56f)) / 2f else (height - dp(96f)).toFloat()
        instruction.textSize = if (compact) 15f else 18f
        instruction.layoutParams = (instruction.layoutParams as LayoutParams).apply { width = if (compact) dp(160f) else dp(300f) }
        instruction.measure(MeasureSpec.makeMeasureSpec(if (compact) dp(160f) else dp(300f), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        instruction.x = if (compact) paper.x + paperWidth + dp(24f) else (width - dp(300f)) / 2f
        instruction.y = if (compact) (height - instruction.measuredHeight) / 2f else paper.y + paperHeight + dp(30f)
        val panelWidth = if (compact) min(paperWidth.toInt(), dp(280f)) else dp(300f)
        modePanel.layoutParams = (modePanel.layoutParams as LayoutParams).apply { width = panelWidth }
        modePanel.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        modePanel.x = if (compact) paper.x + paperWidth + dp(20f) else paper.x
        modePanel.y = if (compact) (height - modePanel.measuredHeight) / 2f else min(paper.y + paperHeight + dp(20f),
            (height - modePanel.measuredHeight - dp(16f)).toFloat())
    }

    fun release() {
        released = true
        removeCallbacks(finished)
        tearAnimation?.cancel()
        artwork.release()
        paper.animate().cancel()
        checkIn.setOnClickListener(null)
        prepare.setOnClickListener(null)
        setOnTouchListener(null)
        paper.findViewById<View>(PassR.id.tear_touch_band).apply { setOnTouchListener(null); setOnClickListener(null) }
    }

    private fun button(text: Int) = MaterialButton(context).apply {
        setText(text); isAllCaps = false; cornerRadius = dp(28f)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (night) Color.WHITE else Color.BLACK)
        setTextColor(if (night) Color.BLACK else Color.WHITE)
        typeface = ResourcesCompat.getFont(context, UiR.font.harmonyos_sans_sc_medium)
    }
    private fun label() = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(if (night) Color.WHITE else Color.BLACK)
        typeface = ResourcesCompat.getFont(context, UiR.font.harmonyos_sans_sc_medium)
    }
    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
}
